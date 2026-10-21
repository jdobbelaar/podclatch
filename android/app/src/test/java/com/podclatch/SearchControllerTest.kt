package com.podclatch

import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.SearchResultDto
import com.podclatch.data.repo.SearchOutcome
import com.podclatch.data.repo.SearchRepository
import com.podclatch.data.repo.SearchResult
import com.podclatch.domain.TokenRejection
import com.podclatch.ui.subscribe.search.DIRECTORY_UNAVAILABLE_TEXT
import com.podclatch.ui.subscribe.search.SearchContent
import com.podclatch.ui.subscribe.search.SearchController
import com.podclatch.ui.subscribe.search.toContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The search logic in virtual time: no test sleeps, and a test that would hang fails after 10 seconds
 * of real time with the usual `runTest` message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {
    private val settings = FakeSettings()
    private val api = FakeApi()
    private val store = FakeCacheStore()
    private val rejection = TokenRejection(settings)
    private val repo = SearchRepository(api, store, rejection)
    private val saved = mutableListOf<String>()

    /** The virtual time (ms) at which each request reached the API. */
    private val requestTimes = mutableListOf<Long>()

    private fun dto(i: Int) = SearchResultDto("https://feeds.example.com/$i.xml", "Title $i", "Author $i", "https://art.example.com/$i.jpg")
    private fun result(i: Int) = SearchResult("https://feeds.example.com/$i.xml", "Title $i", "Author $i", "https://art.example.com/$i.jpg")

    private fun test(block: suspend TestScope.() -> Unit) = runTest(timeout = 10.seconds) { block() }

    private fun TestScope.controller(initial: String = "", debounce: Long = 400) =
        SearchController(repo, backgroundScope as CoroutineScope, debounce, initial) { saved += it }

    private fun TestScope.record(answer: suspend (String) -> List<SearchResultDto> = { listOf(dto(1)) }) {
        api.searchHandler = { q ->
            requestTimes += currentTime
            answer(q)
        }
    }

    private suspend fun TestScope.passMs(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // ---- Debounce ----

    @Test
    fun aBurstSendsOneRequestFourHundredMsAfterTheLastChange() = test {
        record()
        val c = controller()
        c.onQueryChange("h")
        passMs(100)
        c.onQueryChange("hi")
        passMs(100)
        c.onQueryChange("his")
        passMs(399)
        assertEquals(emptyList<String>(), api.searchRequests)
        passMs(1)
        assertEquals(listOf("his"), api.searchRequests)
        assertEquals(listOf(600L), requestTimes)
        assertEquals(SearchContent.Results(listOf(result(1))), c.state.value.content)
        passMs(10_000)
        assertEquals(1, api.searchRequests.size)
    }

    @Test
    fun aChangeAt399MsPostponesTheRequestByAnotherFourHundred() = test {
        record()
        val c = controller()
        c.onQueryChange("a")
        passMs(399)
        c.onQueryChange("ab")
        passMs(399)
        assertEquals(emptyList<String>(), api.searchRequests)
        passMs(1)
        assertEquals(listOf("ab"), api.searchRequests)
        assertEquals(listOf(799L), requestTimes)
    }

    @Test
    fun theTextIsSavedOnEveryChange() = test {
        record()
        val c = controller()
        c.onQueryChange("a")
        c.onQueryChange("a ")
        assertEquals(listOf("a", "a "), saved)
        assertEquals("a ", c.state.value.query)
    }

    // ---- No needless requests ----

    @Test
    fun aTrailingSpaceDuringTheDebounceDoesNotRestartIt() = test {
        record()
        val c = controller()
        c.onQueryChange("his")
        passMs(300)
        c.onQueryChange("his ")
        passMs(100)
        assertEquals(listOf("his"), api.searchRequests)
        assertEquals(listOf(400L), requestTimes)
    }

    @Test
    fun aTermEqualToTheLastRequestedOneSendsNothingAgain() = test {
        record()
        val c = controller()
        c.onQueryChange("his")
        passMs(400)
        assertEquals(1, api.searchRequests.size)
        // A trailing space.
        c.onQueryChange("his ")
        passMs(1_000)
        // Typing a character and deleting it within the window.
        c.onQueryChange("hist")
        passMs(100)
        c.onQueryChange("his")
        passMs(1_000)
        assertEquals(listOf("his"), api.searchRequests)
        assertEquals(SearchContent.Results(listOf(result(1))), c.state.value.content)
    }

    @Test
    fun aDifferentTermAfterAnAnswerSendsANewRequest() = test {
        record()
        val c = controller()
        c.onQueryChange("his")
        passMs(400)
        c.onQueryChange("hist")
        passMs(400)
        assertEquals(listOf("his", "hist"), api.searchRequests)
    }

    @Test
    fun aBlankTermSendsNothingAndShowsIdle() = test {
        record()
        val c = controller()
        c.onQueryChange("   ")
        passMs(5_000)
        assertEquals(emptyList<String>(), api.searchRequests)
        assertEquals(SearchContent.Idle, c.state.value.content)
    }

    @Test
    fun aTooLongTermSendsNothingAndShowsTheLimit() = test {
        record()
        val c = controller()
        c.onQueryChange("a".repeat(201))
        passMs(5_000)
        assertEquals(emptyList<String>(), api.searchRequests)
        assertEquals(SearchContent.TooLong, c.state.value.content)
        c.onQueryChange("a".repeat(200))
        passMs(400)
        assertEquals(1, api.searchRequests.size)
        assertEquals("a".repeat(200), api.searchRequests.single())
    }

    @Test
    fun theTermSentIsTheTrimmedTermWithInnerSpacesUntouched() = test {
        record()
        val c = controller()
        c.onQueryChange("  Rock   &  Roll  ")
        passMs(400)
        assertEquals(listOf("Rock   &  Roll"), api.searchRequests)
    }

    @Test
    fun goingTooLongOrBlankAfterResultsForgetsTheLastRequest() = test {
        record()
        val c = controller()
        c.onQueryChange("his")
        passMs(400)
        c.onQueryChange("")
        assertEquals(SearchContent.Idle, c.state.value.content)
        // The results are gone, so the same term asks again.
        c.onQueryChange("his")
        passMs(400)
        assertEquals(listOf("his", "his"), api.searchRequests)
    }

    // ---- Search key and Try again ----

    @Test
    fun theSearchKeySendsTheCurrentTermAtOnce() = test {
        record()
        val c = controller()
        c.onQueryChange("his")
        c.searchNow()
        runCurrent()
        assertEquals(listOf("his"), api.searchRequests)
        assertEquals(listOf(0L), requestTimes)
        // The debounce that was pending is gone: no second request.
        passMs(5_000)
        assertEquals(1, api.searchRequests.size)
    }

    @Test
    fun theSearchKeyOnABlankTermDoesNothing() = test {
        record()
        val c = controller()
        c.onQueryChange("  ")
        c.searchNow()
        passMs(1_000)
        assertEquals(emptyList<String>(), api.searchRequests)
    }

    @Test
    fun tryAgainSendsTheSameTermAtOnce() = test {
        var fail = true
        record { if (fail) throw ApiException(ApiError.Unreachable(false)) else listOf(dto(1)) }
        val c = controller()
        c.onQueryChange("his")
        passMs(400)
        assertEquals(SearchContent.Error("Cannot reach the server."), c.state.value.content)
        fail = false
        c.searchNow()
        runCurrent()
        assertEquals(listOf("his", "his"), api.searchRequests)
        assertEquals(SearchContent.Results(listOf(result(1))), c.state.value.content)
    }

    // ---- Cancellation ----

    @Test
    fun aNewerTermCancelsTheRequestInFlightAndTheOlderAnswerIsNeverShown() = test {
        val first = CompletableDeferred<List<SearchResultDto>>()
        var firstCancelled = false
        record { q ->
            if (q == "one") {
                try {
                    first.await()
                } catch (e: CancellationException) {
                    firstCancelled = true
                    throw e
                }
            } else {
                listOf(dto(2))
            }
        }
        val c = controller()
        c.onQueryChange("one")
        passMs(400)
        assertEquals(SearchContent.Loading, c.state.value.content)
        c.onQueryChange("two")
        passMs(400)
        assertEquals(listOf("one", "two"), api.searchRequests)
        assertEquals(SearchContent.Results(listOf(result(2))), c.state.value.content)
        assertTrue(firstCancelled)
        first.complete(listOf(dto(1)))
        runCurrent()
        assertEquals(SearchContent.Results(listOf(result(2))), c.state.value.content)
    }

    @Test
    fun anOlderAnswerFromAFakeThatIgnoresCancellationIsStillNotShown() = test {
        // A request that does not stop when cancelled (it only returns later) must not overwrite the newer answer.
        val gate = CompletableDeferred<Unit>()
        record { q ->
            if (q == "one") {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
                listOf(dto(1))
            } else {
                listOf(dto(2))
            }
        }
        val c = controller()
        c.onQueryChange("one")
        passMs(400)
        c.onQueryChange("two")
        passMs(400)
        gate.complete(Unit)
        runCurrent()
        assertEquals(SearchContent.Results(listOf(result(2))), c.state.value.content)
    }

    @Test
    fun clearingTheFieldWhileARequestIsInFlightCancelsItAndShowsIdle() = test {
        val gate = CompletableDeferred<List<SearchResultDto>>()
        var cancelled = false
        record {
            try {
                gate.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        val c = controller()
        c.onQueryChange("his")
        passMs(400)
        assertEquals(SearchContent.Loading, c.state.value.content)
        c.clear()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(SearchContent.Idle, c.state.value.content)
        assertEquals("", c.state.value.query)
        gate.complete(listOf(dto(1)))
        runCurrent()
        assertEquals(SearchContent.Idle, c.state.value.content)
    }

    // ---- States and error mapping ----

    @Test
    fun loadingHidesTheOldResultsAndNoResultsIsEmpty() = test {
        val gate = CompletableDeferred<Unit>()
        record { q ->
            if (q == "two") gate.await()
            if (q == "none") emptyList() else listOf(dto(1))
        }
        val c = controller()
        c.onQueryChange("one")
        passMs(400)
        assertEquals(SearchContent.Results(listOf(result(1))), c.state.value.content)
        c.onQueryChange("two")
        passMs(400)
        assertEquals(SearchContent.Loading, c.state.value.content)
        gate.complete(Unit)
        runCurrent()
        c.onQueryChange("none")
        passMs(400)
        assertEquals(SearchContent.Empty, c.state.value.content)
    }

    @Test
    fun errorsMapToTheirTexts() {
        assertEquals(SearchContent.Error("Cannot reach the server."), SearchOutcome.Unreachable(false).toContent())
        assertEquals(SearchContent.Error("The server did not answer in time."), SearchOutcome.Unreachable(true).toContent())
        assertEquals(
            SearchContent.Error("The podcast directory is unavailable. Try again later."),
            SearchOutcome.DirectoryUnavailable("The podcast directory is unavailable. Try again later.").toContent(),
        )
        assertEquals(SearchContent.Error(DIRECTORY_UNAVAILABLE_TEXT), SearchOutcome.DirectoryUnavailable("").toContent())
        assertEquals(SearchContent.Error(DIRECTORY_UNAVAILABLE_TEXT), SearchOutcome.DirectoryUnavailable("  ").toContent())
        assertEquals(
            SearchContent.Error("The server answered with an error (HTTP 422): The search term must not be blank."),
            SearchOutcome.Failed(ApiError.Http(422, "The search term must not be blank.")).toContent(),
        )
        assertEquals(
            SearchContent.Error("The server's answer was not understood."),
            SearchOutcome.Failed(ApiError.Malformed).toContent(),
        )
        assertNull(SearchOutcome.TokenRejected.toContent())
        assertEquals(SearchContent.Empty, SearchOutcome.Results(emptyList()).toContent())
    }

    @Test
    fun a401ShowsNoErrorTextAndTheTokenIsRejected() = test {
        record { throw ApiException(ApiError.Unauthorized) }
        val c = controller()
        c.onQueryChange("his")
        passMs(400)
        assertEquals(SearchContent.Idle, c.state.value.content)
        assertTrue(rejection.rejected.value)
    }

    @Test
    fun anUnexpectedExceptionBecomesAnErrorWithoutItsText() = test {
        record { throw IllegalStateException("secret term his") }
        val c = controller()
        c.onQueryChange("his")
        passMs(400)
        val content = c.state.value.content
        assertTrue(content is SearchContent.Error)
        assertFalse((content as SearchContent.Error).reason.contains("his"))
    }

    // ---- After process death ----

    @Test
    fun aSavedQuerySearchesOnceAtOnceWhenTheControllerStarts() = test {
        record()
        val c = controller(initial = "his")
        runCurrent()
        assertEquals(listOf("his"), api.searchRequests)
        assertEquals(listOf(0L), requestTimes)
        assertEquals("his", c.state.value.query)
        passMs(5_000)
        assertEquals(1, api.searchRequests.size)
    }

    @Test
    fun aSavedBlankOrTooLongQuerySendsNothing() = test {
        record()
        controller(initial = "  ")
        val long = controller(initial = "a".repeat(201))
        passMs(5_000)
        assertEquals(emptyList<String>(), api.searchRequests)
        assertEquals(SearchContent.TooLong, long.state.value.content)
    }

    // ---- Preview and the Subscribed marker ----

    @Test
    fun openingAndClosingThePreviewSendsNoRequestAndKeepsTheResults() = test {
        record()
        val c = controller()
        c.onQueryChange("his")
        passMs(400)
        c.open(result(1))
        assertEquals(result(1), c.state.value.selected)
        passMs(5_000)
        c.closePreview()
        assertNull(c.state.value.selected)
        assertEquals(SearchContent.Results(listOf(result(1))), c.state.value.content)
        assertEquals(1, api.searchRequests.size)
        assertEquals(listOf("search"), api.calls)
    }

    @Test
    fun theSubscribedAddressesFollowTheCacheLive() = test {
        record()
        val c = controller()
        runCurrent()
        assertEquals(emptySet<String>(), c.subscribed.value)
        store.transaction { insertFeedIfAbsent(cachedFeed(1).copy(feedUrl = "HTTPS://Feeds.Example.com:443/1.xml")) }
        runCurrent()
        assertEquals(setOf("https://feeds.example.com/1.xml"), c.subscribed.value)
        store.transaction { deleteFeed(1) }
        runCurrent()
        assertEquals(emptySet<String>(), c.subscribed.value)
        assertEquals(emptyList<String>(), api.searchRequests)
    }
}
