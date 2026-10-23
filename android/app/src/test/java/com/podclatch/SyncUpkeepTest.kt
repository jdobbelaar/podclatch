package com.podclatch

import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.SyncPageDto
import com.podclatch.domain.AutoDownloadTopUp
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.InsertedEpisode
import com.podclatch.domain.LocalUpkeep
import com.podclatch.domain.OrderedLocalUpkeep
import com.podclatch.domain.PullSummary
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncResult
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.TokenRejection
import com.podclatch.domain.UpkeepStep
import com.podclatch.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real upkeep list inside the real [SyncEngine], and what the pull hands to the upkeep. */
class SyncUpkeepTest {
    private class Env(val settings: FakeSettings = FakeSettings()) {
        val api = FakeApi()
        val store = FakeCacheStore()
        val downloads = RecordingDownloads(store)
        val summaries = mutableListOf<PullSummary>()
        var failingStep: UpkeepStep? = null
        val reports = mutableListOf<String>()

        private val upkeep: LocalUpkeep = OrderedLocalUpkeep(
            listOf(
                "failing" to UpkeepStep { failingStep?.run(it) },
                "top-up" to AutoDownloadTopUp(store, settings, downloads),
                "record" to UpkeepStep { summaries += it },
            ),
            onStepFailed = { step, errorClass -> reports += "$step:$errorClass" },
        )
        val engine = SyncEngine(
            api, settings, store, upkeep, { 5_000 }, TokenRejection(settings), DiskFileDeleter(), Dispatchers.Unconfined,
        )

        fun sync(trigger: SyncTrigger = SyncTrigger.MANUAL) = runBlocking { engine.sync(trigger) }

        fun pullReturns(vararg pages: SyncPageDto) {
            var i = 0
            api.pullHandler = { _, _ -> pages[minOf(i++, pages.size - 1)] }
        }

        fun cursorAt(rev: Long) {
            settings.flow.value = settings.flow.value.copy(lastSyncRev = rev)
        }
    }

    @Test
    fun theTopUpRunsAfterEverySuccessfulSyncWhateverTheTrigger() {
        for (trigger in SyncTrigger.entries) {
            val env = Env()
            env.store.feeds[1] = cachedFeed(1)
            env.store.episodes[10] = cachedEpisode(10, 1)
            assertEquals(SyncResult.Success, env.sync(trigger))
            assertEquals("trigger $trigger", listOf(10L), env.downloads.automatic)
            assertEquals(trigger, env.summaries.single().trigger)
        }
    }

    @Test
    fun theTopUpSeesEpisodesThePullJustInserted() {
        val env = Env()
        env.pullReturns(page(feeds = listOf(feedDto(1)), episodes = listOf(episodeDto(10, 1), episodeDto(11, 1)), nextRev = 5))
        env.sync()
        assertEquals(setOf(10L, 11L), env.downloads.automatic.toSet())
    }

    @Test
    fun aFailingStepDoesNotFailTheSyncOrStopTheTopUp() {
        val env = Env()
        env.store.feeds[1] = cachedFeed(1)
        env.store.episodes[10] = cachedEpisode(10, 1)
        env.failingStep = UpkeepStep { throw java.io.IOException("could not read http://audio.example.com/a.mp3?token=abc") }
        assertEquals(SyncResult.Success, env.sync())
        assertEquals(listOf(10L), env.downloads.automatic)
        assertEquals(listOf("failing:IOException"), env.reports)
        assertNull(env.engine.state.value.lastFailure)
    }

    @Test
    fun aRoomErrorInsideTheTopUpDoesNotFailTheSync() {
        val env = Env()
        env.store.feeds[1] = cachedFeed(1)
        env.store.episodes[10] = cachedEpisode(10, 1)
        val broken = object : Downloads by env.downloads {
            override suspend fun requestAutomatic(episodeId: Long) = throw IllegalStateException("database is locked")
        }
        val upkeep = OrderedLocalUpkeep(listOf("top-up" to AutoDownloadTopUp(env.store, env.settings, broken)))
        val engine = SyncEngine(
            env.api, env.settings, env.store, upkeep, { 5_000 }, TokenRejection(env.settings), DiskFileDeleter(), Dispatchers.Unconfined,
        )
        assertEquals(SyncResult.Success, runBlocking { engine.sync(SyncTrigger.PERIODIC) })
    }

