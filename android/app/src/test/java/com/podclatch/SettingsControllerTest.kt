package com.podclatch

import com.podclatch.data.repo.Settings
import com.podclatch.domain.SettingsChoices
import com.podclatch.domain.SpeedMode
import com.podclatch.ui.settings.SettingsController
import com.podclatch.ui.settings.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SettingsControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = FakeSettings()
    private val controller = SettingsController(scope, settings)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun await(what: String, condition: (SettingsUiState) -> Boolean): SettingsUiState =
        awaitState(controller.uiState, what) { it != null && condition(it) }!!

    @Test
    fun theStateStartsAtTheSpecDefaults() {
        val s = await("the defaults") { true }
        assertEquals(15, s.skipBackSec)
        assertEquals(30, s.skipForwardSec)
        assertEquals(1.25f, s.assignedSpeed)
        assertEquals(true, s.wifiOnly)
        assertEquals(3, s.downloadAhead)
        assertEquals(true, s.autoDelete)
        assertEquals(1, s.syncIntervalHours)
        assertEquals(false, s.notifyNewEpisodes)
    }

    @Test
    fun skipBackAndForwardAreSavedSeparately() {
        controller.setSkipBack(10)
        await("back 10, forward unchanged") { it.skipBackSec == 10 && it.skipForwardSec == 30 }
        controller.setSkipForward(45)
        await("back 10, forward 45") { it.skipBackSec == 10 && it.skipForwardSec == 45 }
        assertEquals(10, settings.flow.value.skipBackSec)
        assertEquals(45, settings.flow.value.skipForwardSec)
    }

    @Test
    fun aNonPositiveSkipIsIgnored() {
        controller.setSkipBack(0)
        controller.setSkipForward(-5)
        controller.setSyncInterval(0)
        controller.setWifiOnly(false)
        await("the later write landed") { !it.wifiOnly }
        assertEquals(15, settings.flow.value.skipBackSec)
        assertEquals(30, settings.flow.value.skipForwardSec)
        assertEquals(1, settings.flow.value.syncIntervalHours)
    }

    @Test
    fun theAssignedSpeedChangesWithoutTouchingTheMode() {
        settings.flow.value = settings.flow.value.copy(speedMode = SpeedMode.NORMAL)
        controller.setAssignedSpeed(2.0f)
        await("speed 2.0") { it.assignedSpeed == 2.0f }
        assertEquals(SpeedMode.NORMAL, settings.flow.value.speedMode)
    }

    @Test
    fun theSwitchesAndChoicesAreSaved() {
        controller.setWifiOnly(false)
        controller.setAutoDelete(false)
        controller.setNotifyNewEpisodes(true)
        controller.setSyncInterval(6)
        controller.setDownloadAhead(7)
        val s = await("all five") { !it.wifiOnly && !it.autoDelete && it.notifyNewEpisodes && it.syncIntervalHours == 6 && it.downloadAhead == 7 }
        assertEquals(7, s.downloadAhead)
        assertEquals(7, settings.flow.value.downloadAheadPerFeed)
    }

    @Test
    fun downloadAheadIsClampedWhenSavedAndWhenShown() {
        controller.setDownloadAhead(99)
        await("clamped to 10") { it.downloadAhead == 10 }
        assertEquals(10, settings.flow.value.downloadAheadPerFeed)
        // A stored value outside 1 to 10 (an older build) is shown as the engine uses it, and stays stored.
        settings.flow.value = settings.flow.value.copy(downloadAheadPerFeed = 0)
        await("shown as 1") { it.downloadAhead == 1 }
        assertEquals(0, settings.flow.value.downloadAheadPerFeed)
    }

    @Test
    fun aChangeMadeElsewhereShowsWithoutReopening() {
        settings.flow.value = settings.flow.value.copy(skipBackSec = 7, skipForwardSec = 90)
        val s = await("7 and 90") { it.skipBackSec == 7 && it.skipForwardSec == 90 }
        assertNotNull(s)
        assertEquals(listOf(7, 10, 15, 30, 45, 60), SettingsChoices.withCurrent(SettingsChoices.SKIP_SECONDS, s.skipBackSec))
        assertEquals(listOf(10, 15, 30, 45, 60, 90), SettingsChoices.withCurrent(SettingsChoices.SKIP_SECONDS, s.skipForwardSec))
    }

    @Test
    fun theTokenIsNeverPartOfTheStateOrThePrintedSettings() {
        val s = await("the state") { true }
        assertEquals(false, s.toString().contains(TEST_TOKEN))
        assertEquals(false, Settings(authToken = TEST_TOKEN).toString().contains(TEST_TOKEN))
    }
}
