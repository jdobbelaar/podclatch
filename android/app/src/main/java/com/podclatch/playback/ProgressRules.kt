package com.podclatch.playback

import com.podclatch.Config
import com.podclatch.data.db.EpisodeStatus

/**
 * True when stopping, pausing, or switching at [positionMs] counts as finished: at or beyond
 * [Config.PLAYED_THRESHOLD_PERCENT] of [durationMs], in integer arithmetic. An unknown duration (null or
 * not above 0) never counts; only reaching the end does.
 */
fun playedAtStop(positionMs: Long, durationMs: Long?): Boolean =
    durationMs != null && durationMs > 0 && positionMs * 100 >= durationMs * Config.PLAYED_THRESHOLD_PERCENT

/** The player's duration when known (above 0), else the feed's `durationSec`, else null. */
fun effectiveDurationMs(playerDurationMs: Long?, durationSec: Int?): Long? =
    playerDurationMs?.takeIf { it > 0 } ?: durationSec?.takeIf { it > 0 }?.let { it * 1000L }

/** Player events are recorded only while an item is loaded and the player is not in an error: an error marks nothing. */
fun recordsPlayerEvents(hasItem: Boolean, hasError: Boolean): Boolean = hasItem && !hasError

/**
 * Where playback of an Episode begins: a `NEW` Episode at its saved position, a `PLAYED` one at 0, and a
 * saved position that is not below the known duration at 0.
 */
fun startPositionMs(status: EpisodeStatus, positionSec: Int, durationMs: Long?): Long {
    if (status != EpisodeStatus.NEW || positionSec <= 0) return 0L
    val positionMs = positionSec * 1000L
    return if (durationMs != null && durationMs > 0 && positionMs >= durationMs) 0L else positionMs
}
