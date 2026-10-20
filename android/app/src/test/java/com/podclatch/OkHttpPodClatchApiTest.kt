package com.podclatch

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.EpisodeStateItem
import com.podclatch.data.network.EpisodeStateResult
import com.podclatch.data.network.OkHttpPodClatchApi
import com.podclatch.data.network.ServerTarget
import com.podclatch.data.network.StateOutcome
import com.podclatch.data.network.SyncPageDto
import com.podclatch.data.network.TombstoneEntityType
import com.podclatch.data.repo.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OkHttpPodClatchApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var server: MockWebServer
    private lateinit var settings: FakeSettings

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settings = FakeSettings(Settings(serverUrl = baseUrl(), authToken = TEST_TOKEN))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun api(client: OkHttpClient = OkHttpClient()) = OkHttpPodClatchApi(client, json, settings)

    private fun resource(name: String) = javaClass.getResource("/openapi/$name")!!.readText()

    private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun failure(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun error(block: () -> Unit): ApiError {
        try {
            block()
        } catch (e: ApiException) {
            return e.error
        }
        throw AssertionError("expected an ApiException")
    }

    // ---- headers ----

    @Test
    fun bearerTokenIsSentOnV1CallsButNotOnHealthz() = runBlocking {
        server.enqueue(ok(resource("health.json")))
        server.enqueue(ok(resource("sync_page.json")))
        api().health()
        api().pullSync(0, 1)
        val health = server.takeRequest()
        val sync = server.takeRequest()
        assertEquals("/healthz", health.url.encodedPath)
        assertNull(health.headers["Authorization"])
        assertEquals("Bearer $TEST_TOKEN", sync.headers["Authorization"])
    }

    @Test
    fun theTokenIsReadAtCallTime() = runBlocking {
        val api = api()
        server.enqueue(ok(resource("sync_page.json")))
        server.enqueue(ok(resource("sync_page.json")))
        api.pullSync(0, 1)
        settings.saveConnection(baseUrl(), "second-token")
        api.pullSync(0, 1)
        assertEquals("Bearer $TEST_TOKEN", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer second-token", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun anExplicitTargetUsesItsOwnAddressAndToken() = runBlocking {
        server.enqueue(ok(resource("health.json")))
        server.enqueue(ok(resource("sync_page.json")))
        settings.saveConnection("http://unused.invalid", "stored-token")
        val target = ServerTarget(baseUrl(), "typed-token", callTimeoutSeconds = 15)
        api().health(target)
        api().pullSync(0, 1, target)
        assertNull(server.takeRequest().headers["Authorization"])
        assertEquals("Bearer typed-token", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun aPathPrefixInTheServerUrlIsKept() = runBlocking {
        settings.saveConnection(baseUrl() + "/pod", TEST_TOKEN)
        server.enqueue(ok(resource("sync_page.json")))
        api().pullSync(0, 1)
        assertEquals("/pod/v1/sync", server.takeRequest().url.encodedPath)
    }

    // ---- requests ----

    @Test
    fun pullSyncSendsSinceAndLimit() = runBlocking {
        server.enqueue(ok(resource("sync_page.json")))
        api().pullSync(since = 1040, limit = 500)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/sync", request.url.encodedPath)
        assertEquals("1040", request.url.queryParameter("since"))
        assertEquals("500", request.url.queryParameter("limit"))
    }

    @Test
    fun theStateBodyIsABareArrayWithAnExplicitNullPlayedAt() = runBlocking {
        server.enqueue(ok(resource("state_results.json")))
        val items = listOf(
            EpisodeStateItem(501, EpisodeStatus.PLAYED, 1760000000000, 1800, 1760000000000),
            EpisodeStateItem(502, EpisodeStatus.NEW, null, 120, 1750000000000),
            EpisodeStateItem(999999, EpisodeStatus.NEW, null, 0, 1760000000000),
        )
        api().pushEpisodeState(items)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/episodes/state", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val body = request.body!!.utf8()
        assertEquals(json.parseToJsonElement(resource("state_request.json")), json.parseToJsonElement(body))
        assertTrue("playedAt must be written as an explicit null", body.contains("\"playedAt\":null"))
        assertTrue(body.trimStart().startsWith("["))
    }

    // ---- decoding ----

    @Test
    fun everyExampleInTheContractDecodes() = runBlocking {
        server.enqueue(ok(resource("sync_page.json")))
        val page = api().pullSync(0, 500)
        assertEquals(1043L, page.nextRev)
        assertTrue(page.hasMore)
        val feed = page.feeds.single()
        assertEquals(12L, feed.id)
        assertEquals(ListenOrder.NEWEST_FIRST, feed.listenOrder)
        assertEquals(1760000000000L, feed.lastRefreshedAt)
        val episode = page.episodes.single()
        assertEquals(2700, episode.durationSec)
        assertNull(episode.artworkUrl)
        assertNull(episode.playedAt)
        assertEquals(EpisodeStatus.NEW, episode.status)
        val tombstone = page.tombstones.single()
        assertEquals(TombstoneEntityType.EPISODE, tombstone.entityType)
        assertEquals(480L, tombstone.entityId)

        // The standalone examples decode with the same serializers.
        json.decodeFromString(com.podclatch.data.network.FeedDto.serializer(), resource("feed.json"))
        json.decodeFromString(com.podclatch.data.network.EpisodeDto.serializer(), resource("episode.json"))
        json.decodeFromString(com.podclatch.data.network.TombstoneDto.serializer(), resource("tombstone.json"))
        assertEquals("ok", json.decodeFromString(com.podclatch.data.network.HealthDto.serializer(), resource("health.json")).status)
    }

    @Test
    fun stateResultsDecodeIncludingUnknownEpisodeWithoutStateFields() = runBlocking {
        server.enqueue(ok(resource("state_results.json")))
        val results = api().pushEpisodeState(listOf(EpisodeStateItem(501, EpisodeStatus.PLAYED, 1, 1, 1)))
        assertEquals(3, results.size)
        assertEquals(StateOutcome.APPLIED, results[0].outcome)
        assertEquals(EpisodeStateResult(502, StateOutcome.STALE, EpisodeStatus.PLAYED, 1755000000000, 2400, 1755000000000), results[1])
        assertEquals(EpisodeStateResult(999999, StateOutcome.UNKNOWN_EPISODE), results[2])
        assertNull(results[2].status)
        assertNull(results[2].positionSec)
    }

    @Test
    fun unknownJsonKeysAreIgnored() = runBlocking {
        server.enqueue(ok("""{"feeds":[],"episodes":[],"tombstones":[],"nextRev":4,"hasMore":false,"future":{"a":1}}"""))
        assertEquals(4L, api().pullSync(0, 1).nextRev)
    }

    @Test
    fun aMissingRequiredFieldAWrongTypeOrAnUnknownEnumIsMalformed() {
        val bodies = listOf(
            """{"feeds":[],"episodes":[],"tombstones":[],"hasMore":false}""",
            """{"feeds":[],"episodes":[],"tombstones":[],"nextRev":"4","hasMore":false}""",
            """{"feeds":[{"id":1}],"episodes":[],"tombstones":[],"nextRev":4,"hasMore":false}""",
            """{"feeds":[],"episodes":[],"tombstones":[{"entityType":"PODCAST","entityId":1,"rev":2}],"nextRev":4,"hasMore":false}""",
            "not json at all",
            "",
            "[]",
        )
        for (body in bodies) {
            server.enqueue(ok(body))
            val outcome = try {
                runBlocking { api().pullSync(0, 1) }
                null
            } catch (e: ApiException) {
                e.error
            }
            assertEquals(body, ApiError.Malformed, outcome)
        }
    }

    @Test
    fun anUnknownStatusInAStateResultIsMalformed() {
        server.enqueue(ok("""[{"episodeId":1,"outcome":"APPLIED","status":"ARCHIVED"}]"""))
        assertEquals(ApiError.Malformed, error { runBlocking { api().pushEpisodeState(listOf(EpisodeStateItem(1, EpisodeStatus.NEW, null, 0, 1))) } })
    }

    @Test
    fun healthMustBeStatusOk() {
        for (body in listOf("""{"status":"degraded"}""", "<html></html>", "{}", """{"status":1}""")) {
            server.enqueue(ok(body))
            assertEquals(body, ApiError.Malformed, error { runBlocking { api().health() } })
        }
    }

    // ---- error mapping ----

    @Test
    fun unauthorizedIsMappedFrom401() {
        server.enqueue(failure(401, """{"detail":"Not authenticated."}"""))
        assertEquals(ApiError.Unauthorized, error { runBlocking { api().pullSync(0, 1) } })
    }

    @Test
    fun otherStatusCodesCarryTheDetailWhenItParses() {
        val cases = listOf(
            Triple(404, """{"detail":"Not Found"}""", "Not Found"),
            Triple(422, """{"detail":"since must be >= 0."}""", "since must be >= 0."),
            Triple(502, """{"detail":"The feed could not be fetched."}""", "The feed could not be fetched."),
            Triple(500, "Internal Server Error", null),
            Triple(422, """{"detail":["not","a","string"]}""", null),
            Triple(503, "", null),
        )
        for ((code, body, detail) in cases) {
            server.enqueue(failure(code, body))
            assertEquals(ApiError.Http(code, detail), error { runBlocking { api().pullSync(0, 1) } })
        }
    }

    @Test
    fun aLongDetailIsTruncatedAndTheTokenIsRedacted() {
        server.enqueue(failure(422, """{"detail":"${"x".repeat(500)}"}"""))
        val long = error { runBlocking { api().pullSync(0, 1) } } as ApiError.Http
        assertEquals(Config.MAX_ERROR_DETAIL_LENGTH, long.detail!!.length)

        server.enqueue(failure(422, """{"detail":"bad token $TEST_TOKEN here"}"""))
        val redacted = error { runBlocking { api().pullSync(0, 1) } } as ApiError.Http
        assertEquals("bad token *** here", redacted.detail)
        assertFalse(TEST_TOKEN in redacted.message)
    }

    @Test
    fun aDroppedConnectionIsUnreachableNotATimeout() {
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        assertEquals(ApiError.Unreachable(timeout = false), error { runBlocking { api().pullSync(0, 1) } })
    }

    @Test
    fun aRefusedConnectionIsUnreachable() {
        server.close()
        assertEquals(ApiError.Unreachable(timeout = false), error { runBlocking { api().pullSync(0, 1) } })
    }

    @Test
    fun aSlowServerIsAnUnreachableTimeout() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").bodyDelay(3, TimeUnit.SECONDS).build())
        val client = OkHttpClient.Builder().callTimeout(400, TimeUnit.MILLISECONDS).build()
        assertEquals(ApiError.Unreachable(timeout = true), error { runBlocking { api(client).pullSync(0, 1) } })
    }

    @Test
    fun anExplicitTargetCanShortenTheCallTimeout() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").bodyDelay(3, TimeUnit.SECONDS).build())
        val target = ServerTarget(baseUrl(), TEST_TOKEN, callTimeoutSeconds = 1)
        val started = System.nanoTime()
        assertEquals(ApiError.Unreachable(timeout = true), error { runBlocking { api().health(target) } })
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
    }

    @Test
    fun noErrorTextEverContainsTheToken() {
        val errors = listOf(
            ApiError.Unreachable(false), ApiError.Unreachable(true), ApiError.Unauthorized,
            ApiError.Http(500, null), ApiError.Malformed,
        )
        for (e in errors) assertFalse(TEST_TOKEN in e.message)
        assertFalse(TEST_TOKEN in ApiException(ApiError.Unauthorized).message!!)
        assertFalse(TEST_TOKEN in ServerTarget(TEST_URL, TEST_TOKEN).toString())
    }
}
