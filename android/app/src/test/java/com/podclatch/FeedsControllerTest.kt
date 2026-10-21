package com.podclatch

import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.FeedListItem
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.RefreshResultDto
import com.podclatch.data.repo.FeedsRepository
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.TokenRejection
import com.podclatch.ui.feeds.FeedsController
import com.podclatch.ui.feeds.FeedsMessage
import com.podclatch.ui.feeds.FeedsUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wraps the store so a test knows when the controller has finished handling a list: `emit` returns only after
 * the controller's collector has processed the list, so [processed] runs after the controller has seen it.
 */
private class ObservedStore(private val inner: FakeCacheStore, private val processed: (List<FeedListItem>) -> Unit) :
    CacheStore by inner {
    override fun observeFeedList(): Flow<List<FeedListItem>> = flow {
        inner.observeFeedList().collect { list ->
            emit(list)
            processed(list)
        }
    }
}

class FeedsControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = FakeSettings()
    private val api = FakeApi()
    private val store = FakeCacheStore()
    private val rejection = TokenRejection(settings)
    private val engine = SyncEngine(api, settings, store, { }, { 5_000 }, rejection, DiskFileDeleter(), Dispatchers.Unconfined)
    private val removalHandled = CompletableDeferred<Unit>()
    private val controller: FeedsController

    init {
        for (id in 1L..3L) store.feeds[id] = cachedFeed(id).copy(priority = (id - 1).toInt(), title = "Feed $id")
        store.episodes[10] = cachedEpisode(10, 1)
        controller = FeedsController(
            FeedsRepository(api, ObservedStore(store) { list -> if (list.none { it.id == 2L }) removalHandled.complete(Unit) }, engine, rejection),
            engine,
            scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Publishes the store's current rows to the controller's list flow. */
    private fun loaded() = controller.await("loaded") { it.items != null && it.items.size == 3 }

    private fun ids(state: FeedsUiState) = state.items!!.map { it.id }

    @Test
    fun theListShowsServerOrderWithUnplayedCounts() {
        runBlocking { store.transaction { updateFeedSynced(cachedFeed(1).copy(priority = 5)) } }
        val state = controller.await("the order 2,3,1") { s -> s.items?.map { it.id } == listOf(2L, 3L, 1L) }
        assertEquals(1, state.items!!.single { it.id == 1L }.unplayedCount)
    }

    @Test
    fun aDropWhereItStartedSendsNothing() {
        loaded()
        controller.onDragStarted()
        controller.await("dragging") { it.dragging } // barrier: the end state below is not true before the drag
        controller.onMove(1, 2)
        controller.onMove(2, 1) // ids: after the first move the list is 2,1,3; moving 2 onto 1's slot restores 1,2,3
        controller.onDragStopped()
        val state = controller.await("dropped on the start order") { !it.dragging && !it.busy && it.items?.map { f -> f.id } == listOf(1L, 2L, 3L) }
        assertEquals(listOf(1L, 2L, 3L), ids(state))
        assertTrue(api.reorderRequests.isEmpty())
    }

    @Test
    fun aDropSendsOneFullListAndKeepsTheDroppedOrder() {
        loaded()
        val release = CompletableDeferred<Unit>()
        api.reorderHandler = { requested ->
            release.await()
            requested.mapIndexed { i, id -> feedDto(id, priority = i, title = "Feed $id") }
        }
        controller.onDragStarted()
        controller.onMove(1, 3)
        controller.await("moved to 2,3,1") { it.items?.map { f -> f.id } == listOf(2L, 3L, 1L) }
        controller.onDragStopped()
        val busy = controller.await("busy with rows 2,3,1") { it.busy && it.items?.map { f -> f.id } == listOf(2L, 3L, 1L) }
        assertEquals(listOf(2L, 3L, 1L), ids(busy)) // rows stay in the dropped order while the request runs
        assertFalse(controller.refresh()) // a second server action does not start
        release.complete(Unit)
        val done = controller.await("done with rows 2,3,1") { !it.busy && !it.dragging && it.items?.map { f -> f.id } == listOf(2L, 3L, 1L) }
        assertEquals(listOf(listOf(2L, 3L, 1L)), api.reorderRequests)
        assertEquals(listOf(2L, 3L, 1L), ids(done))
        assertNull(done.message)
        assertEquals(listOf(2L, 3L, 1L), store.feeds.values.sortedBy { it.priority }.map { it.id })
    }

    @Test
    fun aFailedReorderSnapsBackAndShowsTheBanner() {
        loaded()
        api.reorderHandler = { throw ApiException(ApiError.Unreachable(false)) }
        controller.onDragStarted()
        controller.onMove(1, 3)
        controller.onDragStopped()
        val state = controller.await("the reorder failed") { !it.busy && it.message == FeedsMessage.MoveFailed("Cannot reach the server.") }
        assertEquals(listOf(1L, 2L, 3L), ids(state))
        assertEquals(FeedsMessage.MoveFailed("Cannot reach the server."), state.message)
        controller.dismissMessage()
        controller.await("dismissed") { it.message == null }
    }

    @Test
    fun aRejectedTokenShowsNoBanner() {
        loaded()
        api.reorderHandler = { throw ApiException(ApiError.Unauthorized) }
        controller.onDragStarted()
        controller.await("dragging") { it.dragging } // barrier: the pre-drag state must not satisfy the wait below
        controller.onMove(1, 3)
        controller.onDragStopped()
        // The token is rejected before busy clears; waiting on it avoids reading the stale pre-action state.
        // Not dragging and not busy only holds after the reorder call ran; before the drop the state was dragging.
        val state = controller.await("the token was rejected") { !it.dragging && !it.busy && rejection.rejected.value }
        assertNull(state.message)
        assertTrue(rejection.rejected.value)
    }

    @Test
    fun aFeedRemovedDuringTheDragSendsNothingAndTellsTheUser() {
        loaded()
        controller.onDragStarted()
        controller.onMove(1, 3)
        runBlocking { store.transaction { deleteFeed(2) } }
        // Barrier: the controller has handled the list without the removed Feed before the drop.
        awaitGate("the controller to handle the removal of Feed 2", removalHandled)
        controller.await("rows do not jump") { s -> s.dragging && s.items!!.map { it.id } == listOf(2L, 3L, 1L) }
        controller.onDragStopped()
        val state = controller.await("told the list changed") { !it.dragging && it.message == FeedsMessage.ListChanged }
        assertEquals(FeedsMessage.ListChanged, state.message)
        assertEquals(listOf(1L, 3L), ids(state))
        assertTrue(api.reorderRequests.isEmpty())
    }

    @Test
    fun refreshRunsOnceAndReportsPerFeedFailures() {
        loaded()
        val release = CompletableDeferred<Unit>()
        api.refreshHandler = { release.await(); listOf(RefreshResultDto(1, true), RefreshResultDto(2, false, "Could not fetch")) }
        assertTrue(controller.refresh())
        assertFalse(controller.refresh())
        assertTrue(controller.await("refreshing") { it.refreshing }.busy)
        controller.onDragStarted() // inert while a server action runs
        // Barrier instead of a sleep: a state change made after onDragStarted() that is visible in the state,
        // so a state carrying the marker is newer than the drag attempt and shows whether it took effect.
        controller.showMessage(FeedsMessage.ListChanged)
        val afterDragAttempt = controller.await("the marker after the drag attempt") { it.message == FeedsMessage.ListChanged }
        assertFalse(afterDragAttempt.dragging)
        release.complete(Unit)
        val done = controller.await("the refresh finished with a partial result") { !it.refreshing && !it.busy && it.message is FeedsMessage.RefreshPartial }
        assertEquals(listOf("refresh", "pull"), api.calls.filter { it != "health" })
        val message = done.message as FeedsMessage.RefreshPartial
        assertEquals("Feed 2", message.lines.single().title)
    }

    @Test
    fun aFailedRefreshCallShowsTheApiText() {
        loaded()
        api.refreshHandler = { throw ApiException(ApiError.Unreachable(false)) }
        controller.refresh()
        // Wait for the message too: the first emitted state can still be the stale one without refreshing.
        val done = controller.await("the refresh failed") { !it.refreshing && !it.busy && it.message == FeedsMessage.RefreshFailed("Cannot reach the server.") }
        assertEquals(FeedsMessage.RefreshFailed("Cannot reach the server."), done.message)
        assertEquals(listOf("refresh"), api.calls)
    }

    @Test
    fun unsubscribeAsksFirstAndCancelChangesNothing() {
        loaded()
        controller.requestUnsubscribe(2)
        assertEquals("Feed 2", controller.await("asked") { it.unsubscribeTarget != null }.unsubscribeTarget!!.title)
        controller.cancelUnsubscribe()
        controller.await("cancelled") { it.unsubscribeTarget == null }
        assertTrue(api.unsubscribeRequests.isEmpty())
    }

    @Test
    fun confirmingUnsubscribeSendsTheDeleteAndFailureKeepsTheRow() {
        loaded()
        api.unsubscribeHandler = { throw ApiException(ApiError.Http(500, null)) }
        controller.requestUnsubscribe(2)
        controller.confirmUnsubscribe()
        val state = controller.await("the unsubscribe failed") {
            !it.busy && it.message == FeedsMessage.UnsubscribeFailed("Feed 2", "The server answered with an error (HTTP 500).")
        }
        assertEquals(listOf(2L), api.unsubscribeRequests)
        assertEquals(FeedsMessage.UnsubscribeFailed("Feed 2", "The server answered with an error (HTTP 500)."), state.message)
        assertEquals(listOf(1L, 2L, 3L), ids(state))
    }
}
