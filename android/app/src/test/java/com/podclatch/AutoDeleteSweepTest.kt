package com.podclatch

import com.podclatch.Config
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.SweepRow
import com.podclatch.data.repo.Settings
import com.podclatch.domain.AutoDeleteSweep
import com.podclatch.domain.AutoDownloadTopUp
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.LoadedEpisodeSource
import com.podclatch.domain.LocalUpkeep
import com.podclatch.domain.OrderedLocalUpkeep
import com.podclatch.domain.PullSummary
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncResult
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.TokenRejection
import com.podclatch.domain.planSweep
import com.podclatch.download.DownloadCoordinator
import com.podclatch.download.DownloadPaths
import com.podclatch.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val MIN = 60_000L
private const val NOW = 1_000_000_000L

class SweepPlanTest {
    private fun row(id: Long, playedAt: Long?, state: DownloadState = DownloadState.DOWNLOADED, path: String? = "p") =
        SweepRow(id, playedAt, state, path)

    private fun plan(vararg rows: SweepRow, loaded: Long? = null) =
        planSweep(rows.toList(), NOW, loaded, Config.PLAYED_DELETE_DELAY.inWholeMilliseconds)

    @Test
    fun theDelayIsOneHour() {
        assertEquals(60 * MIN, Config.PLAYED_DELETE_DELAY.inWholeMilliseconds)
    }

    @Test
    fun fiftyNineMinutesStaysSixtyAndSixtyOneGo() {
        assertEquals(emptyList<Long>(), plan(row(1, NOW - 59 * MIN)))
        assertEquals(listOf(2L), plan(row(2, NOW - 60 * MIN)))
        assertEquals(listOf(3L), plan(row(3, NOW - 61 * MIN)))
        assertEquals("one millisecond short", emptyList<Long>(), plan(row(4, NOW - 60 * MIN + 1)))
    }

    @Test
    fun aLocalPathCountsAsADownloadEvenIfTheStateSaysOtherwise() {
        assertEquals(listOf(1L), plan(row(1, NOW - 120 * MIN, DownloadState.NONE, "p")))
    }

    @Test
    fun anIncompleteDownloadGoesAtOnceWhateverItsAge() {
        for (state in listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.FAILED)) {
            assertEquals(state.name, listOf(1L), plan(row(1, NOW, state, null)))
        }
    }

    @Test
    fun theLoadedEpisodeAndANullPlayedAtAreSkipped() {
        assertEquals(listOf(2L), plan(row(1, NOW - 120 * MIN), row(2, NOW - 120 * MIN), loaded = 1))
        assertEquals(emptyList<Long>(), plan(row(1, null), row(2, null, DownloadState.DOWNLOADING, null)))
    }

    @Test
    fun aRowWithNothingToDeleteIsSkipped() {
        assertEquals(emptyList<Long>(), plan(row(1, NOW - 120 * MIN, DownloadState.NONE, null)))
    }
}

