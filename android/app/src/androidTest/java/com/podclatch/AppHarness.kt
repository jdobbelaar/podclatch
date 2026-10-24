package com.podclatch

import android.Manifest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.podclatch.debug.DebugEntryPoint
import com.podclatch.di.DebugApiOverride
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The harness of the Compose UI tests (US-20): the real app (real Hilt graph, Room, player, and download
 * engine) with the debug build's API seam pointing at a [FakeServerApi], and a local audio server inside the
 * test that serves a short WAV, so no host fixture and no `adb reverse` are needed. [start] resets the
 * app's saved state to a configured, empty install; [stop] removes everything the test created (Room rows,
 * downloaded files, the loaded player) and puts the HTTP client back.
 */
class AppHarness {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    private val entry =
        EntryPointAccessors.fromApplication(context.applicationContext, DebugEntryPoint::class.java)

    private var audio: MockWebServer? = null

    /** The paths the audio server was asked for, in order. */
    val audioRequests = CopyOnWriteArrayList<String>()

    val api = FakeServerApi { audio!!.url("/").toString().trimEnd('/') }

    private var scenario: ActivityScenario<MainActivity>? = null

    fun start(configured: Boolean = true) {
        grantRuntimePermissions(context.packageName)
        audioRequests.clear()
        audio = MockWebServer().also { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    audioRequests += request.url.encodedPath
                    if (request.url.encodedPath.startsWith("/missing")) return MockResponse.Builder().code(404).build()
                    val body = Buffer().write(silentWav(seconds = 90))
                    return MockResponse.Builder().code(200).addHeader("Content-Type", "audio/wav").body(body).build()
                }
            }
            server.start()
        }
        DebugApiOverride.fake = api
        runBlocking {
            entry.store().transaction { clearAll() }
            val settings = entry.settings()
            settings.saveConnection("http://fake-server.invalid", "test-token")
            settings.setLastSyncRev(0)
            settings.setDownloadAheadPerFeed(1)
            settings.setDownloadWifiOnly(false)
            settings.setNotifyNewEpisodes(false)
            if (!configured) settings.clearToken()
        }
    }

    fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    /** Closes the Activity and removes everything the test created. Safe to call when [start] failed halfway. */
    fun stop() {
        runCatching { scenario?.close() }
        scenario = null
        runBlocking {
            runCatching { entry.playback().pause() }
            for (id in api.episodeIds()) runCatching { entry.downloads().remove(id) }
            runCatching { entry.store().transaction { clearAll() } }
            runCatching { entry.settings().clearToken() }
        }
        DebugApiOverride.fake = null
        api.failure = null
        runCatching { audio?.close() }
        audio = null
        // The playback service unloads the Episode once its row is gone; wait for that exact end state.
        val deadline = System.currentTimeMillis() + 15_000
        while (entry.playback().state.value.episodeId != null && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun silentWav(seconds: Int): ByteArray {
        val data = seconds * 16_000
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        return b.array()
    }
}

/**
 * Grants the runtime permissions the app may ask for (`POST_NOTIFICATIONS`, and `ACCESS_LOCAL_NETWORK` where it
 * exists, API 37), so a test does not depend on what an earlier run left on the emulator. A fresh emulator has
 * neither granted, and the local-network rationale dialog (#55) would then block the connection form.
 */
fun grantRuntimePermissions(packageName: String) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
    if (android.os.Build.VERSION.SDK_INT >= 37) {
        automation.grantRuntimePermission(packageName, "android.permission.ACCESS_LOCAL_NETWORK")
    }
}

/** True when a node with this test tag is on the screen right now. */
fun ComposeTestRule.exists(tag: String): Boolean = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

/**
 * Waits for the exact end state [what] (no fixed sleeps). On timeout the failure names it and prints the
 * last state seen: the texts, descriptions, and tags on the screen.
 */
fun ComposeTestRule.await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
    try {
        waitUntil(timeoutMs, condition)
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("Not reached within $timeoutMs ms: $what; the screen showed: ${screenText()}")
    }
}

fun ComposeTestRule.awaitTag(tag: String, timeoutMs: Long = 30_000) = await("a node tagged $tag", timeoutMs) { exists(tag) }

fun ComposeTestRule.awaitGone(tag: String, timeoutMs: Long = 30_000) = await("no node tagged $tag", timeoutMs) { !exists(tag) }

/** The visible texts, content descriptions, and tags, depth first. Used only in failure messages. */
fun ComposeTestRule.screenText(): String = runCatching {
    val out = StringBuilder()
    fun walk(node: SemanticsNode) {
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("|")
        if (tag != null || text != null || description != null) {
            out.append("[").append(listOfNotNull(tag?.let { "tag=$it" }, text?.let { "text=$it" }, description?.let { "desc=$it" }).joinToString(" ")).append("] ")
        }
        node.children.forEach(::walk)
    }
    onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
    out.toString().take(3_000)
}.getOrElse { "unavailable (${it.javaClass.simpleName})" }
