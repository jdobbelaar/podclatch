package com.podclatch

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.RefreshResultDto
import com.podclatch.data.network.TombstoneEntityType
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.FeedsRepository
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.TokenRejection
import com.podclatch.playback.PlaybackController
import com.podclatch.ui.feeddetail.FeedDetailController
import com.podclatch.ui.feeddetail.FeedDetailDialog
import com.podclatch.ui.feeddetail.FeedDetailMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Feed Detail controller with fakes. The action scope uses `Dispatchers.Unconfined`, so an action
 * that never suspends for real has finished when the call returns; states are still awaited by their
 * exact end state.
 */
class FeedDetailControllerTest {
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = FakeSettings()
    private val api = FakeApi()
    private val store = FakeCacheStore()
    private val rejection = TokenRejection(settings)
    private val engine = SyncEngine(api, settings, store, { }, { 5_000 }, rejection, DiskFileDeleter(), Dispatchers.Unconfined)
    private val feeds = FeedsRepository(api, store, engine, rejection)
    private var now = 50_000L
    private val states = EpisodeStateRepository(store) { now }
    private val playback = FakePlaybackController(knownIds = setOf(10L, 11L, 12L))

    init {
        store.feeds[1] = cachedFeed(1).copy(title = "History", description = "<p>About</p>")
        store.feeds[2] = cachedFeed(2).copy(title = "Other")
        store.episodes[10] = cachedEpisode(10, 1).copy(publishedAt = 100, title = "Oldest")
        store.episodes[11] = cachedEpisode(11, 1, positionSec = 30).copy(publishedAt = 200, title = "Middle")
        store.episodes[12] = cachedEpisode(12, 1, EpisodeStatus.PLAYED, playedAt = 900, stateUpdatedAt = 900).copy(publishedAt = 300, title = "Newest")
        store.episodes[20] = cachedEpisode(20, 2).copy(publishedAt = 100)
    }

    @After
    fun tearDown() {
        actionScope.cancel()
        stateScope.cancel()
    }

    private fun controller(
        feedId: Long = 1,
        playbackController: PlaybackController = playback,
        scope: CoroutineScope = actionScope,
        stateScope: CoroutineScope = this.stateScope,
    ) = FeedDetailController(feedId, feeds, states, engine, playbackController, scope, stateScope)

    private fun pulls() = api.calls.count { it == "pull" }

    /** The Episodes whose state the Sync pushed: the outbox rows (a Sync settles them, so Room no longer holds them). */
    private fun pushedIds() = api.pushRequests.flatten().map { it.episodeId }.toSet()

    // ---- loading ----

    @Test
    fun theHeaderAndTheEpisodesShowInTheFeedsListenOrder() {
        val c = controller()
        val state = c.await("loaded with 3 episodes") { it.loaded && it.episodes.size == 3 }
        assertEquals("History", state.header!!.title)
        assertEquals(listOf(12L, 11L, 10L), state.episodes.map { it.id })
        assertFalse(state.feedGone)
    }

    @Test
    fun aFeedThatIsNotCachedPopsAfterTheFirstRead() {
        val c = controller(feedId = 99)
        val state = c.await("read once, no Feed") { it.loaded && it.header == null }
        assertTrue(state.feedGone)
        assertTrue(state.episodes.isEmpty())
    }

    @Test
    fun beforeTheFirstReadNothingPops() {
        val gate = CompletableDeferred<Unit>()
        val slowStore = object : com.podclatch.data.db.CacheStore by store {
            override fun observeFeed(id: Long) = kotlinx.coroutines.flow.flow {
                gate.await()
                emitAll(store.observeFeed(id))
            }
        }
        val c = FeedDetailController(
            1, FeedsRepository(api, slowStore, engine, rejection), states, engine, playback, actionScope, stateScope,
        )
        assertFalse(c.uiState.value.loaded)
        assertFalse(c.uiState.value.feedGone)
        gate.complete(Unit)
        c.await("loaded") { it.loaded && it.header != null }
    }

