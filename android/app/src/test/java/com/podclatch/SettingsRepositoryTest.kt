package com.podclatch

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.podclatch.data.repo.DataStoreSettingsRepository
import com.podclatch.data.repo.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    fun toStringStillRedactsTheTokenWithTheNewKeys() {
        val text = Settings(authToken = "secret-token-value", downloadAheadPerFeed = 5, notifyNewEpisodes = true).toString()
        assertFalse(text.contains("secret-token-value"))
        assertTrue(text.contains("downloadAheadPerFeed=5"))
        assertTrue(text.contains("notifyNewEpisodes=true"))
    }
}
