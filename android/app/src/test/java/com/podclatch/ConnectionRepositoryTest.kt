package com.podclatch

import com.podclatch.data.db.DownloadState
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.OkHttpPodClatchApi
import com.podclatch.data.repo.ConnectionRepository
import com.podclatch.data.repo.ConnectionTestResult
import com.podclatch.data.repo.SaveResult
import com.podclatch.data.repo.Settings
import com.podclatch.data.repo.TestStage
import com.podclatch.data.repo.toTestResult
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.ServerUrlValidator
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.TokenError
import com.podclatch.domain.TokenRejection
import com.podclatch.domain.UrlError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConnectionRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

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

    private fun serverUrl() = server.url("/").toString().trimEnd('/')
    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private val healthOk get() = ok("""{"status":"ok"}""")
    private val syncOk get() = ok("""{"feeds":[],"episodes":[],"tombstones":[],"nextRev":0,"hasMore":false}""")

    private class Env(
        val api: com.podclatch.data.network.PodClatchApi,
        val settings: FakeSettings,
        val store: FakeCacheStore = FakeCacheStore(),
    ) {
        val rejection = TokenRejection(settings)
        val engine = SyncEngine(api, settings, store, { }, { 0 }, rejection, DiskFileDeleter(), Dispatchers.Unconfined)
        val repo = ConnectionRepository(
            api, settings, store, engine, rejection, DiskFileDeleter(), ServerUrlValidator(isDebuggable = true),
            Dispatchers.Unconfined, CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun realEnv(settings: FakeSettings = FakeSettings(Settings())) =
        Env(OkHttpPodClatchApi(OkHttpClient(), Json { ignoreUnknownKeys = true }, settings), settings)

    // ---- test connection ----

    @Test
    fun testCallsHealthzWithoutTheTokenThenSyncWithTheTypedTokenAndWritesNothing() = runBlocking {
        val stored = Settings(serverUrl = "https://stored.example.com", authToken = "stored-token", lastSyncRev = 3)
        val env = realEnv(FakeSettings(stored))
        server.enqueue(healthOk)
        server.enqueue(syncOk)

        val result = env.repo.test("  ${serverUrl()}/ ", " typed-token ")

        assertEquals(ConnectionTestResult.Success("localhost:${server.port}"), result)
        val health = server.takeRequest()
        val sync = server.takeRequest()
        assertEquals("/healthz", health.url.encodedPath)
        assertNull(health.headers["Authorization"])
        assertEquals("/v1/sync", sync.url.encodedPath)
        assertEquals("0", sync.url.queryParameter("since"))
        assertEquals("1", sync.url.queryParameter("limit"))
        assertEquals("Bearer typed-token", sync.headers["Authorization"])
        assertEquals(stored, env.settings.flow.value)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aWrongTokenIsReportedAfterAHealthyHealthCheck() = runBlocking {
        val env = realEnv()
        server.enqueue(healthOk)
        server.enqueue(MockResponse.Builder().code(401).body("""{"detail":"Not authenticated."}""").build())
        assertEquals(ConnectionTestResult.TokenRejected, env.repo.test(serverUrl(), "wrong"))
    }

    @Test
    fun aNonPodClatchAddressIsReportedAndStopsBeforeTheAuthenticatedCall() = runBlocking {
        val env = realEnv()
        server.enqueue(ok("<html>hello</html>"))
        assertEquals(ConnectionTestResult.NotPodClatch, env.repo.test(serverUrl(), "tok"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anUnreachableServerIsReported() = runBlocking {
        val env = realEnv()
        val url = serverUrl()
        server.close()
        assertEquals(ConnectionTestResult.Unreachable, env.repo.test(url, "tok"))
    }

    @Test
    fun validationFailuresMakeNoNetworkCall() = runBlocking {
        val env = Env(FakeApi(), FakeSettings(Settings()))
        assertEquals(ConnectionTestResult.InvalidUrl(UrlError.BAD_SCHEME), env.repo.test("ftp://x", "tok"))
        assertEquals(ConnectionTestResult.InvalidUrl(UrlError.BLANK), env.repo.test("", "tok"))
        assertEquals(ConnectionTestResult.InvalidToken(TokenError.BLANK), env.repo.test("https://h", " "))
        assertEquals(ConnectionTestResult.InvalidToken(TokenError.INVALID_CHARACTERS), env.repo.test("https://h", "a b"))
        assertEquals(emptyList<String>(), (env.api as FakeApi).calls)
    }

    @Test
    fun errorsMapToTheDocumentedMessages() {
        assertEquals(ConnectionTestResult.Unreachable, ApiError.Unreachable(false).toTestResult(TestStage.HEALTH))
        assertEquals(ConnectionTestResult.Timeout, ApiError.Unreachable(true).toTestResult(TestStage.AUTHENTICATED))
        assertEquals(ConnectionTestResult.TokenRejected, ApiError.Unauthorized.toTestResult(TestStage.AUTHENTICATED))
        assertEquals(ConnectionTestResult.NotPodClatch, ApiError.Unauthorized.toTestResult(TestStage.HEALTH))
        assertEquals(ConnectionTestResult.NotPodClatch, ApiError.Malformed.toTestResult(TestStage.HEALTH))
        assertEquals(ConnectionTestResult.NotPodClatch, ApiError.Malformed.toTestResult(TestStage.AUTHENTICATED))
        assertEquals(ConnectionTestResult.HttpError(500, null), ApiError.Http(500, null).toTestResult(TestStage.HEALTH))
        assertEquals(ConnectionTestResult.HttpError(422, "bad"), ApiError.Http(422, "bad").toTestResult(TestStage.AUTHENTICATED))
    }

    @Test
    fun aTestDoesNotClearTheStoredToken() = runBlocking {
        val env = realEnv(FakeSettings(Settings(serverUrl = serverUrl(), authToken = "stored-token")))
        server.enqueue(healthOk)
        server.enqueue(MockResponse.Builder().code(401).body("""{"detail":"Not authenticated."}""").build())
        env.repo.test(serverUrl(), "typed")
        assertEquals("stored-token", env.settings.flow.value.authToken)
        assertFalse(env.rejection.rejected.value)
    }

    // ---- save and server switch ----

    private fun envWithCache(oldUrl: String): Env {
        val env = Env(FakeApi(), FakeSettings(Settings(oldUrl, "old-token", lastSyncRev = 50)))
        env.store.feeds[1] = cachedFeed(1)
        env.store.episodes[10] = cachedEpisode(10, 1)
        return env
    }

    @Test
    fun savingTheSameUrlWithANewTokenKeepsTheCacheAndTheCursor() = runBlocking {
        val env = envWithCache("https://pod.example.com")
        env.rejection.onTokenRejected()
        assertFalse(env.repo.requiresWipe("https://POD.example.com/"))
        assertEquals(SaveResult.Saved, env.repo.save("https://POD.example.com/", " new-token "))
        assertEquals(Settings("https://pod.example.com", "new-token", 50), env.settings.flow.value)
        assertEquals(1, env.store.feeds.size)
        assertEquals(1, env.store.episodes.size)
        assertFalse("the notice is cleared", env.rejection.rejected.value)
    }

    @Test
    fun savingADifferentUrlWipesTheCacheTheOutboxTheFilesAndTheCursor() = runBlocking {
        val env = envWithCache("https://old.example.com")
        val file = tmp.newFile("old.mp3")
        env.store.episodes[10] = cachedEpisode(10, 1, downloadState = DownloadState.DOWNLOADED, localPath = file.path)
        env.addPendingRow()
        assertTrue(env.repo.requiresWipe("https://new.example.com"))

        assertEquals(SaveResult.Saved, env.repo.save("https://new.example.com", "tok"))

        assertTrue(env.store.feeds.isEmpty() && env.store.episodes.isEmpty() && env.store.pending.isEmpty())
        assertFalse(file.exists())
        assertEquals("https://new.example.com", env.settings.flow.value.serverUrl)
        assertEquals("tok", env.settings.flow.value.authToken)
        // The follow-up Sync started from cursor zero.
        assertEquals(0L, (env.api as FakeApi).pullRequests.first().first)
    }

    @Test
    fun theFirstSaveWithNoPreviousUrlWipesNothing() = runBlocking {
        val env = Env(FakeApi(), FakeSettings(Settings()))
        env.store.feeds[1] = cachedFeed(1)
        assertFalse(env.repo.requiresWipe("https://new.example.com"))
        env.repo.save("https://new.example.com", "tok")
        assertEquals(1, env.store.feeds.size)
    }

    @Test
    fun saveRejectsInvalidInputAndWritesNothing() = runBlocking {
        val env = envWithCache("https://old.example.com")
        assertEquals(SaveResult.InvalidUrl(UrlError.QUERY), env.repo.save("https://h?x=1", "tok"))
        assertEquals(SaveResult.InvalidToken(TokenError.BLANK), env.repo.save("https://new.example.com", ""))
        assertEquals("https://old.example.com", env.settings.flow.value.serverUrl)
        assertEquals(1, env.store.feeds.size)
    }

    @Test
    fun theWipeWaitsForARunningSync() {
        val env = envWithCache("https://old.example.com")
        val api = env.api as FakeApi
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        api.pullHandler = { s, _ -> entered.complete(Unit); gate.await(); page(nextRev = s) }
        runBlocking {
            val running = async(Dispatchers.Default) { env.engine.sync(SyncTrigger.PERIODIC) }
            withTimeout(5_000) { entered.await() }
            val saving = async(Dispatchers.Default) { env.repo.save("https://new.example.com", "tok") }
            Thread.sleep(300)
            assertEquals("nothing is wiped while a run is in progress", 1, env.store.feeds.size)
            assertEquals("https://old.example.com", env.settings.flow.value.serverUrl)
            gate.complete(Unit)
            running.await()
            withTimeout(5_000) { saving.await() }
        }
        assertTrue(env.store.feeds.isEmpty())
        assertEquals("https://new.example.com", env.settings.flow.value.serverUrl)
    }

    private fun Env.addPendingRow() {
        store.pending[10] = com.podclatch.data.db.PendingStateWrite(10, com.podclatch.data.db.EpisodeStatus.PLAYED, 5, 1, 5)
    }
}
