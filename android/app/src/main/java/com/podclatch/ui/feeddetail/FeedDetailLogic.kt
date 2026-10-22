package com.podclatch.ui.feeddetail

import com.podclatch.Config
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.FeedEpisode
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.network.RefreshResultDto
import com.podclatch.domain.SyncResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * The pure rules behind Feed Detail: the sort, the description text, the row texts, the row style, and
 * the banner messages. Plain functions, so JVM tests cover them without a device.
 */

/**
 * Newest first is `publishedAt` descending then `id` descending; oldest first is the exact reverse
 * (`publishedAt` ascending then `id` ascending). Which state an Episode is in never changes its place.
 */
fun sortEpisodes(episodes: List<FeedEpisode>, order: ListenOrder): List<FeedEpisode> {
    val newestFirst = compareByDescending<FeedEpisode> { it.publishedAt }.thenByDescending { it.id }
    return when (order) {
        ListenOrder.NEWEST_FIRST -> episodes.sortedWith(newestFirst)
        ListenOrder.OLDEST_FIRST -> episodes.sortedWith(newestFirst.reversed())
    }
}

private val BREAK_TAG = Regex("""<\s*br\s*/?\s*>|<\s*/\s*p\s*>""", RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex("<[^>]*>")
private val ENTITY = Regex("&(amp|lt|gt|quot|#39|nbsp);")
private val BLANK_LINES = Regex("\n{3,}")

/**
 * A feed description as plain text: `<br>` and `</p>` become line breaks, every other tag is removed, and
 * the common entities (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&#39;`, `&nbsp;`) are decoded. Tags go first
 * and entities last in one pass, so `&lt;b&gt;` stays the literal text `<b>` and `&amp;lt;` becomes `&lt;`.
 */
fun descriptionToPlainText(html: String): String {
    val withBreaks = BREAK_TAG.replace(html, "\n")
    val withoutTags = ANY_TAG.replace(withBreaks, "")
    val decoded = ENTITY.replace(withoutTags) { m ->
        when (m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "#39" -> "'"
            else -> " "
        }
    }
    return BLANK_LINES.replace(decoded.lines().joinToString("\n") { it.trimEnd() }, "\n\n").trim()
}

/** The publish date in the locale's medium format, in [zone]. */
fun formatEpisodeDate(publishedAtMs: Long, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)
        .format(Instant.ofEpochMilli(publishedAtMs))

/** "<1 min", "<n> min" (floor), or "<h> h <mm> min"; null when the duration is unknown. */
fun formatDuration(durationSec: Int?): String? {
    if (durationSec == null) return null
    val totalMinutes = maxOf(durationSec, 0) / 60
    return when {
        totalMinutes < 1 -> "<1 min"
        totalMinutes < 60 -> "$totalMinutes min"
        else -> "${totalMinutes / 60} h ${(totalMinutes % 60).toString().padStart(2, '0')} min"
    }
}

/** How a New Episode with progress shows it. */
sealed interface EpisodeProgress {
    /** A determinate bar and "<percent> percent played"; [percent] is the floor, 0 to 100. */
    data class Known(val percent: Int) : EpisodeProgress

    /** The duration is unknown: the label "Started" and no bar. */
    data object Started : EpisodeProgress
}

/** Progress is shown for a New Episode with `positionSec > 0` only; a Played one shows none. */
fun episodeProgress(status: EpisodeStatus, positionSec: Int, durationSec: Int?): EpisodeProgress? {
    if (status != EpisodeStatus.NEW || positionSec <= 0) return null
    if (durationSec == null || durationSec <= 0) return EpisodeProgress.Started
    // Long arithmetic: positionSec * 100 would overflow an Int for huge positions.
    return EpisodeProgress.Known((positionSec.toLong() * 100 / durationSec).coerceIn(0, 100).toInt())
}

enum class TitleColor { NORMAL, MUTED }
enum class TitleWeight { SEMIBOLD, NORMAL }
enum class RowBackground { NORMAL, PLAYED }

/** What differs between a New and a Played row besides the label: title colour, title weight, background. */
data class EpisodeRowStyle(val titleColor: TitleColor, val titleWeight: TitleWeight, val background: RowBackground)

fun episodeRowStyle(status: EpisodeStatus): EpisodeRowStyle = when (status) {
    EpisodeStatus.NEW -> EpisodeRowStyle(TitleColor.NORMAL, TitleWeight.SEMIBOLD, RowBackground.NORMAL)
    EpisodeStatus.PLAYED -> EpisodeRowStyle(TitleColor.MUTED, TitleWeight.NORMAL, RowBackground.PLAYED)
}

/**
 * The one accessibility description of a row: "<title>, <date>, <duration>, <New or Played>", plus
 * ", <p> percent played" ([percentPlayedLabel]) for a known progress. The duration is left out when unknown.
 */
fun episodeDescription(
    title: String,
    date: String,
    duration: String?,
    statusLabel: String,
    percentPlayedLabel: String?,
): String = buildList {
    add(title)
    add(date)
    if (duration != null) add(duration)
    add(statusLabel)
    if (percentPlayedLabel != null) add(percentPlayedLabel)
}.joinToString(", ")

/** What the banner says. The screen turns each case into text from `strings.xml`. */
sealed interface FeedDetailMessage {
    data class ListenOrderFailed(val reason: String) : FeedDetailMessage

    /** The Feed's own refresh failed (`ok: false`); [error] is the server's text, already cut. */
    data class FeedRefreshFailed(val error: String) : FeedDetailMessage

    /** The refresh request itself failed (unreachable, timeout, 404, 422, and so on). */
    data class RefreshRequestFailed(val reason: String) : FeedDetailMessage
    data class RefreshSyncFailed(val reason: String) : FeedDetailMessage
    data class UnsubscribeFailed(val title: String, val reason: String) : FeedDetailMessage
    data class UnsubscribedSyncFailed(val title: String, val reason: String) : FeedDetailMessage
    data object PlayFailed : FeedDetailMessage
    data object Unexpected : FeedDetailMessage
}

/**
 * The message after a Feed's refresh request succeeded; null when nothing failed. The server's error is
 * cut to [Config.MAX_REFRESH_ERROR_LENGTH] characters. A failed follow-up Sync comes second: the list
 * will update at the next Sync.
 */
fun buildFeedRefreshMessage(feedId: Long, results: List<RefreshResultDto>, sync: SyncResult): FeedDetailMessage? {
    val result = results.firstOrNull { it.feedId == feedId } ?: results.firstOrNull()
    if (result != null && !result.ok) {
        return FeedDetailMessage.FeedRefreshFailed((result.error ?: "").take(Config.MAX_REFRESH_ERROR_LENGTH))
    }
    return (sync as? SyncResult.Failed)?.let { FeedDetailMessage.RefreshSyncFailed(it.reason.message) }
}
