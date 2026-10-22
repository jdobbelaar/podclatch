package com.podclatch

import androidx.work.NetworkType
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.repo.Settings
import com.podclatch.download.DownloadCoordinator
import com.podclatch.download.Downloads
import com.podclatch.download.JobState
import com.podclatch.playback.PlayDownloadInfo
import com.podclatch.playback.PlayDownloadStarter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** What a play request does to the download (US-12), against the real coordinator over fakes and against a recording fake. */
class PlayDownloadStarterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val store = FakeDownloadStore()
    private val scheduler = FakeDownloadScheduler()
    private val settings = FakeSettings(Settings(serverUrl = TEST_URL, authToken = TEST_TOKEN))
    private val dir by lazy { tmp.newFolder("audio") }
    private val coordinator by lazy { DownloadCoordinator(store, scheduler, FakeFileSystem(dir), settings) }
    private val statuses = mutableMapOf<Long, EpisodeStatus>()

    private fun starterOver(downloads: Downloads) = PlayDownloadStarter(
        lookup = { id ->
            val row = store.row(id)
            val status = statuses[id]
            if (row == null || status == null) null else PlayDownloadInfo(status, row.downloadState, row.localPath)
        },
        downloads = downloads,
        fileUsable = { path -> File(path).let { it.isFile && it.length() > 0 } },
        fileDispatcher = Dispatchers.Unconfined,
    )

    private val starter by lazy { starterOver(coordinator) }

    private fun episode(
        id: Long,
        state: DownloadState,
        status: EpisodeStatus = EpisodeStatus.NEW,
        path: String? = null,
        permanent: Boolean = false,
    ) {
        store.put(downloadRow(id, state = state, localPath = path, permanent = permanent))
        statuses[id] = status
    }

    private fun play(id: Long) = runBlocking { starter.onPlayRequested(id) }

    private fun usableFile(id: Long): String = File(dir, "$id.audio").also { it.writeText("audio") }.path

    @Test
    fun aNewEpisodeWithNoDownloadStartsOneManualDownloadOnAnyNetwork() {
        episode(10, DownloadState.NONE)
        play(10)
        assertEquals(DownloadState.DOWNLOADING, store.row(10)?.downloadState)
        assertEquals(listOf(Triple(10L, NetworkType.CONNECTED, true)), scheduler.enqueued.toList())
    }

    @Test
    fun theRequestIsManualEvenWhenWifiOnlyIsOn() {
        assertTrue(runBlocking { settings.current().downloadWifiOnly })
        episode(10, DownloadState.NONE)
        play(10)
        assertEquals(NetworkType.CONNECTED, scheduler.jobs.getValue(10).network)
    }

    @Test
    fun playReturnsWithoutWaitingForTheDownloadToFinish() {
        episode(10, DownloadState.NONE)
        // The scheduler never runs the job: the request returns as soon as the job is enqueued.
        play(10)
        assertEquals(JobState.WAITING, scheduler.jobs.getValue(10).state)
        assertEquals(DownloadState.DOWNLOADING, store.row(10)?.downloadState)
    }

    @Test
    fun aQueuedEpisodeIsPromotedToTheManualJobWithoutASecondJob() {
        episode(10, DownloadState.NONE)
        runBlocking { coordinator.requestAutomatic(10) }
        assertEquals(NetworkType.UNMETERED, scheduler.jobs.getValue(10).network)
        play(10)
        assertEquals(DownloadState.DOWNLOADING, store.row(10)?.downloadState)
        assertEquals(1, scheduler.jobs.size)
        assertEquals(NetworkType.CONNECTED, scheduler.jobs.getValue(10).network)
        assertTrue(scheduler.jobs.getValue(10).manual)
    }

    @Test
    fun aFailedEpisodeIsRequestedAgainAndAPermanentFailureMarkIsCleared() {
        episode(10, DownloadState.FAILED)
        episode(11, DownloadState.FAILED, permanent = true)
        play(10)
        play(11)
        assertEquals(DownloadState.DOWNLOADING, store.row(10)?.downloadState)
        assertEquals(DownloadState.DOWNLOADING, store.row(11)?.downloadState)
        assertFalse(store.row(11)?.downloadPermanentFailure ?: true)
        assertEquals(setOf(10L, 11L), scheduler.jobs.keys)
    }

    @Test
    fun aDownloadingEpisodeStartsNothingNew() {
        episode(10, DownloadState.DOWNLOADING)
        runBlocking { scheduler.enqueue(10, NetworkType.CONNECTED, manual = true, replace = false) }
        scheduler.start(10)
        scheduler.enqueued.clear()
        play(10)
        assertTrue(scheduler.enqueued.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
        assertEquals(JobState.RUNNING, scheduler.jobs.getValue(10).state)
        assertEquals(DownloadState.DOWNLOADING, store.row(10)?.downloadState)
    }

    @Test
    fun aDownloadedEpisodeWithItsFileStartsNothing() {
        episode(10, DownloadState.DOWNLOADED, path = usableFile(10))
        play(10)
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(DownloadState.DOWNLOADED, store.row(10)?.downloadState)
    }

    @Test
    fun aDownloadedEpisodeWhoseFileIsMissingOrEmptyIsRequestedAgain() {
        episode(10, DownloadState.DOWNLOADED, path = File(dir, "10.audio").path)
        val empty = File(dir, "11.audio").also { it.writeText("") }
        episode(11, DownloadState.DOWNLOADED, path = empty.path)
        play(10)
        play(11)
        assertEquals(DownloadState.DOWNLOADING, store.row(10)?.downloadState)
        assertEquals(DownloadState.DOWNLOADING, store.row(11)?.downloadState)
        assertNull(store.row(10)?.localPath)
        assertEquals(setOf(10L, 11L), scheduler.jobs.keys)
    }

    @Test
    fun aPlayedEpisodeStartsNoDownloadInAnyState() {
        episode(10, DownloadState.NONE, status = EpisodeStatus.PLAYED)
        episode(11, DownloadState.FAILED, status = EpisodeStatus.PLAYED)
        episode(12, DownloadState.DOWNLOADED, status = EpisodeStatus.PLAYED, path = usableFile(12))
        episode(13, DownloadState.DOWNLOADED, status = EpisodeStatus.PLAYED, path = File(dir, "13.audio").path)
        listOf(10L, 11L, 12L, 13L).forEach { play(it) }
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(DownloadState.NONE, store.row(10)?.downloadState)
        assertEquals(DownloadState.DOWNLOADED, store.row(12)?.downloadState)
    }

    @Test
    fun anUnknownEpisodeChangesNothing() {
        play(99)
        assertTrue(scheduler.enqueued.isEmpty())
        assertNull(store.row(99))
    }

    @Test
    fun tappingTheSameEpisodeTwiceOrTwoEpisodesAtOnceMakesOneJobEach() {
        episode(10, DownloadState.NONE)
        episode(11, DownloadState.NONE)
        runBlocking {
            listOf(10L, 10L, 11L, 10L, 11L).map { async(Dispatchers.Default) { starter.onPlayRequested(it) } }.awaitAll()
        }
        assertEquals(setOf(10L, 11L), scheduler.jobs.keys)
        assertEquals(setOf(10L, 11L), scheduler.enqueued.map { it.first }.toSet())
        assertEquals(DownloadState.DOWNLOADING, store.row(10)?.downloadState)
        assertEquals(DownloadState.DOWNLOADING, store.row(11)?.downloadState)
    }

    @Test
    fun playingOutOfOrderChangesNoOtherEpisode() {
        episode(10, DownloadState.NONE)
        episode(11, DownloadState.NONE)
        episode(12, DownloadState.DOWNLOADED, path = usableFile(12))
        play(11)
        assertEquals(DownloadState.NONE, store.row(10)?.downloadState)
        assertEquals(DownloadState.DOWNLOADING, store.row(11)?.downloadState)
        assertEquals(DownloadState.DOWNLOADED, store.row(12)?.downloadState)
        assertEquals(EpisodeStatus.NEW, statuses[11])
    }

    @Test
    fun theDecisionCallsTheDownloadsInterfaceExactlyOncePerPlayAndOnlyWhenNeeded() {
        val calls = CopyOnWriteArrayList<String>()
        val recording = object : Downloads {
            override suspend fun requestAutomatic(episodeId: Long) { calls += "auto $episodeId" }
            override suspend fun requestManual(episodeId: Long) { calls += "manual $episodeId" }
            override suspend fun remove(episodeId: Long) { calls += "remove $episodeId" }
        }
        episode(10, DownloadState.NONE)
        episode(11, DownloadState.DOWNLOADING)
        episode(12, DownloadState.QUEUED)
        val s = starterOver(recording)
        runBlocking { listOf(10L, 11L, 12L).forEach { s.onPlayRequested(it) } }
        assertEquals(listOf("manual 10", "manual 12"), calls.toList())
    }

    @Test
    fun aFailingRequestDoesNotThrowSoPlaybackGoesOn() {
        val failing = object : Downloads {
            override suspend fun requestAutomatic(episodeId: Long) = Unit
            override suspend fun requestManual(episodeId: Long): Unit = throw IllegalStateException("disk")
            override suspend fun remove(episodeId: Long) = Unit
        }
        episode(10, DownloadState.NONE)
        runBlocking { starterOver(failing).onPlayRequested(10) }
    }

    @Test
    fun aRequestThatNeverCompletesDoesNotBlockTheCallerWhenLaunchedAsTheServiceDoes() {
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val hanging = object : Downloads {
            override suspend fun requestAutomatic(episodeId: Long) = Unit
            override suspend fun requestManual(episodeId: Long) {
                reached.complete(Unit)
                gate.await()
            }
            override suspend fun remove(episodeId: Long) = Unit
        }
        episode(10, DownloadState.NONE)
        val s = starterOver(hanging)
        @Suppress("OPT_IN_USAGE")
        val job = GlobalScope.async { s.onPlayRequested(10) }
        awaitGate("the request reached the Downloads interface", reached)
        assertFalse(job.isCompleted)
        gate.complete(Unit)
        runBlocking { job.await() }
    }
}
