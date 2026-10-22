package com.podclatch.ui.feeddetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.FeedsRepository
import com.podclatch.di.ApplicationScope
import com.podclatch.domain.SyncEngine
import com.podclatch.playback.PlaybackController
import com.podclatch.ui.nav.FeedDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * A thin wrapper over [FeedDetailController]. The controller's server actions run in the application
 * scope, so they outlive this ViewModel (cleared when the screen leaves the back stack).
 */
@HiltViewModel
class FeedDetailViewModel @Inject constructor(
    feeds: FeedsRepository,
    states: EpisodeStateRepository,
    engine: SyncEngine,
    playback: PlaybackController,
    @ApplicationScope appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val controller = FeedDetailController(
        feedId = savedState.toRoute<FeedDetail>().feedId,
        feeds = feeds,
        states = states,
        engine = engine,
        playback = playback,
        scope = appScope,
        stateScope = viewModelScope,
    )

    val state: StateFlow<FeedDetailUiState> = controller.uiState

    fun selectListenOrder(order: ListenOrder) = controller.selectListenOrder(order)
    fun refresh() = controller.refresh()
    fun requestUnsubscribe() = controller.requestUnsubscribe()
    fun confirmUnsubscribe() = controller.confirmUnsubscribe()
    fun requestMarkAllPlayed() = controller.requestMarkAllPlayed()
    fun requestMarkAllNew() = controller.requestMarkAllNew()
    fun confirmMarkAll() = controller.confirmMarkAll()
    fun cancelDialog() = controller.cancelDialog()
    fun markPlayed(episodeId: Long) = controller.markPlayed(episodeId)
    fun markNew(episodeId: Long) = controller.markNew(episodeId)
    fun play(episodeId: Long) = controller.play(episodeId)
    fun dismissMessage() = controller.dismissMessage()
}
