package com.podclatch.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.podclatch.Config
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
) {
    /** Configured means both the server URL and the token are non-blank. */
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && authToken.isNotBlank()

    /** Redacts the token so a log line or exception message can never carry it. */
    override fun toString(): String =
        "Settings(serverUrl=$serverUrl, authToken=***, lastSyncRev=$lastSyncRev, syncIntervalHours=$syncIntervalHours, " +
            "skipBackSec=$skipBackSec, skipForwardSec=$skipForwardSec, assignedSpeed=$assignedSpeed, speedMode=$speedMode)"
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

    private companion object {
        fun modeOf(stored: String?): SpeedMode = SpeedMode.entries.firstOrNull { it.name == stored } ?: SpeedMode.ASSIGNED
        val ASSIGNED_SPEED = floatPreferencesKey("assignedSpeed")
        val SPEED_MODE = stringPreferencesKey("speedMode")
        val SERVER_URL = stringPreferencesKey("serverUrl")
        val AUTH_TOKEN = stringPreferencesKey("authToken")
        val LAST_SYNC_REV = longPreferencesKey("lastSyncRev")
        val SYNC_INTERVAL_HOURS = intPreferencesKey("syncIntervalHours")
        val SKIP_BACK_SEC = intPreferencesKey("skipBackSec")
        val SKIP_FORWARD_SEC = intPreferencesKey("skipForwardSec")
    }
}