    @Test
    fun anUnreachableServerFailsTheSyncAndTheTopUpDoesNotRun() {
        val env = Env()
        env.store.feeds[1] = cachedFeed(1)
        env.store.episodes[10] = cachedEpisode(10, 1)
        env.api.pullHandler = { _, _ -> throw ApiException(ApiError.Unreachable(false)) }
        assertTrue(env.sync() is SyncResult.Failed)
        assertTrue(env.downloads.automatic.isEmpty())
        assertTrue(env.summaries.isEmpty())

        // The next trigger, with the server back, runs it.
        env.api.pullHandler = { s, _ -> page(nextRev = s + 1) }
        assertEquals(SyncResult.Success, env.sync(SyncTrigger.PERIODIC))
        assertEquals(listOf(10L), env.downloads.automatic)
    }

    // ---- what the pull hands over ----

    @Test
    fun insertedNewEpisodesAreReportedAndChangedOrPlayedOnesAreNot() {
        val env = Env()
        env.store.feeds[1] = cachedFeed(1)
        env.store.episodes[10] = cachedEpisode(10, 1)
        env.cursorAt(3)
        env.pullReturns(
            page(
                episodes = listOf(
                    episodeDto(10, 1, title = "Changed title"),
                    episodeDto(11, 1),
                    episodeDto(12, 1, status = EpisodeStatus.PLAYED, playedAt = 1, stateUpdatedAt = 2_000),
                ),
                nextRev = 9,
            ),
        )
        env.sync(SyncTrigger.PERIODIC)
        val summary = env.summaries.single()
        assertEquals(SyncTrigger.PERIODIC, summary.trigger)
        assertFalse(summary.firstPull)
        assertEquals(listOf(InsertedEpisode(11, 1, feedWasCached = true)), summary.insertedNew)
    }

    @Test
    fun aFeedThePullBroughtInIsMarkedAsNotCachedBefore() {
        val env = Env()
        env.store.feeds[1] = cachedFeed(1)
        env.cursorAt(3)
        env.pullReturns(
            page(
                feeds = listOf(feedDto(2)),
                episodes = listOf(episodeDto(11, 1), episodeDto(20, 2), episodeDto(21, 2)),
                nextRev = 9,
            ),
        )
        env.sync(SyncTrigger.PERIODIC)
        val byEpisode = env.summaries.single().insertedNew.associate { it.episodeId to it.feedWasCached }
        assertEquals(mapOf(11L to true, 20L to false, 21L to false), byEpisode)
    }

    @Test
    fun anEpisodeThatArrivesOnTwoPagesIsReportedOnce() {
        val env = Env()
        env.store.feeds[1] = cachedFeed(1)
        env.cursorAt(3)
        env.pullReturns(
            page(episodes = listOf(episodeDto(11, 1)), nextRev = 6, hasMore = true),
            page(episodes = listOf(episodeDto(11, 1, rev = 8)), nextRev = 9),
        )
        env.sync(SyncTrigger.PERIODIC)
        assertEquals(listOf(11L), env.summaries.single().insertedNew.map { it.episodeId })
    }

    @Test
    fun theFirstPullIsMarkedAsSuch() {
        val env = Env()
        env.pullReturns(page(feeds = listOf(feedDto(1)), episodes = listOf(episodeDto(10, 1)), nextRev = 5))
        env.sync(SyncTrigger.PERIODIC)
        val summary = env.summaries.single()
        assertTrue(summary.firstPull)
        assertEquals(listOf(InsertedEpisode(10, 1, feedWasCached = false)), summary.insertedNew)
        assertEquals(DownloadState.QUEUED, env.store.episodes.getValue(10).downloadState)
    }
}
