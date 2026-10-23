package com.podclatch.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.FeedsRepository
import com.podclatch.data.repo.QueueRepository
import com.podclatch.di.ApplicationScope
import com.podclatch.domain.SyncEngine
import com.podclatch.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * A thin wrapper over [QueueController]. The controller's actions run in the application scope, so they
 * outlive this ViewModel (cleared when the Queue destination leaves the back stack).
 */
@HiltViewModel
class QueueViewModel @Inject constructor(
    queue: QueueRepository,
    feeds: FeedsRepository,
    states: EpisodeStateRepository,
    engine: SyncEngine,
    playback: PlaybackController,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {
    private val controller = QueueController(
        queue = queue,
        feeds = feeds,
        states = states,
        engine = engine,
        playback = playback,
        scope = appScope,
        stateScope = viewModelScope,
    )

    val state: StateFlow<QueueUiState> = controller.uiState

    fun play(episodeId: Long) = controller.play(episodeId)
    fun markPlayed(episodeId: Long) = controller.markPlayed(episodeId)
    fun markNew(episodeId: Long) = controller.markNew(episodeId)
    fun refresh() = controller.refresh()
    fun syncNow() = controller.syncNow()
    fun dismissMessage() = controller.dismissMessage()
}
