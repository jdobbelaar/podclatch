package com.podclatch

import com.podclatch.playback.NotificationPermissionPolicy
import com.podclatch.playback.shouldRequestNotificationPermission
import com.podclatch.playback.shouldStopOnTaskRemoved
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRulesTest {
    @Test
    fun taskRemovalStopsTheServiceOnlyWhenNothingIsPlaying() {
        assertTrue(shouldStopOnTaskRemoved(isPlaying = false))
        assertFalse(shouldStopOnTaskRemoved(isPlaying = true))
    }

    @Test
    fun theNotificationPermissionIsAskedOnlyFromAndroid13WhenNotGrantedAndNotYetAsked() {
        assertTrue(shouldRequestNotificationPermission(sdkInt = 33, granted = false, alreadyAsked = false))
        assertTrue(shouldRequestNotificationPermission(sdkInt = 37, granted = false, alreadyAsked = false))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 32, granted = false, alreadyAsked = false))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 26, granted = false, alreadyAsked = false))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 37, granted = true, alreadyAsked = false))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 37, granted = false, alreadyAsked = true))
    }

    @Test
    fun thePolicyAsksAtMostOncePerProcess() {
        val policy = NotificationPermissionPolicy()
        assertTrue(policy.shouldAsk(sdkInt = 37, granted = false))
        assertFalse(policy.shouldAsk(sdkInt = 37, granted = false))
        assertFalse(policy.shouldAsk(sdkInt = 37, granted = false))
    }

    @Test
    fun thePolicyDoesNotSpendItsOneAskWhenAskingIsNotNeeded() {
        val policy = NotificationPermissionPolicy()
        assertFalse(policy.shouldAsk(sdkInt = 37, granted = true))
        assertTrue(policy.shouldAsk(sdkInt = 37, granted = false))
    }

    @Test
    fun thePolicyNeverAsksBeforeAndroid13() {
        val policy = NotificationPermissionPolicy()
        assertFalse(policy.shouldAsk(sdkInt = 30, granted = false))
    }
}
