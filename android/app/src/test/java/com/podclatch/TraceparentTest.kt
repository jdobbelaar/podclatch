package com.podclatch

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.PendingStateWrite
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.ClientSpan
import com.podclatch.data.network.OkHttpPodClatchApi
import com.podclatch.data.network.ServerTarget
import com.podclatch.data.network.SpanRecorder
import com.podclatch.data.network.TokenGuardInterceptor
import com.podclatch.data.network.artworkHttpClient
import com.podclatch.data.network.EpisodeStateItem
import com.podclatch.data.repo.FeedsRepository
import com.podclatch.data.repo.Settings
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.TokenRejection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TraceparentTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val format = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$")
    private lateinit var server: MockWebServer
    private lateinit var settings: FakeSettings
    private lateinit var recorder: SpanRecorder

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settings = FakeSettings(Settings(serverUrl = baseUrl(), authToken = TEST_TOKEN))
        recorder = SpanRecorder(capacity = 5)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun api(client: OkHttpClient = OkHttpClient()) = OkHttpPodClatchApi(client, json, settings, recorder)

    private fun resource(name: String) = javaClass.getResource("/openapi/$name")!!.readText()

    private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun failure(code: Int) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body("{\"detail\":\"nope\"}").build()

    data class Ids(val traceId: String, val spanId: String)

    private fun ids(request: RecordedRequest): Ids {
        val all = request.headers.values("traceparent")
        assertEquals("exactly one traceparent header", 1, all.size)
        val match = format.matchEntire(all[0]) ?: throw AssertionError("bad traceparent: ${all[0]}")
        val (trace, span) = match.destructured
        assertNotEquals("0".repeat(32), trace)
        assertNotEquals("0".repeat(16), span)
        assertNull(request.headers["tracestate"])
        assertNull(request.headers["baggage"])
        return Ids(trace, span)
    }

    private val pageJson = """{"feeds":[],"episodes":[],"tombstones":[],"nextRev":1,"hasMore":false}"""
    private val pushResult = """[{"episodeId":501,"outcome":"APPLIED","status":"PLAYED","playedAt":5,"positionSec":30,"stateUpdatedAt":5}]"""

    // ---- header format ----

    @Test
    fun everyCallCarriesOneWellFormedTraceparent() = runBlocking {
        server.enqueue(ok(resource("sync_page.json")))
        server.enqueue(ok(resource("state_results.json")))
        server.enqueue(ok(resource("refresh_results.json")))
        val a = api()
        a.pullSync(0, 1)
        a.pushEpisodeState(listOf(EpisodeStateItem(501, EpisodeStatus.PLAYED, 1, 1, 1)))
        a.refreshAllFeeds()
        repeat(3) { ids(server.takeRequest()) }
    }

    @Test
    fun healthAndTheOtherMethodsCarryTheHeaderToo() = runBlocking {
        server.enqueue(ok(resource("health.json")))
        server.enqueue(ok(resource("refresh_results.json")))
        server.enqueue(ok(resource("search_results.json")))
        val a = api()
        a.health()
        a.refreshFeed(12)
        a.searchPodcasts("x")
        repeat(3) { ids(server.takeRequest()) }
    }

    @Test
    fun separateCallsGetDifferentTraceIdsAndSpanIds() = runBlocking {
        server.enqueue(ok(resource("sync_page.json")))
        server.enqueue(ok(resource("sync_page.json")))
        val a = api()
        a.pullSync(0, 1)
        a.pullSync(0, 1)
        val first = ids(server.takeRequest())
        val second = ids(server.takeRequest())
        assertNotEquals(first.traceId, second.traceId)
        assertNotEquals(first.spanId, second.spanId)
    }

    @Test
    fun theHeaderIsStillSentToAConnectionTestTarget() = runBlocking {
        server.enqueue(ok(resource("health.json")))
        api().health(ServerTarget(baseUrl(), "typed-token", callTimeoutSeconds = 15))
        assertNotNull(ids(server.takeRequest()))
    }

    // ---- trace grouping ----

    @Test
    fun aSyncRunSharesOneTraceWithDifferentSpans() {
        val store = FakeCacheStore()
        store.pending[501] = PendingStateWrite(501, EpisodeStatus.PLAYED, 5, 30, 5)
        server.enqueue(ok(pushResult))
        server.enqueue(ok(resource("sync_page.json"))) // hasMore = true
        server.enqueue(ok(pageJson))
        val engine = SyncEngine(api(), settings, store, { }, { 5_000 }, TokenRejection(settings), DiskFileDeleter(), Dispatchers.Unconfined)
        runBlocking { engine.sync(SyncTrigger.MANUAL) }
        val calls = List(3) { ids(server.takeRequest()) }
        assertEquals(1, calls.map { it.traceId }.toSet().size)
        assertEquals(3, calls.map { it.spanId }.toSet().size)
    }

    @Test
    fun twoSyncRunsAreTwoTraces() {
        server.enqueue(ok(pageJson))
        server.enqueue(ok(pageJson))
        val engine = SyncEngine(api(), settings, FakeCacheStore(), { }, { 5_000 }, TokenRejection(settings), DiskFileDeleter(), Dispatchers.Unconfined)
        runBlocking {
            engine.sync(SyncTrigger.MANUAL)
            engine.sync(SyncTrigger.MANUAL)
        }
        assertNotEquals(ids(server.takeRequest()).traceId, ids(server.takeRequest()).traceId)
    }

    @Test
    fun aRefreshAndTheSyncThatFollowsItShareOneTrace() {
        server.enqueue(ok(resource("refresh_results.json")))
        server.enqueue(ok(pageJson))
        val store = FakeCacheStore()
        val engine = SyncEngine(api(), settings, store, { }, { 5_000 }, TokenRejection(settings), DiskFileDeleter(), Dispatchers.Unconfined)
        val repo = FeedsRepository(api(), store, engine, TokenRejection(settings))
        runBlocking { repo.refreshAll() }
        val refresh = ids(server.takeRequest())
        val sync = ids(server.takeRequest())
        assertEquals(refresh.traceId, sync.traceId)
        assertNotEquals(refresh.spanId, sync.spanId)
    }

    @Test
    fun aSecondRefreshIsADifferentTrace() {
        repeat(2) {
            server.enqueue(ok(resource("refresh_results.json")))
            server.enqueue(ok(pageJson))
        }
        val store = FakeCacheStore()
        val engine = SyncEngine(api(), settings, store, { }, { 5_000 }, TokenRejection(settings), DiskFileDeleter(), Dispatchers.Unconfined)
        val repo = FeedsRepository(api(), store, engine, TokenRejection(settings))
        runBlocking {
            repo.refreshAll()
            repo.refreshFeed(12)
        }
        val traces = List(4) { ids(server.takeRequest()).traceId }
        assertEquals(traces[0], traces[1])
        assertEquals(traces[2], traces[3])
        assertNotEquals(traces[0], traces[2])
    }

    @Test
    fun aRedirectKeepsTheCallsSingleSpanId() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/v1/sync2").build())
        server.enqueue(ok(resource("sync_page.json")))
        api().pullSync(0, 1)
        // The application interceptor ran once, so only one span exists and one header was built.
        assertEquals(1, recorder.snapshot().size)
        val first = ids(server.takeRequest())
        // OkHttp copies the headers onto the redirected request, so the same value is sent again.
        assertEquals(first, ids(server.takeRequest()))
        assertEquals(first.spanId, recorder.snapshot().single().spanId)
    }

    // ---- spans ----

    @Test
    fun aSpanIsRecordedOnceForSuccessHttpErrorAndConnectionFailure() = runBlocking {
        server.enqueue(ok(resource("refresh_results.json")))
        server.enqueue(failure(500))
        val a = api()
        a.refreshAllFeeds()
        try {
            a.refreshAllFeeds()
            throw AssertionError("expected an ApiException")
        } catch (e: ApiException) {
            assertTrue(e.error is ApiError.Http)
        }
        val okIds = ids(server.takeRequest())
        val errIds = ids(server.takeRequest())
        server.close()
        try {
            a.refreshAllFeeds()
            throw AssertionError("expected an ApiException")
        } catch (e: ApiException) {
            assertTrue(e.error is ApiError.Unreachable)
        }

        val spans = recorder.snapshot()
        assertEquals(3, spans.size)
        assertEquals(ClientSpan("POST /v1/refresh", okIds.traceId, okIds.spanId, "POST", "/v1/refresh", 200, false), spans[0])
        assertEquals(ClientSpan("POST /v1/refresh", errIds.traceId, errIds.spanId, "POST", "/v1/refresh", 500, true), spans[1])
        assertEquals("client", spans[0].kind)
        assertNull(spans[2].statusCode)
        assertTrue(spans[2].error)
    }

    @Test
    fun anUnauthorizedCallStillSurfacesAsUnauthorized() = runBlocking {
        server.enqueue(failure(401))
        try {
            api().pullSync(0, 1)
            throw AssertionError("expected an ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiError.Unauthorized, e.error)
        }
        assertEquals(401, recorder.snapshot().single().statusCode)
    }

    @Test
    fun aRefusedCleartextCallStillSendsItsHeaderAndEndsItsSpanWithAnError() = runBlocking {
        // The guard is a network interceptor on the shared client, as AppModule installs it.
        val guarded = OkHttpClient.Builder()
            .dns { listOf(java.net.InetAddress.getLoopbackAddress()) }
            .addNetworkInterceptor(TokenGuardInterceptor(isDebuggable = false))
            .build()
        settings.saveConnection("http://publisher.test:${server.port}", TEST_TOKEN)
        try {
            api(guarded).pullSync(0, 1)
            throw AssertionError("expected an ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiError.CleartextRefused, e.error)
        }
        val span = recorder.snapshot().single()
        assertTrue(span.error)
        assertNull(span.statusCode)
    }

    @Test
    fun spanDataHoldsNoSecretsAndUsesRouteTemplates() = runBlocking {
        server.enqueue(ok(resource("search_results.json")))
        server.enqueue(ok(resource("feed.json")))
        server.enqueue(ok(resource("refresh_results.json")))
        val a = api()
        a.searchPodcasts("secret-term")
        a.subscribeFeed("https://example.test/feed?token=abc")
        a.refreshFeed(12)
        val headers = List(3) { server.takeRequest().headers["traceparent"]!! }
        val forbidden = listOf("secret-term", "token=abc", "example.test/feed", TEST_TOKEN, server.hostName)
        val spans = recorder.snapshot()
        val text = spans.joinToString("\n") { it.toString() } + headers.joinToString("\n")
        for (s in forbidden) assertFalse("leaked $s", text.contains(s))
        assertEquals(listOf("GET /v1/search", "POST /v1/feeds", "POST /v1/feeds/{id}/refresh"), spans.map { it.name })
    }

    @Test
    fun theRecorderIsBounded() = runBlocking {
        repeat(8) { server.enqueue(ok(resource("health.json"))) }
        val a = api()
        repeat(8) { a.health() }
        assertEquals(5, recorder.snapshot().size)
    }

    // ---- scope: nothing but PodClatchApi calls ----

    @Test
    fun clientsDerivedFromTheSharedClientNeverSendTheHeaderToAPublisher() {
        val publisher = MockWebServer()
        publisher.start()
        try {
            // Built the way AppModule.okHttpClient builds it (its TokenGuard is the only interceptor),
            // then handed to the API, which must not change it.
            val shared = OkHttpClient.Builder().addNetworkInterceptor(TokenGuardInterceptor(false)).build()
            api(shared)
            val audio = shared.newBuilder().followRedirects(true).callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
            val download = OkHttpClient.Builder().followRedirects(true).build() // as DownloadModule builds it
            val artwork = artworkHttpClient()
            for (client in listOf(audio, download, artwork)) {
                publisher.enqueue(MockResponse.Builder().code(200).body("x").build())
                client.newCall(Request.Builder().url(publisher.url("/episode.mp3")).build()).execute().close()
                assertNull(publisher.takeRequest().headers["traceparent"])
            }
            assertTrue(shared.interceptors.isEmpty())
        } finally {
            publisher.close()
        }
    }

    @Test
    fun theHeaderIsOnlyEverAddedByTheApiClient() = runBlocking {
        server.enqueue(ok(resource("health.json")))
        api().health()
        assertNotNull(server.takeRequest().headers["traceparent"])
    }
}
