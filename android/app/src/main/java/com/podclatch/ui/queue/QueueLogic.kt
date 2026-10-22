package com.podclatch.ui.queue

import com.podclatch.ui.feeddetail.formatDuration

/*
 * The pure rules behind the Queue rows: the time text, the download percent, the playback bar's fill, its
 * colours, and the one description of a row. Plain functions, so JVM tests cover them without a device.
 */

/** The time on a row: [text] is a duration ("12 min"), and [left] says it is the time still to go. */
data class QueueTime(val text: String, val left: Boolean)

/**
 * The duration when there is no progress; the time left (`durationSec - positionSec`, never below 0) when
 * `positionSec > 0` and the duration is known; null (nothing shown) when the duration is unknown.
 */
fun queueTime(durationSec: Int?, positionSec: Int): QueueTime? {
    if (durationSec == null) return null
    if (positionSec <= 0) return formatDuration(durationSec)?.let { QueueTime(it, left = false) }
    return formatDuration(maxOf(durationSec - positionSec, 0))?.let { QueueTime(it, left = true) }
}

/** The whole percent downloaded (floor, 0 to 100); null when the total is not known, so the bar is indeterminate. */
fun downloadPercent(bytesReceived: Long, totalBytes: Long?): Int? {
    if (totalBytes == null || totalBytes <= 0) return null
    return (bytesReceived.coerceAtLeast(0).toDouble() * 100 / totalBytes).toInt().coerceIn(0, 100)
}

/** How far playback got, for the vertical bar and its description. */
sealed interface PlayedBar {
    /** From 0 (nothing played) to 1 (all played). */
    val fraction: Float

    /** The duration is known: [percent] is the floor, 0 to 100. */
    data class Known(val percent: Int, override val fraction: Float) : PlayedBar

    /** There is progress but no duration: the bar stays unfilled and says "Started". */
    data object Started : PlayedBar {
        override val fraction = 0f
    }

    /** No progress and no duration. */
    data object NotStarted : PlayedBar {
        override val fraction = 0f
    }
}

fun playedBar(positionSec: Int, durationSec: Int?): PlayedBar {
    if (durationSec != null && durationSec > 0) {
        val percent = (positionSec.coerceAtLeast(0).toLong() * 100 / durationSec).coerceIn(0, 100).toInt()
        val fraction = (positionSec.coerceAtLeast(0).toDouble() / durationSec).coerceIn(0.0, 1.0).toFloat()
        return PlayedBar.Known(percent, fraction)
    }
    return if (positionSec > 0) PlayedBar.Started else PlayedBar.NotStarted
}

/** The two colours of the playback bar, as ARGB: the unplayed part and the part that fills from the top. */
data class PlayedBarColors(val unplayed: Long, val played: Long)

/**
 * Chosen apart in luminance (contrast above 3 : 1 in both themes, checked by a unit test), so the two parts
 * differ by lightness and not by hue alone.
 */
val PlayedBarLight = PlayedBarColors(unplayed = 0xFFC9C5D0, played = 0xFF3B2D7A)
val PlayedBarDark = PlayedBarColors(unplayed = 0xFF49454F, played = 0xFFD0BCFF)

/** The WCAG contrast ratio between two ARGB colours (1 to 21). */
fun contrastRatio(a: Long, b: Long): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private fun relativeLuminance(argb: Long): Double {
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

/**
 * The one description of a row: "<title>, <Feed name>, <time text>, <played text>", plus ", now playing" on
 * the playing row. The time text is left out when the duration is unknown.
 */
fun queueRowDescription(
    title: String,
    feedName: String,
    timeText: String?,
    playedText: String,
    nowPlayingText: String?,
): String = listOfNotNull(title, feedName, timeText, playedText, nowPlayingText).joinToString(", ")
