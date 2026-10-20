package com.podclatch

import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.PendingStateWrite
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.EpisodeStateResult
import com.podclatch.data.network.StateOutcome
import com.podclatch.data.network.TombstoneEntityType
import com.podclatch.data.repo.Settings
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.FailureReason
import com.podclatch.domain.LocalUpkeep
import com.podclatch.domain.SkipReason
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncResult
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.TokenRejection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Env(
        val settings: FakeSettings = FakeSettings(),
        pageLimit: Int = 500,
        pushBatchLimit: Int = 1000,
    ) {
        val api = FakeApi()
        val store = FakeCacheStore()
        var now = 5_000L
        val order = mutableListOf<String>()
        val upkeep = LocalUpkeep { order += "upkeep" }
        val rejection = TokenRejection(settings)
        val engine = SyncEngine(
            api, settings, store, upkeep, { now }, rejection, DiskFileDeleter(), Dispatchers.Unconfined,
            pageLimit, pushBatchLimit,
        )

        init {
            val pushHandler = api.pushHandler
            api.pushHandler = { order += "push"; pushHandler(it) }
            val pullHandler = api.pullHandler
            api.pullHandler = { s, l -> order += "pull"; pullHandler(s, l) }
        }

        fun sync(trigger: SyncTrigger = SyncTrigger.MANUAL) = runBlocking { engine.sync(trigger) }
        val rev get() = settings.flow.value.lastSyncRev
        fun addPending(episodeId: Long, stateUpdatedAt: Long, status: EpisodeStatus = EpisodeStatus.PLAYED) {
            store.pending[episodeId] = PendingStateWrite(
                episodeId, status, if (status == EpisodeStatus.PLAYED) stateUpdatedAt else null, 30, stateUpdatedAt,
            )
        }
    }

    // ---- order, configuration ----

    @Test
    fun runsPushThenPullThenUpkeepOnce() {
        val env = Env()
        env.store.feeds[1] = cachedFeed(1)
        env.store.episodes[10] = cachedEpisode(10, 1)
        env.addPending(10, 3_000)
        env.api.pullHandler = { s, _ -> env.order += "pull"; page(nextRev = s + 1) }

        assertEquals(SyncResult.Success, env.sync())
        assertEquals(listOf("push", "pull", "upkeep"), env.order)
    }

    @Test
    fun upkeepIsNotCalledWhenPushFails() {
        val env = Env()
        env.addPending(10, 3_000)
        env.api.pushHandler = { throw ApiException(ApiError.Http(500, null)) }
        val result = env.sync()
        assertTrue(result is SyncResult.Failed)
        assertEquals(listOf<String>(), env.order)
        assertEquals(listOf("push"), env.api.calls)
    }

    @Test
    fun upkeepIsNotCalledWhenPullFails() {
        val env = Env()
        env.api.pullHandler = { _, _ -> throw ApiException(ApiError.Unreachable(false)) }
        assertTrue(env.sync() is SyncResult.Failed)
        assertFalse("upkeep" in env.order)
    }

    @Test
    fun notConfiguredIsSkippedWithoutNetworkAndIsNotAFailure() {
        for (settings in listOf(Settings(), Settings(serverUrl = TEST_URL), Settings(authToken = TEST_TOKEN))) {
            val env = Env(FakeSettings(settings))
            assertEquals(SyncResult.Skipped(SkipReason.NotConfigured), env.sync(SyncTrigger.PERIODIC))
            assertEquals(emptyList<String>(), env.api.calls)
            assertNull(env.engine.state.value.lastFailure)
            assertFalse(env.engine.state.value.running)
        }
    }

    // ---- push ----

    @Test
    fun emptyOutboxMakesNoPushCall() {
        val env = Env()
        env.sync()
        assertEquals(listOf("pull"), env.api.calls)
    }

    @Test
    fun appliedStaleAndUnknownAllSettleTheirRows() {
        val env = Env()
        env.store.feeds[1] = cachedFeed(1)
        for (id in 10L..12L) env.store.episodes[id] = cachedEpisode(id, 1, EpisodeStatus.PLAYED, 3_000, 30, 3_000)
        for (id in 10L..12L) env.addPending(id, 3_000)
        env.api.pushHandler = {
            listOf(
                EpisodeStateResult(10, StateOutcome.APPLIED, EpisodeStatus.PLAYED, 3_000, 30, 3_000),
                // Server has a newer NEW state: it must replace the local row (server wins).
                EpisodeStateResult(11, StateOutcome.STALE, EpisodeStatus.NEW, null, 7, 9_000),
                EpisodeStateResult(12, StateOutcome.UNKNOWN_EPISODE),
            )
        }
        assertEquals(SyncResult.Success, env.sync())
        assertTrue(env.store.pending.isEmpty())
        val stale = env.store.episodes.getValue(11)
        assertEquals(EpisodeStatus.NEW, stale.status)
        assertNull(stale.playedAt)
        assertEquals(7, stale.positionSec)
        assertEquals(9_000L, stale.stateUpdatedAt)
        // APPLIED and UNKNOWN leave the local state as it was.
        assertEquals(EpisodeStatus.PLAYED, env.store.episodes.getValue(10).status)
        assertEquals(EpisodeStatus.PLAYED, env.store.episodes.getValue(12).status)
    }

    @Test
    fun aNewerLocalWriteMadeDuringThePushSurvives() {
        val env = Env()
        env.store.feeds[1] = cachedFeed(1)
        env.store.episodes[10] = cachedEpisode(10, 1, EpisodeStatus.PLAYED, 3_000, 30, 3_000)
        env.addPending(10, 3_000)
        env.api.pushHandler = {
            // The user marks the Episode New while the call is in flight.
            env.store.pending[10] = PendingStateWrite(10, EpisodeStatus.NEW, null, 0, 4_000)
            env.store.episodes[10] = env.store.episodes.getValue(10).copy(status = EpisodeStatus.NEW, playedAt = null, positionSec = 0, stateUpdatedAt = 4_000)
            listOf(EpisodeStateResult(10, StateOutcome.STALE, EpisodeStatus.PLAYED, 9_000, 99, 9_000))
        }
        env.sync()
        assertEquals(4_000L, env.store.pending.getValue(10).stateUpdatedAt)
        val ep = env.store.episodes.getValue(10)
        assertEquals(EpisodeStatus.NEW, ep.status)
        assertEquals(4_000L, ep.stateUpdatedAt)
    }

    @Test
    fun aFailedPushKeepsEveryOutboxRowAndSkipsThePull() {
        val failures = listOf(
            ApiError.Unreachable(false), ApiError.Unreachable(true), ApiError.Unauthorized,
            ApiError.Http(422, "bad"), ApiError.Http(500, null), ApiError.Malformed,
        )
        for (error in failures) {
            val env = Env(FakeSettings(Settings(TEST_URL, TEST_TOKEN, lastSyncRev = 42)))
            env.addPending(10, 3_000)
            env.addPending(11, 3_100)
            val before = env.store.pending.toMap()
            env.api.pushHandler = { throw ApiException(error) }
            val result = env.sync()
            assertEquals(SyncResult.Failed(FailureReason.Api(error)), result)
            assertEquals(before, env.store.pending.toMap())
            assertEquals(42L, env.rev)
            assertEquals(listOf("push"), env.api.calls)
        }
    }

    @Test
    fun aResultListOfTheWrongSizeIsMalformedAndKeepsTheOutbox() {
        val env = Env()
        env.addPending(10, 3_000)
        env.addPending(11, 3_100)
        env.api.pushHandler = { listOf(EpisodeStateResult(10, StateOutcome.APPLIED, EpisodeStatus.PLAYED, 3_000, 30, 3_000)) }
        val result = env.sync()
        assertEquals(SyncResult.Failed(FailureReason.Api(ApiError.Malformed)), result)
        assertEquals(2, env.store.pending.size)
        assertEquals(listOf("push"), env.api.calls)
    }

    @Test
    fun aLargeOutboxIsSentInBatches() {
        val env = Env(pushBatchLimit = 2)
        for (id in 1L..5L) env.addPending(id, 3_000 + id)
        env.sync()
        assertEquals(listOf(2, 2, 1), env.api.pushRequests.map { it.size })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), env.api.pushRequests.flatten().map { it.episodeId })
        assertTrue(env.store.pending.isEmpty())
    }

    // ---- pull ----

    @Test
    fun pagesAreFetchedUntilHasMoreIsFalseAndTheLastNextRevIsStored() {
        val env = Env(FakeSettings(Settings(TEST_URL, TEST_TOKEN, lastSyncRev = 7)), pageLimit = 3)
        val pages = mapOf(
            7L to page(feeds = listOf(feedDto(1, rev = 8)), episodes = listOf(episodeDto(10, 1, rev = 9)), nextRev = 9, hasMore = true),
            9L to page(episodes = listOf(episodeDto(11, 1, rev = 10), episodeDto(12, 1, rev = 11)), nextRev = 11, hasMore = true),
            11L to page(episodes = listOf(episodeDto(13, 1, rev = 12)), nextRev = 12, hasMore = false),
        )
        env.api.pullHandler = { s, _ -> pages.getValue(s) }
        assertEquals(SyncResult.Success, env.sync())
        assertEquals(listOf(7L to 3, 9L to 3, 11L to 3), env.api.pullRequests)
        assertEquals(setOf(1L), env.store.feeds.keys)
        assertEquals(setOf(10L, 11L, 12L, 13L), env.store.episodes.keys)
        assertEquals(12L, env.rev)
    }

    @Test
    fun theDefaultPageLimitIs500() {
        val env = Env()
        env.sync()
        assertEquals(listOf(0L to Config.SYNC_PAGE_LIMIT), env.api.pullRequests)
        assertEquals(500, Config.SYNC_PAGE_LIMIT)
    }

    @Test
    fun aPageThatClaimsMoreButDoesNotAdvanceFailsAsMalformed() {
        val env = Env(FakeSettings(Settings(TEST_URL, TEST_TOKEN, lastSyncRev = 5)))
        env.api.pullHandler = { s, _ -> page(feeds = listOf(feedDto(1)), nextRev = s, hasMore = true) }
        val result = env.sync()
        assertEquals(SyncResult.Failed(FailureReason.Api(ApiError.Malformed)), result)
        assertEquals(1, env.api.pullRequests.size)
        assertTrue(env.store.feeds.isEmpty())
        assertEquals(5L, env.rev)
    }

    @Test
    fun anEmptyFirstPageLeavesTheCursorAlone() {
        val env = Env(FakeSettings(Settings(TEST_URL, TEST_TOKEN, lastSyncRev = 5)))
        assertEquals(SyncResult.Success, env.sync())
        assertEquals(5L, env.rev)
    }

    @Test
    fun aFailureOnALaterPageChangesNothing() {
        val errors = listOf<Throwable>(
            ApiException(ApiError.Http(500, null)), ApiException(ApiError.Unauthorized),
            ApiException(ApiError.Unreachable(false)), ApiException(ApiError.Malformed),
        )
        for (error in errors) {
            val env = Env(FakeSettings(Settings(TEST_URL, TEST_TOKEN, lastSyncRev = 3)))
            env.api.pullHandler = { s, _ ->
                if (s == 3L) page(feeds = listOf(feedDto(1)), episodes = listOf(episodeDto(10, 1)), nextRev = 6, hasMore = true)
                else throw error
            }
            assertTrue(env.sync() is SyncResult.Failed)
            assertEquals(2, env.api.pullRequests.size)
            assertTrue("no row from page 1 may be stored", env.store.feeds.isEmpty() && env.store.episodes.isEmpty())
            assertEquals(3L, env.rev)
        }
    }

    @Test
    fun aTransactionThatThrowsLeavesTheStoreAndTheCursorUnchanged() {
        val env = Env(FakeSettings(Settings(TEST_URL, TEST_TOKEN, lastSyncRev = 3)))
        env.store.feeds[1] = cachedFeed(1)
        env.api.pullHandler = { _, _ -> page(feeds = listOf(feedDto(1, title = "New")), nextRev = 9) }
        env.store.failNextTransaction = IllegalStateException("disk full")
        val result = env.sync()
        assertEquals(SyncResult.Failed(FailureReason.Local), result)
        assertEquals("Old", env.store.feeds.getValue(1).title)
        assertEquals(3L, env.rev)
        // The next trigger retries from the same cursor and succeeds.
        assertEquals(SyncResult.Success, env.sync())
        assertEquals("New", env.store.feeds.getValue(1).title)
        assertEquals(9L, env.rev)
        assertEquals(listOf(3L, 3L), env.api.pullRequests.map { it.first })
    }

    @Test
    fun aMidTransactionFailureRollsBackEarlierWrites() {
        val env = Env()
        env.api.pullHandler = { _, _ ->
            page(feeds = listOf(feedDto(1)), episodes = listOf(episodeDto(10, 1), episodeDto(11, 1)), nextRev = 4)
        }
        val store = env.store
        // The store fails on the second Episode, after the Feed and the first Episode were written.
        val failing = object : com.podclatch.data.db.CacheStore by store {
            override suspend fun <R> transaction(block: suspend com.podclatch.data.db.CacheTransaction.() -> R): R =
                store.transaction {
                    val real = this
                    val wrapped = object : com.podclatch.data.db.CacheTransaction by real {
                        override suspend fun insertEpisodeIfAbsent(episode: com.podclatch.data.db.Episode) {
                            if (episode.id == 11L) throw IOException("boom")
                            real.insertEpisodeIfAbsent(episode)
                        }
                    }
                    wrapped.block()
                }
        }
        val engine = SyncEngine(env.api, env.settings, failing, { }, { 0 }, env.rejection, DiskFileDeleter(), Dispatchers.Unconfined)
        val result = runBlocking { engine.sync(SyncTrigger.MANUAL) }
        assertEquals(SyncResult.Failed(FailureReason.Local), result)
        assertTrue(store.feeds.isEmpty() && store.episodes.isEmpty())
        assertEquals(0L, env.rev)
    }

    @Test
    fun aFailedRunNeverAdvancesTheCursorAndTheNextTriggerRetriesFromIt() {
        val env = Env(FakeSettings(Settings(TEST_URL, TEST_TOKEN, lastSyncRev = 3)))
        env.store.feeds[1] = cachedFeed(1)
        env.api.pullHandler = { _, _ -> throw ApiException(ApiError.Http(503, null)) }
        env.sync()
        assertEquals(3L, env.rev)
        assertEquals(1, env.store.feeds.size)
        env.api.pullHandler = { _, _ -> page(nextRev = 8) }
        assertEquals(SyncResult.Success, env.sync(SyncTrigger.PERIODIC))
        assertEquals(listOf(3L, 3L), env.api.pullRequests.map { it.first })
        assertEquals(8L, env.rev)
    }

    // ---- apply: tombstones and files ----

    @Test
    fun anEpisodeTombstoneDeletesTheRowAndItsFile() {
        val env = Env()
        val file = tmp.newFile("audio-10.mp3")
        env.store.feeds[1] = cachedFeed(1)
        env.store.episodes[10] = cachedEpisode(10, 1, downloadState = DownloadState.DOWNLOADED, localPath = file.path)
        env.store.episodes[11] = cachedEpisode(11, 1)
        env.api.pullHandler = { _, _ -> page(tombstones = listOf(tombstone(TombstoneEntityType.EPISODE, 10, 5)), nextRev = 5) }
        env.sync()
        assertEquals(setOf(11L), env.store.episodes.keys)
        assertFalse(file.exists())
    }

    @Test
    fun aFeedTombstoneDeletesItsEpisodesAndTheirFiles() {
        val env = Env()
        val a = tmp.newFile("a.mp3")
        val b = tmp.newFile("b.mp3")
        val other = tmp.newFile("other.mp3")
        env.store.feeds[1] = cachedFeed(1)
        env.store.feeds[2] = cachedFeed(2)
        env.store.episodes[10] = cachedEpisode(10, 1, localPath = a.path, downloadState = DownloadState.DOWNLOADED)
        env.store.episodes[11] = cachedEpisode(11, 1, localPath = b.path, downloadState = DownloadState.DOWNLOADED)
        env.store.episodes[20] = cachedEpisode(20, 2, localPath = other.path, downloadState = DownloadState.DOWNLOADED)
        env.api.pullHandler = { _, _ -> page(tombstones = listOf(tombstone(TombstoneEntityType.FEED, 1, 5)), nextRev = 5) }
        env.sync()
        assertEquals(setOf(2L), env.store.feeds.keys)
        assertEquals(setOf(20L), env.store.episodes.keys)
        assertFalse(a.exists())
        assertFalse(b.exists())
        assertTrue(other.exists())
    }

    @Test
    fun aTombstoneForAnEpisodeThatWasNeverCachedIsIgnored() {
        val env = Env()
        val keep = tmp.newFile("keep.mp3")
        env.store.feeds[1] = cachedFeed(1)
        env.store.episodes[11] = cachedEpisode(11, 1, localPath = keep.path)
        env.api.pullHandler = { _, _ ->
            page(tombstones = listOf(tombstone(TombstoneEntityType.EPISODE, 999, 5), tombstone(TombstoneEntityType.FEED, 998, 6)), nextRev = 6)
        }
        assertEquals(SyncResult.Success, env.sync())
        assertEquals(setOf(11L), env.store.episodes.keys)
        assertTrue(keep.exists())
        assertEquals(6L, env.rev)
    }

    @Test
    fun aMissingLocalFileIsNotAnError() {
        val env = Env()
        env.store.feeds[1] = cachedFeed(1)
        env.store.episodes[10] = cachedEpisode(10, 1, localPath = tmp.root.resolve("gone.mp3").path)
        env.api.pullHandler = { _, _ -> page(tombstones = listOf(tombstone(TombstoneEntityType.EPISODE, 10, 5)), nextRev = 5) }
        assertEquals(SyncResult.Success, env.sync())
        assertTrue(env.store.episodes.isEmpty())
    }

    @Test
    fun aTombstoneOnALaterPageBeatsAnUpsertOnAnEarlierOne() {
        val env = Env()
        env.api.pullHandler = { s, _ ->
            if (s == 0L) page(feeds = listOf(feedDto(1, 1), feedDto(2, 2)), episodes = listOf(episodeDto(10, 1, 3), episodeDto(20, 2, 3)), nextRev = 3, hasMore = true)
            else page(tombstones = listOf(tombstone(TombstoneEntityType.EPISODE, 10, 4), tombstone(TombstoneEntityType.FEED, 2, 5)), nextRev = 5)
        }
        env.sync()
        assertEquals(setOf(1L), env.store.feeds.keys)
        assertTrue(env.store.episodes.isEmpty())
    }

    @Test
    fun anEpisodeWhoseFeedIsNotCachedIsSkippedInsteadOfFailingEveryPull() {
        val env = Env()
        env.api.pullHandler = { _, _ -> page(episodes = listOf(episodeDto(10, feedId = 77)), nextRev = 3) }
        assertEquals(SyncResult.Success, env.sync())
        assertTrue(env.store.episodes.isEmpty())
        assertEquals(3L, env.rev)
    }

    // ---- apply: upserts ----

    @Test
    fun aPullOverwritesSyncedColumnsAndLeavesDeviceOnlyColumnsAlone() {
        val env = Env()
        env.store.feeds[1] = cachedFeed(1)
        env.store.episodes[10] = cachedEpisode(10, 1, downloadState = DownloadState.DOWNLOADED, localPath = "/data/x.mp3")
        env.api.pullHandler = { _, _ ->
            page(feeds = listOf(feedDto(1, title = "Renamed")), episodes = listOf(episodeDto(10, 1, title = "New title"), episodeDto(11, 1)), nextRev = 5)
        }
        env.sync()
        assertEquals("Renamed", env.store.feeds.getValue(1).title)
        val updated = env.store.episodes.getValue(10)
        assertEquals("New title", updated.title)
        assertEquals(DownloadState.DOWNLOADED, updated.downloadState)
        assertEquals("/data/x.mp3", updated.localPath)
        val fresh = env.store.episodes.getValue(11)
        assertEquals(DownloadState.NONE, fresh.downloadState)
        assertNull(fresh.localPath)
    }

    // A local write made while the pull's network phase is in flight (the push already finished).
    private fun Env.localWriteDuringPull(episodeId: Long, stamp: Long) {
        store.episodes[episodeId] = store.episodes.getValue(episodeId).copy(
            status = EpisodeStatus.PLAYED, playedAt = stamp, positionSec = 30, stateUpdatedAt = stamp,
        )
        addPending(episodeId, stamp)
    }

    @Test
    fun theServerWinsWhenItsStateIsNewerOrEqualToAPendingWrite() {
        for (serverStamp in listOf(5_000L, 4_000L)) {
            val env = Env()
            env.store.feeds[1] = cachedFeed(1)
            env.store.episodes[10] = cachedEpisode(10, 1)
            env.api.pullHandler = { _, _ ->
                env.localWriteDuringPull(10, 4_000)
                page(episodes = listOf(episodeDto(10, 1, status = EpisodeStatus.NEW, positionSec = 5, stateUpdatedAt = serverStamp)), nextRev = 9)
            }
            env.sync()
            val ep = env.store.episodes.getValue(10)
            assertNull(ep.playedAt)
            assertEquals(EpisodeStatus.NEW, ep.status)
            assertEquals(5, ep.positionSec)
            assertEquals(serverStamp, ep.stateUpdatedAt)
            assertTrue("outbox row removed", env.store.pending.isEmpty())
        }
    }

    @Test
    fun aNewerPendingWriteIsLeftAloneForTheNextPush() {
        val env = Env()
        env.store.feeds[1] = cachedFeed(1)
        env.store.episodes[10] = cachedEpisode(10, 1)
        // The user marks it Played during the pull; the pull then reports an older server state.
        env.api.pullHandler = { _, _ ->
            env.localWriteDuringPull(10, 6_000)
            page(episodes = listOf(episodeDto(10, 1, status = EpisodeStatus.NEW, stateUpdatedAt = 5_000, title = "Fresh")), nextRev = 9)
        }
        env.sync()
        val ep = env.store.episodes.getValue(10)
        assertEquals(EpisodeStatus.PLAYED, ep.status)
        assertEquals(6_000L, ep.stateUpdatedAt)
        assertEquals("other synced columns still update", "Fresh", ep.title)
        assertEquals(6_000L, env.store.pending.getValue(10).stateUpdatedAt)
    }

    // ---- 401 ----

    @Test
    fun aRejectedTokenClearsTheTokenKeepsTheUrlAndStopsBackgroundRuns() {
        val env = Env(FakeSettings(Settings(TEST_URL, TEST_TOKEN, lastSyncRev = 4)))
        env.store.feeds[1] = cachedFeed(1)
        env.addPending(10, 3_000)
        env.api.pullHandler = { _, _ -> throw ApiException(ApiError.Unauthorized) }
        val result = env.sync()
        assertEquals(SyncResult.Failed(FailureReason.Api(ApiError.Unauthorized)), result)
        val settings = env.settings.flow.value
        assertEquals("", settings.authToken)
        assertEquals(TEST_URL, settings.serverUrl)
        assertEquals(4L, settings.lastSyncRev)
        assertEquals(1, env.store.feeds.size)
        assertTrue(env.rejection.rejected.value)

        val calls = env.api.calls.size
        assertEquals(SyncResult.Skipped(SkipReason.NotConfigured), env.sync(SyncTrigger.PERIODIC))
        assertEquals(calls, env.api.calls.size)
    }

    @Test
    fun aRejectedTokenDuringPushLeavesTheOutboxAlone() {
        val env = Env()
        env.addPending(10, 3_000)
        env.api.pushHandler = { throw ApiException(ApiError.Unauthorized) }
        assertEquals(SyncResult.Failed(FailureReason.Api(ApiError.Unauthorized)), env.sync())
        assertEquals(1, env.store.pending.size)
        assertEquals("", env.settings.flow.value.authToken)
    }

    // ---- state flow ----

    @Test
    fun theStateFlowTracksRunningLastSuccessAndLastFailure() {
        val env = Env()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        env.api.pullHandler = { s, _ -> entered.complete(Unit); gate.await(); page(nextRev = s) }
        assertFalse(env.engine.state.value.running)
        assertNull(env.engine.state.value.lastSuccessAt)

        runBlocking {
            val run = async(Dispatchers.Default) { env.engine.sync(SyncTrigger.MANUAL) }
            withTimeout(5_000) { entered.await() }
            assertTrue(env.engine.state.value.running)
            env.now = 7_000
            gate.complete(Unit)
            assertEquals(SyncResult.Success, run.await())
        }
        assertFalse(env.engine.state.value.running)
        assertEquals(7_000L, env.engine.state.value.lastSuccessAt)
        assertNull(env.engine.state.value.lastFailure)

        env.now = 8_000
        env.api.pullHandler = { _, _ -> throw ApiException(ApiError.Http(500, "oops")) }
        env.sync()
        assertEquals(7_000L, env.engine.state.value.lastSuccessAt)
        assertEquals(FailureReason.Api(ApiError.Http(500, "oops")), env.engine.state.value.lastFailure)

        env.api.pullHandler = { s, _ -> page(nextRev = s) }
        env.now = 9_000
        env.sync()
        assertEquals(9_000L, env.engine.state.value.lastSuccessAt)
        assertNull(env.engine.state.value.lastFailure)
    }

    @Test
    fun aFailureMessageNeverContainsTheToken() {
        val env = Env()
        env.api.pullHandler = { _, _ -> throw ApiException(ApiError.Http(500, "***")) }
        env.sync()
        val message = env.engine.state.value.lastFailure!!.message
        assertFalse(TEST_TOKEN in message)
    }

    // ---- overlap ----

    @Test
    fun launchAndPeriodicTriggersDuringARunAreDroppedAndAtMostOneCallIsInsideTheApi() {
        val env = Env()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        env.api.pullHandler = { s, _ -> entered.complete(Unit); gate.await(); page(nextRev = s) }
        runBlocking {
            val first = async(Dispatchers.Default) { env.engine.sync(SyncTrigger.LAUNCH_OR_FOREGROUND) }
            withTimeout(5_000) { entered.await() }
            val callsBefore = env.api.calls.size
            assertEquals(SyncResult.Skipped(SkipReason.AlreadyRunning), env.engine.sync(SyncTrigger.PERIODIC))
            assertEquals(SyncResult.Skipped(SkipReason.AlreadyRunning), env.engine.sync(SyncTrigger.LAUNCH_OR_FOREGROUND))
            assertEquals("dropped triggers make no API call", callsBefore, env.api.calls.size)
            gate.complete(Unit)
            assertEquals(SyncResult.Success, first.await())
        }
        assertEquals(1, env.api.maxInFlight.get())
        assertEquals(1, env.api.pullRequests.size)
    }

    @Test
    fun aManualTriggerDuringARunWaitsAndThenRunsItsOwnFullPassAndManualCallsCoalesce() {
        val env = Env()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var pulls = 0
        env.api.pullHandler = { s, _ ->
            pulls++
            if (pulls == 1) {
                entered.complete(Unit)
                gate.await()
            }
            page(nextRev = s)
        }
        runBlocking {
            val first = async(Dispatchers.Default) { env.engine.sync(SyncTrigger.PERIODIC) }
            withTimeout(5_000) { entered.await() }
            val m1 = async(Dispatchers.Default) { env.engine.sync(SyncTrigger.MANUAL) }
            val m2 = async(Dispatchers.Default) { env.engine.sync(SyncTrigger.MANUAL) }
            Thread.sleep(300)
            assertEquals("the manual runs have not started", 1, env.api.pullRequests.size)
            gate.complete(Unit)
            assertEquals(SyncResult.Success, first.await())
            assertEquals(SyncResult.Success, withTimeout(5_000) { m1.await() })
            assertEquals(SyncResult.Success, withTimeout(5_000) { m2.await() })
        }
        assertEquals("one run, plus exactly one coalesced manual run", 2, env.api.pullRequests.size)
        assertEquals(1, env.api.maxInFlight.get())
    }

    @Test
    fun exclusiveBlocksSyncUntilItFinishes() {
        val env = Env()
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runBlocking {
            val holder = async(Dispatchers.Default) { env.engine.exclusive { inside.complete(Unit); release.await() } }
            withTimeout(5_000) { inside.await() }
            assertEquals(SyncResult.Skipped(SkipReason.AlreadyRunning), env.engine.sync(SyncTrigger.PERIODIC))
            assertEquals(emptyList<String>(), env.api.calls)
            release.complete(Unit)
            holder.await()
        }
    }
}