    @Test
    fun theMarkAllButtonsFollowWhatWouldChange() {
        val c = controller()
        val both = c.await("both enabled") { it.loaded && it.episodes.isNotEmpty() }
        assertTrue(both.canMarkAllPlayed) // 10 and 11 are New
        assertTrue(both.canMarkAllNew) // 12 is Played, 11 has progress
        runBlocking { states.markAllPlayed(1) }
        val allPlayed = c.await("all played") { s -> s.episodes.all { it.status == EpisodeStatus.PLAYED } }
        assertFalse(allPlayed.canMarkAllPlayed)
        assertTrue(allPlayed.canMarkAllNew)
        runBlocking { states.markAllNew(1) }
        val allNew = c.await("all new") { s -> s.episodes.all { it.status == EpisodeStatus.NEW && it.positionSec == 0 } }
        assertTrue(allNew.canMarkAllPlayed)
        assertFalse(allNew.canMarkAllNew)
    }

    // ---- Listen Order ----

    @Test
    fun selectingTheOtherOrderPatchesWritesRoomThenSyncsAndTheListFlips() {
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.selectListenOrder(ListenOrder.OLDEST_FIRST)
        val state = c.await("oldest first, idle") {
            it.header?.listenOrder == ListenOrder.OLDEST_FIRST && !it.busy && it.episodes.map { e -> e.id } == listOf(10L, 11L, 12L)
        }
        assertNull(state.message)
        assertEquals(listOf(1L to ListenOrder.OLDEST_FIRST), api.listenOrderRequests)
        assertEquals(listOf("listenOrder", "pull"), api.calls.filter { it != "health" })
    }

    @Test
    fun selectingTheOrderTheFeedAlreadyHasSendsNothing() {
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.selectListenOrder(ListenOrder.NEWEST_FIRST)
        c.selectListenOrder(ListenOrder.OLDEST_FIRST)
        c.await("oldest first, idle") { it.header?.listenOrder == ListenOrder.OLDEST_FIRST && !it.busy }
        assertEquals(listOf(1L to ListenOrder.OLDEST_FIRST), api.listenOrderRequests)
    }

    @Test
    fun onlyOneServerActionRunsAtATime() {
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        api.listenOrderHandler = { id, order ->
            reached.complete(Unit)
            gate.await()
            feedDto(id).copy(listenOrder = order)
        }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.selectListenOrder(ListenOrder.OLDEST_FIRST)
        awaitGate("the PATCH reached the fake", reached)
        val busy = c.await("busy") { it.busy }
        assertTrue(busy.actionsLocked)
        // Everything that talks to the server is refused while it runs.
        c.selectListenOrder(ListenOrder.NEWEST_FIRST)
        assertFalse(c.refresh())
        c.requestUnsubscribe()
        gate.complete(Unit)
        val done = c.await("idle on oldest first") { !it.busy && it.header?.listenOrder == ListenOrder.OLDEST_FIRST }
        assertNull(done.dialog)
        assertEquals(listOf("listenOrder", "pull"), api.calls.filter { it != "health" })
        assertEquals(1, api.maxInFlight.get())
    }

    @Test
    fun aFailedChangeLeavesTheSelectionTheListAndRoomAsTheyWereAndShowsTheBanner() {
        for (error in listOf(ApiError.Unreachable(false), ApiError.Unreachable(true), ApiError.Http(422, "bad"), ApiError.Http(500, null))) {
            val api = FakeApi()
            api.listenOrderHandler = { _, _ -> throw ApiException(error) }
            val feeds = FeedsRepository(api, store, engine, rejection)
            val engine2 = SyncEngine(api, settings, store, { }, { 5_000 }, rejection, DiskFileDeleter(), Dispatchers.Unconfined)
            val c = FeedDetailController(1, feeds, states, engine2, playback, actionScope, stateScope)
            c.await("loaded") { it.loaded && it.header != null }
            c.selectListenOrder(ListenOrder.OLDEST_FIRST)
            val state = c.await("banner for $error") { !it.busy && it.message == FeedDetailMessage.ListenOrderFailed(error.message) }
            assertEquals(ListenOrder.NEWEST_FIRST, state.header!!.listenOrder)
            assertEquals(listOf(12L, 11L, 10L), state.episodes.map { it.id })
            assertEquals(ListenOrder.NEWEST_FIRST, store.feeds.getValue(1).listenOrder)
            assertEquals(listOf("listenOrder"), api.calls)
        }
    }

