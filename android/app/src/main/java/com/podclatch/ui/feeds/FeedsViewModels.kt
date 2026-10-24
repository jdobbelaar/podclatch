package com.podclatch.ui.feeds

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
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
    fun moveByOne(feedId: Long, delta: Int) = controller.moveByOne(feedId, delta)
    fun requestUnsubscribe(feedId: Long) = controller.requestUnsubscribe(feedId)
    fun cancelUnsubscribe() = controller.cancelUnsubscribe()
    fun confirmUnsubscribe() = controller.confirmUnsubscribe()
    fun dismissMessage() = controller.dismissMessage()
    fun showMessage(message: FeedsMessage) = controller.showMessage(message)
}
