package com.podclatch

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedsRepositoryTest {
    private class Env {
        val settings = FakeSettings()
        val api = FakeApi()
        val store = FakeCacheStore()
        val rejection = TokenRejection(settings)
        val engine = SyncEngine(api, settings, store, { }, { 5_000 }, rejection, DiskFileDeleter(), Dispatchers.Unconfined)
        val repo = FeedsRepository(api, store, engine, rejection)

        init {
            store.feeds[1] = cachedFeed(1).copy(priority = 0, title = "A")
            store.feeds[2] = cachedFeed(2).copy(priority = 1, title = "B")
            store.feeds[3] = cachedFeed(3).copy(priority = 2, title = "C")
        }

        val priorities get() = store.feeds.values.associate { it.id to it.priority }
    }

    private fun failing(error: ApiError): suspend (Any?) -> Nothing = { throw ApiException(error) }

    // ---- reorder ----

    @Test
    fun reorderAppliesTheReturnedFeedsThenSyncs() = runBlocking {
        val env = Env()
        env.api.reorderHandler = { listOf(feedDto(3, priority = 0), feedDto(1, priority = 1), feedDto(2, priority = 2), feedDto(99, priority = 3)) }
        val result = env.repo.reorder(listOf(3, 1, 2))
        assertTrue(result is FeedsResult.Ok)
        assertEquals(listOf("reorder", "pull"), env.api.calls.filter { it != "health" })
        assertEquals(mapOf(1L to 1, 2L to 2, 3L to 0), env.priorities)
        assertFalse(99L in env.store.feeds)
        assertEquals(listOf(listOf(3L, 1L, 2L)), env.api.reorderRequests)
    }

    @Test
    fun aFailedFollowUpSyncIsNotAReorderFailure() = runBlocking {
        val env = Env()
        env.api.pullHandler = { _, _ -> throw ApiException(ApiError.Unreachable(false)) }
        val result = env.repo.reorder(listOf(3, 1, 2))
        assertTrue(result is FeedsResult.Ok)
        assertTrue((result as FeedsResult.Ok).value is SyncResult.Failed)
        assertEquals(0, env.priorities[3])
    }

    @Test
    fun reorderLeavesRoomUntouchedOnFailureAndRejectsTheToken() = runBlocking {
        val env = Env()
        env.api.reorderHandler = { throw ApiException(ApiError.Unreachable(false)) }
        val before = env.priorities
        assertEquals(FeedsResult.Failed(ApiError.Unreachable(false)), env.repo.reorder(listOf(3, 1, 2)))
        assertEquals(before, env.priorities)
        assertEquals(listOf("reorder"), env.api.calls)

        env.api.reorderHandler = { throw ApiException(ApiError.Unauthorized) }
        assertEquals(FeedsResult.Failed(ApiError.Unauthorized), env.repo.reorder(listOf(3, 1, 2)))
        assertTrue(env.rejection.rejected.value)
        assertEquals("", env.settings.flow.value.authToken)
    }

    @Test
    fun a422IsFollowedByASyncSoAStaleListCatchesUp() = runBlocking {
        val env = Env()
        env.api.reorderHandler = { throw ApiException(ApiError.Http(422, "stale")) }
        val result = env.repo.reorder(listOf(3, 1, 2))
        assertEquals(FeedsResult.Failed(ApiError.Http(422, "stale")), result)
        assertEquals(listOf("reorder", "pull"), env.api.calls)
    }

    // ---- unsubscribe ----

    @Test
    fun unsubscribeDeletesThenSyncsAndLeavesRemovalToTheTombstone() = runBlocking {
        val env = Env()
        val result = env.repo.unsubscribe(2)
        assertTrue(result is FeedsResult.Ok)
        assertEquals(listOf("unsubscribe", "pull"), env.api.calls)
        assertTrue(2L in env.store.feeds) // the fake pull carried no Tombstone
    }

    @Test
    fun aMissingFeedOnTheServerCountsAsSuccess() = runBlocking {
        val env = Env()
        env.api.unsubscribeHandler = { throw ApiException(ApiError.Http(404, "gone")) }
        assertTrue(env.repo.unsubscribe(2) is FeedsResult.Ok)
        assertEquals(listOf("unsubscribe", "pull"), env.api.calls)
    }

    @Test
    fun otherUnsubscribeFailuresChangeNothingAndStartNoSync() = runBlocking {
        for (error in listOf(ApiError.Unauthorized, ApiError.Http(422, "x"), ApiError.Http(500, null), ApiError.Unreachable(true))) {
            val env = Env()
            env.api.unsubscribeHandler = { throw ApiException(error) }
            assertEquals(FeedsResult.Failed(error), env.repo.unsubscribe(2))
            assertEquals(listOf("unsubscribe"), env.api.calls)
            assertEquals(3, env.store.feeds.size)
            assertEquals(error == ApiError.Unauthorized, env.rejection.rejected.value)
        }
    }

    @Test
    fun aSyncFailureAfter204IsReportedInTheValue() = runBlocking {
        val env = Env()
        env.api.pullHandler = { _, _ -> throw ApiException(ApiError.Unreachable(false)) }
        val result = env.repo.unsubscribe(2) as FeedsResult.Ok
        assertTrue(result.value is SyncResult.Failed)
    }

    // ---- refresh ----

    @Test
    fun refreshAlwaysSyncsAfterASuccessfulCallEvenWithFailedFeeds() = runBlocking {
        val env = Env()
        env.api.refreshHandler = { listOf(RefreshResultDto(1, true), RefreshResultDto(2, false, "bad")) }
        val result = env.repo.refreshAll() as FeedsResult.Ok
        assertEquals(2, result.value.results.size)
        assertEquals(listOf("refresh", "pull"), env.api.calls)
    }

    @Test
    fun aFailedRefreshCallStartsNoSync() = runBlocking {
        val env = Env()
        env.api.refreshHandler = { throw ApiException(ApiError.Http(502, "bad gateway")) }
        assertEquals(FeedsResult.Failed(ApiError.Http(502, "bad gateway")), env.repo.refreshAll())
        assertEquals(listOf("refresh"), env.api.calls)

        env.api.refreshHandler = { throw ApiException(ApiError.Unauthorized) }
        env.repo.refreshAll()
        assertTrue(env.rejection.rejected.value)
    }
}
