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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `POST /v1/feeds` against MockWebServer. */
class SubscribeApiTest {
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
    fun sendsAPostWithExactlyOneKeyAndDecodesTheCreatedFeed() = runBlocking {
        server.enqueue(reply(201, resource("feed.json")))
        val feed = api().subscribeFeed("https://example.com/feeds/history.xml")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/feeds", request.url.encodedPath)
        assertEquals("Bearer $TEST_TOKEN", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals("""{"feedUrl":"https://example.com/feeds/history.xml"}""", request.body!!.utf8())
        assertEquals(12L, feed.id)
        assertEquals("Example History Podcast", feed.title)
        assertEquals(ListenOrder.NEWEST_FIRST, feed.listenOrder)
    }

    @Test
    fun theAddressIsSentUnchanged() = runBlocking {
        server.enqueue(reply(201, resource("feed.json")))
        api().subscribeFeed("HTTP://Host.Example/a b.xml?x=\"1\"")
        assertEquals("""{"feedUrl":"HTTP://Host.Example/a b.xml?x=\"1\""}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun mapsEveryFailure() {
        server.enqueue(reply(201, "garbage"))
        assertEquals(ApiError.Malformed, error { api().subscribeFeed("u") })
        server.enqueue(reply(401, """{"detail":"no"}"""))
        assertEquals(ApiError.Unauthorized, error { api().subscribeFeed("u") })
        server.enqueue(reply(409, """{"detail":"Already subscribed to this feed."}"""))
        assertEquals(ApiError.Http(409, "Already subscribed to this feed."), error { api().subscribeFeed("u") })
        server.enqueue(reply(422, """{"detail":"The feed could not be fetched or parsed."}"""))
        assertEquals(
            ApiError.Http(422, "The feed could not be fetched or parsed."),
            error { api().subscribeFeed("u") },
        )
        server.enqueue(MockResponse.Builder().code(500).body("<html>").build())
        assertEquals(ApiError.Http(500, null), error { api().subscribeFeed("u") })
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        assertEquals(ApiError.Unreachable(timeout = false), error { api().subscribeFeed("u") })
    }

    @Test
    fun theRequestBodyNeverShowsTheAddressInItsText() {
        assertEquals(
            "SubscribeRequest(feedUrl=***)",
            com.podclatch.data.network.SubscribeRequest("http://h/x?token=SECRETMARK123").toString(),
        )
    }
}
