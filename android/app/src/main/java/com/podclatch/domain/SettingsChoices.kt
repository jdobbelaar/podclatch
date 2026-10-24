package com.podclatch.domain

import com.podclatch.Config
import kotlin.math.abs

/** The pure rules behind the Settings screen's choices (US-17): the option lists and how stored values map onto them. */
object SettingsChoices {
    /** Skip intervals offered for both skip buttons, in seconds (spec section 4). */
    val SKIP_SECONDS: List<Int> = listOf(10, 15, 30, 45, 60)

    /** Sync intervals offered, in hours. */
    val SYNC_HOURS: List<Int> = listOf(1, 3, 6, 12)

    /** Download-ahead counts offered, per Feed. */
    val DOWNLOAD_AHEAD: List<Int> = (Config.MIN_DOWNLOAD_AHEAD_PER_FEED..Config.MAX_DOWNLOAD_AHEAD_PER_FEED).toList()

    /** Assigned speeds offered: 0.5 to 3.0 in 0.1 steps, with no float noise. */
    val SPEEDS: List<Float> = (5..30).map { it / 10f }

    /**
     * [options] with [current] added in its sorted place when it is not one of them. A stored value outside the
     * list (written by the debug receiver, or by an older build) is shown as its own entry and is never changed
     * until the user picks another.
     */
    fun <T : Comparable<T>> withCurrent(options: List<T>, current: T): List<T> =
        if (current in options) options else (options + current).sorted()

    /** The same for speeds, where "in the list" means equal within float noise. */
    fun speedsWithCurrent(current: Float): List<Float> =
        if (SPEEDS.any { sameSpeed(it, current) }) SPEEDS else (SPEEDS + current).sorted()

    /** True when [a] and [b] are the same speed, ignoring float noise. */
    fun sameSpeed(a: Float, b: Float): Boolean = abs(a - b) < 1e-4f

    /** The download-ahead count as the engine uses it: within 1 to 10. */
    fun clampDownloadAhead(count: Int): Int =
        count.coerceIn(Config.MIN_DOWNLOAD_AHEAD_PER_FEED, Config.MAX_DOWNLOAD_AHEAD_PER_FEED)

    /**
     * The speed to store from Settings: limited to 0.5 to 3.0 and rounded to 0.1, NaN becoming the default.
     * Reading never passes through here, so an older stored 1.25 stays exactly as it is.
     */
    fun assignedSpeedToStore(speed: Float): Float = normalizeSpeed(speed)

    /** Whether the "Notifications are blocked" note shows: the setting is on, the OS asks for a permission, and it is missing. */
    fun notificationsBlocked(sdkInt: Int, notifyEnabled: Boolean, permissionGranted: Boolean): Boolean =
        notifyEnabled && needsNotificationPermission(sdkInt) && !permissionGranted

    /** Whether turning the switch on must ask for the permission now. */
    fun shouldRequestOnEnable(sdkInt: Int, enabling: Boolean, permissionGranted: Boolean): Boolean =
        enabling && needsNotificationPermission(sdkInt) && !permissionGranted

    /** `POST_NOTIFICATIONS` is a runtime permission from Android 13 (API 33). */
    fun needsNotificationPermission(sdkInt: Int): Boolean = sdkInt >= 33
}