    @Test
    fun aRejectedTokenShowsNoBannerAndClearsTheToken() {
        api.listenOrderHandler = { _, _ -> throw ApiException(ApiError.Unauthorized) }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.selectListenOrder(ListenOrder.OLDEST_FIRST)
        val state = c.await("idle after the 401") { !it.busy && rejection.rejected.value }
        assertNull(state.message)
        assertEquals("", settings.flow.value.authToken)
    }

    @Test
    fun a404RunsASyncAndTheScreenPopsOnceTheFeedLeavesRoom() {
        api.listenOrderHandler = { _, _ -> throw ApiException(ApiError.Http(404, "Feed not found")) }
        api.pullHandler = { _, _ -> page(tombstones = listOf(tombstone(TombstoneEntityType.FEED, 1, 5)), nextRev = 5) }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.selectListenOrder(ListenOrder.OLDEST_FIRST)
        val state = c.await("popped") { it.feedGone && !it.busy }
        assertEquals(listOf("listenOrder", "pull"), api.calls.filter { it != "health" })
        assertTrue(state.episodes.isEmpty())
    }

    // ---- Refresh ----

    @Test
    fun refreshRefreshesThisFeedOnlyThenSyncsAndNewEpisodesAppear() {
        api.pullHandler = { _, _ ->
            page(episodes = listOf(episodeDto(30, 1).copy(publishedAt = 400), episodeDto(31, 1).copy(publishedAt = 500)), nextRev = 9)
        }
        val c = controller()
        c.await("loaded") { it.loaded && it.episodes.size == 3 }
        assertTrue(c.refresh())
        val state = c.await("five episodes, idle") { it.episodes.size == 5 && !it.busy && !it.refreshing }
        assertNull(state.message)
        assertEquals(listOf(31L, 30L, 12L, 11L, 10L), state.episodes.map { it.id })
        assertEquals(listOf(1L), api.refreshFeedRequests)
        assertEquals(listOf("refreshFeed", "pull"), api.calls.filter { it != "health" })
        assertFalse("refresh" in api.calls)
    }

    @Test
    fun theRefreshingFlagStaysOnWhileTheRequestRuns() {
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        api.refreshFeedHandler = { id ->
            reached.complete(Unit)
            gate.await()
            listOf(RefreshResultDto(id, true))
        }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        assertTrue(c.refresh())
        awaitGate("the refresh reached the fake", reached)
        val running = c.await("refreshing") { it.refreshing && it.busy }
        assertTrue(running.actionsLocked)
        gate.complete(Unit)
        c.await("idle") { !it.refreshing && !it.busy }
    }

    @Test
    fun aFailedFeedShowsTheServersTextAndStillSyncs() {
        api.refreshFeedHandler = { id -> listOf(RefreshResultDto(id, false, "Could not fetch the feed (HTTP 503).")) }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.refresh()
        c.await("banner") { !it.busy && it.message == FeedDetailMessage.FeedRefreshFailed("Could not fetch the feed (HTTP 503).") }
        assertEquals(1, pulls())
    }

    @Test
    fun anUnreachableServerShowsTheApiMessageAndWritesNothing() {
        api.refreshFeedHandler = { throw ApiException(ApiError.Unreachable(true)) }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.refresh()
        c.await("banner") { !it.busy && it.message == FeedDetailMessage.RefreshRequestFailed(ApiError.Unreachable(true).message) }
        assertEquals(0, pulls())
        assertEquals(3, store.episodes.values.count { it.feedId == 1L })
    }