/** The sweep against the in-memory cache and the real [DownloadCoordinator] (fake store and WorkManager, real temp files). */
class AutoDeleteSweepTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = NOW
    private val cache = FakeCacheStore()
    private val downloadStore = FakeDownloadStore()
    private val scheduler = FakeDownloadScheduler()
    private val settings = FakeSettings(Settings(serverUrl = TEST_URL, authToken = TEST_TOKEN))
    private val dir by lazy { tmp.newFolder("audio") }
    private val coordinator by lazy { DownloadCoordinator(downloadStore, scheduler, FakeFileSystem(dir), settings) }
    private var loaded: Long? = null
    private val sweep by lazy { AutoDeleteSweep(cache, settings, coordinator, { loaded }, { now }) }
    private val pull = PullSummary(SyncTrigger.MANUAL, firstPull = false)

    private fun run() = runBlocking { sweep.run(pull) }

    /** A cached Episode and its download row, with a real file when DOWNLOADED and a partial file when in flight. */
    private fun episode(
        id: Long,
        status: EpisodeStatus = EpisodeStatus.PLAYED,
        playedAgoMin: Long? = 0,
        state: DownloadState = DownloadState.DOWNLOADED,
    ): File? {
        val file = if (state == DownloadState.DOWNLOADED) DownloadPaths.finalFile(dir, id).also { it.writeText("audio") } else null
        cache.episodes[id] = cachedEpisode(
            id, 1, status = status, playedAt = playedAgoMin?.let { now - it * MIN }, downloadState = state, localPath = file?.path,
        )
        downloadStore.put(downloadRow(id, state = state, localPath = file?.path))
        if (state != DownloadState.DOWNLOADED) DownloadPaths.partial(dir, id).writeText("part")
        if (state == DownloadState.QUEUED || state == DownloadState.DOWNLOADING) {
            runBlocking { scheduler.enqueue(id, androidx.work.NetworkType.CONNECTED, manual = false, replace = false) }
        }
        return file
    }

    private fun stateOf(id: Long) = downloadStore.row(id)!!.downloadState

    @Test
    fun fiftyNineMinutesIsKeptSixtyAndSixtyOneAreDeleted() {
        val kept = episode(1, playedAgoMin = 59)
        val exact = episode(2, playedAgoMin = 60)
        val old = episode(3, playedAgoMin = 61)
        run()
        assertTrue(kept!!.exists())
        assertEquals(DownloadState.DOWNLOADED, stateOf(1))
        assertFalse(exact!!.exists())
        assertEquals(DownloadState.NONE, stateOf(2))
        assertNull(downloadStore.row(2)!!.localPath)
        assertFalse(old!!.exists())
        assertEquals(DownloadState.NONE, stateOf(3))
    }

    @Test
    fun anEpisodeBroughtBackToNewBeforeTheSweepIsKept() {
        val file = episode(1, status = EpisodeStatus.NEW, playedAgoMin = 120)
        run()
        assertTrue(file!!.exists())
        assertEquals(DownloadState.DOWNLOADED, stateOf(1))
    }

    @Test
    fun theLoadedEpisodeIsKeptUntilItIsNoLongerLoaded() {
        val file = episode(1, playedAgoMin = 120)
        loaded = 1
        run()
        assertTrue(file!!.exists())
        assertEquals(DownloadState.DOWNLOADED, stateOf(1))
        loaded = null
        run()
        assertFalse(file.exists())
        assertEquals(DownloadState.NONE, stateOf(1))
    }

    @Test
    fun withTheSettingOffNothingHappensAndTurningItOnDeletesAtTheNextSync() {
        val file = episode(1, playedAgoMin = 120)
        episode(2, playedAgoMin = 0, state = DownloadState.DOWNLOADING)
        runBlocking { settings.setAutoDeletePlayedDownloads(false) }
        run()
        assertTrue(file!!.exists())
        assertEquals(DownloadState.DOWNLOADED, stateOf(1))
        assertEquals(DownloadState.DOWNLOADING, stateOf(2))
        assertTrue(scheduler.cancelled.isEmpty())
        runBlocking { settings.setAutoDeletePlayedDownloads(true) }
        run()
        assertFalse(file.exists())
        assertEquals(DownloadState.NONE, stateOf(1))
        assertEquals(DownloadState.NONE, stateOf(2))
    }

    @Test
    fun otherEpisodesAreNeverTouched() {
        val newWithFile = episode(1, status = EpisodeStatus.NEW, playedAgoMin = null)
        episode(2, playedAgoMin = 120, state = DownloadState.NONE)
        val young = episode(3, playedAgoMin = 5)
        val noPlayedAt = episode(4, playedAgoMin = null)
        run()
        assertTrue(newWithFile!!.exists())
        assertTrue(young!!.exists())
        assertTrue(noPlayedAt!!.exists())
        assertEquals(
            listOf(DownloadState.DOWNLOADED, DownloadState.NONE, DownloadState.DOWNLOADED, DownloadState.DOWNLOADED),
            (1L..4).map { stateOf(it) },
        )
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun anIncompleteDownloadOfAPlayedEpisodeIsCancelledWithoutTheDelay() {
        episode(1, playedAgoMin = 0, state = DownloadState.QUEUED)
        episode(2, playedAgoMin = 0, state = DownloadState.DOWNLOADING)
        episode(3, playedAgoMin = 0, state = DownloadState.FAILED)
        run()
        for (id in 1L..3L) {
            assertEquals(DownloadState.NONE, stateOf(id))
            assertFalse("partial of $id", DownloadPaths.partial(dir, id).exists())
        }
        assertEquals(setOf(1L, 2L, 3L), scheduler.cancelled.toSet())
    }

    @Test
    fun aFileThatIsAlreadyGoneStillEndsNone() {
        val file = episode(1, playedAgoMin = 120)
        assertTrue(file!!.delete())
        run()
        assertEquals(DownloadState.NONE, stateOf(1))
        assertNull(downloadStore.row(1)!!.localPath)
    }

    @Test
    fun aFileThatCannotBeDeletedStaysDownloadedAndTheOthersAreStillSwept() {
        // A directory with a child cannot be deleted like a file: the stand-in for a failed delete.
        val stuck = File(dir, "stuck").also { it.mkdirs(); File(it, "child").writeText("x") }
        cache.episodes[1] = cachedEpisode(
            1, 1, status = EpisodeStatus.PLAYED, playedAt = now - 120 * MIN, downloadState = DownloadState.DOWNLOADED, localPath = stuck.path,
        )
        downloadStore.put(downloadRow(1, state = DownloadState.DOWNLOADED, localPath = stuck.path))
        val fine = episode(2, playedAgoMin = 120)
        run()
        assertEquals(DownloadState.DOWNLOADED, stateOf(1))
        assertEquals(stuck.path, downloadStore.row(1)!!.localPath)
        assertFalse(fine!!.exists())
        assertEquals(DownloadState.NONE, stateOf(2))
        // The next Sync retries and succeeds once the obstacle is gone.
        File(stuck, "child").delete()
        run()
        assertEquals(DownloadState.NONE, stateOf(1))
    }

    @Test
    fun anExceptionFromOneRemovalDoesNotStopTheOthersOrReachTheCaller() {
        episode(1, playedAgoMin = 120)
        episode(2, playedAgoMin = 120)
        val removed = mutableListOf<Long>()
        val downloads = object : Downloads {
            override suspend fun requestAutomatic(episodeId: Long) = Unit
            override suspend fun requestManual(episodeId: Long) = Unit
            override suspend fun remove(episodeId: Long) {
                removed += episodeId
                if (episodeId == 1L) throw java.io.IOException("could not delete http://audio.example.com/a.mp3?token=abc")
            }
        }
        runBlocking { AutoDeleteSweep(cache, settings, downloads, { null }, { now }).run(pull) }
        assertEquals(listOf(1L, 2L), removed.sorted())
    }

    @Test
    fun markAllPlayedDeletesNothingForAnHourThenTheSweepTakesThem() {
        val justMarked = episode(1, playedAgoMin = 0)
        run()
        assertTrue(justMarked!!.exists())
        now += 61 * MIN
        run()
        assertFalse("an hour and a minute later", justMarked.exists())
    }

    @Test
    fun theRealUpkeepRunsTheSweepThenTheTopUpAndAServerPlayedStateIsSweptInTheSameSync() {
        cache.feeds[1] = cachedFeed(1)
        val calls = mutableListOf<String>()
        val recording = object : Downloads {
            override suspend fun requestAutomatic(episodeId: Long) {
                calls += "request $episodeId"
            }
            override suspend fun requestManual(episodeId: Long) = Unit
            override suspend fun remove(episodeId: Long) {
                calls += "remove $episodeId"
                coordinator.remove(episodeId)
            }
        }
        // Episode 1 is New with a file here; the pull brings in the server's Played state from two hours ago.
        val file = episode(1, status = EpisodeStatus.NEW, playedAgoMin = null)
        cache.episodes[2] = cachedEpisode(2, 1)
        val api = FakeApi()
        api.pullHandler = { _, _ ->
            page(
                episodes = listOf(episodeDto(1, 1, rev = 5, status = EpisodeStatus.PLAYED, playedAt = now - 120 * MIN, stateUpdatedAt = 9_000)),
                nextRev = 5,
            )
        }
        val upkeep: LocalUpkeep = OrderedLocalUpkeep(
            listOf(
                "auto-delete sweep" to AutoDeleteSweep(cache, settings, recording, LoadedEpisodeSource { null }, { now }),
                "top-up" to AutoDownloadTopUp(cache, settings, recording),
            ),
        )
        val engine = SyncEngine(api, settings, cache, upkeep, { now }, TokenRejection(settings), DiskFileDeleter(), Dispatchers.Unconfined)
        assertEquals(SyncResult.Success, runBlocking { engine.sync(SyncTrigger.PERIODIC) })
        assertFalse(file!!.exists())
        assertEquals("the sweep first; the swept Episode is Played, so never a top-up candidate", listOf("remove 1", "request 2"), calls)
    }
}
