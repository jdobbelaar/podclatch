package com.podclatch

import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.repo.SubscribeRepository
import com.podclatch.data.repo.SubscribeResult
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.FailureReason
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.TokenRejection
import com.podclatch.ui.feeds.FeedsMessage
import com.podclatch.ui.feeds.subscribeOutcomeMessage
import com.podclatch.ui.subscribe.SubscribeController
import com.podclatch.ui.subscribe.SubscribeMessage
import com.podclatch.ui.subscribe.SubscribeOutcome
import com.podclatch.ui.subscribe.SubscribeUiState
import com.podclatch.ui.subscribe.addressToSend
import com.podclatch.ui.subscribe.toMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscribeControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = FakeSettings()
    private val api = FakeApi()
    private val store = FakeCacheStore()
    private val rejection = TokenRejection(settings)
    private val engine = SyncEngine(api, settings, store, { }, { 5_000 }, rejection, DiskFileDeleter(), Dispatchers.Unconfined)
    private val controller = SubscribeController(SubscribeRepository(api, engine, rejection), scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun await(condition: (SubscribeUiState) -> Boolean): SubscribeUiState = runBlocking {
        withTimeout(5_000) { controller.uiState.first(condition) }
    }

    // ---- The mapping from result to message ----

    @Test
    fun everyResultMapsToItsMessage() {
        assertNull(SubscribeResult.Subscribed(1, "t", null).toMessage())
        assertNull(SubscribeResult.TokenRejected.toMessage())
        assertNull(SubscribeResult.Busy.toMessage())
        assertEquals(SubscribeMessage.AlreadySubscribed, SubscribeResult.AlreadySubscribed.toMessage())
        assertEquals(
            SubscribeMessage.NotReadable("The response is not an RSS feed."),
            SubscribeResult.NotReadable("The response is not an RSS feed.").toMessage(),
        )
        assertEquals(SubscribeMessage.NotReadableNoDetail, SubscribeResult.NotReadable("").toMessage())
        assertEquals(SubscribeMessage.NotReadableNoDetail, SubscribeResult.NotReadable("  ").toMessage())
        assertEquals(SubscribeMessage.Failed("Cannot reach the server."), SubscribeResult.Unreachable(false).toMessage())
        assertEquals(
            SubscribeMessage.Failed("The server did not answer in time."),
            SubscribeResult.Unreachable(true).toMessage(),
        )
        assertEquals(
            SubscribeMessage.Failed("The server answered with an error (HTTP 500): boom"),
            SubscribeResult.ServerError(ApiError.Http(500, "boom")).toMessage(),
        )
        assertEquals(
            SubscribeMessage.Failed("The server's answer was not understood."),
            SubscribeResult.ServerError(ApiError.Malformed).toMessage(),
        )
    }

    @Test
    fun theSyncFailureBecomesTheFeedsBanner() {
        assertNull(subscribeOutcomeMessage(null))
        val reason = FailureReason.Api(ApiError.Unreachable(false)).message
        assertEquals(FeedsMessage.SubscribedSyncFailed(reason), subscribeOutcomeMessage(reason))
        assertEquals("Cannot reach the server.", reason)
    }

    // ---- The trigger ----

    @Test
    fun theSentTextIsTrimmedAndOtherwiseUnchanged() {
        assertEquals("HTTP://Host/a b.xml", addressToSend("  \tHTTP://Host/a b.xml \n"))
        controller.onTextChange("  http://feedhost:8080/feeds/alpha.xml  ")
        assertTrue(controller.subscribe())
        await { it.outcome != null }
        assertEquals(listOf("http://feedhost:8080/feeds/alpha.xml"), api.subscribeRequests)
    }

    @Test
    fun anEmptyOrBlankFieldSendsNothingAndDisablesTheButton() {
        assertFalse(controller.uiState.value.canSubscribe)
        controller.onTextChange("   ")
        assertFalse(controller.uiState.value.canSubscribe)
        assertFalse(controller.subscribe())
        controller.onTextChange(" x ")
        assertTrue(await { it.text == " x " }.canSubscribe)
        assertTrue(api.subscribeRequests.isEmpty())
    }

    @Test
    fun successProducesTheOutcomeOnceAndTheSyncFailureTravelsWithIt() {
        api.pullHandler = { _, _ -> throw ApiException(ApiError.Unreachable(false)) }
        api.subscribeHandler = { feedDto(9, title = "Nine") }
        controller.onTextChange("http://h/x.xml")
        controller.subscribe()
        val state = await { it.outcome != null }
        assertEquals(SubscribeOutcome(9, "Nine", "Cannot reach the server."), state.outcome)
        assertFalse(state.busy)
        controller.consumeOutcome()
        assertNull(await { it.outcome == null }.outcome)
    }

    @Test
    fun anErrorKeepsTheTextShowsTheMessageAndAllowsAnotherTry() {
        api.subscribeHandler = { throw ApiException(ApiError.Http(409, "Already subscribed to this feed.")) }
        controller.onTextChange("http://h/x.xml")
        controller.subscribe()
        val state = await { it.message != null }
        assertEquals(SubscribeMessage.AlreadySubscribed, state.message)
        assertEquals("http://h/x.xml", state.text)
        assertTrue(await { !it.busy }.canSubscribe)
        assertNull(state.outcome)

        // Typing clears the message; a new attempt clears it too.
        controller.onTextChange("http://h/y.xml")
        assertNull(await { it.text == "http://h/y.xml" }.message)
    }

    @Test
    fun twoQuickTapsSendOneRequestAndBusyDisablesTheControls() = runBlocking {
        val release = CompletableDeferred<Unit>()
        api.subscribeHandler = { release.await(); feedDto(5) }
        controller.onTextChange("http://h/x.xml")
        assertTrue(controller.subscribe())
        assertFalse(controller.subscribe())
        val busy = await { it.busy }
        assertFalse(busy.canSubscribe)
        delay(100)
        assertEquals(1, api.subscribeRequests.size)
        release.complete(Unit)
        await { it.outcome != null }
        assertEquals(1, api.subscribeRequests.size)
    }

    @Test
    fun aRejectedTokenShowsNoMessage() {
        api.subscribeHandler = { throw ApiException(ApiError.Unauthorized) }
        controller.onTextChange("http://h/x.xml")
        controller.subscribe()
        val state = await { !it.busy && rejection.rejected.value }
        assertNull(state.message)
        assertNull(state.outcome)
    }

    @Test
    fun anUnexpectedErrorShowsAFixedMessageWithoutItsText() {
        api.subscribeHandler = { throw IllegalStateException("leaks http://h/x.xml?token=SECRETMARK123") }
        controller.onTextChange("http://h/x.xml")
        controller.subscribe()
        assertEquals(SubscribeMessage.Unexpected, await { it.message != null }.message)
    }
}
