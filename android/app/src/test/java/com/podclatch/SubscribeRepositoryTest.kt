package com.podclatch

import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.repo.SubscribeRepository
import com.podclatch.data.repo.SubscribeResult
import com.podclatch.data.repo.redactAddress
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.FailureReason
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.TokenRejection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscribeRepositoryTest {
    private class Env {
        val settings = FakeSettings()
        val api = FakeApi()
        val store = FakeCacheStore()
        val rejection = TokenRejection(settings)
        val engine = SyncEngine(api, settings, store, { }, { 5_000 }, rejection, DiskFileDeleter(), Dispatchers.Unconfined)
        val repo = SubscribeRepository(api, engine, rejection)

        /** The calls that matter here: the fake API also records `push`, which an empty outbox never makes. */
        val calls get() = api.calls.filter { it != "health" }
    }

    private val url = "http://feedhost:8080/feeds/alpha.xml?token=SECRETMARK123"

    private fun failing(error: ApiError): suspend (String) -> Nothing = { throw ApiException(error) }

    private fun Env.assertNothingChanged() {
        assertEquals(listOf("subscribe"), calls)
        assertTrue(store.feeds.isEmpty() && store.episodes.isEmpty() && store.pending.isEmpty())
    }

    @Test
    fun successSyncsAfterThePostAndLeavesTheCacheToSync() = runBlocking {
        val env = Env()
        env.api.subscribeHandler = { feedDto(7, title = "Alpha") }
        env.api.pullHandler = { since, _ ->
            // Nothing wrote the Feed before the pull: it arrives through Sync only.
            assertTrue(env.store.feeds.isEmpty())
            page(feeds = listOf(feedDto(7, title = "Alpha")), episodes = listOf(episodeDto(70, 7)), nextRev = since + 3)
        }
        val result = env.repo.subscribe(url)
        assertEquals(SubscribeResult.Subscribed(7, "Alpha", null), result)
        assertEquals(listOf("subscribe", "pull"), env.calls)
        assertEquals(listOf(url), env.api.subscribeRequests)
        assertEquals(setOf(7L), env.store.feeds.keys)
        assertEquals(setOf(70L), env.store.episodes.keys)
    }

    @Test
    fun aFailedFollowUpSyncStillCountsAsSubscribedAndIsNotRetried() = runBlocking {
        val env = Env()
        env.api.pullHandler = { _, _ -> throw ApiException(ApiError.Unreachable(timeout = true)) }
        val result = env.repo.subscribe(url) as SubscribeResult.Subscribed
        assertEquals(FailureReason.Api(ApiError.Unreachable(true)), result.syncFailure)
        assertEquals(1, env.api.subscribeRequests.size)
        assertEquals(listOf("subscribe", "pull"), env.calls)
    }

    @Test
    fun aSkippedSyncIsAlsoASyncFailure() = runBlocking {
        val env = Env()
        // The token disappears while the POST is in flight, so the manual Sync is skipped (not configured).
        env.api.subscribeHandler = { env.settings.clearToken(); feedDto(7) }
        val result = env.repo.subscribe(url) as SubscribeResult.Subscribed
        assertEquals(FailureReason.Local, result.syncFailure)
        assertEquals(listOf("subscribe"), env.calls)
    }

    @Test
    fun a409IsAlreadySubscribedWithoutSyncOrWrites() = runBlocking {
        val env = Env()
        env.api.subscribeHandler = failing(ApiError.Http(409, "Already subscribed to this feed."))
        assertEquals(SubscribeResult.AlreadySubscribed, env.repo.subscribe(url))
        env.assertNothingChanged()
    }

    @Test
    fun a422CarriesTheDetailWithoutTheAddress() = runBlocking {
        val env = Env()
        env.api.subscribeHandler = failing(ApiError.Http(422, "Could not read $url as a feed."))
        val result = env.repo.subscribe(url) as SubscribeResult.NotReadable
        assertEquals("Could not read (the feed address) as a feed.", result.detail)
        env.assertNothingChanged()

        env.api.subscribeHandler = failing(ApiError.Http(422, null))
        assertEquals(SubscribeResult.NotReadable(""), env.repo.subscribe(url))
    }

    @Test
    fun anEchoedAddressIsRedactedInAnyLetterCaseAndByItsQueryAlone() {
        assertEquals("a (the feed address) b", redactAddress("a HTTP://FEEDHOST:8080/feeds/alpha.xml?token=SECRETMARK123 b", url))
        assertEquals("query (the feed address)", redactAddress("query token=SECRETMARK123", url))
        assertEquals("nothing to hide", redactAddress("nothing to hide", url))
        assertEquals("keep", redactAddress("keep", ""))
    }

    @Test
    fun unreachableAndTimeoutKeepTheFlag() = runBlocking {
        val env = Env()
        env.api.subscribeHandler = failing(ApiError.Unreachable(false))
        assertEquals(SubscribeResult.Unreachable(false), env.repo.subscribe(url))
        env.api.subscribeHandler = failing(ApiError.Unreachable(true))
        assertEquals(SubscribeResult.Unreachable(true), env.repo.subscribe(url))
        assertEquals(listOf("subscribe", "subscribe"), env.calls)
        assertTrue(env.store.feeds.isEmpty())
    }

    @Test
    fun otherErrorsAndMalformedAreServerErrorsWithTheAddressRedacted() = runBlocking {
        val env = Env()
        env.api.subscribeHandler = failing(ApiError.Http(500, "boom for $url"))
        assertEquals(
            SubscribeResult.ServerError(ApiError.Http(500, "boom for (the feed address)")),
            env.repo.subscribe(url),
        )
        env.api.subscribeHandler = failing(ApiError.Malformed)
        assertEquals(SubscribeResult.ServerError(ApiError.Malformed), env.repo.subscribe(url))
        assertEquals(listOf("subscribe", "subscribe"), env.calls)
    }

    @Test
    fun a401ClearsTheTokenAndDoesNotSync() = runBlocking {
        val env = Env()
        env.api.subscribeHandler = failing(ApiError.Unauthorized)
        assertEquals(SubscribeResult.TokenRejected, env.repo.subscribe(url))
        assertTrue(env.rejection.rejected.value)
        assertEquals("", env.settings.flow.value.authToken)
        env.assertNothingChanged()
    }

    @Test
    fun noResultTextOrErrorContainsTheAddress() = runBlocking {
        val env = Env()
        env.api.subscribeHandler = failing(ApiError.Http(500, "echo $url"))
        val text = env.repo.subscribe(url).toString()
        assertFalse(text.contains("SECRETMARK123"))
        assertFalse(text.contains("feedhost"))
    }

    @Test
    fun onlyOneSubscribeRunsAtATime() = runBlocking {
        val env = Env()
        val release = CompletableDeferred<Unit>()
        env.api.subscribeHandler = { release.await(); feedDto(7) }
        val first = async(Dispatchers.Default) { env.repo.subscribe(url) }
        awaitCondition("the subscribe request to reach the API") { env.api.subscribeRequests.isNotEmpty() }
        assertTrue(env.repo.inProgress.value)

        assertEquals(SubscribeResult.Busy, env.repo.subscribe(url))
        assertEquals(SubscribeResult.Busy, env.repo.subscribe("http://other/x.xml"))
        assertEquals(1, env.api.subscribeRequests.size)

        release.complete(Unit)
        assertTrue(first.await() is SubscribeResult.Subscribed)
        assertFalse(env.repo.inProgress.value)

        // A new call is accepted once the first has finished.
        assertTrue(env.repo.subscribe(url) is SubscribeResult.Subscribed)
        assertEquals(2, env.api.subscribeRequests.size)
    }

    @Test
    fun theWorkContinuesWhenTheCallerIsCancelled() = runBlocking {
        val env = Env()
        val release = CompletableDeferred<Unit>()
        env.api.subscribeHandler = { release.await(); feedDto(7) }
        val caller = launch(Dispatchers.Default, start = CoroutineStart.DEFAULT) { env.repo.subscribe(url) }
        awaitCondition("the subscribe request to reach the API") { env.api.subscribeRequests.isNotEmpty() }

        caller.cancel()
        release.complete(Unit)
        caller.join()

        awaitCondition("the subscribe to finish") { !env.repo.inProgress.value }
        assertEquals(listOf("subscribe", "pull"), env.calls)
        assertFalse(env.repo.inProgress.value)
    }
}
