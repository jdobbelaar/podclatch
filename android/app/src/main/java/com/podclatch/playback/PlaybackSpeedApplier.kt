package com.podclatch.playback

import com.podclatch.domain.PlaybackSpeedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Follows the speed setting and hands each distinct `currentSpeed` to [sink]: once at the start and once
 * per change. [sink] is the only code that sets the player's speed; the service runs it on the player's
 * thread by passing a main-thread [scope].
 */
class PlaybackSpeedApplier(
    private val speeds: Flow<PlaybackSpeedState>,
    private val scope: CoroutineScope,
    private val sink: (Float) -> Unit,
) {
    fun start(): Job = scope.launch {
        speeds.map { it.currentSpeed }.distinctUntilChanged().collect { sink(it) }
    }
}
