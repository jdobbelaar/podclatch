package com.podclatch

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.podclatch.data.db.DownloadState
import com.podclatch.debug.DebugEntryPoint
import com.podclatch.di.DebugApiOverride
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The end-to-end test (US-32): the real app (real Hilt graph, Room, player, and download engine, no fakes)
 * against the real PodClatch Server from a Compose stack, with a fake feed host and an audio fixture. It runs
 * the one journey the spec names: connect, subscribe, play from Feed Detail, see the Episode in the Queue
 * with its download finished, mark it Played, and read the server's own answer.
 *
 * The stack is started by `make test-e2e` (android/tools/e2e.py), which passes these instrumentation
 * arguments: `serverUrl`, `token`, `feedUrl` (the address the server fetches), and `audioBase`. Without all
 * four the test skips itself with an assumption, so a plain `connectedDebugAndroidTest` is unaffected.
 *
 * Every wait is for an exact end state, with a timeout. A failure names the step, the last visible screen
 * text, and the last HTTP status from the server, with the token and every address removed ([E2eReport]).
 */
class EndToEndTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val serverUrl = arguments.getString("serverUrl").orEmpty()
    private val token = arguments.getString("token").orEmpty()
    private val feedUrl = arguments.getString("feedUrl").orEmpty()
    private val audioBase = arguments.getString("audioBase").orEmpty()

    private var scenario: ActivityScenario<MainActivity>? = null
    private var entry: DebugEntryPoint? = null
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    @Volatile
    private var step = "setup"

    @Volatile
    private var lastStatus = "no request yet"

    @Before
    fun setUp() {
        assumeTrue(
            "no stack parameters (serverUrl, token, feedUrl, audioBase): run `make test-e2e`",
            listOf(serverUrl, token, feedUrl, audioBase).all { it.isNotEmpty() },
        )
        val context = instrumentation.targetContext
        entry = EntryPointAccessors.fromApplication(context.applicationContext, DebugEntryPoint::class.java)
        grantRuntimePermissions(context.packageName)
        DebugApiOverride.fake = null // the real HTTP API, not the debug seam's fake
        // A cleared app: no Feed, Episode, or token, so the first screen is the connection form.
        runBlocking<Unit> {
            entry!!.store().transaction { clearAll() }
            val settings = entry!!.settings()
            settings.clearToken()
            settings.setLastSyncRev(0)
            settings.setDownloadAheadPerFeed(1)
            settings.setDownloadWifiOnly(false)
            settings.setNotifyNewEpisodes(false)
        }
    }

    @After
    fun tearDown() {
        val entry = entry ?: return // skipped: nothing was started
        runCatching { scenario?.close() }
        scenario = null
        runBlocking<Unit> {
            runCatching { entry.playback().pause() }
            runCatching {
                val ids = entry.store().queue().map { it.episodeId }
                for (id in ids) runCatching { entry.downloads().remove(id) }
            }
            runCatching { entry.store().transaction { clearAll() } }
            runCatching { entry.settings().clearToken() }
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (entry.playback().state.value.episodeId != null && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    @Test
    fun subscribeDownloadPlayQueueMarkPlayedAndTheServerKnows() {
        try {
            journey()
        } catch (e: Throwable) {
            val screen = compose.screenText()
            throw AssertionError(
                E2eReport.build(step, e, lastStatus, screen, listOf(token, serverUrl, feedUrl, audioBase)),
            )
        }
    }

    private fun journey() {
        // Step 1: the app starts unconfigured, so the connection screen shows; connect and save.
        step = "1 connect: enter the server address and token, Test connection, Save"
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.awaitTag("server_url", 60_000)
        compose.onNodeWithTag("server_url").performTextInput(serverUrl)
        compose.onNodeWithTag("auth_token").performTextInput(token)
        compose.onNodeWithTag("test_connection").performClick()
        compose.await("Save is enabled after the connection test passed", 60_000) {
            compose.onAllNodes(hasTestTag("save_continue") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("save_continue").performClick()
        compose.await("the tabs show after saving", 60_000) {
            if (compose.exists("confirm_switch")) compose.onNodeWithTag("confirm_switch").performClick()
            compose.exists("tab_queue")
        }
        compose.awaitTag("queue_empty")

        // Step 2: subscribe by pasting the feed address; the Feed appears on the Feeds tab.
        step = "2 subscribe: paste the feed address in the Subscribe view"
        compose.onNodeWithTag("tab_feeds").performClick()
        compose.awaitTag("subscribe_fab")
        compose.onNodeWithTag("subscribe_fab").performClick()
        compose.awaitTag("subscribe_url_field")
        compose.onNodeWithTag("subscribe_url_field").performTextInput(feedUrl)
        compose.onNodeWithTag("subscribe_url_button").performClick()
        val feedId = awaitServer("the server lists the new Feed", 60_000) {
            serverFeedIds().singleOrNull()
        }
        compose.awaitTag("feed_row_$feedId", 60_000)

        // Step 3: open the Feed and tap the oldest Episode (the top-up downloads only the newest); playback
        // starts and the Episode's download starts. Pause at once so the Episode cannot play to its end and
        // mark itself Played before step 5 does it on purpose.
        step = "3 play: open the Feed and tap an Episode"
        val episode = awaitServer("the server lists the Feed's Episodes", 60_000) {
            serverEpisodes(feedId).firstOrNull { it.title == OLDEST_TITLE }
        }
        val id = episode.id
        compose.onNodeWithTag("feed_row_$feedId").performClick()
        compose.awaitTag("episode_row_$id", 60_000)
        compose.onNodeWithTag("episode_row_$id").performClick()
        compose.await("the mini player shows $OLDEST_TITLE", 60_000) {
            compose.onAllNodes(hasTestTag("mini_player") and hasText(OLDEST_TITLE)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.await("the Episode is playing", 60_000) {
            entry!!.playback().state.value.let { it.episodeId == id && it.isPlaying }
        }
        compose.onNodeWithTag("mini_play_pause").performClick()
        compose.await("playback is paused", 30_000) { !entry!!.playback().state.value.isPlaying }

        // Step 4: the Queue tab lists that Episode, and its download reaches DOWNLOADED (the row loses its bar).
        step = "4 queue and download: the Queue lists the Episode and its download finishes"
        compose.onNodeWithTag("nav_back").performClick()
        compose.awaitTag("tab_queue")
        compose.onNodeWithTag("tab_queue").performClick()
        compose.awaitTag("queue_item_$id", 60_000)
        compose.await("the download of the Episode is DOWNLOADED", 120_000) {
            runBlocking { entry!!.store().queueRowOf(id)?.downloadState } == DownloadState.DOWNLOADED
        }
        compose.awaitGone("queue_download_$id", 30_000)
        compose.awaitTag("queue_item_$id")

        // Step 5: mark the Episode Played from its menu in Feed Detail.
        step = "5 mark Played: use the Episode's menu in Feed Detail"
        compose.onNodeWithTag("tab_feeds").performClick()
        compose.awaitTag("feed_row_$feedId")
        compose.onNodeWithTag("feed_row_$feedId").performClick()
        compose.awaitTag("episode_menu_$id", 60_000)
        compose.onNodeWithTag("episode_menu_$id").performClick()
        compose.awaitTag("menu_toggle_played_$id")
        compose.onNodeWithTag("menu_toggle_played_$id").performClick()
        compose.await("the app shows the Episode as Played", 30_000) {
            compose.onAllNodes(hasTestTag("episode_status_$id") and hasAnyDescendant(hasText("Played")))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Step 6: the server's own answer is the final proof.
        step = "6 server state: the Episode is PLAYED on the server with playedAt set"
        val played = awaitServer("the server reports the Episode as PLAYED with playedAt", 60_000) {
            serverEpisodes(feedId).firstOrNull { it.id == id && it.status == "PLAYED" && it.playedAt != null }
        }
        check(played.id == id)
    }

    // ---- the server's HTTP API, called directly with the bearer token ----

    private class ServerEpisode(val id: Long, val title: String, val status: String, val playedAt: Long?)

    private fun get(path: String): JSONArray? {
        val request = Request.Builder().url(serverUrl.trimEnd('/') + path).header("Authorization", "Bearer $token").build()
        return try {
            http.newCall(request).execute().use { response ->
                lastStatus = "GET ${path.substringBefore('?')} -> HTTP ${response.code}"
                if (response.isSuccessful) JSONArray(response.body.string()) else null
            }
        } catch (e: java.io.IOException) {
            lastStatus = "GET ${path.substringBefore('?')} -> ${e.javaClass.simpleName}"
            null
        }
    }

    private fun serverFeedIds(): List<Long> {
        val feeds = get("/v1/feeds") ?: return emptyList()
        return (0 until feeds.length()).map { feeds.getJSONObject(it).getLong("id") }
    }

    private fun serverEpisodes(feedId: Long): List<ServerEpisode> {
        val episodes = get("/v1/feeds/$feedId/episodes") ?: return emptyList()
        return (0 until episodes.length()).map {
            val e = episodes.getJSONObject(it)
            ServerEpisode(
                id = e.getLong("id"),
                title = e.getString("title"),
                status = e.getString("status"),
                playedAt = if (e.isNull("playedAt")) null else e.getLong("playedAt"),
            )
        }
    }

    /** Polls the server (and lets the UI settle between tries) until [read] returns a value. */
    private fun <T : Any> awaitServer(what: String, timeoutMs: Long, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            compose.waitForIdle()
            Thread.sleep(500)
        }
        throw AssertionError("Not reached within $timeoutMs ms: $what")
    }

    private companion object {
        const val OLDEST_TITLE = "E2E Episode One"
    }
}

/** Builds the failure text of the end-to-end test: the step, the cause, the last HTTP status, and the screen. */
object E2eReport {
    private val address = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""")

    fun build(step: String, cause: Throwable, lastStatus: String, screen: String, secrets: List<String>): String {
        val text = "End-to-end test failed at step $step. Cause: ${cause.message ?: cause.javaClass.simpleName}. " +
            "Last server HTTP status: $lastStatus. The screen showed: $screen"
        return redact(text, secrets)
    }

    /** Removes every secret and every address (a URL carries the host and may carry a query string). */
    fun redact(text: String, secrets: List<String>): String {
        var out = text
        for (secret in secrets.filter { it.isNotEmpty() }) out = out.replace(secret, "***")
        return address.replace(out, "<address>")
    }
}
