package com.podclatch

import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.OkHttpPodClatchApi
import com.podclatch.data.network.artworkHttpClient
import com.podclatch.data.repo.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `PUT /v1/feeds/order`, `DELETE /v1/feeds/{id}`, and `POST /v1/refresh` against MockWebServer. */
class FeedsApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var server: MockWebServer
    private lateinit var settings: FakeSettings

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settings = FakeSettings(Settings(serverUrl = server.url("/").toString().trimEnd('/'), authToken = TEST_TOKEN))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api() = OkHttpPodClatchApi(okhttp3.OkHttpClient(), json, settings)
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

    @Test
    fun reorderSendsAPutWithTheFullListAndDecodesTheExample() = runBlocking {
        server.enqueue(reply(200, resource("reorder_response.json")))
        val feeds = api().reorderFeeds(listOf(12, 7))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/feeds/order", request.url.encodedPath)
        assertEquals("Bearer $TEST_TOKEN", request.headers["Authorization"])
        assertEquals("""{"feedIds":[12,7]}""", request.body!!.utf8())
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals(listOf(12L, 7L), feeds.map { it.id })
        assertEquals(1, feeds[1].priority)
        assertNull(feeds[1].lastRefreshedAt)
    }

    @Test
    fun reorderMapsErrors() {
        server.enqueue(reply(401, """{"detail":"no"}"""))
        assertEquals(ApiError.Unauthorized, error { api().reorderFeeds(listOf(1)) })
        server.enqueue(reply(422, """{"detail":"feedIds must list every Feed"}"""))
        assertEquals(ApiError.Http(422, "feedIds must list every Feed"), error { api().reorderFeeds(listOf(1)) })
        server.enqueue(reply(200, "garbage"))
        assertEquals(ApiError.Malformed, error { api().reorderFeeds(listOf(1)) })
        server.enqueue(MockResponse.Builder().code(500).body("<html>").build())
        assertEquals(ApiError.Http(500, null), error { api().reorderFeeds(listOf(1)) })
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        assertEquals(ApiError.Unreachable(timeout = false), error { api().reorderFeeds(listOf(1)) })
    }

    @Test
    fun unsubscribeSendsADeleteAndAccepts204WithoutABody() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        api().unsubscribeFeed(31)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/feeds/31", request.url.encodedPath)
        assertEquals("Bearer $TEST_TOKEN", request.headers["Authorization"])
    }

    @Test
    fun unsubscribeMapsErrors() {
        server.enqueue(reply(404, """{"detail":"Feed not found"}"""))
        assertEquals(ApiError.Http(404, "Feed not found"), error { api().unsubscribeFeed(1) })
        server.enqueue(reply(401, "{}"))
        assertEquals(ApiError.Unauthorized, error { api().unsubscribeFeed(1) })
        server.enqueue(reply(422, """{"detail":"bad id"}"""))
        assertEquals(ApiError.Http(422, "bad id"), error { api().unsubscribeFeed(1) })
        server.enqueue(MockResponse.Builder().code(500).body("oops").build())
        assertEquals(ApiError.Http(500, null), error { api().unsubscribeFeed(1) })
    }

    @Test
    fun refreshSendsAPostWithAnEmptyBodyAndDecodesTheExample() = runBlocking {
        server.enqueue(reply(200, resource("refresh_results.json")))
        val results = api().refreshAllFeeds()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/refresh", request.url.encodedPath)
        assertEquals("Bearer $TEST_TOKEN", request.headers["Authorization"])
        assertEquals(0, request.body?.size ?: 0)
        assertEquals(2, results.size)
        assertTrue(results[0].ok)
        assertNull(results[0].error)
        assertEquals("Could not fetch the feed (HTTP 503).", results[1].error)
    }

    @Test
    fun refreshAnEmptyAnswerIsAnEmptyList() = runBlocking {
        server.enqueue(reply(200, "[]"))
        assertEquals(emptyList<Any>(), api().refreshAllFeeds())
    }

    @Test
    fun refreshMapsErrors() {
        server.enqueue(reply(200, """{"not":"a list"}"""))
        assertEquals(ApiError.Malformed, error { api().refreshAllFeeds() })
        server.enqueue(reply(401, "{}"))
        assertEquals(ApiError.Unauthorized, error { api().refreshAllFeeds() })
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        assertEquals(ApiError.Unreachable(timeout = false), error { api().refreshAllFeeds() })
    }

    @Test
    fun artworkRequestsNeverCarryAnAuthorizationHeader() {
        server.enqueue(MockResponse.Builder().code(200).body("png").build())
        val request = okhttp3.Request.Builder().url(server.url("/art.png")).build()
        artworkHttpClient().newCall(request).execute().use { assertEquals(200, it.code) }
        assertNull(server.takeRequest().headers["Authorization"])
    }
}
