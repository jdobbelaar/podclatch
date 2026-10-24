package com.podclatch.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podclatch.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** A thin wrapper over [SettingsController]. */
@HiltViewModel
class SettingsViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    private val controller = SettingsController(viewModelScope, settings)

    val state: StateFlow<SettingsUiState?> = controller.uiState

    fun setSkipBack(seconds: Int) = controller.setSkipBack(seconds)
    fun setSkipForward(seconds: Int) = controller.setSkipForward(seconds)
    fun setAssignedSpeed(speed: Float) = controller.setAssignedSpeed(speed)
    fun setWifiOnly(enabled: Boolean) = controller.setWifiOnly(enabled)
    fun setDownloadAhead(count: Int) = controller.setDownloadAhead(count)
    fun setAutoDelete(enabled: Boolean) = controller.setAutoDelete(enabled)
    fun setSyncInterval(hours: Int) = controller.setSyncInterval(hours)
    fun setNotifyNewEpisodes(enabled: Boolean) = controller.setNotifyNewEpisodes(enabled)
}
