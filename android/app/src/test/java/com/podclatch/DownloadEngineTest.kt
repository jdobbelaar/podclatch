package com.podclatch

import com.podclatch.data.db.DownloadState
import com.podclatch.download.ContentRange
import com.podclatch.download.DownloadEngine
import com.podclatch.download.DownloadOutcome
import com.podclatch.download.DownloadPaths
import com.podclatch.download.ProgressThrottle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** How [ContentServer] answers a request that carries a `Range` header. */
private enum class RangeMode { HONOR, IGNORE, WRONG_START, ALWAYS_416 }

/** Serves [content] at any path and records the `Range` and `Authorization` headers of every request. */
private class ContentServer(val content: ByteArray, var mode: RangeMode = RangeMode.HONOR) : Dispatcher() {
    val ranges = CopyOnWriteArrayList<String?>()
    val auths = CopyOnWriteArrayList<String?>()

    private fun full(code: Int = 200) = MockResponse.Builder().code(code).body(Buffer().write(content)).build()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val range = request.headers["Range"]
        ranges += range
        auths += request.headers["Authorization"]
        if (range == null) return full()
        val start = Regex("""bytes=(\d+)-""").matchEntire(range)!!.groupValues[1].toInt()
        return when (mode) {
            RangeMode.IGNORE -> full()
            RangeMode.ALWAYS_416 ->
                MockResponse.Builder().code(416).addHeader("Content-Range", "bytes */${content.size}").build()
            RangeMode.WRONG_START -> MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 0-${content.size - 1}/${content.size}")
                .body(Buffer().write(content)).build()
            RangeMode.HONOR ->
                if (start >= content.size) {
                    MockResponse.Builder().code(416).addHeader("Content-Range", "bytes */${content.size}").build()
                } else {
                    MockResponse.Builder().code(206)
                        .addHeader("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                        .body(Buffer().write(content, start, content.size - start)).build()
                }
        }
    }
}

class DownloadEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val store = FakeDownloadStore()
    private lateinit var fs: FakeFileSystem

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = tmp.newFolder("audio")
        fs = FakeFileSystem(dir)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun url(path: String = "/audio.mp3") = server.url(path).toString()

    private fun engine(
        client: OkHttpClient = OkHttpClient(),
        clock: () -> Long = { 0L },
        chunk: Int = 8 * 1024,
        slots: Semaphore = Semaphore(2),
    ) = DownloadEngine(store, fs, client, { clock() }, Dispatchers.IO, slots, 1_000L, chunk)

    private fun download(
        id: Long = 10,
        manual: Boolean = true,
        engine: DownloadEngine = engine(),
    ): DownloadOutcome = runBlocking { engine.run(id, manual) }

    private fun queue(id: Long = 10, path: String = "/audio.mp3", state: DownloadState = DownloadState.QUEUED) =
        store.put(downloadRow(id, url(path), state))

    private fun part(id: Long = 10) = DownloadPaths.partial(dir, id)
    private fun finalFile(id: Long = 10) = DownloadPaths.finalFile(dir, id)

    private fun serve(content: ByteArray, mode: RangeMode = RangeMode.HONOR) =
        ContentServer(content, mode).also { server.dispatcher = it }

    // ---- success and the finished state ----

    @Test
    fun aDownloadGoesQueuedThenDownloadingThenDownloadedWithTheServedBytes() {
        val content = audioBytes(100_000)
        serve(content)
        queue()
        assertEquals(DownloadOutcome.DONE, download(manual = false))
        assertEquals(listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.DOWNLOADED), store.statesOf(10))
        val row = store.row(10)!!
        assertEquals(finalFile().absolutePath, row.localPath)
        assertEquals("10.audio", File(row.localPath!!).name)
        assertArrayEquals(content, finalFile().readBytes())
        assertFalse(part().exists())
        assertEquals(100_000L, row.bytesReceived)
        assertEquals(100_000L, row.totalBytes)
        assertFalse(row.downloadPermanentFailure)
    }

    @Test
    fun aDownloadWithZeroBytesIsATransientFailureWithNoFinalFile() {
        serve(ByteArray(0))
        queue()
        assertEquals(DownloadOutcome.FAILED_TRANSIENT, download())
        assertEquals(DownloadState.FAILED, store.row(10)!!.downloadState)
        assertFalse(store.row(10)!!.downloadPermanentFailure)
        assertFalse(finalFile().exists())
        assertFalse(part().exists())
    }

    @Test
    fun aDownloadWithFewerBytesThanAnnouncedIsATransientFailureWithNoFinalFile() {
        server.enqueue(
            MockResponse.Builder().body(Buffer().write(audioBytes(500))).setHeader("Content-Length", "1000")
                .onResponseEnd(SocketEffect.CloseSocket()).build(),
        )
        queue()
        assertEquals(DownloadOutcome.FAILED_TRANSIENT, download())
        assertEquals(DownloadState.FAILED, store.row(10)!!.downloadState)
        assertFalse(store.row(10)!!.downloadPermanentFailure)
        assertFalse(finalFile().exists())
        assertEquals("the bytes that did arrive are kept for the next try", 500L, part().length())
    }

    @Test
    fun unavailableStorageEndsAsFailedWithoutThrowing() {
        serve(audioBytes(1_000))
        fs.directory = null
        queue()
        assertEquals(DownloadOutcome.FAILED_TRANSIENT, download())
        assertEquals(DownloadState.FAILED, store.row(10)!!.downloadState)
        assertEquals("no request was made", 0, server.requestCount)
    }

    @Test
    fun anUnwritableDirectoryEndsAsFailedAndLeavesNoPartialFile() {
        serve(audioBytes(1_000))
        fs.failOpen = true
        queue()
        assertEquals(DownloadOutcome.FAILED_TRANSIENT, download())
        assertEquals(DownloadState.FAILED, store.row(10)!!.downloadState)
        assertFalse(part().exists())
    }

    @Test
    fun aFullDiskEndsAsFailedAndDeletesThePartialFile() {
        serve(audioBytes(100_000))
        fs.failWritesAfter = 20_000
        queue()
        assertEquals(DownloadOutcome.FAILED_TRANSIENT, download())
        val row = store.row(10)!!
        assertEquals(DownloadState.FAILED, row.downloadState)
        assertFalse(row.downloadPermanentFailure)
        assertFalse(part().exists())
        assertFalse(finalFile().exists())
    }

    // ---- network rules ----

    @Test
    fun noRequestCarriesAnAuthorizationHeaderEvenAfterARedirect() {
        val other = MockWebServer().also { it.start() }
        try {
            val content = audioBytes(5_000)
            val behind = ContentServer(content)
            other.dispatcher = behind
            val front = ContentServer(content)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    front.auths += request.headers["Authorization"]
                    return MockResponse.Builder().code(302).addHeader("Location", other.url("/real.mp3").toString()).build()
                }
            }
            queue()
            assertEquals(DownloadOutcome.DONE, download())
            assertEquals(listOf<String?>(null), front.auths)
            assertEquals(listOf<String?>(null), behind.auths)
            assertArrayEquals(content, finalFile().readBytes())
        } finally {
            other.close()
        }
    }

    @Test
    fun theRangeHeaderSurvivesARedirectToAnotherHost() {
        val other = MockWebServer().also { it.start() }
        try {
            val content = audioBytes(5_000)
            val behind = ContentServer(content)
            other.dispatcher = behind
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse.Builder().code(302).addHeader("Location", other.url("/real.mp3").toString()).build()
            }
            part().writeBytes(content.copyOf(2_000))
            queue()
            assertEquals(DownloadOutcome.DONE, download())
            assertEquals(listOf<String?>("bytes=2000-"), behind.ranges)
            assertArrayEquals(content, finalFile().readBytes())
        } finally {
            other.close()
        }
    }

    @Test
    fun aRedirectFromHttpToHttpsIsFollowed() {
        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(localhost).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
        val secure = MockWebServer().also {
            it.useHttps(serverCerts.sslSocketFactory())
            it.start()
        }
        try {
            val content = audioBytes(5_000)
            val behind = ContentServer(content)
            secure.dispatcher = behind
            val target = secure.url("/real.mp3").newBuilder().host("localhost").build().toString()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse.Builder().code(302).addHeader("Location", target).build()
            }
            val client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
            queue()
            assertEquals(DownloadOutcome.DONE, download(engine = engine(client)))
            assertEquals(listOf<String?>(null), behind.auths)
            assertArrayEquals(content, finalFile().readBytes())
        } finally {
            secure.close()
        }
    }

    @Test
    fun anHttpsHostWithAnUntrustedCertificateIsATransientFailure() {
        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(localhost).build()
        val secure = MockWebServer().also {
            it.useHttps(serverCerts.sslSocketFactory())
            it.dispatcher = ContentServer(audioBytes(1_000))
            it.start()
        }
        try {
            store.put(downloadRow(10, secure.url("/real.mp3").newBuilder().host("localhost").build().toString()))
            assertEquals(DownloadOutcome.FAILED_TRANSIENT, download())
            val row = store.row(10)!!
            assertEquals(DownloadState.FAILED, row.downloadState)
            assertFalse(row.downloadPermanentFailure)
            assertFalse(finalFile().exists())
        } finally {
            secure.close()
        }
    }

    // ---- Range ----

    private fun resumeFrom(n: Int, content: ByteArray, mode: RangeMode): ContentServer {
        val behind = serve(content, mode)
        part().writeBytes(content.copyOf(n))
        queue(state = DownloadState.DOWNLOADING)
        return behind
    }

    @Test
    fun a206ThatStartsAtThePartialSizeAppends() {
        val content = audioBytes(50_000)
        val behind = resumeFrom(20_000, content, RangeMode.HONOR)
        assertEquals(DownloadOutcome.DONE, download())
        assertEquals(listOf<String?>("bytes=20000-"), behind.ranges)
        assertArrayEquals(content, finalFile().readBytes())
    }

    @Test
    fun a200ToARangeRequestTruncatesThePartialAndRestartsFromZero() {
        val content = audioBytes(50_000)
        val behind = resumeFrom(20_000, content, RangeMode.IGNORE)
        assertEquals(DownloadOutcome.DONE, download())
        assertEquals(listOf<String?>("bytes=20000-"), behind.ranges)
        assertArrayEquals(content, finalFile().readBytes())
    }

    @Test
    fun a416WhenThePartialIsTheWholeFileCountsAsComplete() {
        val content = audioBytes(30_000)
        val behind = resumeFrom(30_000, content, RangeMode.ALWAYS_416)
        assertEquals(DownloadOutcome.DONE, download())
        assertEquals(listOf<String?>("bytes=30000-"), behind.ranges)
        assertArrayEquals(content, finalFile().readBytes())
        assertEquals(DownloadState.DOWNLOADED, store.row(10)!!.downloadState)
    }

    @Test
    fun a416WhenThePartialIsNotTheWholeFileDeletesItAndRestarts() {
        val content = audioBytes(30_000)
        val behind = resumeFrom(40_000, content, RangeMode.HONOR) // the partial is longer than the file now
        assertEquals(DownloadOutcome.DONE, download())
        assertEquals(listOf<String?>("bytes=40000-", null), behind.ranges)
        assertArrayEquals(content, finalFile().readBytes())
    }

    @Test
    fun a206ThatStartsElsewhereRestartsFromZero() {
        val content = audioBytes(30_000)
        val behind = resumeFrom(10_000, content, RangeMode.WRONG_START)
        // The server keeps answering a Range request wrongly, so the restart (no Range) is the one that works.
        assertEquals(DownloadOutcome.DONE, download())
        assertEquals(listOf<String?>("bytes=10000-", null), behind.ranges)
        assertArrayEquals(content, finalFile().readBytes())
    }

    // ---- 404 and 410 are permanent; everything else is transient ----

    private fun permanent(code: Int) {
        server.enqueue(MockResponse.Builder().code(code).build())
        part().writeBytes(audioBytes(100))
        queue(state = DownloadState.DOWNLOADING)
        assertEquals(DownloadOutcome.FAILED_PERMANENT, download())
        val row = store.row(10)!!
        assertEquals(DownloadState.FAILED, row.downloadState)
        assertTrue(row.downloadPermanentFailure)
        assertFalse(part().exists())
        assertFalse(finalFile().exists())
        assertEquals("one request, no retry", 1, server.requestCount)
    }

    @Test fun a404IsPermanent() = permanent(404)

    @Test fun a410IsPermanent() = permanent(410)

    private val noRetry get() = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    private fun transient(response: MockResponse, client: OkHttpClient = noRetry) {
        server.enqueue(response)
        part().writeBytes(audioBytes(100))
        queue(state = DownloadState.DOWNLOADING)
        assertEquals(DownloadOutcome.FAILED_TRANSIENT, download(engine = engine(client)))
        val row = store.row(10)!!
        assertEquals(DownloadState.FAILED, row.downloadState)
        assertFalse(row.downloadPermanentFailure)
        assertEquals("the partial file is kept", 100L, part().length())
        assertFalse(finalFile().exists())
        assertEquals("one request, no retry", 1, server.requestCount)
    }

    @Test fun a500IsTransient() = transient(MockResponse.Builder().code(500).build())

    @Test fun a503IsTransient() = transient(MockResponse.Builder().code(503).build())

    @Test fun a429IsTransient() = transient(MockResponse.Builder().code(429).build())

    @Test fun a403IsTransient() = transient(MockResponse.Builder().code(403).build())

    @Test
    fun aDroppedConnectionIsTransient() = transient(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())

    @Test
    fun aTimeoutIsTransient() = transient(
        MockResponse.Builder().onResponseStart(SocketEffect.Stall).build(),
        OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build(),
    )

    // ---- progress ----

    @Test
    fun progressIsWrittenAtMostOncePerIntervalAndTheLastChunkIsAlwaysStored() {
        val content = audioBytes(10_000)
        serve(content)
        queue()
        val ticks = AtomicLong(0)
        // One millisecond passes per chunk read, so 10,000 one-byte chunks are about ten intervals.
        assertEquals(DownloadOutcome.DONE, download(engine = engine(clock = { ticks.incrementAndGet() }, chunk = 1)))
        val writes = store.progressWrites.map { it.second }
        assertTrue("at most about one write per interval, got ${writes.size}", writes.size <= 14)
        assertTrue("some progress is written along the way, got ${writes.size}", writes.size >= 5)
        assertEquals(writes.sorted(), writes)
        assertEquals(10_000L, writes.last())
        assertEquals(10_000L, store.row(10)!!.bytesReceived)
    }

    @Test
    fun theThrottleLetsOneWritePerIntervalThrough() {
        var now = 0L
        val throttle = ProgressThrottle({ now }, 1_000)
        var allowed = 0
        repeat(10_000) {
            now += 1
            if (throttle.due()) allowed += 1
        }
        assertEquals(10, allowed)
    }

    // ---- stopped, resumed, and removed ----

    @Test
    fun aStoppedRunKeepsThePartialFileAndTheNextRunContinuesFromItsRealSize() {
        val content = audioBytes(60_000)
        val behind = serve(content)
        queue()
        val engine = engine(chunk = 4_000)
        var runs = 0
        while (store.row(10)!!.downloadState != DownloadState.DOWNLOADED) {
            runs += 1
            assertTrue("it never finishes", runs < 20)
            runBlocking {
                val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { engine.run(10, manual = true) }
                fs.onBytes = { bytes -> if (bytes >= 16_000) job.cancel() }
                job.start()
                job.join()
            }
            if (store.row(10)!!.downloadState == DownloadState.DOWNLOADING) {
                assertTrue(part().exists())
                assertEquals("progress is the real size", part().length(), store.row(10)!!.bytesReceived)
                // The stored number is only a cache: a wrong one must not matter.
                store.overwrite(10) { it.copy(bytesReceived = 1) }
            }
        }
        assertTrue("it took several runs, took $runs", runs >= 3)
        assertArrayEquals(content, finalFile().readBytes())
        assertEquals(null, behind.ranges.first())
        assertTrue(behind.ranges.drop(1).all { it != null && it != "bytes=1-" })
    }

    @Test
    fun whenTheEpisodeLeavesTheCacheTheJobStopsDeletesThePartialAndWritesNothingBack() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(audioBytes(100_000))).throttleBody(5_000, 1, TimeUnit.SECONDS).build())
        queue()
        val engine = engine(chunk = 1_000)
        val result = runBlocking {
            val run = async(Dispatchers.Default) { engine.run(10, manual = true) }
            awaitCondition("some bytes are on disk") { part().exists() && part().length() > 0 }
            store.delete(10)
            awaitCondition("the run ended") { run.isCompleted }
            run.await()
        }
        assertEquals(DownloadOutcome.REMOVED, result)
        assertFalse(part().exists())
        assertFalse(finalFile().exists())
        assertNull("no row is re-created", store.row(10))
    }

    // ---- concurrency ----

    @Test
    fun atMostTwoAutomaticDownloadsRunAtOnceAndManualOnesNeverWait() {
        serve(audioBytes(20_000))
        for (id in 1L..3L) queue(id)
        queue(4)
        val release = CountDownLatch(1)
        fs.onBytes = { release.await(10, TimeUnit.SECONDS) }
        val engine = engine(slots = Semaphore(2))
        val jobs = mutableListOf<Job>()
        runBlocking {
            for (id in 1L..3L) jobs += launch(Dispatchers.Default) { engine.run(id, manual = false) }
            awaitCondition("two are downloading and the third waits as QUEUED") {
                (1L..3L).count { store.row(it)!!.downloadState == DownloadState.DOWNLOADING } == 2 &&
                    (1L..3L).count { store.row(it)!!.downloadState == DownloadState.QUEUED } == 1
            }
            assertEquals(2, store.maxDownloading)
            val manual = launch(Dispatchers.Default) { engine.run(4, manual = true) }
            awaitCondition("the manual one is downloading") { store.row(4)!!.downloadState == DownloadState.DOWNLOADING }
            assertEquals(3, store.maxDownloading)
            fs.onBytes = null
            release.countDown()
            awaitCondition("everything finished") { (1L..4L).all { store.row(it)!!.downloadState == DownloadState.DOWNLOADED } }
            jobs.forEach { it.join() }
            manual.join()
        }
    }

    @Test
    fun anEpisodeThatIsNotQueuedOrDownloadingIsSkipped() {
        serve(audioBytes(1_000))
        queue(state = DownloadState.NONE)
        assertEquals(DownloadOutcome.SKIPPED, download())
        assertEquals(0, server.requestCount)
        assertEquals(DownloadOutcome.REMOVED, download(id = 99))
    }

    @Test
    fun theWaitingJobCompletesOnceAnotherReleasesItsSlot() {
        serve(audioBytes(1_000))
        queue(1)
        queue(2)
        val slots = Semaphore(1)
        val engine = engine(slots = slots)
        runBlocking {
            val a = launch(Dispatchers.Default) { engine.run(1, false) }
            val b = launch(Dispatchers.Default) { engine.run(2, false) }
            a.join()
            b.join()
        }
        assertEquals(DownloadState.DOWNLOADED, store.row(1)!!.downloadState)
        assertEquals(DownloadState.DOWNLOADED, store.row(2)!!.downloadState)
        assertEquals(1, store.maxDownloading)
    }

    // ---- Content-Range ----

    @Test
    fun contentRangeParsing() {
        assertEquals(ContentRange(100, 1000), ContentRange.parse("bytes 100-999/1000"))
        assertEquals(ContentRange(0, null), ContentRange.parse("bytes 0-9/*"))
        assertEquals(ContentRange(null, 1000), ContentRange.parse("bytes */1000"))
        assertNull(ContentRange.parse("items 1-2/3"))
        assertNull(ContentRange.parse(null))
    }
}
