package com.podclatch.data.repo

import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.PodClatchApi
import com.podclatch.domain.FailureReason
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncResult
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.TokenRejection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The outcome of one subscribe attempt. No case holds the submitted address or the token: a server
 * detail that echoes the address has it replaced before it is stored here.
 */
sealed interface SubscribeResult {
    /** 201. [syncFailure] is null when the follow-up Sync worked, otherwise why it did not. */
    data class Subscribed(val feedId: Long, val title: String, val syncFailure: FailureReason?) : SubscribeResult

    /** 409. */
    data object AlreadySubscribed : SubscribeResult

    /** 422. [detail] is the server's text (address redacted), possibly blank. */
    data class NotReadable(val detail: String) : SubscribeResult

    data class Unreachable(val timeout: Boolean) : SubscribeResult

    /** 401. The token has been cleared, so the connection screen takes over. */
    data object TokenRejected : SubscribeResult

    /** Any other `Http` answer, or a body that is not understood. */
    data class ServerError(val error: ApiError) : SubscribeResult

    /** Another subscribe is still running; nothing was sent. */
    data object Busy : SubscribeResult
}

/**
 * Subscribes to a Feed by address (`POST /v1/feeds`) and then runs a Sync, which is how the Feed reaches
 * Room: this class never writes to the cache. One subscribe runs at a time, and a running one is not
 * cancelled when its caller goes away, so a left screen still ends with the Feed in the cache.
 */
@Singleton
class SubscribeRepository @Inject constructor(
    private val api: PodClatchApi,
    private val engine: SyncEngine,
    private val tokenRejection: TokenRejection,
) {
    private val _inProgress = MutableStateFlow(false)

    /** True from the moment a subscribe is accepted until its Sync has finished. */
    val inProgress: StateFlow<Boolean> = _inProgress.asStateFlow()

    suspend fun subscribe(feedUrl: String): SubscribeResult {
        if (!_inProgress.compareAndSet(expect = false, update = true)) return SubscribeResult.Busy
        try {
            return withContext(NonCancellable) { run(feedUrl) }
        } finally {
            _inProgress.value = false
        }
    }

    private suspend fun run(feedUrl: String): SubscribeResult {
        val feed = try {
            api.subscribeFeed(feedUrl)
        } catch (e: ApiException) {
            return failure(e.error, feedUrl)
        }
        val sync = try {
            engine.sync(SyncTrigger.MANUAL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Failed(FailureReason.Local)
        }
        val syncFailure = when (sync) {
            SyncResult.Success -> null
            is SyncResult.Failed -> sync.reason
            // Only "not configured" can happen for a manual run (the token went away meanwhile).
            is SyncResult.Skipped -> FailureReason.Local
        }
        return SubscribeResult.Subscribed(feed.id, feed.title, syncFailure)
    }

    private suspend fun failure(error: ApiError, feedUrl: String): SubscribeResult = when {
        error is ApiError.Unauthorized -> {
            try {
                tokenRejection.onTokenRejected()
            } catch (_: Exception) {
                // Still reported as rejected.
            }
            SubscribeResult.TokenRejected
        }
        error is ApiError.Http && error.code == 409 -> SubscribeResult.AlreadySubscribed
        error is ApiError.Http && error.code == 422 ->
            SubscribeResult.NotReadable(redactAddress(error.detail.orEmpty(), feedUrl))
        error is ApiError.Unreachable -> SubscribeResult.Unreachable(error.timeout)
        error is ApiError.Http -> SubscribeResult.ServerError(error.copy(detail = error.detail?.let { redactAddress(it, feedUrl) }))
        else -> SubscribeResult.ServerError(error)
    }
}

/** The text that stands in for a feed address in anything shown to the user. */
const val ADDRESS_PLACEHOLDER = "(the feed address)"

/**
 * Replaces the submitted [feedUrl] in [text] (any letter case), and its query string on its own, since a
 * server may echo only a normalised or shortened form and the query is where secrets live.
 */
fun redactAddress(text: String, feedUrl: String): String {
    if (feedUrl.isEmpty()) return text
    var result = text.replace(feedUrl, ADDRESS_PLACEHOLDER, ignoreCase = true)
    val query = feedUrl.substringAfter('?', "").substringBefore('#')
    if (query.length >= 4) result = result.replace(query, ADDRESS_PLACEHOLDER, ignoreCase = true)
    return result
}
