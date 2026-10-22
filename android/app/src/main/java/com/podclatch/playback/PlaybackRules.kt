package com.podclatch.playback

import javax.inject.Inject
import javax.inject.Singleton

/** Swiping the app out of recents stops the service only when nothing is playing. */
fun shouldStopOnTaskRemoved(isPlaying: Boolean): Boolean = !isPlaying

/** `POST_NOTIFICATIONS` is a runtime permission from Android 13 (API 33). */
private const val NOTIFICATION_PERMISSION_API = 33

/** Ask only on Android 13 or later, only when not granted, and at most once per process. */
fun shouldRequestNotificationPermission(sdkInt: Int, granted: Boolean, alreadyAsked: Boolean): Boolean =
    sdkInt >= NOTIFICATION_PERMISSION_API && !granted && !alreadyAsked

/** Remembers, for this process, that the permission was asked for. */
@Singleton
class NotificationPermissionPolicy @Inject constructor() {
    private var asked = false

    /** True the first time it should ask; every later call is false. Call it from the main thread. */
    fun shouldAsk(sdkInt: Int, granted: Boolean): Boolean {
        val ask = shouldRequestNotificationPermission(sdkInt, granted, asked)
        if (ask) asked = true
        return ask
    }
}
