package com.podclatch.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.di.ApplicationScope
import com.podclatch.domain.PlaybackSpeedUseCase
import com.podclatch.playback.PlaybackController
import com.podclatch.playback.PlaybackUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** A thin wrapper over [PlayerController]; used by the mini player (activity scope) and the full player. */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    playback: PlaybackController,
    speed: PlaybackSpeedUseCase,
    settings: SettingsRepository,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {
    private val controller = PlayerController(playback, speed, settings, appScope, viewModelScope)

    /** Cheap: no position polling. Drives whether the mini player shows at all. */
    val playbackState: StateFlow<PlaybackUiState> = controller.playbackState

    /** Polls the position while collected. */
    val state: StateFlow<PlayerUiState> = controller.uiState

    fun playPause() = controller.playPause()
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun skipBack() = controller.skipBack()
    fun skipForward() = controller.skipForward()
    fun toggleSpeed() = controller.toggleSpeed()
    fun confirmSpeed(value: Float, initial: Float) = controller.confirmSpeed(value, initial)
}
