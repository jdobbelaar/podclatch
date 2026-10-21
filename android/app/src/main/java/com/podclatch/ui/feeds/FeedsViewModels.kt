package com.podclatch.ui.feeds

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.podclatch.data.db.FeedListItem
import com.podclatch.data.repo.FeedsRepository
import com.podclatch.ui.nav.FeedDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * A thin wrapper over [FeedsController]. The controller (and so a running Refresh, reorder, or
 * unsubscribe) outlives this ViewModel, which is cleared when the Feeds destination leaves the back stack.
 */
@HiltViewModel
class FeedsViewModel @Inject constructor(private val controller: FeedsController) : ViewModel() {
    val state: StateFlow<FeedsUiState> = controller.uiState

    fun onDragStarted() = controller.onDragStarted()
    fun onMove(fromId: Long, toId: Long) = controller.onMove(fromId, toId)
    fun onDragStopped() = controller.onDragStopped()
    fun refresh() = controller.refresh()
    fun requestUnsubscribe(feedId: Long) = controller.requestUnsubscribe(feedId)
    fun cancelUnsubscribe() = controller.cancelUnsubscribe()
    fun confirmUnsubscribe() = controller.confirmUnsubscribe()
    fun dismissMessage() = controller.dismissMessage()
}

/** The Feed Detail placeholder: the Feed's title from Room, and whether to pop itself. */
@HiltViewModel
class FeedDetailViewModel @Inject constructor(
    repo: FeedsRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    val feedId: Long = savedState.toRoute<FeedDetail>().feedId

    /** Null until the cache has been read once, so nothing pops before the first load. */
    val items: StateFlow<List<FeedListItem>?> =
        repo.feeds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
