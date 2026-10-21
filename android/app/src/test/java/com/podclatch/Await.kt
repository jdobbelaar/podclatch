package com.podclatch

import com.podclatch.ui.feeds.FeedsController
import com.podclatch.ui.feeds.FeedsUiState
import com.podclatch.ui.subscribe.SubscribeController
import com.podclatch.ui.subscribe.SubscribeUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** The default time a test waits for a state before it fails. */
const val AWAIT_TIMEOUT_MS = 5_000L

/**
 * The one wait for a state flow in the JVM unit tests. Waits until [condition] holds and returns that
 * state; on timeout it fails (never hangs) with [what] and the last state as printed by [describe].
 *
 * A controller's `uiState` lags the action that changes it by a coroutine hop and conflates, so [what]
 * must be the exact END state the test asserts on: a condition that was already true before the action
 * can return the stale state. Pass a [describe] that leaves out anything that can hold a feed address.
 */
fun <T> awaitState(
    flow: StateFlow<T>,
    what: String,
    timeoutMs: Long = AWAIT_TIMEOUT_MS,
    describe: (T) -> String = { it.toString() },
    condition: (T) -> Boolean,
): T {
    val state = runBlocking { withTimeoutOrNull(timeoutMs) { flow.first(condition) } }
    return state ?: throw AssertionError("Not reached within $timeoutMs ms: $what; the last state was ${describe(flow.value)}")
}

/** Waits for a gate that a deliberately held fake completes when it is reached; fails with [what] on timeout. */
fun awaitGate(what: String, gate: CompletableDeferred<Unit>, timeoutMs: Long = AWAIT_TIMEOUT_MS) {
    val reached = runBlocking { withTimeoutOrNull(timeoutMs) { gate.await() } }
    if (reached == null) throw AssertionError("Not reached within $timeoutMs ms: $what")
}

/** Polls a plain condition that is not a state flow (for example a list a fake fills in); fails with [what] on timeout. */
fun awaitCondition(what: String, timeoutMs: Long = AWAIT_TIMEOUT_MS, condition: () -> Boolean) {
    val reached = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(5)
            true
        }
    }
    if (reached == null) throw AssertionError("Not reached within $timeoutMs ms: $what")
}

fun FeedsController.await(what: String, condition: (FeedsUiState) -> Boolean): FeedsUiState =
    awaitState(uiState, what, describe = ::describeFeeds, condition = condition)

fun SubscribeController.await(what: String, condition: (SubscribeUiState) -> Boolean): SubscribeUiState =
    awaitState(uiState, what, describe = ::describeSubscribe, condition = condition)

private fun describeFeeds(s: FeedsUiState) =
    "items=${s.items?.map { it.id }} dragging=${s.dragging} busy=${s.busy} refreshing=${s.refreshing} " +
        "message=${s.message} unsubscribeTarget=${s.unsubscribeTarget?.feedId}"

/** Leaves out `text` and the outcome's title: they can hold a feed address. */
private fun describeSubscribe(s: SubscribeUiState) =
    "busy=${s.busy} message=${s.message} hasOutcome=${s.outcome != null} validationError=${s.validationError} " +
        "textLength=${s.text.length}"
