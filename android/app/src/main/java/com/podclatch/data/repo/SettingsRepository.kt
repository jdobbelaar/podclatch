package com.podclatch.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.podclatch.Config
import com.podclatch.domain.SettingsChoices
import com.podclatch.domain.SpeedMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The settings this story owns; the other spec section 4 keys belong to the stories that use them. */
data class Settings(
    val serverUrl: String = "",
    val authToken: String = "",
    val lastSyncRev: Long = 0,
    val syncIntervalHours: Int = Config.DEFAULT_SYNC_INTERVAL_HOURS,
    val skipBackSec: Int = Config.DEFAULT_SKIP_BACK_SEC,
    val skipForwardSec: Int = Config.DEFAULT_SKIP_FORWARD_SEC,
    val assignedSpeed: Float = Config.DEFAULT_ASSIGNED_SPEED,
    val speedMode: SpeedMode = SpeedMode.ASSIGNED,
    val downloadWifiOnly: Boolean = true,
    val downloadAheadPerFeed: Int = Config.DEFAULT_DOWNLOAD_AHEAD_PER_FEED,
    val notifyNewEpisodes: Boolean = false,
    val autoDeletePlayedDownloads: Boolean = true,
) {
    /** Configured means both the server URL and the token are non-blank. */
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && authToken.isNotBlank()

    /** Redacts the token so a log line or exception message can never carry it. */
    override fun toString(): String =
        "Settings(serverUrl=$serverUrl, authToken=***, lastSyncRev=$lastSyncRev, syncIntervalHours=$syncIntervalHours, " +
            "skipBackSec=$skipBackSec, skipForwardSec=$skipForwardSec, assignedSpeed=$assignedSpeed, speedMode=$speedMode, downloadWifiOnly=$downloadWifiOnly, downloadAheadPerFeed=$downloadAheadPerFeed, notifyNewEpisodes=$notifyNewEpisodes, autoDeletePlayedDownloads=$autoDeletePlayedDownloads)"
}

interface SettingsRepository {
    val settings: Flow<Settings>

    suspend fun current(): Settings = settings.first()

    /** Writes the URL and token in one edit. */
    suspend fun saveConnection(serverUrl: String, authToken: String)

    /** Clears only the token (a rejected token); the URL and the cache stay. */
    suspend fun clearToken()

    suspend fun setLastSyncRev(rev: Long)

    suspend fun setSyncIntervalHours(hours: Int)

    /** Sets the skip-back and skip-forward amounts together; both must be positive. */
    suspend fun setSkipIntervals(backSec: Int, forwardSec: Int)

    /** Flips [SpeedMode] between ASSIGNED and NORMAL in one edit; nothing else changes. */
    suspend fun toggleSpeedMode()

    /**
     * Stores [speed] (0.5 to 3.0) as the assigned speed and sets the mode to ASSIGNED in one edit. A null
     * [speed] keeps the stored assigned speed and only sets the mode.
     */
    suspend fun assignSpeed(speed: Float?)

    /**
     * Stores [speed] as the assigned speed and leaves the mode alone (Settings is not the Speed button).
     * Limited to 0.5 to 3.0; see [SettingsChoices.assignedSpeedToStore].
     */
    suspend fun setAssignedSpeed(speed: Float)

    /** Whether automatic downloads wait for an unmetered network (spec section 4; default true). */
    suspend fun setDownloadWifiOnly(enabled: Boolean)

    /** How many New Episodes per Feed the top-up keeps downloaded or on their way (spec section 4; default 3). */
    suspend fun setDownloadAheadPerFeed(count: Int)

    /** Whether a background Sync that brought in new Episodes posts a notification (default false). */
    suspend fun setNotifyNewEpisodes(enabled: Boolean)

    /** Whether the sweep deletes the downloads of Episodes Played for at least an hour (default true). */
    suspend fun setAutoDeletePlayedDownloads(enabled: Boolean)
}

