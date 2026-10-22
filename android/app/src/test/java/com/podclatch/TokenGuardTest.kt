package com.podclatch

import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.CleartextRefusedException
import com.podclatch.data.network.OkHttpPodClatchApi
import com.podclatch.data.network.TokenGuardInterceptor
import com.podclatch.data.network.tokenMayBeSent
import com.podclatch.data.repo.ConnectionRepository
import com.podclatch.data.repo.ConnectionTestResult
import com.podclatch.data.repo.Settings
import com.podclatch.data.repo.TestStage
import com.podclatch.data.repo.toTestResult
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.FailureReason
import com.podclatch.domain.ServerUrlValidator
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncResult
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.TokenRejection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/** The token guard (`tokenMayBeSent` and its network interceptor) and the refusal it produces. */
class TokenGuardTest {
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

    private fun may(url: String, debug: Boolean) = tokenMayBeSent(url.toHttpUrl(), debug)

    // ---- the pure function ----

    @Test
    fun httpsAnywherePasses() {
        assertTrue(may("https://example.com/", debug = false))
        assertTrue(may("https://192.168.1.20:8443/", debug = false))
        assertTrue(may("https://localhost.example.com/", debug = false))
    }

    @Test
    fun loopbackAndTheEmulatorHostPassOverHttp() {
        assertTrue(may("http://localhost:8180/", debug = false))
        assertTrue(may("http://127.0.0.1:8180/", debug = false))
        assertTrue(may("http://10.0.2.2:8180/", debug = false))
    }

    @Test
    fun anyOtherHostFailsOverHttpInRelease() {
        assertFalse(may("http://192.168.1.20:8180/", debug = false))
        assertFalse(may("http://example.com/", debug = false))
        assertFalse(may("http://localhost.example.com/", debug = false))
        assertFalse(may("http://127.0.0.1.example.com/", debug = false))
    }

    @Test
    fun everythingPassesInDebug() {
        assertTrue(may("http://192.168.1.20:8180/", debug = true))
        assertTrue(may("http://example.com/", debug = true))
        assertTrue(may("http://localhost.example.com/", debug = true))
    }

    // ---- the interceptor, on a real socket ----

