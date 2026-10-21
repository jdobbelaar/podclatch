package com.podclatch

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedsControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = FakeSettings()
    private val api = FakeApi()
    private val store = FakeCacheStore()
    private val rejection = TokenRejection(settings)
    private val engine = SyncEngine(api, settings, store, { }, { 5_000 }, rejection, DiskFileDeleter(), Dispatchers.Unconfined)
    private val controller: FeedsController

    init {
        for (id in 1L..3L) store.feeds[id] = cachedFeed(id).copy(priority = (id - 1).toInt(), title = "Feed $id")
        store.episodes[10] = cachedEpisode(10, 1)
        controller = FeedsController(FeedsRepository(api, store, engine, rejection), engine, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun await(what: String, condition: (FeedsUiState) -> Boolean): FeedsUiState = runBlocking {
        withTimeout(5_000) { controller.uiState.first(condition) }.also { check(condition(it)) { what } }
    }

    /** Publishes the store's current rows to the controller's list flow. */
    private fun loaded() = await("loaded") { it.items != null && it.items.size == 3 }

    private fun ids(state: FeedsUiState) = state.items!!.map { it.id }

    @Test
    fun theListShowsServerOrderWithUnplayedCounts() {
        runBlocking { store.transaction { updateFeedSynced(cachedFeed(1).copy(priority = 5)) } }
        val state = await("order") { s -> s.items?.map { it.id } == listOf(2L, 3L, 1L) }
        assertEquals(1, state.items!!.single { it.id == 1L }.unplayedCount)
    }

    @Test
    fun aDropWhereItStartedSendsNothing() {
        loaded()
        controller.onDragStarted()
        controller.onMove(1, 2)
        controller.onMove(2, 1) // ids: after the first move the list is 2,1,3; moving 2 onto 1's slot restores 1,2,3
        controller.onDragStopped()
        val state = await("settled") { !it.dragging && !it.busy }
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
        await("moved") { it.items?.map { f -> f.id } == listOf(2L, 3L, 1L) }
        controller.onDragStopped()
        val busy = await("busy") { it.busy }
        assertEquals(listOf(2L, 3L, 1L), ids(busy)) // rows stay in the dropped order while the request runs
        assertFalse(controller.refresh()) // a second server action does not start
        release.complete(Unit)
        val done = await("done") { !it.busy }
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
        val state = await("failed") { !it.busy && it.message != null }
        assertEquals(listOf(1L, 2L, 3L), ids(state))
        assertEquals(FeedsMessage.MoveFailed("Cannot reach the server."), state.message)
        controller.dismissMessage()
        await("dismissed") { it.message == null }
    }

    @Test
    fun aRejectedTokenShowsNoBanner() {
        loaded()
        api.reorderHandler = { throw ApiException(ApiError.Unauthorized) }
        controller.onDragStarted()
        controller.onMove(1, 3)
        controller.onDragStopped()
        val state = await("failed") { !it.busy }
        assertNull(state.message)
        assertTrue(rejection.rejected.value)
    }

    @Test
    fun aFeedRemovedDuringTheDragSendsNothingAndTellsTheUser() {
        loaded()
        controller.onDragStarted()
        controller.onMove(1, 3)
        runBlocking { store.transaction { deleteFeed(2) } }
        await("removed") { s -> s.dragging && s.items!!.map { it.id } == listOf(2L, 3L, 1L) } // rows do not jump
        controller.onDragStopped()
        val state = await("told") { !it.dragging && it.message != null }
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
        assertTrue(await("refreshing") { it.refreshing }.busy)
        controller.onDragStarted() // inert while a server action runs
        Thread.sleep(200)
        assertFalse(controller.uiState.value.dragging)
        release.complete(Unit)
        val done = await("done") { !it.refreshing }
        assertEquals(listOf("refresh", "pull"), api.calls.filter { it != "health" })
        val message = done.message as FeedsMessage.RefreshPartial
        assertEquals("Feed 2", message.lines.single().title)
    }

    @Test
    fun aFailedRefreshCallShowsTheApiText() {
        loaded()
        api.refreshHandler = { throw ApiException(ApiError.Unreachable(false)) }
        controller.refresh()
        val done = await("done") { !it.refreshing }
        assertEquals(FeedsMessage.RefreshFailed("Cannot reach the server."), done.message)
        assertEquals(listOf("refresh"), api.calls)
    }

    @Test
    fun unsubscribeAsksFirstAndCancelChangesNothing() {
        loaded()
        controller.requestUnsubscribe(2)
        assertEquals("Feed 2", await("asked") { it.unsubscribeTarget != null }.unsubscribeTarget!!.title)
        controller.cancelUnsubscribe()
        await("cancelled") { it.unsubscribeTarget == null }
        assertTrue(api.unsubscribeRequests.isEmpty())
    }

    @Test
    fun confirmingUnsubscribeSendsTheDeleteAndFailureKeepsTheRow() {
        loaded()
        api.unsubscribeHandler = { throw ApiException(ApiError.Http(500, null)) }
        controller.requestUnsubscribe(2)
        controller.confirmUnsubscribe()
        val state = await("failed") { !it.busy && it.message != null }
        assertEquals(listOf(2L), api.unsubscribeRequests)
        assertEquals(FeedsMessage.UnsubscribeFailed("Feed 2", "The server answered with an error (HTTP 500)."), state.message)
        assertEquals(listOf(1L, 2L, 3L), ids(state))
    }
}
