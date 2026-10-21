package com.podclatch

import com.podclatch.data.repo.SubscribeRepository
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.TokenRejection
import com.podclatch.ui.subscribe.SubscribeController
import com.podclatch.ui.subscribe.SubscribeMessage
import com.podclatch.ui.subscribe.SubscribeUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The preview's Subscribe button uses the subscribe action of the address field, with the result's address. */
class SubscribeFromPreviewTest {
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

    /** Waits for the exact end state; fails fast with a clear message after 5 s instead of hanging. */
    private fun await(what: String, condition: (SubscribeUiState) -> Boolean): SubscribeUiState = runBlocking {
        try {
            withTimeout(5_000) { controller.uiState.first(condition) }
        } catch (e: TimeoutCancellationException) {
            fail("Not reached within 5 s: $what; the last state was ${controller.uiState.value.copy(text = "<hidden>")}")
            throw e
        }
    }

    private fun awaitEntered(entered: CompletableDeferred<Unit>) = runBlocking {
        try {
            withTimeout(5_000) { entered.await() }
        } catch (e: TimeoutCancellationException) {
            fail("The subscribe call was not reached within 5 s")
        }
    }

    @Test
    fun theAddressIsSentExactlyAsGivenWithoutTrimmingOrTheFieldText() {
        controller.onTextChange("https://typed.example.com/ignored.xml")
        val address = "HTTP://Feeds.Example.com:80/a b.xml?x=1 "
        assertTrue(controller.subscribeTo(address))
        await("the outcome") { it.outcome != null && !it.busy }
        assertEquals(listOf(address), api.subscribeRequests)
        // The typed text is not touched.
        assertEquals("https://typed.example.com/ignored.xml", controller.uiState.value.text)
    }

    @Test
    fun aSecondTapWhileBusyDoesNothingAndTheBusyStateIsShown() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.subscribeHandler = {
            entered.complete(Unit)
            release.await()
            feedDto(5)
        }
        assertTrue(controller.subscribeTo("https://a.example.com/1.xml"))
        awaitEntered(entered)
        await("busy") { it.busy }
        assertFalse(controller.subscribeTo("https://b.example.com/2.xml"))
        assertFalse(controller.subscribe())
        release.complete(Unit)
        await("the outcome and not busy") { it.outcome != null && !it.busy }
        assertEquals(listOf("https://a.example.com/1.xml"), api.subscribeRequests)
    }

    @Test
    fun theMessageAppearsAndOpeningThePreviewCanClearIt() {
        api.subscribeHandler = { throw com.podclatch.data.network.ApiException(com.podclatch.data.network.ApiError.Http(409, "dup")) }
        assertTrue(controller.subscribeTo("https://a.example.com/1.xml"))
        await("the already-subscribed message") { it.message == SubscribeMessage.AlreadySubscribed && !it.busy }
        controller.clearMessage()
        await("no message") { it.message == null && !it.busy }
    }

    @Test
    fun anEmptyAddressSendsNothing() {
        assertFalse(controller.subscribeTo(""))
        assertEquals(emptyList<String>(), api.subscribeRequests)
    }
}
