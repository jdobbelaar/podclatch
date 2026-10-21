package com.podclatch.data.repo

import com.podclatch.data.db.CacheStore
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.PodClatchApi
import com.podclatch.data.network.SearchResultDto
import com.podclatch.domain.FeedUrlNormalizer
import com.podclatch.domain.TokenRejection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One podcast found by the search. The address can hold a secret, so [toString] leaves it out; it is
 * kept exactly as the server returned it, because that is what a Subscribe sends.
 */
data class SearchResult(
    val feedUrl: String,
    val title: String,
    val author: String,
    val artworkUrl: String,
) {
    override fun toString(): String = "SearchResult(title=$title)"
}

/** The outcome of one search. No case holds the term, an address, a raw exception, or the token. */
sealed interface SearchOutcome {
    /** 200: the results in the server's order (possibly none). */
    data class Results(val results: List<SearchResult>) : SearchOutcome

    /** 401. The token has been cleared, so the connection screen takes over. */
    data object TokenRejected : SearchOutcome

    data class Unreachable(val timeout: Boolean) : SearchOutcome

    /** 502: the podcast directory failed. [detail] is the server's text, possibly blank. */
    data class DirectoryUnavailable(val detail: String) : SearchOutcome

    /** Any other error, including the 422 for a term the server refuses. */
    data class Failed(val error: ApiError) : SearchOutcome
}

/**
 * Searches for podcasts through the server (`GET /v1/search`) and tells which addresses are already
 * Feeds. It never logs or stores a term.
 */
@Singleton
class SearchRepository @Inject constructor(
    private val api: PodClatchApi,
    private val store: CacheStore,
    private val tokenRejection: TokenRejection,
) {
    /** [term] is already trimmed and within the length limit. */
    suspend fun search(term: String): SearchOutcome {
        val found = try {
            api.searchPodcasts(term)
        } catch (e: ApiException) {
            return failure(e.error)
        }
        return SearchOutcome.Results(found.map { it.toResult() })
    }

    /** The normalised address of every cached Feed (see [FeedUrlNormalizer]); follows the cache live. */
    fun subscribedAddresses(): Flow<Set<String>> =
        store.feedUrls().map { urls -> urls.mapTo(HashSet()) { FeedUrlNormalizer.normalize(it) } }

    private suspend fun failure(error: ApiError): SearchOutcome = when {
        error is ApiError.Unauthorized -> {
            try {
                tokenRejection.onTokenRejected()
            } catch (_: Exception) {
                // Still reported as rejected.
            }
            SearchOutcome.TokenRejected
        }
        error is ApiError.Unreachable -> SearchOutcome.Unreachable(error.timeout)
        error is ApiError.Http && error.code == 502 -> SearchOutcome.DirectoryUnavailable(error.detail.orEmpty())
        else -> SearchOutcome.Failed(error)
    }

    private fun SearchResultDto.toResult() = SearchResult(feedUrl, title, author, artworkUrl)
}