    @Test
    fun aGoodRefreshFollowedByAFailedSyncSaysTheListWillUpdateLater() {
        api.pullHandler = { _, _ -> throw ApiException(ApiError.Unreachable(false)) }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.refresh()
        c.await("sync banner") { !it.busy && it.message == FeedDetailMessage.RefreshSyncFailed(ApiError.Unreachable(false).message) }
    }

    @Test
    fun aRejectedTokenOnRefreshShowsNoBanner() {
        api.refreshFeedHandler = { throw ApiException(ApiError.Unauthorized) }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.refresh()
        val state = c.await("idle after the 401") { !it.busy && rejection.rejected.value }
        assertNull(state.message)
    }

    @Test
    fun a404OnRefreshRunsASyncAndThePop() {
        api.refreshFeedHandler = { throw ApiException(ApiError.Http(404, "gone")) }
        api.pullHandler = { _, _ -> page(tombstones = listOf(tombstone(TombstoneEntityType.FEED, 1, 5)), nextRev = 5) }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.refresh()
        c.await("popped") { it.feedGone && !it.busy }
        assertEquals(1, pulls())
    }

    // ---- Unsubscribe ----

    @Test
    fun cancellingTheUnsubscribeDialogChangesNothing() {
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.requestUnsubscribe()
        c.await("dialog open") { it.dialog == FeedDetailDialog.Unsubscribe("History") }
        c.cancelDialog()
        c.await("dialog closed") { it.dialog == null }
        c.confirmUnsubscribe() // no dialog open: nothing happens
        assertEquals(emptyList<Long>(), api.unsubscribeRequests)
    }

    @Test
    fun confirmDeletesThenSyncsAndTheScreenPopsWhenTheTombstoneArrives() {
        api.pullHandler = { _, _ -> page(tombstones = listOf(tombstone(TombstoneEntityType.FEED, 1, 5)), nextRev = 5) }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.requestUnsubscribe()
        c.await("dialog open") { it.dialog is FeedDetailDialog.Unsubscribe }
        c.confirmUnsubscribe()
        val state = c.await("popped") { it.feedGone && !it.busy }
        assertNull(state.message)
        assertNull(state.dialog)
        assertEquals(listOf(1L), api.unsubscribeRequests)
        assertEquals(listOf("unsubscribe", "pull"), api.calls.filter { it != "health" })
        assertFalse(1L in store.feeds)
        assertTrue(2L in store.feeds)
    }

    @Test
    fun aFailedUnsubscribeKeepsTheScreenAndNamesTheFeed() {
        api.unsubscribeHandler = { throw ApiException(ApiError.Unreachable(false)) }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.requestUnsubscribe()
        c.await("dialog open") { it.dialog is FeedDetailDialog.Unsubscribe }
        c.confirmUnsubscribe()
        val state = c.await("banner") {
            !it.busy && it.message == FeedDetailMessage.UnsubscribeFailed("History", ApiError.Unreachable(false).message)
        }
        assertFalse(state.feedGone)
        assertEquals(0, pulls())
        assertTrue(1L in store.feeds)
    }

    @Test
    fun aRejectedTokenOnUnsubscribeShowsNoBanner() {
        api.unsubscribeHandler = { throw ApiException(ApiError.Unauthorized) }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.requestUnsubscribe()
        c.await("dialog open") { it.dialog is FeedDetailDialog.Unsubscribe }
        c.confirmUnsubscribe()
        val state = c.await("idle after the 401") { !it.busy && rejection.rejected.value }
        assertNull(state.message)
    }

