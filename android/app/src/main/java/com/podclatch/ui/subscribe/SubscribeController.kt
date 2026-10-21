package com.podclatch.ui.subscribe

import com.podclatch.data.network.ApiError
import com.podclatch.data.repo.SubscribeRepository
import com.podclatch.data.repo.SubscribeResult
import com.podclatch.domain.FeedUrlProblem
import com.podclatch.domain.FeedUrlResult
import com.podclatch.domain.FeedUrlValidator
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
    /** Why the typed address was not sent; cleared as soon as the text changes. */
    val validationError: FeedUrlProblem? = null,
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
    initialText: String = "",
    /** Told every new field text, so the ViewModel can keep it in its `SavedStateHandle` (and nowhere else). */
    private val onTextChanged: (String) -> Unit = {},
) {
    private data class Local(
        val text: String = "",
        val validationError: FeedUrlProblem? = null,
        val submitting: Boolean = false,
        val message: SubscribeMessage? = null,
        val outcome: SubscribeOutcome? = null,
    )

    private val local = MutableStateFlow(Local(text = initialText))

    val uiState: StateFlow<SubscribeUiState> = combine(local, repo.inProgress) { l, running ->
        SubscribeUiState(l.text, l.validationError, l.submitting || running, l.message, l.outcome)
    }.stateIn(scope, SharingStarted.Eagerly, SubscribeUiState())

    fun onTextChange(text: String) {
        local.update { it.copy(text = text, message = null, validationError = null) }
        onTextChanged(text)
    }

    /** Empties the field, the validation error, and the server message. Does nothing while a subscribe runs. */
    fun clear() {
        var cleared = false
        local.update { l ->
            cleared = !(l.submitting || uiState.value.busy)
            if (cleared) l.copy(text = "", message = null, validationError = null) else l
        }
        if (cleared) onTextChanged("")
    }

    /**
     * Validates the typed address, then starts a subscribe unless one is running or the trimmed text is
     * empty. An unacceptable address sends nothing and sets [SubscribeUiState.validationError] (clearing any
     * earlier server message). Returns whether a request started.
     */
    fun subscribe(): Boolean {
        var address: String? = null
        val running = uiState.value.busy
        local.update { l ->
            address = null
            val trimmed = addressToSend(l.text)
            if (l.submitting || running || trimmed.isEmpty()) {
                l
            } else {
                when (val check = FeedUrlValidator.validate(trimmed)) {
                    is FeedUrlResult.NotAcceptable -> l.copy(validationError = check.problem, message = null)
                    is FeedUrlResult.Acceptable -> {
                        address = check.url
                        l.copy(submitting = true, message = null, validationError = null)
                    }
                }
            }
        }
        val toSend = address ?: return false
        scope.launch { run(toSend) }
        return true
    }

    /**
     * Subscribes to [address] exactly as given (a search result's address, as the server returned it):
     * no trimming and no field validation. Does nothing while a subscribe runs. Returns whether a request
     * started.
     */
    fun subscribeTo(address: String): Boolean {
        var started = false
        val running = uiState.value.busy
        local.update { l ->
            started = false
            if (l.submitting || running || address.isEmpty()) {
                l
            } else {
                started = true
                l.copy(submitting = true, message = null, validationError = null)
            }
        }
        if (!started) return false
        scope.launch { run(address) }
        return true
    }

    /** Drops the server message under the field or the preview (the preview opening or closing). */
    fun clearMessage() = local.update { it.copy(message = null) }

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
