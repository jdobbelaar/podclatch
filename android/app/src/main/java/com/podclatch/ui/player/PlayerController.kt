package com.podclatch.ui.player

import com.podclatch.Config
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.domain.PlaybackSpeedState
import com.podclatch.domain.PlaybackSpeedUseCase
import com.podclatch.domain.SpeedMode
import com.podclatch.playback.PlaybackController
import com.podclatch.playback.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the mini player and the full player show. */
data class PlayerUiState(
    val playback: PlaybackUiState = PlaybackUiState(),
    val positionMs: Long = 0,
    val speed: PlaybackSpeedState = PlaybackSpeedState(Config.DEFAULT_ASSIGNED_SPEED, SpeedMode.ASSIGNED),
    val skipBackSec: Int = Config.DEFAULT_SKIP_BACK_SEC,
    val skipForwardSec: Int = Config.DEFAULT_SKIP_FORWARD_SEC,
)

/** One tick per [periodMs], forever; the position is read on each. */
fun positionTicks(periodMs: Long = Config.PLAYER_POSITION_POLL_MS): Flow<Unit> = flow {
    while (true) {
        delay(periodMs)
        emit(Unit)
    }
}

/**
 * The player screens' logic without any Android type. The position is read from [PlaybackController] on
 * every tick of [ticks] (and right after a seek or skip) while an Episode is loaded; the speed goes only
 * through [PlaybackSpeedUseCase]. [scope] runs the setting changes (the application scope in the app);
 * [stateScope] only keeps the state flow alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerController(
    private val playback: PlaybackController,
    private val speed: PlaybackSpeedUseCase,
    settings: SettingsRepository,
    private val scope: CoroutineScope,
    stateScope: CoroutineScope,
    ticks: Flow<Unit> = positionTicks(),
) {
    private val nudges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    private val position: Flow<Long> = playback.state
        .map { it.episodeId != null }
        .distinctUntilChanged()
        .flatMapLatest { loaded ->
            if (!loaded) flowOf(0L) else merge(ticks, nudges).onStart { emit(Unit) }.map { playback.currentPositionMs() }
        }

    val playbackState: StateFlow<PlaybackUiState> = playback.state

    val uiState: StateFlow<PlayerUiState> = combine(
        playback.state,
        position,
        speed.state,
        settings.settings.map { it.skipBackSec to it.skipForwardSec }.distinctUntilChanged(),
    ) { p, pos, s, skips ->
        PlayerUiState(p, pos, s, skips.first, skips.second)
    }.stateIn(stateScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState(playback = playback.state.value))

    fun playPause() {
        if (playback.state.value.isPlaying) playback.pause() else playback.resume()
    }

    /**
     * Retry after a playback error: asks for the loaded Episode again once, which resumes at the saved
     * position. Does nothing when no Episode is loaded or the last error cannot be retried.
     */
    fun retry() {
        val state = playback.state.value
        val id = state.episodeId ?: return
        if (playerErrorUi(state.error)?.canRetry != true) return
        scope.launch {
            try {
                playback.play(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The state flow keeps showing the error; there is nothing more to say.
            }
        }
    }

    fun pause() = playback.pause()
    fun resume() = playback.resume()

    fun seekTo(positionMs: Long) {
        playback.seekTo(positionMs)
        nudges.tryEmit(Unit)
    }

    fun skipBack() {
        playback.skipBack()
        nudges.tryEmit(Unit)
    }

    fun skipForward() {
        playback.skipForward()
        nudges.tryEmit(Unit)
    }

    /** A tap on the Speed button: flips between the assigned speed and 1.0. */
    fun toggleSpeed() {
        scope.launch { speed.toggle() }
    }

    /**
     * "Set" in the speed sheet. A value the user did not move from the stored one keeps it exactly (the
     * 1.25 default is not a multiple of 0.1) and only switches to ASSIGNED.
     */
    fun confirmSpeed(value: Float, initial: Float) {
        scope.launch { if (value == initial) speed.keepAssigned() else speed.assign(value) }
    }
}
