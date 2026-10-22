package com.podclatch.ui.player

import com.podclatch.playback.PlaybackUiState

/** The mini player is visible exactly while an Episode is loaded. */
fun miniPlayerVisible(state: PlaybackUiState): Boolean = state.episodeId != null

/** "m:ss", or "h:mm:ss" from one hour; negative values read as zero. */
fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val ss = if (s < 10) "0$s" else "$s"
    return if (h > 0) "$h:${if (m < 10) "0$m" else "$m"}:$ss" else "$m:$ss"
}

/** The remaining time as "-m:ss" (or "-h:mm:ss"); "--:--" when the duration is unknown. */
fun formatRemaining(positionMs: Long, durationMs: Long?): String =
    if (durationMs == null || durationMs <= 0) "--:--" else "-" + formatClock((durationMs - positionMs).coerceAtLeast(0))

/** The seek bar position as 0..1; 0 when the duration is unknown. */
fun sliderFraction(positionMs: Long, durationMs: Long?): Float =
    if (durationMs == null || durationMs <= 0) 0f else (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)

/** The position a seek bar fraction stands for. */
fun fractionToMs(fraction: Float, durationMs: Long?): Long =
    if (durationMs == null || durationMs <= 0) 0L else Math.round(fraction.coerceIn(0f, 1f).toDouble() * durationMs)

/**
 * The seek bar while a finger is on it: the dragged fraction replaces the polled position so the polling
 * does not fight the thumb, and one seek happens on release.
 */
data class SeekDrag(val fraction: Float? = null) {
    val dragging: Boolean get() = fraction != null

    fun onChange(newFraction: Float) = SeekDrag(newFraction.coerceIn(0f, 1f))

    fun displayFraction(positionMs: Long, durationMs: Long?): Float = fraction ?: sliderFraction(positionMs, durationMs)

    fun displayPositionMs(positionMs: Long, durationMs: Long?): Long = fraction?.let { fractionToMs(it, durationMs) } ?: positionMs

    /** The position to seek to on release; null when nothing was dragged (so no seek is sent). */
    fun releaseTarget(durationMs: Long?): Long? = fraction?.let { fractionToMs(it, durationMs) }
}
