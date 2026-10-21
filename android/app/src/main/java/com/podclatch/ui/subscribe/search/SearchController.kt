package com.podclatch.ui.subscribe.search

import com.podclatch.data.network.ApiError
import com.podclatch.data.repo.SearchOutcome
import com.podclatch.data.repo.SearchRepository
import com.podclatch.data.repo.SearchResult
import com.podclatch.domain.PreparedTerm
import com.podclatch.domain.prepareSearchTerm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the results area shows. */
sealed interface SearchContent {
    /** Blank query. */
    data object Idle : SearchContent

    /** More than 200 characters; no request was sent. */
    data object TooLong : SearchContent

    data object Loading : SearchContent
    data class Results(val results: List<SearchResult>) : SearchContent
    data object Empty : SearchContent

    /** [reason] is the text after `Could not search: `; it holds no term and no address. */
    data class Error(val reason: String) : SearchContent
}

data class SearchUiState(
    /** The text in the search field, exactly as typed. */
    val query: String = "",
    val content: SearchContent = SearchContent.Idle,
    /** The result whose preview is open, or null. Not kept across process death. */
    val selected: SearchResult? = null,
)

/** The server's own wording, used when a 502 comes without a `detail`. */
const val DIRECTORY_UNAVAILABLE_TEXT = "The podcast directory is unavailable. Try again later."

/** The content a finished search shows; null for a rejected token (the connection screen takes over). */
fun SearchOutcome.toContent(): SearchContent? = when (this) {
    is SearchOutcome.Results -> if (results.isEmpty()) SearchContent.Empty else SearchContent.Results(results)
    SearchOutcome.TokenRejected -> null
    is SearchOutcome.Unreachable -> SearchContent.Error(ApiError.Unreachable(timeout).message)
    is SearchOutcome.DirectoryUnavailable -> SearchContent.Error(detail.ifBlank { DIRECTORY_UNAVAILABLE_TEXT })
    is SearchOutcome.Failed -> SearchContent.Error(error.message)
}

/**
 * The Subscribe view's search, without any Android type. The scope and the debounce delay are parameters,
 * so a test runs it in virtual time. Rules:
 * - a request is sent [debounceMs] after the last change to the prepared (trimmed) term;
 * - a blank or over-long term sends nothing, and a term equal to the last requested one sends nothing
 *   again (unless [searchNow] asks);
 * - a changed term cancels the request in flight, and an older answer is never shown.
 *
 * Terms are never logged or put in an exception message.
 */
class SearchController(
    private val repo: SearchRepository,
    private val scope: CoroutineScope,
    private val debounceMs: Long,
    initialQuery: String = "",
    /** Told every new field text, so the ViewModel can keep it in its `SavedStateHandle`. */
    private val onQueryChanged: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(SearchUiState(query = initialQuery))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** The normalised addresses of the cached Feeds, live. */
    val subscribed: StateFlow<Set<String>> =
        repo.subscribedAddresses().stateIn(scope, SharingStarted.Eagerly, emptySet())

    private var job: Job? = null

    /** The term of the latest request started, while its answer is still showing or awaited; else null. */
    private var lastRequested: String? = null

    /** The term whose debounce is running (no request yet), else null. */
    private var pendingTerm: String? = null
    private var inFlight = false

    init {
        // After process death the saved text is searched once more, at once.
        val prepared = prepareSearchTerm(initialQuery)
        if (prepared is PreparedTerm.Term) start(prepared.text, immediate = true)
        else if (prepared is PreparedTerm.TooLong) _state.update { it.copy(content = SearchContent.TooLong) }
    }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text) }
        onQueryChanged(text)
        when (val prepared = prepareSearchTerm(text)) {
            PreparedTerm.Blank -> reset(SearchContent.Idle)
            PreparedTerm.TooLong -> reset(SearchContent.TooLong)
            is PreparedTerm.Term -> {
                val term = prepared.text
                when {
                    term == pendingTerm -> Unit
                    term == lastRequested -> if (!inFlight) cancelPending()
                    else -> start(term, immediate = false)
                }
            }
        }
    }

    /** The Search key and the Try again button: sends the current term at once. */
    fun searchNow() {
        val prepared = prepareSearchTerm(_state.value.query)
        if (prepared is PreparedTerm.Term) start(prepared.text, immediate = true)
    }

    fun clear() = onQueryChange("")

    fun open(result: SearchResult) = _state.update { it.copy(selected = result) }

    fun closePreview() = _state.update { it.copy(selected = null) }

    private fun reset(content: SearchContent) {
        cancelPending()
        lastRequested = null
        _state.update { it.copy(content = content, selected = null) }
    }

    /** Cancels the debounce or the request in flight. A cancelled request no longer counts as requested. */
    private fun cancelPending() {
        job?.cancel()
        job = null
        pendingTerm = null
        if (inFlight) {
            inFlight = false
            lastRequested = null
        }
    }

    private fun start(term: String, immediate: Boolean) {
        cancelPending()
        pendingTerm = term
        job = scope.launch {
            if (!immediate) delay(debounceMs)
            pendingTerm = null
            lastRequested = term
            inFlight = true
            _state.update { it.copy(content = SearchContent.Loading) }
            val outcome = try {
                repo.search(term)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            ensureActive()
            inFlight = false
            val content = outcome?.toContent()
            if (content == null) lastRequested = null
            _state.update { it.copy(content = content ?: if (outcome == null) UNEXPECTED else SearchContent.Idle) }
        }
    }

    private companion object {
        val UNEXPECTED = SearchContent.Error("Something went wrong.")
    }
}
