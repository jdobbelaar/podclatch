package com.podclatch.ui.subscribe

import com.podclatch.data.network.ApiError
import com.podclatch.data.repo.SubscribeRepository
import com.podclatch.data.repo.SubscribeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Subscribe view hands back to the Feeds tab through the Feeds back stack entry's
 * `SavedStateHandle`: the new Feed, and why the follow-up Sync failed (null when it worked).
 * [java.io.Serializable] so a SavedStateHandle accepts it.
 */
data class SubscribeOutcome(val feedId: Long, val title: String, val syncFailure: String?) : java.io.Serializable

/** The key of the result on the Feeds back stack entry's `SavedStateHandle`. */
const val SUBSCRIBE_OUTCOME_KEY = "subscribe_outcome"

/** What the message line under the address field says. The composable turns each case into a string resource. */
sealed interface SubscribeMessage {
    /** 409. */
    data object AlreadySubscribed : SubscribeMessage

    /** 422 with the server's detail (address already redacted). */
    data class NotReadable(val detail: String) : SubscribeMessage

    /** 422 with a blank detail. */
    data object NotReadableNoDetail : SubscribeMessage

    /** Unreachable, any other server error, or a malformed answer: [reason] is `ApiError.message`. */
    data class Failed(val reason: String) : SubscribeMessage

    /** An unexpected local error; no exception text is kept. */
    data object Unexpected : SubscribeMessage
}

/** The message for a result that is shown on this screen; null when the result shows none (401, busy, success). */
fun SubscribeResult.toMessage(): SubscribeMessage? = when (this) {
    is SubscribeResult.Subscribed, SubscribeResult.TokenRejected, SubscribeResult.Busy -> null
    SubscribeResult.AlreadySubscribed -> SubscribeMessage.AlreadySubscribed
    is SubscribeResult.NotReadable ->
        if (detail.isBlank()) SubscribeMessage.NotReadableNoDetail else SubscribeMessage.NotReadable(detail)
    is SubscribeResult.Unreachable -> SubscribeMessage.Failed(ApiError.Unreachable(timeout).message)
    is SubscribeResult.ServerError -> SubscribeMessage.Failed(error.message)
}

/** The address as sent: the field text without leading and trailing whitespace, otherwise unchanged. */
fun addressToSend(fieldText: String): String = fieldText.trim()

data class SubscribeUiState(
    val text: String = "",
    /** From the tap until the outcome is known, including the Sync (or while another visit's request runs). */
    val busy: Boolean = false,
    val message: SubscribeMessage? = null,
    /** Set once when a subscribe succeeded; the screen hands it to Feeds and calls [SubscribeController.consumeOutcome]. */
    val outcome: SubscribeOutcome? = null,
) {
    val canSubscribe: Boolean get() = !busy && addressToSend(text).isNotEmpty()
}

/**
 * The Subscribe view's logic without any Android type. The scope is a parameter (the ViewModel's scope in
 * the app). Leaving the screen cancels this scope, but not the request: [SubscribeRepository] finishes
 * the POST and the Sync on its own, and the Feed arrives in Room.
 */
class SubscribeController(
    private val repo: SubscribeRepository,
    private val scope: CoroutineScope,
) {
    private data class Local(
        val text: String = "",
        val submitting: Boolean = false,
        val message: SubscribeMessage? = null,
        val outcome: SubscribeOutcome? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<SubscribeUiState> = combine(local, repo.inProgress) { l, running ->
        SubscribeUiState(l.text, l.submitting || running, l.message, l.outcome)
    }.stateIn(scope, SharingStarted.Eagerly, SubscribeUiState())

    fun onTextChange(text: String) = local.update { it.copy(text = text, message = null) }

    /** Starts a subscribe unless one is running or the trimmed text is empty. Returns whether it started. */
    fun subscribe(): Boolean {
        var address: String? = null
        local.update { l ->
            address = null
            val trimmed = addressToSend(l.text)
            if (l.submitting || trimmed.isEmpty()) {
                l
            } else {
                address = trimmed
                l.copy(submitting = true, message = null)
            }
        }
        val toSend = address ?: return false
        scope.launch { run(toSend) }
        return true
    }

    private suspend fun run(address: String) {
        val result = try {
            repo.subscribe(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        local.update { l ->
            when (result) {
                null -> l.copy(submitting = false, message = SubscribeMessage.Unexpected)
                is SubscribeResult.Subscribed -> l.copy(
                    submitting = false,
                    outcome = SubscribeOutcome(result.feedId, result.title, result.syncFailure?.message),
                )
                else -> l.copy(submitting = false, message = result.toMessage())
            }
        }
    }

    fun consumeOutcome() = local.update { it.copy(outcome = null) }
}