    @Test
    fun aFeedAlreadyGoneOnTheServerCountsAsUnsubscribed() {
        api.unsubscribeHandler = { throw ApiException(ApiError.Http(404, "gone")) }
        api.pullHandler = { _, _ -> page(tombstones = listOf(tombstone(TombstoneEntityType.FEED, 1, 5)), nextRev = 5) }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.requestUnsubscribe()
        c.await("dialog open") { it.dialog is FeedDetailDialog.Unsubscribe }
        c.confirmUnsubscribe()
        val state = c.await("popped") { it.feedGone && !it.busy }
        assertNull(state.message)
    }

    @Test
    fun aSyncFailureAfterTheDeleteSaysTheFeedLeavesAtTheNextSync() {
        api.pullHandler = { _, _ -> throw ApiException(ApiError.Unreachable(false)) }
        val c = controller()
        c.await("loaded") { it.loaded && it.header != null }
        c.requestUnsubscribe()
        c.await("dialog open") { it.dialog is FeedDetailDialog.Unsubscribe }
        c.confirmUnsubscribe()
        c.await("banner") {
            !it.busy && it.message == FeedDetailMessage.UnsubscribedSyncFailed("History", ApiError.Unreachable(false).message)
        }
    }

    // ---- Mark all ----

    @Test
    fun theDialogStatesHowManyEpisodesChange() {
        val c = controller()
        c.await("loaded") { it.loaded && it.episodes.size == 3 }
        c.requestMarkAllPlayed()
        c.await("played dialog") { it.dialog == FeedDetailDialog.MarkAllPlayed(2, "History") }
        c.cancelDialog()
        c.requestMarkAllNew()
        c.await("new dialog") { it.dialog == FeedDetailDialog.MarkAllNew(2, "History") }
    }

    @Test
    fun cancellingMarkAllChangesNothing() {
        val c = controller()
        c.await("loaded") { it.loaded && it.episodes.size == 3 }
        c.requestMarkAllPlayed()
        c.await("dialog") { it.dialog is FeedDetailDialog.MarkAllPlayed }
        c.cancelDialog()
        c.await("closed") { it.dialog == null }
        assertTrue(store.pending.isEmpty())
        assertEquals(EpisodeStatus.NEW, store.episodes.getValue(10).status)
        assertEquals(0, pulls())
    }

    @Test
    fun confirmingMarkAllPlayedChangesRoomAndTheOutboxThenSyncs() {
        val c = controller()
        c.await("loaded") { it.loaded && it.episodes.size == 3 }
        c.requestMarkAllPlayed()
        c.await("dialog") { it.dialog is FeedDetailDialog.MarkAllPlayed }
        c.confirmMarkAll()
        val state = c.await("all played") { s -> s.dialog == null && s.episodes.all { it.status == EpisodeStatus.PLAYED } }
        assertTrue(state.episodes.all { it.positionSec == 0 })
        assertEquals(setOf(10L, 11L), pushedIds())
        assertEquals(900L, store.episodes.getValue(12).playedAt) // already Played: untouched
        assertEquals(1, pulls())
        assertEquals(listOf("push", "pull"), api.calls.filter { it != "health" })
    }

    @Test
    fun confirmingMarkAllNewResetsPlayedAndStartedEpisodes() {
        val c = controller()
        c.await("loaded") { it.loaded && it.episodes.size == 3 }
        c.requestMarkAllNew()
        c.await("dialog") { it.dialog is FeedDetailDialog.MarkAllNew }
        c.confirmMarkAll()
        c.await("all new") { s -> s.dialog == null && s.episodes.all { it.status == EpisodeStatus.NEW && it.positionSec == 0 } }
        assertEquals(setOf(11L, 12L), pushedIds())
        assertEquals(1, pulls())
    }

    // ---- one Episode ----

    @Test
    fun markingAnEpisodePlayedWritesTheOutboxAndSyncs() {
        val c = controller()
        c.await("loaded") { it.loaded && it.episodes.size == 3 }
        c.markPlayed(10)
        c.await("episode 10 played") { s -> s.episodes.single { it.id == 10L }.status == EpisodeStatus.PLAYED }
        assertEquals(setOf(10L), pushedIds())
        assertEquals(1, pulls())
    }

