package com.podclatch

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.podclatch.data.repo.DataStoreSettingsRepository
import com.podclatch.data.repo.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.podclatch.domain.PlaybackSpeedState
import com.podclatch.domain.SettingsChoices
import com.podclatch.domain.SpeedMode
import com.podclatch.domain.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The real DataStore on a temporary file. */
class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun repo(file: File = File(tmp.root, "settings.preferences_pb")) =
        DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })

    @Test
    fun skipIntervalsDefaultToFifteenBackAndThirtyForward() = runBlocking {
        val settings = repo().current()
        assertEquals(15, settings.skipBackSec)
        assertEquals(30, settings.skipForwardSec)
        assertEquals(15, Settings().skipBackSec)
        assertEquals(30, Settings().skipForwardSec)
    }

    @Test
    fun theSkipPairIsStoredAndReplacedTogether() = runBlocking {
        val repo = repo()
        repo.setSkipIntervals(10, 45)
        assertEquals(10, repo.current().skipBackSec)
        assertEquals(45, repo.current().skipForwardSec)
        repo.setSkipIntervals(60, 10)
        assertEquals(60, repo.current().skipBackSec)
        assertEquals(10, repo.current().skipForwardSec)
    }

    @Test
    fun theSkipPairIsWrittenTogetherAndLeavesTheConnectionAlone() = runBlocking {
        val repo = repo()
        repo.saveConnection("https://pod.example.com", "secret-token-value")
        repo.setSkipIntervals(10, 45)
        val settings = repo.current()
        assertEquals(10, settings.skipBackSec)
        assertEquals(45, settings.skipForwardSec)
        assertEquals("https://pod.example.com", settings.serverUrl)
        assertEquals("secret-token-value", settings.authToken)
    }

    @Test
    fun nonPositiveSkipIntervalsAreRejectedAndNothingIsWritten() = runBlocking {
        val repo = repo()
        for ((back, forward) in listOf(0 to 30, 15 to 0, -1 to 30, 15 to -5)) {
            try {
                repo.setSkipIntervals(back, forward)
                fail("expected a rejection of $back/$forward")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(15, repo.current().skipBackSec)
        assertEquals(30, repo.current().skipForwardSec)
    }

    @Test
    fun downloadWifiOnlyDefaultsToTrueAndIsStored() = runBlocking {
        val file = File(tmp.root, "wifi.preferences_pb")
        val repo = repo(file)
        assertTrue(Settings().downloadWifiOnly)
        assertTrue(repo.current().downloadWifiOnly)
        repo.setDownloadWifiOnly(false)
        assertFalse(repo.current().downloadWifiOnly)
        repo.setDownloadWifiOnly(true)
        assertTrue(repo.current().downloadWifiOnly)
    }

    @Test
    fun toStringStillRedactsTheTokenAndShowsTheSkipIntervals() {
        val text = Settings(serverUrl = "https://pod.example.com", authToken = "secret-token-value", skipBackSec = 10, skipForwardSec = 45).toString()
        assertFalse(text.contains("secret-token-value"))
        assertTrue(text.contains("authToken=***"))
        assertTrue(text.contains("skipBackSec=10"))
        assertTrue(text.contains("skipForwardSec=45"))
    }

    @Test
    fun downloadAheadDefaultsToThreeAndNotifyToOffAndBothAreStored() = runBlocking {
        val repo = repo(File(tmp.root, "ahead.preferences_pb"))
        assertEquals(3, Settings().downloadAheadPerFeed)
        assertFalse(Settings().notifyNewEpisodes)
        assertEquals(3, repo.current().downloadAheadPerFeed)
        assertFalse(repo.current().notifyNewEpisodes)
        repo.setDownloadAheadPerFeed(7)
        repo.setNotifyNewEpisodes(true)
        assertEquals(7, repo.current().downloadAheadPerFeed)
        assertTrue(repo.current().notifyNewEpisodes)
        repo.setNotifyNewEpisodes(false)
        assertFalse(repo.current().notifyNewEpisodes)
    }

    @Test
    fun autoDeleteDefaultsToOnAndIsStored() = runBlocking {
        val repo = repo(File(tmp.root, "auto.preferences_pb"))
        assertTrue(Settings().autoDeletePlayedDownloads)
        assertTrue(repo.current().autoDeletePlayedDownloads)
        repo.setAutoDeletePlayedDownloads(false)
        assertFalse(repo.current().autoDeletePlayedDownloads)
        repo.setAutoDeletePlayedDownloads(true)
        assertTrue(repo.current().autoDeletePlayedDownloads)
        val text = Settings(authToken = "secret-token-value", autoDeletePlayedDownloads = false).toString()
        assertFalse(text.contains("secret-token-value"))
        assertTrue(text.contains("autoDeletePlayedDownloads=false"))
    }

    @Test
    fun toStringStillRedactsTheTokenWithTheNewKeys() {
        val text = Settings(authToken = "secret-token-value", downloadAheadPerFeed = 5, notifyNewEpisodes = true).toString()
        assertFalse(text.contains("secret-token-value"))
        assertTrue(text.contains("downloadAheadPerFeed=5"))
        assertTrue(text.contains("notifyNewEpisodes=true"))
    }

    @Test
    fun setAssignedSpeedStoresTheSpeedAndLeavesTheModeAlone() = runBlocking<Unit> {
        val repo = repo(File(tmp.root, "assigned.preferences_pb"))
        repo.toggleSpeedMode()
        assertEquals(SpeedMode.NORMAL, repo.current().speedMode)
        repo.setAssignedSpeed(2.0f)
        assertEquals(2.0f, repo.current().assignedSpeed)
        assertEquals(SpeedMode.NORMAL, repo.current().speedMode)
        repo.toggleSpeedMode()
        repo.setAssignedSpeed(1.5f)
        assertEquals(SpeedMode.ASSIGNED, repo.current().speedMode)
        assertEquals(1.5f, repo.current().assignedSpeed)
    }

    @Test
    fun setAssignedSpeedRoundsToTenthsAndLimitsTheRange() = runBlocking<Unit> {
        val repo = repo(File(tmp.root, "range.preferences_pb"))
        repo.setAssignedSpeed(1.34f)
        assertEquals(1.3f, repo.current().assignedSpeed)
        repo.setAssignedSpeed(0.44f)
        assertEquals(0.5f, repo.current().assignedSpeed)
        repo.setAssignedSpeed(3.04f)
        assertEquals(3.0f, repo.current().assignedSpeed)
        repo.setAssignedSpeed(2.0f)
        repo.setAssignedSpeed(Float.NaN)
        assertEquals(1.3f, repo.current().assignedSpeed)
    }

    @Test
    fun aStoredOnePointTwoFiveIsReadAsIsAndOpeningTheScreensWritesNothing() = runBlocking<Unit> {
        val file = File(tmp.root, "legacy.preferences_pb")
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        PreferenceDataStoreFactory.create(scope = seedScope) { file }
            .edit { it[floatPreferencesKey("assignedSpeed")] = 1.25f }
        seedScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        val before = file.readBytes()

        val repo = repo(file)
        val settings = repo.current()
        assertEquals(1.25f, settings.assignedSpeed)
        assertEquals("1.25x", formatSpeed(settings.assignedSpeed))
        assertEquals(1.25f, PlaybackSpeedState(settings.assignedSpeed, settings.speedMode).currentSpeed)
        assertEquals(1.25f, repo.current().assignedSpeed)
        // The Settings screen and the assign sheet list it as its own entry.
        assertTrue(SettingsChoices.speedsWithCurrent(settings.assignedSpeed).contains(1.25f))
        assertEquals(27, SettingsChoices.speedsWithCurrent(settings.assignedSpeed).size)
        // Reading and listing wrote nothing.
        assertTrue(before.contentEquals(file.readBytes()))
    }
}
