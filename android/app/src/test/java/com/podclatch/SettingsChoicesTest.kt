package com.podclatch

import com.podclatch.domain.SettingsChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsChoicesTest {
    @Test
    fun theOptionListsAreTheSpecs() {
        assertEquals(listOf(10, 15, 30, 45, 60), SettingsChoices.SKIP_SECONDS)
        assertEquals(listOf(1, 3, 6, 12), SettingsChoices.SYNC_HOURS)
        assertEquals((1..10).toList(), SettingsChoices.DOWNLOAD_AHEAD)
        assertEquals(26, SettingsChoices.SPEEDS.size)
        assertEquals(0.5f, SettingsChoices.SPEEDS.first())
        assertEquals(3.0f, SettingsChoices.SPEEDS.last())
        assertTrue(SettingsChoices.SPEEDS.contains(1.3f))
    }

    @Test
    fun aValueInTheListIsNotDuplicated() {
        assertEquals(SettingsChoices.SKIP_SECONDS, SettingsChoices.withCurrent(SettingsChoices.SKIP_SECONDS, 30))
    }

    @Test
    fun aValueOutsideTheListGetsItsOwnSortedEntry() {
        assertEquals(listOf(7, 10, 15, 30, 45, 60), SettingsChoices.withCurrent(SettingsChoices.SKIP_SECONDS, 7))
        assertEquals(listOf(1, 3, 6, 12, 24), SettingsChoices.withCurrent(SettingsChoices.SYNC_HOURS, 24))
    }

    @Test
    fun aStoredOnePointTwoFiveIsItsOwnEntryBetweenOnePointTwoAndOnePointThree() {
        val speeds = SettingsChoices.speedsWithCurrent(1.25f)
        assertEquals(27, speeds.size)
        assertEquals(listOf(1.2f, 1.25f, 1.3f), speeds.subList(7, 10))
        assertEquals(SettingsChoices.SPEEDS, SettingsChoices.speedsWithCurrent(1.5f))
        assertEquals(SettingsChoices.SPEEDS, SettingsChoices.speedsWithCurrent(0.1f + 0.2f + 1.2f))
    }

    @Test
    fun sameSpeedIgnoresFloatNoise() {
        assertTrue(SettingsChoices.sameSpeed(1.5f, 1.5f + 1e-6f))
        assertFalse(SettingsChoices.sameSpeed(1.25f, 1.3f))
    }

    @Test
    fun downloadAheadIsClampedToOneThroughTen() {
        assertEquals(1, SettingsChoices.clampDownloadAhead(-4))
        assertEquals(1, SettingsChoices.clampDownloadAhead(0))
        assertEquals(5, SettingsChoices.clampDownloadAhead(5))
        assertEquals(10, SettingsChoices.clampDownloadAhead(11))
    }

    @Test
    fun theStoredSpeedIsLimitedAndRoundedToTenths() {
        assertEquals(1.3f, SettingsChoices.assignedSpeedToStore(1.25f))
        assertEquals(0.5f, SettingsChoices.assignedSpeedToStore(0.44f))
        assertEquals(3.0f, SettingsChoices.assignedSpeedToStore(3.04f))
        assertEquals(1.3f, SettingsChoices.assignedSpeedToStore(Float.NaN))
        assertEquals(1.3f, SettingsChoices.assignedSpeedToStore(1.34f))
        assertEquals(0.5f, SettingsChoices.assignedSpeedToStore(-1f))
        assertEquals(3.0f, SettingsChoices.assignedSpeedToStore(Float.POSITIVE_INFINITY))
        assertEquals(1.5f, SettingsChoices.assignedSpeedToStore(1.5f))
    }

    @Test
    fun theBlockedNoteShowsOnlyWhenOnWithoutThePermissionFromAndroidThirteen() {
        assertTrue(SettingsChoices.notificationsBlocked(33, notifyEnabled = true, permissionGranted = false))
        assertFalse(SettingsChoices.notificationsBlocked(33, notifyEnabled = false, permissionGranted = false))
        assertFalse(SettingsChoices.notificationsBlocked(33, notifyEnabled = true, permissionGranted = true))
        assertFalse(SettingsChoices.notificationsBlocked(32, notifyEnabled = true, permissionGranted = false))
    }

    @Test
    fun thePermissionIsRequestedOnlyWhenTurningOnWithoutIt() {
        assertTrue(SettingsChoices.shouldRequestOnEnable(36, enabling = true, permissionGranted = false))
        assertFalse(SettingsChoices.shouldRequestOnEnable(36, enabling = false, permissionGranted = false))
        assertFalse(SettingsChoices.shouldRequestOnEnable(36, enabling = true, permissionGranted = true))
        assertFalse(SettingsChoices.shouldRequestOnEnable(30, enabling = true, permissionGranted = false))
    }
}
