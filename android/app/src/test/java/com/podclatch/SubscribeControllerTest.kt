package com.podclatch

import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.repo.SubscribeRepository
import com.podclatch.data.repo.SubscribeResult
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.FailureReason
import com.podclatch.domain.FeedUrlProblem
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.TokenRejection
import com.podclatch.ui.feeds.FeedsMessage
import com.podclatch.ui.feeds.subscribeOutcomeMessage
import com.podclatch.ui.subscribe.SubscribeController
import com.podclatch.ui.subscribe.SubscribeMessage
import com.podclatch.ui.subscribe.SubscribeOutcome
import com.podclatch.ui.subscribe.addressToSend
import com.podclatch.ui.subscribe.toMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
        controller.await("the outcome") { it.outcome != null }
        assertEquals(listOf("http://feedhost:8080/feeds/alpha.xml"), api.subscribeRequests)
    }

    @Test
    fun anEmptyOrBlankFieldSendsNothingAndDisablesTheButton() {
        assertFalse(controller.uiState.value.canSubscribe)
        controller.onTextChange("   ")
        assertFalse(controller.uiState.value.canSubscribe)
        assertFalse(controller.subscribe())
        controller.onTextChange(" x ")
        assertTrue(controller.await("the text ' x '") { it.text == " x " }.canSubscribe)
        assertTrue(api.subscribeRequests.isEmpty())
    }

    @Test
    fun successProducesTheOutcomeOnceAndTheSyncFailureTravelsWithIt() {
        api.pullHandler = { _, _ -> throw ApiException(ApiError.Unreachable(false)) }
        api.subscribeHandler = { feedDto(9, title = "Nine") }
        controller.onTextChange("http://h/x.xml")
        controller.subscribe()
        val state = controller.await("the outcome and not busy") { it.outcome != null && !it.busy }
        assertEquals(SubscribeOutcome(9, "Nine", "Cannot reach the server."), state.outcome)
        assertFalse(state.busy)
        controller.consumeOutcome()
        assertNull(controller.await("the outcome consumed") { it.outcome == null }.outcome)
    }

    @Test
    fun anErrorKeepsTheTextShowsTheMessageAndAllowsAnotherTry() {
        api.subscribeHandler = { throw ApiException(ApiError.Http(409, "Already subscribed to this feed.")) }
        controller.onTextChange("http://h/x.xml")
        controller.subscribe()
        val state = controller.await("the already-subscribed message and not busy") { it.message == SubscribeMessage.AlreadySubscribed && !it.busy }
        assertEquals(SubscribeMessage.AlreadySubscribed, state.message)
        assertEquals("http://h/x.xml", state.text)
        assertTrue(state.canSubscribe)
        assertNull(state.outcome)

        // Typing clears the message; a new attempt clears it too.
        controller.onTextChange("http://h/y.xml")
        assertNull(controller.await("the new text without a message") { it.text == "http://h/y.xml" && it.message == null }.message)
    }

    @Test
    fun twoQuickTapsSendOneRequestAndBusyDisablesTheControls() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.subscribeHandler = { entered.complete(Unit); release.await(); feedDto(5) }
        controller.onTextChange("http://h/x.xml")
        assertTrue(controller.subscribe())
        // The second tap is refused synchronously (the running request is the single one); no waiting is needed.
        assertFalse(controller.subscribe())
        awaitGate("the first request to reach the API", entered)
        val busy = controller.await("busy") { it.busy }
        assertFalse(busy.canSubscribe)
        assertEquals(1, api.subscribeRequests.size)
        release.complete(Unit)
        controller.await("the outcome and not busy") { it.outcome != null && !it.busy }
        assertEquals(1, api.subscribeRequests.size)
    }

    @Test
    fun aRejectedTokenShowsNoMessage() {
        val release = CompletableDeferred<Unit>()
        api.subscribeHandler = { release.await(); throw ApiException(ApiError.Unauthorized) }
        controller.onTextChange("http://h/x.xml")
        controller.subscribe()
        controller.await("busy") { it.busy } // barrier: the idle state before the tap must not satisfy the wait below
        release.complete(Unit)
        val state = controller.await("the token was rejected") { !it.busy && rejection.rejected.value }
        assertNull(state.message)
        assertNull(state.outcome)
    }

    @Test
    fun anUnexpectedErrorShowsAFixedMessageWithoutItsText() {
        api.subscribeHandler = { throw IllegalStateException("leaks http://h/x.xml?token=SECRETMARK123") }
        controller.onTextChange("http://h/x.xml")
        controller.subscribe()
        assertEquals(SubscribeMessage.Unexpected, controller.await("the unexpected-error message") { it.message == SubscribeMessage.Unexpected }.message)
    }

    // ---- Validation, Clear, and restore ----
    // Every wait below is for the exact END state that is asserted, never for "some error" or "not busy",
    // so a state published by an earlier step can never satisfy it.

    @Test
    fun theTrimmedTextAsTypedReachesTheRepository() {
        controller.onTextChange("  HTTP://Feedhost:8080/Feeds/A.xml?x=1#frag ")
        assertTrue(controller.subscribe())
        controller.await("the outcome") { it.outcome != null && !it.busy }
        assertEquals(listOf("HTTP://Feedhost:8080/Feeds/A.xml?x=1#frag"), api.subscribeRequests)
    }

    @Test
    fun anUnacceptableAddressSendsNothingKeepsTheTextAndClearsTheServerMessage() {
        api.subscribeHandler = { throw ApiException(ApiError.Http(409, "Already subscribed to this feed.")) }
        controller.onTextChange("http://h/x.xml")
        controller.subscribe()
        controller.await("the already-subscribed message") { it.message == SubscribeMessage.AlreadySubscribed && !it.busy }

        controller.onTextChange("ftp://example.com/x")
        assertFalse(controller.subscribe())
        val scheme = controller.await("the bad-scheme error") { it.text == "ftp://example.com/x" && it.validationError == FeedUrlProblem.BAD_SCHEME && it.message == null }
        assertTrue(scheme.canSubscribe)

        controller.onTextChange("https://")
        assertFalse(controller.subscribe())
        controller.await("the incomplete error") { it.text == "https://" && it.validationError == FeedUrlProblem.INCOMPLETE && it.message == null }

        controller.onTextChange("https://e")
        controller.await("the acceptable address") { it.text == "https://e" && it.validationError == null && it.message == null }
        assertEquals(1, api.subscribeRequests.size)
    }

    @Test
    fun clearEmptiesTheFieldTheErrorAndTheMessageAndReportsTheText() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val c = SubscribeController(SubscribeRepository(api, engine, rejection), scope, "http://h/restored.xml") { seen += it }
        c.await("the expected state") { it.text == "http://h/restored.xml" }
        c.onTextChange("nope")
        assertFalse(c.subscribe())
        c.await("the expected state") { it.text == "nope" && it.validationError == FeedUrlProblem.BAD_SCHEME }
        c.clear()
        c.await("the expected state") { it.text.isEmpty() && it.validationError == null && it.message == null }
        assertEquals(listOf("nope", ""), seen.toList())
    }

    @Test
    fun clearAndSubscribeDoNothingWhileASubscribeIsRunning() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.subscribeHandler = { entered.complete(Unit); release.await(); feedDto(5) }
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val c = SubscribeController(SubscribeRepository(api, engine, rejection), scope, "http://h/x.xml") { seen += it }
        c.await("the expected state") { it.text == "http://h/x.xml" }
        assertTrue(c.subscribe())
        // The fake is now blocked inside the subscribe call, so the request is running for certain.
        awaitGate("the subscribe call to be reached", entered)
        c.await("busy") { it.busy }

        c.clear()
        assertFalse(c.subscribe())
        assertEquals("clear() must not touch the saved text while busy", emptyList<String>(), seen.toList())

        release.complete(Unit)
        // The state that carries the outcome also carries the text: it must still be the original.
        val done = c.await("the expected state") { it.outcome != null && !it.busy }
        assertEquals("http://h/x.xml", done.text)
        assertEquals(1, api.subscribeRequests.size)
    }
}
