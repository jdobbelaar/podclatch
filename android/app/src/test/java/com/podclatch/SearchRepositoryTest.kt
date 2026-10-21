package com.podclatch

import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.SearchResultDto
import com.podclatch.data.repo.SearchOutcome
import com.podclatch.data.repo.SearchRepository
import com.podclatch.data.repo.SearchResult
import com.podclatch.domain.TokenRejection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRepositoryTest {
    private val settings = FakeSettings()
    private val api = FakeApi()
    private val store = FakeCacheStore()
    private val rejection = TokenRejection(settings)
    private val repo = SearchRepository(api, store, rejection)

    private fun failing(error: ApiError): suspend (String) -> Nothing = { throw ApiException(error) }

    @Test
    fun resultsKeepTheServersOrderAndEveryField() = runBlocking {
        api.searchHandler = {
            listOf(
                SearchResultDto("https://b/2.xml", "Second?", "B", "https://b/2.jpg"),
                SearchResultDto("https://a/1.xml", "First?", "", ""),
            )
        }
        val outcome = repo.search("history")
        assertEquals(
            SearchOutcome.Results(
                listOf(
                    SearchResult("https://b/2.xml", "Second?", "B", "https://b/2.jpg"),
                    SearchResult("https://a/1.xml", "First?", "", ""),
                ),
            ),
            outcome,
        )
        assertEquals(listOf("history"), api.searchRequests)
    }

    @Test
    fun noResultsIsAnEmptyList() = runBlocking {
        assertEquals(SearchOutcome.Results(emptyList()), repo.search("zzz"))
    }

    @Test
    fun a401CallsTokenRejectionAndClearsTheToken() = runBlocking {
        api.searchHandler = failing(ApiError.Unauthorized)
        assertEquals(SearchOutcome.TokenRejected, repo.search("x"))
        assertTrue(rejection.rejected.value)
        assertEquals("", settings.flow.value.authToken)
    }

    @Test
    fun unreachableCarriesTheTimeoutFlag() = runBlocking {
        api.searchHandler = failing(ApiError.Unreachable(timeout = false))
        assertEquals(SearchOutcome.Unreachable(false), repo.search("x"))
        api.searchHandler = failing(ApiError.Unreachable(timeout = true))
        assertEquals(SearchOutcome.Unreachable(true), repo.search("x"))
        assertFalse(rejection.rejected.value)
    }

    @Test
    fun a502CarriesTheServersDetailOrBlank() = runBlocking {
        api.searchHandler = failing(ApiError.Http(502, "The podcast directory is unavailable. Try again later."))
        assertEquals(
            SearchOutcome.DirectoryUnavailable("The podcast directory is unavailable. Try again later."),
            repo.search("x"),
        )
        api.searchHandler = failing(ApiError.Http(502, null))
        assertEquals(SearchOutcome.DirectoryUnavailable(""), repo.search("x"))
    }

    @Test
    fun anyOtherErrorIsFailedWithTheApiError() = runBlocking {
        api.searchHandler = failing(ApiError.Http(422, "The search term must not be blank."))
        assertEquals(SearchOutcome.Failed(ApiError.Http(422, "The search term must not be blank.")), repo.search("x"))
        api.searchHandler = failing(ApiError.Malformed)
        assertEquals(SearchOutcome.Failed(ApiError.Malformed), repo.search("x"))
        api.searchHandler = failing(ApiError.Http(500, null))
        assertEquals(SearchOutcome.Failed(ApiError.Http(500, null)), repo.search("x"))
    }

    @Test
    fun cancellationIsNotAnOutcome() {
        api.searchHandler = { throw CancellationException("cancelled") }
        try {
            runBlocking { repo.search("x") }
            throw AssertionError("expected a CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun subscribedAddressesAreNormalisedAndFollowTheCache() = runBlocking {
        assertEquals(emptySet<String>(), repo.subscribedAddresses().first())
        store.transaction {
            insertFeedIfAbsent(cachedFeed(1).copy(feedUrl = "HTTPS://Example.com:443/feed.xml"))
        }
        assertEquals(setOf("https://example.com/feed.xml"), repo.subscribedAddresses().first())
        store.transaction { deleteFeed(1) }
        assertEquals(emptySet<String>(), repo.subscribedAddresses().first())
    }

    @Test
    fun resultsNeverShowTheAddressInToString() {
        assertFalse(SearchResult("https://h/x?token=SECRETMARK123", "T", "A", "").toString().contains("SECRETMARK123"))
    }
}
