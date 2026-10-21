package com.podclatch.ui.subscribe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podclatch.data.repo.SubscribeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** A thin wrapper over [SubscribeController], scoped to the Subscribe destination. */
@HiltViewModel
class SubscribeViewModel @Inject constructor(repo: SubscribeRepository) : ViewModel() {
    private val controller = SubscribeController(repo, viewModelScope)

    val state: StateFlow<SubscribeUiState> = controller.uiState

    fun onTextChange(text: String) = controller.onTextChange(text)
    fun subscribe() = controller.subscribe()
    fun consumeOutcome() = controller.consumeOutcome()
}
