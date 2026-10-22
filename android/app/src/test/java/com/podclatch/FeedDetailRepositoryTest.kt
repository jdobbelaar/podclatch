package com.podclatch

import com.podclatch.data.db.ListenOrder
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.RefreshResultDto
import com.podclatch.data.repo.FeedsRepository
import com.podclatch.data.repo.FeedsResult
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncResult
import com.podclatch.domain.TokenRejection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `FeedsRepository.updateListenOrder` and `refreshFeed`, with a fake API and store. */
class FeedDetailRepositoryTest {
    private class Env {
        val settings = FakeSettings()
        val api = FakeApi()
        val store = FakeCacheStore()
        val rejection = TokenRejection(settings)
        val engine = SyncEngine(api, settings, store, { }, { 5_000 }, rejection, DiskFileDeleter(), Dispatchers.Unconfined)
        val repo = FeedsRepository(api, store, engine, rejection)

        init {
            store.feeds[1] = cachedFeed(1).copy(title = "A")
            store.feeds[2] = cachedFeed(2).copy(title = "B")
        }
    }

    // ---- updateListenOrder ----

    @Test
    fun listenOrderPatchesThenWritesRoomThenSyncsInThatOrder() = runBlocking {
        val env = Env()
        // The write to Room must already be visible when the follow-up pull starts.
        var orderWhenPulling: ListenOrder? = null
        env.api.pullHandler = { since, _ ->
            orderWhenPulling = env.store.feeds.getValue(1).listenOrder
            page(nextRev = since)
        }
        val result = env.repo.updateListenOrder(1, ListenOrder.OLDEST_FIRST)
        assertTrue(result is FeedsResult.Ok)
        assertEquals(listOf("listenOrder", "pull"), env.api.calls.filter { it != "health" })
        assertEquals(listOf(1L to ListenOrder.OLDEST_FIRST), env.api.listenOrderRequests)
        assertEquals(ListenOrder.OLDEST_FIRST, env.store.feeds.getValue(1).listenOrder)
        assertEquals(ListenOrder.OLDEST_FIRST, orderWhenPulling)
        assertEquals(ListenOrder.NEWEST_FIRST, env.store.feeds.getValue(2).listenOrder)
    }

    @Test
    fun aFailedFollowUpSyncIsNotAListenOrderFailure() = runBlocking {
        val env = Env()
        env.api.pullHandler = { _, _ -> throw ApiException(ApiError.Unreachable(false)) }
        val result = env.repo.updateListenOrder(1, ListenOrder.OLDEST_FIRST) as FeedsResult.Ok
        assertTrue(result.value is SyncResult.Failed)
        assertEquals(ListenOrder.OLDEST_FIRST, env.store.feeds.getValue(1).listenOrder)
    }

    @Test
    fun listenOrderFailuresChangeNothingAndStartNoSync() = runBlocking {
        for (error in listOf(ApiError.Unreachable(false), ApiError.Unreachable(true), ApiError.Http(422, "x"), ApiError.Http(500, null))) {
            val env = Env()
            env.api.listenOrderHandler = { _, _ -> throw ApiException(error) }
            assertEquals(FeedsResult.Failed(error), env.repo.updateListenOrder(1, ListenOrder.OLDEST_FIRST))
            assertEquals(listOf("listenOrder"), env.api.calls)
            assertEquals(ListenOrder.NEWEST_FIRST, env.store.feeds.getValue(1).listenOrder)
        }
    }

    @Test
    fun aRejectedTokenGoesThroughTokenRejection() = runBlocking {
        val env = Env()
        env.api.listenOrderHandler = { _, _ -> throw ApiException(ApiError.Unauthorized) }
        assertEquals(FeedsResult.Failed(ApiError.Unauthorized), env.repo.updateListenOrder(1, ListenOrder.OLDEST_FIRST))
        assertTrue(env.rejection.rejected.value)
        assertEquals(listOf("listenOrder"), env.api.calls)
    }

    @Test
    fun aMissingFeedOnTheServerRunsASync() = runBlocking {
        val env = Env()
        env.api.listenOrderHandler = { _, _ -> throw ApiException(ApiError.Http(404, "gone")) }
        assertEquals(FeedsResult.Failed(ApiError.Http(404, "gone")), env.repo.updateListenOrder(1, ListenOrder.OLDEST_FIRST))
        assertEquals(listOf("listenOrder", "pull"), env.api.calls.filter { it != "health" })
        assertEquals(ListenOrder.NEWEST_FIRST, env.store.feeds.getValue(1).listenOrder)
    }

    // ---- refreshFeed ----

    @Test
    fun refreshFeedRefreshesOneFeedThenAlwaysSyncs() = runBlocking {
        val env = Env()
        env.api.refreshFeedHandler = { id -> listOf(RefreshResultDto(id, false, "bad")) }
        val result = env.repo.refreshFeed(2) as FeedsResult.Ok
        assertEquals(listOf(RefreshResultDto(2, false, "bad")), result.value.results)
        assertEquals(listOf("refreshFeed", "pull"), env.api.calls.filter { it != "health" })
        assertEquals(listOf(2L), env.api.refreshFeedRequests)
    }

    @Test
    fun aFailedRefreshCallStartsNoSyncExceptAfterA404() = runBlocking {
        for (error in listOf(ApiError.Unreachable(true), ApiError.Http(422, "x"), ApiError.Http(502, "bad"))) {
            val env = Env()
            env.api.refreshFeedHandler = { throw ApiException(error) }
            assertEquals(FeedsResult.Failed(error), env.repo.refreshFeed(1))
            assertEquals(listOf("refreshFeed"), env.api.calls)
        }
        val env = Env()
        env.api.refreshFeedHandler = { throw ApiException(ApiError.Http(404, "gone")) }
        assertEquals(FeedsResult.Failed(ApiError.Http(404, "gone")), env.repo.refreshFeed(1))
        assertEquals(listOf("refreshFeed", "pull"), env.api.calls.filter { it != "health" })
    }

    @Test
    fun aRejectedTokenOnRefreshGoesThroughTokenRejection() = runBlocking {
        val env = Env()
        env.api.refreshFeedHandler = { throw ApiException(ApiError.Unauthorized) }
        env.repo.refreshFeed(1)
        assertTrue(env.rejection.rejected.value)
    }
}
