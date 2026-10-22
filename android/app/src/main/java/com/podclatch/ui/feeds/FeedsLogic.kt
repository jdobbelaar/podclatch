package com.podclatch.ui.feeds

import com.podclatch.Config
import com.podclatch.data.network.RefreshResultDto
import com.podclatch.domain.SyncResult

/*
 * The pure rules behind the Feeds tab: list moves, the badge, the messages, and the
 * Feed Detail pop rule. They are plain functions so JVM tests cover them without a device.
 */

/**
 * Moves the element at [from] to position [to]. Out-of-range indices return [list] unchanged, as does
 * a move to the same position.
 */
fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> {
    if (from !in list.indices || to !in list.indices || from == to) return list
    val result = list.toMutableList()
    result.add(to, result.removeAt(from))
    return result
}

/** What to do when a drag ends. */
sealed interface DropDecision {
    /** The working order equals the server order (or the gesture was cancelled): send nothing. */
    data object NoChange : DropDecision

    /** The set of Feed ids changed while dragging: send nothing and show the latest list. */
    data object ListChanged : DropDecision

    /** Send exactly one reorder with [ids], every Feed once. */
    data class Send(val ids: List<Long>) : DropDecision
}

/** Compares the [working] order with the latest cache order, [serverIds]. */
fun resolveDrop(working: List<Long>, serverIds: List<Long>): DropDecision = when {
    working.size != serverIds.size || working.toSet() != serverIds.toSet() -> DropDecision.ListChanged
    working == serverIds -> DropDecision.NoChange
    else -> DropDecision.Send(working)
}

/** The badge text: the number, or `99+` above 99. The content description keeps the exact number. */
fun badgeText(count: Int): String = if (count > 99) "99+" else count.toString()

/**
 * Feed Detail pops itself only after the cache has been read once ([feedRead]) and the Feed is not in it
 * ([feedPresent] is false), so nothing pops before the first load.
 */
fun shouldPopFeedDetail(feedRead: Boolean, feedPresent: Boolean): Boolean = feedRead && !feedPresent

/** A Feed's refresh failure: [title] is null when the Feed is not in the cache. */
data class RefreshFeedError(val title: String?, val error: String)

/** What the banner says. The composable turns each case into text from `strings.xml`. */
sealed interface FeedsMessage {
    data class MoveFailed(val reason: String) : FeedsMessage
    data object ListChanged : FeedsMessage
    data class UnsubscribeFailed(val title: String, val reason: String) : FeedsMessage
    data class UnsubscribedSyncFailed(val title: String, val reason: String) : FeedsMessage
    data class RefreshPartial(
        val failed: Int,
        val total: Int,
        val lines: List<RefreshFeedError>,
        val more: Int,
    ) : FeedsMessage

    data class SubscribedSyncFailed(val reason: String) : FeedsMessage
    data class RefreshFailed(val reason: String) : FeedsMessage
    data class RefreshSyncFailed(val reason: String) : FeedsMessage
    data object Unexpected : FeedsMessage
}

/** The banner after a subscribe whose follow-up Sync failed; null when the Sync worked. */
fun subscribeOutcomeMessage(syncFailure: String?): FeedsMessage? =
    syncFailure?.let { FeedsMessage.SubscribedSyncFailed(it) }

/**
 * The message after a Refresh whose request succeeded; null when nothing failed. Failed Feeds are listed
 * by title (up to [Config.MAX_REFRESH_ERROR_LINES]) with the server's error cut to
 * [Config.MAX_REFRESH_ERROR_LENGTH] characters.
 */
fun buildRefreshMessage(
    results: List<RefreshResultDto>,
    sync: SyncResult,
    titlesById: Map<Long, String>,
): FeedsMessage? {
    val failures = results.filter { !it.ok }
    if (failures.isNotEmpty()) {
        val lines = failures.take(Config.MAX_REFRESH_ERROR_LINES).map {
            RefreshFeedError(titlesById[it.feedId], (it.error ?: "").take(Config.MAX_REFRESH_ERROR_LENGTH))
        }
        return FeedsMessage.RefreshPartial(
            failed = failures.size,
            total = results.size,
            lines = lines,
            more = failures.size - lines.size,
        )
    }
    return (sync as? SyncResult.Failed)?.let { FeedsMessage.RefreshSyncFailed(it.reason.message) }
}
