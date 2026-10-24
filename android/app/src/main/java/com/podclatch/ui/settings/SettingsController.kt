package com.podclatch.ui.settings

import com.podclatch.data.repo.Settings
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.domain.SettingsChoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The device settings the screen edits (never the token, [Settings.speedMode], or the last Sync revision). */
data class SettingsUiState(
    val skipBackSec: Int,
    val skipForwardSec: Int,
    val assignedSpeed: Float,
    val wifiOnly: Boolean,
    val downloadAhead: Int,
    val autoDelete: Boolean,
    val syncIntervalHours: Int,
    val notifyNewEpisodes: Boolean,
)

internal fun Settings.toUiState() = SettingsUiState(
    skipBackSec = skipBackSec,
    skipForwardSec = skipForwardSec,
    assignedSpeed = assignedSpeed,
    wifiOnly = downloadWifiOnly,
    downloadAhead = SettingsChoices.clampDownloadAhead(downloadAheadPerFeed),
    autoDelete = autoDeletePlayedDownloads,
    syncIntervalHours = syncIntervalHours,
    notifyNewEpisodes = notifyNewEpisodes,
)

/**
 * The logic of the Settings screen (US-17), with no Android types. Every change is written at once; the state
 * is always the stored values, so a change made elsewhere (the debug receiver, another path) shows up live.
 * It never touches the mode of the Speed button, the connection, or the network.
 */
class SettingsController(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    /** Null until the stored settings are first read. */
    val uiState: StateFlow<SettingsUiState?> =
        settings.settings.map { it.toUiState() }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    fun setSkipBack(seconds: Int) {
        if (seconds <= 0) return
        scope.launch { settings.setSkipIntervals(seconds, settings.current().skipForwardSec) }
    }

    fun setSkipForward(seconds: Int) {
        if (seconds <= 0) return
        scope.launch { settings.setSkipIntervals(settings.current().skipBackSec, seconds) }
    }

    /** Stores the speed only; the mode stays as the Speed button left it. */
    fun setAssignedSpeed(speed: Float) {
        scope.launch { settings.setAssignedSpeed(speed) }
    }

    fun setWifiOnly(enabled: Boolean) {
        scope.launch { settings.setDownloadWifiOnly(enabled) }
    }

    fun setDownloadAhead(count: Int) {
        scope.launch { settings.setDownloadAheadPerFeed(SettingsChoices.clampDownloadAhead(count)) }
    }

    fun setAutoDelete(enabled: Boolean) {
        scope.launch { settings.setAutoDeletePlayedDownloads(enabled) }
    }

    /** The periodic Sync is rescheduled by the application when this value changes; nothing syncs now. */
    fun setSyncInterval(hours: Int) {
        if (hours <= 0) return
        scope.launch { settings.setSyncIntervalHours(hours) }
    }

    fun setNotifyNewEpisodes(enabled: Boolean) {
        scope.launch { settings.setNotifyNewEpisodes(enabled) }
    }
}
