package com.podclatch

import com.podclatch.data.db.ListenOrder
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.OkHttpPodClatchApi
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

/** `PATCH /v1/feeds/{id}` and `POST /v1/feeds/{id}/refresh` against MockWebServer. */
class FeedDetailApiTest {
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

    // ---- PATCH /v1/feeds/{id} ----

    @Test
    fun updateListenOrderSendsAPatchWithTheOrderAndDecodesTheFeed() = runBlocking {
        server.enqueue(reply(200, resource("feed.json")))
        val feed = api().updateFeedListenOrder(12, ListenOrder.OLDEST_FIRST)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/v1/feeds/12", request.url.encodedPath)
        assertEquals("Bearer $TEST_TOKEN", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals("""{"listenOrder":"OLDEST_FIRST"}""", request.body!!.utf8())
        assertEquals(12L, feed.id)
    }

    @Test
    fun updateListenOrderSendsNewestFirstToo() = runBlocking {
        server.enqueue(reply(200, resource("feed.json")))
        api().updateFeedListenOrder(5, ListenOrder.NEWEST_FIRST)
        assertEquals("""{"listenOrder":"NEWEST_FIRST"}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun updateListenOrderMapsErrors() {
        server.enqueue(reply(401, """{"detail":"no"}"""))
        assertEquals(ApiError.Unauthorized, error { api().updateFeedListenOrder(1, ListenOrder.OLDEST_FIRST) })
        server.enqueue(reply(404, """{"detail":"Feed not found"}"""))
        assertEquals(ApiError.Http(404, "Feed not found"), error { api().updateFeedListenOrder(1, ListenOrder.OLDEST_FIRST) })
        server.enqueue(reply(422, """{"detail":"bad order"}"""))
        assertEquals(ApiError.Http(422, "bad order"), error { api().updateFeedListenOrder(1, ListenOrder.OLDEST_FIRST) })
        server.enqueue(reply(200, "garbage"))
        assertEquals(ApiError.Malformed, error { api().updateFeedListenOrder(1, ListenOrder.OLDEST_FIRST) })
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        assertEquals(
            ApiError.Unreachable(timeout = false),
            error { api().updateFeedListenOrder(1, ListenOrder.OLDEST_FIRST) },
        )
    }

    // ---- POST /v1/feeds/{id}/refresh ----

    @Test
    fun refreshFeedSendsAPostWithNoBodyAndDecodesOneResult() = runBlocking {
        server.enqueue(reply(200, """[{"feedId":12,"ok":true}]"""))
        val results = api().refreshFeed(12)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/feeds/12/refresh", request.url.encodedPath)
        assertEquals("Bearer $TEST_TOKEN", request.headers["Authorization"])
        assertEquals(0, request.body?.size ?: 0)
        assertEquals(1, results.size)
        assertEquals(12L, results[0].feedId)
        assertTrue(results[0].ok)
        assertNull(results[0].error)
    }

    @Test
    fun refreshFeedDecodesAFailureInsideThe200() = runBlocking {
        server.enqueue(reply(200, """[{"feedId":12,"ok":false,"error":"Could not fetch the feed (HTTP 503)."}]"""))
        val result = api().refreshFeed(12).single()
        assertEquals(false, result.ok)
        assertEquals("Could not fetch the feed (HTTP 503).", result.error)
    }

    @Test
    fun refreshFeedMapsErrors() {
        server.enqueue(reply(401, "{}"))
        assertEquals(ApiError.Unauthorized, error { api().refreshFeed(1) })
        server.enqueue(reply(404, """{"detail":"Feed not found"}"""))
        assertEquals(ApiError.Http(404, "Feed not found"), error { api().refreshFeed(1) })
        server.enqueue(reply(422, """{"detail":"bad id"}"""))
        assertEquals(ApiError.Http(422, "bad id"), error { api().refreshFeed(1) })
        server.enqueue(reply(200, """{"not":"a list"}"""))
        assertEquals(ApiError.Malformed, error { api().refreshFeed(1) })
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        assertEquals(ApiError.Unreachable(timeout = false), error { api().refreshFeed(1) })
    }
}