    @Test
    fun markingAnEpisodeNewResetsItsProgress() {
        val c = controller()
        c.await("loaded") { it.loaded && it.episodes.size == 3 }
        c.markNew(11)
        c.await("episode 11 without progress") { s -> s.episodes.single { it.id == 11L }.positionSec == 0 }
        assertEquals(setOf(11L), pushedIds())
        assertEquals(1, pulls())
    }

    @Test
    fun aNoOpMarkWritesNothingAndStartsNoSync() {
        val c = controller()
        c.await("loaded") { it.loaded && it.episodes.size == 3 }
        c.markPlayed(12) // already Played
        c.markNew(10) // New without progress
        // A real change afterwards proves the two calls above were handled before it.
        c.markPlayed(10)
        c.await("episode 10 played") { s -> s.episodes.single { it.id == 10L }.status == EpisodeStatus.PLAYED }
        assertEquals(setOf(10L), pushedIds())
        assertEquals(900L, store.episodes.getValue(12).stateUpdatedAt)
        assertEquals(1, pulls())
    }

    @Test
    fun marksWorkWhileAServerActionRuns() {
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        api.refreshFeedHandler = { id ->
            reached.complete(Unit)
            gate.await()
            listOf(RefreshResultDto(id, true))
        }
        val c = controller()
        c.await("loaded") { it.loaded && it.episodes.size == 3 }
        c.refresh()
        awaitGate("the refresh reached the fake", reached)
        c.markPlayed(10)
        c.await("marked while busy") { it.busy && it.episodes.single { e -> e.id == 10L }.status == EpisodeStatus.PLAYED }
        gate.complete(Unit)
        c.await("idle") { !it.busy }
    }

    // ---- play ----

    @Test
    fun tappingARowPlaysThatEpisodeOnce() {
        val c = controller()
        c.await("loaded") { it.loaded && it.episodes.size == 3 }
        c.play(11)
        awaitCondition("the play call arrived") { playback.played == listOf(11L) }
        val state = c.await("no banner") { it.message == null }
        assertEquals(listOf(11L), playback.played)
        assertNull(state.message)
    }

    @Test
    fun aFailedPlayShowsTheBannerWithoutCrashing() {
        val c = controller()
        c.await("loaded") { it.loaded && it.episodes.size == 3 }
        c.play(999) // not known to the fake: NotFound
        c.await("play banner") { it.message == FeedDetailMessage.PlayFailed }
        c.dismissMessage()
        c.await("banner dismissed") { it.message == null }
    }

    @Test
    fun aThrowingPlaybackControllerShowsTheBanner() {
        val throwing = object : PlaybackController by playback {
            override suspend fun play(episodeId: Long) = throw IllegalStateException("boom")
        }
        val c = controller(playbackController = throwing)
        c.await("loaded") { it.loaded && it.episodes.size == 3 }
        c.play(10)
        c.await("play banner") { it.message == FeedDetailMessage.PlayFailed }
    }

    // ---- scope ----

    @Test
    fun aServerActionFinishesEvenAfterTheScreensScopeIsCancelled() {
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        api.listenOrderHandler = { id, order ->
            reached.complete(Unit)
            gate.await()
            feedDto(id).copy(listenOrder = order)
        }
        val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = controller(stateScope = screenScope)
        c.await("loaded") { it.loaded && it.header != null }
        c.selectListenOrder(ListenOrder.OLDEST_FIRST)
        awaitGate("the PATCH reached the fake", reached)
        screenScope.cancel() // the ViewModel is cleared: the user left the screen
        gate.complete(Unit)
        awaitCondition("Room holds the new order and the Sync ran") {
            store.feeds.getValue(1).listenOrder == ListenOrder.OLDEST_FIRST && pulls() == 1
        }
    }
}