/** DataStore (Preferences) in the app's private storage. The token is stored in plain text there, by decision. */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override val settings: Flow<Settings> = dataStore.data.map { prefs ->
        Settings(
            serverUrl = prefs[SERVER_URL] ?: "",
            authToken = prefs[AUTH_TOKEN] ?: "",
            lastSyncRev = prefs[LAST_SYNC_REV] ?: 0L,
            syncIntervalHours = prefs[SYNC_INTERVAL_HOURS] ?: Config.DEFAULT_SYNC_INTERVAL_HOURS,
            skipBackSec = prefs[SKIP_BACK_SEC] ?: Config.DEFAULT_SKIP_BACK_SEC,
            skipForwardSec = prefs[SKIP_FORWARD_SEC] ?: Config.DEFAULT_SKIP_FORWARD_SEC,
            assignedSpeed = prefs[ASSIGNED_SPEED] ?: Config.DEFAULT_ASSIGNED_SPEED,
            speedMode = modeOf(prefs[SPEED_MODE]),
            downloadWifiOnly = prefs[DOWNLOAD_WIFI_ONLY] ?: true,
            downloadAheadPerFeed = prefs[DOWNLOAD_AHEAD_PER_FEED] ?: Config.DEFAULT_DOWNLOAD_AHEAD_PER_FEED,
            notifyNewEpisodes = prefs[NOTIFY_NEW_EPISODES] ?: false,
            autoDeletePlayedDownloads = prefs[AUTO_DELETE_PLAYED] ?: true,
        )
    }

    override suspend fun saveConnection(serverUrl: String, authToken: String) {
        dataStore.edit {
            it[SERVER_URL] = serverUrl
            it[AUTH_TOKEN] = authToken
        }
    }

    override suspend fun clearToken() {
        dataStore.edit { it[AUTH_TOKEN] = "" }
    }

    override suspend fun setLastSyncRev(rev: Long) {
        dataStore.edit { it[LAST_SYNC_REV] = rev }
    }

    override suspend fun setSyncIntervalHours(hours: Int) {
        dataStore.edit { it[SYNC_INTERVAL_HOURS] = hours }
    }

    override suspend fun setSkipIntervals(backSec: Int, forwardSec: Int) {
        require(backSec > 0 && forwardSec > 0) { "Skip intervals must be positive." }
        dataStore.edit {
            it[SKIP_BACK_SEC] = backSec
            it[SKIP_FORWARD_SEC] = forwardSec
        }
    }

    override suspend fun toggleSpeedMode() {
        dataStore.edit {
            it[SPEED_MODE] = if (modeOf(it[SPEED_MODE]) == SpeedMode.ASSIGNED) SpeedMode.NORMAL.name else SpeedMode.ASSIGNED.name
        }
    }

    override suspend fun assignSpeed(speed: Float?) {
        require(speed == null || speed in Config.SPEED_MIN..Config.SPEED_MAX) { "Speed out of range." }
        dataStore.edit {
            if (speed != null) it[ASSIGNED_SPEED] = speed
            it[SPEED_MODE] = SpeedMode.ASSIGNED.name
        }
    }

    override suspend fun setAssignedSpeed(speed: Float) {
        val stored = SettingsChoices.assignedSpeedToStore(speed)
        dataStore.edit { it[ASSIGNED_SPEED] = stored }
    }

    override suspend fun setDownloadWifiOnly(enabled: Boolean) {
        dataStore.edit { it[DOWNLOAD_WIFI_ONLY] = enabled }
    }

    override suspend fun setDownloadAheadPerFeed(count: Int) {
        dataStore.edit { it[DOWNLOAD_AHEAD_PER_FEED] = count }
    }

    override suspend fun setNotifyNewEpisodes(enabled: Boolean) {
        dataStore.edit { it[NOTIFY_NEW_EPISODES] = enabled }
    }

    override suspend fun setAutoDeletePlayedDownloads(enabled: Boolean) {
        dataStore.edit { it[AUTO_DELETE_PLAYED] = enabled }
    }

    private companion object {
        fun modeOf(stored: String?): SpeedMode = SpeedMode.entries.firstOrNull { it.name == stored } ?: SpeedMode.ASSIGNED
        val ASSIGNED_SPEED = floatPreferencesKey("assignedSpeed")
        val SPEED_MODE = stringPreferencesKey("speedMode")
        val SERVER_URL = stringPreferencesKey("serverUrl")
        val AUTH_TOKEN = stringPreferencesKey("authToken")
        val LAST_SYNC_REV = longPreferencesKey("lastSyncRev")
        val SYNC_INTERVAL_HOURS = intPreferencesKey("syncIntervalHours")
        val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("downloadWifiOnly")
        val DOWNLOAD_AHEAD_PER_FEED = intPreferencesKey("downloadAheadPerFeed")
        val NOTIFY_NEW_EPISODES = booleanPreferencesKey("notifyNewEpisodes")
        val AUTO_DELETE_PLAYED = booleanPreferencesKey("autoDeletePlayedDownloads")
        val SKIP_BACK_SEC = intPreferencesKey("skipBackSec")
        val SKIP_FORWARD_SEC = intPreferencesKey("skipForwardSec")
    }
}
