package com.podclatch.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * The UI's only door to playback (shared decision 1): no Media3 types cross it. The implementation talks
 * to [PlaybackService] through a `MediaController`; tests use a fake.
 */
interface PlaybackController {
    val state: StateFlow<PlaybackUiState>

    /**
     * Plays the Episode. The one already loaded continues where it is. An unknown id returns
     * [PlayResult.NotFound] without starting the service.
     */
    suspend fun play(episodeId: Long): PlayResult

    fun pause()

    fun resume()

    /** Moves to [positionMs] in the loaded Episode. */
    fun seekTo(positionMs: Long)

    /** Moves back by the Settings skip-back seconds (not below 0). */
    fun skipBack()

    /** Moves forward by the Settings skip-forward seconds. */
    fun skipForward()

    /** The current position in milliseconds; 0 when nothing is loaded. Call on the main thread. */
    fun currentPositionMs(): Long
}
