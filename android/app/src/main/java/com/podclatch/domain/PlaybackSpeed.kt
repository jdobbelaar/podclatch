package com.podclatch.domain

import com.podclatch.Config
import com.podclatch.data.repo.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Spec 5.3: ASSIGNED plays at `assignedSpeed`, NORMAL plays at 1.0. */
enum class SpeedMode { ASSIGNED, NORMAL }

data class PlaybackSpeedState(val assigned: Float, val mode: SpeedMode) {
    /** The speed the player runs at: 1.0 when NORMAL, else the assigned speed. */
    val currentSpeed: Float get() = if (mode == SpeedMode.NORMAL) 1.0f else assigned

    /** The speed one tap on the Speed button switches to. */
    val otherSpeed: Float get() = if (mode == SpeedMode.NORMAL) assigned else 1.0f
}

/**
 * The one place the speed rules live (spec 5.3), shared by the phone Player UI and, later, the media
 * session. It has no Android, UI, or Media3 types: it only changes the setting, and the playback service
 * applies the result to the player.
 */
@Singleton
class PlaybackSpeedUseCase @Inject constructor(private val settings: SettingsRepository) {
    val state: Flow<PlaybackSpeedState> = settings.settings
        .map { PlaybackSpeedState(it.assignedSpeed, it.speedMode) }
        .distinctUntilChanged()

    /** Flips ASSIGNED and NORMAL in one DataStore edit; nothing else changes. */
    suspend fun toggle() = settings.toggleSpeedMode()

    /** Assigns [speed] (rounded to 0.1, limited to 0.5 to 3.0) and switches to ASSIGNED in one edit. */
    suspend fun assign(speed: Float) = settings.assignSpeed(normalizeSpeed(speed))

    /** Switches to ASSIGNED and keeps the stored assigned speed exactly as it is (for example an older 1.25). */
    suspend fun keepAssigned() = settings.assignSpeed(null)
}

/** Rounds to the nearest 0.1 and limits to the allowed range. */
fun normalizeSpeed(speed: Float): Float {
    if (speed.isNaN()) return Config.DEFAULT_ASSIGNED_SPEED
    val clamped = speed.coerceIn(Config.SPEED_MIN, Config.SPEED_MAX)
    return (clamped * 10).roundToInt() / 10f
}

/** "1.25x", "1.0x", "1.3x": at most two decimals, at least one, never float noise, never locale dependent. */
fun formatSpeed(speed: Float): String {
    val text = String.format(Locale.ROOT, "%.2f", speed.toDouble())
    return (if (text.endsWith("0")) text.dropLast(1) else text) + "x"
}

/**
 * Moves [speed] to the next multiple of 0.1 in [direction] (positive is up, negative is down), within
 * 0.5 to 3.0. A value that is already a multiple of 0.1 moves by one step.
 */
fun stepSpeed(speed: Float, direction: Int): Float {
    val tenths = speed * 10.0
    val nearest = tenths.roundToInt()
    val onTick = abs(tenths - nearest) < 1e-3
    val target = if (direction > 0) {
        if (onTick) nearest + 1 else ceil(tenths).toInt()
    } else {
        if (onTick) nearest - 1 else floor(tenths).toInt()
    }
    return (target / 10f).coerceIn(Config.SPEED_MIN, Config.SPEED_MAX)
}