    /** Maps `publisher.test` (and only it) to the loopback address MockWebServer listens on. */
    private val dns = Dns { host ->
        if (host == "publisher.test") listOf(InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(host)
    }

    private fun client(debug: Boolean) =
        OkHttpClient.Builder().dns(dns).addNetworkInterceptor(TokenGuardInterceptor(debug)).build()

    private fun get(client: OkHttpClient, url: String, auth: Boolean): Int {
        val request = Request.Builder().url(url).apply { if (auth) header("Authorization", "Bearer $TEST_TOKEN") }.build()
        client.newCall(request).execute().use { return it.code }
    }

    private fun publisher() = "http://publisher.test:${server.port}/"
    private fun loopback() = "http://127.0.0.1:${server.port}/"

    @Test
    fun releaseRefusesAnAuthenticatedHttpRequestToAnotherHostAndSendsNothing() {
        server.enqueue(MockResponse.Builder().code(200).build())
        try {
            get(client(debug = false), publisher(), auth = true)
            fail("expected the guard to refuse")
        } catch (e: CleartextRefusedException) {
            assertFalse(e.message.orEmpty().contains(TEST_TOKEN))
            assertFalse(e.message.orEmpty().contains("publisher"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun releaseSendsTheSameRequestWithoutAuthorization() {
        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(200, get(client(debug = false), publisher(), auth = false))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun releaseSendsAnAuthenticatedRequestToLoopback() {
        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(200, get(client(debug = false), loopback(), auth = true))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun debugSendsEverything() {
        server.enqueue(MockResponse.Builder().code(200).build())
        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(200, get(client(debug = true), publisher(), auth = true))
        assertEquals(200, get(client(debug = true), publisher(), auth = false))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRedirectToCleartextOnAnotherHostIsCheckedOnEveryHop() {
        // The first hop (loopback) may carry the token; the redirect target may not.
        server.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", publisher()).build(),
        )
        server.enqueue(MockResponse.Builder().code(200).build())
        val redirecting = OkHttpClient.Builder().dns(dns).followRedirects(true)
            .addNetworkInterceptor(object : okhttp3.Interceptor {
                // Redirects drop nothing here: re-add the header on every hop to prove the guard sees each one.
                override fun intercept(chain: okhttp3.Interceptor.Chain) =
                    chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $TEST_TOKEN").build())
            })
            .addNetworkInterceptor(TokenGuardInterceptor(isDebuggable = false))
            .build()
        try {
            get(redirecting, loopback(), auth = true)
            fail("expected the guard to refuse the redirect hop")
        } catch (_: CleartextRefusedException) {
            // expected
        }
        assertEquals(1, server.requestCount)
    }

    // ---- the refusal as an ApiError, and what Sync and Test connection show ----

    private val json = Json { ignoreUnknownKeys = true }

    private fun apiFor(settings: FakeSettings, debug: Boolean = false) = OkHttpPodClatchApi(client(debug), json, settings)

    @Test
    fun theApiMapsTheRefusalToCleartextRefusedNotUnreachable() = runBlocking {
        val settings = FakeSettings(Settings(serverUrl = publisher().trimEnd('/'), authToken = TEST_TOKEN))
        try {
            apiFor(settings).pullSync(0, 10, null)
            fail("expected an ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiError.CleartextRefused, e.error)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theRefusalMessageNamesNeitherAnAddressNorTheToken() {
        val message = ApiError.CleartextRefused.message
        assertTrue(message.contains("https://"))
        assertTrue(message.contains("unencrypted"))
        assertFalse(message.contains(TEST_TOKEN))
        assertFalse(message.contains("publisher"))
    }

    @Test
    fun syncWithAnUnencryptedServerAddressFailsWithTheRefusalAndSendsNothing() = runBlocking {
        val settings = FakeSettings(Settings(serverUrl = publisher().trimEnd('/'), authToken = TEST_TOKEN))
        val engine = SyncEngine(
            apiFor(settings), settings, FakeCacheStore(), { }, { 0 }, TokenRejection(settings), DiskFileDeleter(),
            Dispatchers.Unconfined,
        )
        val result = engine.sync(SyncTrigger.MANUAL)
        val reason = (result as SyncResult.Failed).reason
        assertEquals(FailureReason.Api(ApiError.CleartextRefused), reason)
        assertEquals(ApiError.CleartextRefused.message, reason.message)
        assertEquals(0, server.requestCount)
        assertEquals(TEST_TOKEN, settings.flow.value.authToken)
    }

    @Test
    fun testConnectionEndsInTheRefusalAfterTheUnauthenticatedHealthCheck() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"status":"ok"}""").build())
        val settings = FakeSettings(Settings())
        val store = FakeCacheStore()
        val api = apiFor(settings)
        val rejection = TokenRejection(settings)
        val engine = SyncEngine(api, settings, store, { }, { 0 }, rejection, DiskFileDeleter(), Dispatchers.Unconfined)
        // A debug validator accepts the address, like a server address saved by an older build.
        val repo = ConnectionRepository(
            api, settings, store, engine, rejection, DiskFileDeleter(), ServerUrlValidator(isDebuggable = true),
            Dispatchers.Unconfined, CoroutineScope(Dispatchers.Unconfined),
        )
        val result = repo.test(publisher().trimEnd('/'), TEST_TOKEN)
        assertEquals(ConnectionTestResult.CleartextRefused, result)
        // Only /healthz (no Authorization) reached the server; the authenticated call sent nothing.
        assertEquals(1, server.requestCount)
        assertEquals("/healthz", server.takeRequest().url.encodedPath)
        assertEquals(ConnectionTestResult.CleartextRefused, ApiError.CleartextRefused.toTestResult(TestStage.AUTHENTICATED))
        assertEquals(ConnectionTestResult.CleartextRefused, ApiError.CleartextRefused.toTestResult(TestStage.HEALTH))
    }
}
