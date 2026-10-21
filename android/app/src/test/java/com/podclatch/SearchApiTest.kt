package com.podclatch

import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.OkHttpPodClatchApi
import com.podclatch.data.network.SearchResultDto
import com.podclatch.data.repo.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** `GET /v1/search` against MockWebServer. */
class SearchApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api(): OkHttpPodClatchApi {
        val settings = FakeSettings(Settings(serverUrl = server.url("/").toString().trimEnd('/'), authToken = TEST_TOKEN))
        return OkHttpPodClatchApi(okhttp3.OkHttpClient(), json, settings)
    }

    private fun resource(name: String) = javaClass.getResource("/openapi/$name")!!.readText()
    private fun reply(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun error(block: suspend () -> Unit): ApiError {
        try {
            runBlocking { block() }
        } catch (e: ApiException) {
            return e.error
        }
        throw AssertionError("expected an ApiException")
    }

    private fun item(i: Int) =
        """{"feedUrl":"https://example.com/$i.xml","title":"T$i","author":"A$i","artworkUrl":"https://example.com/$i.jpg"}"""

    @Test
    fun theTermArrivesDecodedExactlyAsGiven() = runBlocking {
        for (term in listOf("history", "rock & roll", "a+b", "100%", "#1 hits", "café 日本")) {
            server.enqueue(reply(200, "[]"))
            api().searchPodcasts(term)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/search", request.url.encodedPath)
            assertEquals(term, request.url.queryParameter("q"))
            assertEquals("Bearer $TEST_TOKEN", request.headers["Authorization"])
            // Only q is sent, so an encoded "&" or "#" did not start another parameter or a fragment.
            assertEquals(listOf("q"), request.url.queryParameterNames.toList())
        }
    }

    @Test
    fun theContractExampleDecodes() = runBlocking {
        server.enqueue(reply(200, resource("search_results.json")))
        assertEquals(
            listOf(
                SearchResultDto(
                    "https://example.com/feeds/history.xml",
                    "Example History Podcast",
                    "Example Media",
                    "https://example.com/art/history-600.jpg",
                ),
            ),
            api().searchPodcasts("history"),
        )
    }

    @Test
    fun anEmptyListAndTwentyFiveItemsDecode() = runBlocking {
        server.enqueue(reply(200, "[]"))
        assertEquals(emptyList<SearchResultDto>(), api().searchPodcasts("x"))
        server.enqueue(reply(200, (1..25).joinToString(prefix = "[", postfix = "]") { item(it) }))
        val found = api().searchPodcasts("x")
        assertEquals(25, found.size)
        assertEquals("T1", found.first().title)
        assertEquals("T25", found.last().title)
    }

    @Test
    fun anItemWithAMissingFieldIsMalformed() {
        for (field in listOf("feedUrl", "title", "author", "artworkUrl")) {
            val broken = item(1).replace("\"$field\"", "\"other\"")
            server.enqueue(reply(200, "[$broken]"))
            assertEquals("missing $field", ApiError.Malformed, error { api().searchPodcasts("x") })
        }
        server.enqueue(reply(200, """{"feedUrl":"u"}"""))
        assertEquals(ApiError.Malformed, error { api().searchPodcasts("x") })
    }

    @Test
    fun mapsEveryFailure() {
        server.enqueue(reply(401, """{"detail":"no"}"""))
        assertEquals(ApiError.Unauthorized, error { api().searchPodcasts("x") })
        server.enqueue(reply(422, """{"detail":"The search term must not be blank."}"""))
        assertEquals(ApiError.Http(422, "The search term must not be blank."), error { api().searchPodcasts("x") })
        server.enqueue(reply(502, """{"detail":"The podcast directory is unavailable. Try again later."}"""))
        assertEquals(
            ApiError.Http(502, "The podcast directory is unavailable. Try again later."),
            error { api().searchPodcasts("x") },
        )
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        assertEquals(ApiError.Unreachable(timeout = false), error { api().searchPodcasts("x") })
    }

    @Test
    fun theTermAndTheAddressStayOutOfToString() {
        val dto = SearchResultDto("https://h/x?token=SECRETMARK123", "Title", "Author", "https://h/a.jpg")
        assertFalse(dto.toString().contains("SECRETMARK123"))
    }
}
