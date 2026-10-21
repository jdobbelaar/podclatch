package com.podclatch.ui.subscribe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podclatch.data.repo.SubscribeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** A thin wrapper over [SubscribeController], scoped to the Subscribe destination. */
@HiltViewModel
class SubscribeViewModel @Inject constructor(
    repo: SubscribeRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    // The typed text lives in the SavedStateHandle only (never Room, DataStore, or a log): it can hold a secret.
    private val controller = SubscribeController(
        repo,
        viewModelScope,
        initialText = saved.get<String>(TEXT_KEY).orEmpty(),
        onTextChanged = { saved[TEXT_KEY] = it },
    )

    val state: StateFlow<SubscribeUiState> = controller.uiState

    fun onTextChange(text: String) = controller.onTextChange(text)
    fun subscribe() = controller.subscribe()
    fun subscribeTo(address: String) = controller.subscribeTo(address)
    fun clearMessage() = controller.clearMessage()
    fun clear() = controller.clear()
    fun consumeOutcome() = controller.consumeOutcome()
}

private const val TEXT_KEY = "address_text"
