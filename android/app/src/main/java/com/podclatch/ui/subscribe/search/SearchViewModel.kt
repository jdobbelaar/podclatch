package com.podclatch.ui.subscribe.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podclatch.Config
import com.podclatch.data.repo.SearchRepository
import com.podclatch.data.repo.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** A thin wrapper over [SearchController], scoped to the Subscribe destination. */
@HiltViewModel
class SearchViewModel @Inject constructor(
    repo: SearchRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    // The typed text lives in the SavedStateHandle only (never Room, DataStore, or a log): a term is personal.
    private val controller = SearchController(
        repo,
        viewModelScope,
        Config.SEARCH_DEBOUNCE_MS,
        initialQuery = saved.get<String>(QUERY_KEY).orEmpty(),
        onQueryChanged = { saved[QUERY_KEY] = it },
    )

    val state: StateFlow<SearchUiState> = controller.state
    val subscribed: StateFlow<Set<String>> = controller.subscribed

    fun onQueryChange(text: String) = controller.onQueryChange(text)
    fun searchNow() = controller.searchNow()
    fun clear() = controller.clear()
    fun open(result: SearchResult) = controller.open(result)
    fun closePreview() = controller.closePreview()
}

private const val QUERY_KEY = "search_query"
