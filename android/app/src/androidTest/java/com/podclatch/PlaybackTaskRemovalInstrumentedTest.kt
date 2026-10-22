package com.podclatch

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.Episode
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.Feed
import com.podclatch.data.db.ListenOrder
import com.podclatch.debug.DebugEntryPoint
import com.podclatch.playback.PlayResult
import com.podclatch.playback.PlaybackController
import com.podclatch.playback.PlaybackService
import com.podclatch.playback.PlaybackUiState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression test for "swipe the app out of recents while paused": the service must stop AND the app's
 * own `MediaController` binding must be released, so the service is destroyed and its notification goes.
 * (Before the fix `stopSelf()` ran but the singleton controller kept the service bound, so the service
 * record, the session, and the notification outlived the task.) Runs the real service, the real
 * controller, and the real Room store; the audio comes from an in-process MockWebServer.
 */
class PlaybackTaskRemovalInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var server: MockWebServer
    private lateinit var entry: DebugEntryPoint
    private val episodeId = 987_654L
    private val feedId = 987_653L

    private val playback: PlaybackController get() = entry.playback()

    @Before
    fun setUp() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).addHeader("Content-Type", "audio/wav").body(Buffer().write(silentWav(30))).build()
        }
        server.start()
        entry = EntryPointAccessors.fromApplication(context.applicationContext, DebugEntryPoint::class.java)
        runBlocking {
            entry.store().transaction {
                insertFeedIfAbsent(
                    Feed(feedId, "https://feeds.example.com/t.xml", "Removal Feed", "", "", "", 0, ListenOrder.NEWEST_FIRST, null, 1, 1),
                )
                insertEpisodeIfAbsent(
                    Episode(
                        episodeId, feedId, "g", "Removal Episode", "", server.url("/silence.wav").toString(), 2_000, 30, null,
                        EpisodeStatus.NEW, null, 0, 1_000, 1, DownloadState.NONE, null,
                    ),
                )
            }
        }
    }

    @After
    fun tearDown() {
        playback.pause()
        runBlocking { entry.store().transaction { deleteFeed(feedId) } }
        server.close()
    }

    private fun <T> await(what: String, timeoutMs: Long = 10_000, block: () -> T?): T = runBlocking {
        val result = withTimeoutOrNull(timeoutMs) {
            var v = block()
            while (v == null) {
                delay(20)
                v = block()
            }
            v
        }
        result ?: throw AssertionError("Not reached within $timeoutMs ms: $what")
    }

    private fun awaitState(what: String, condition: (PlaybackUiState) -> Boolean): PlaybackUiState = runBlocking {
        withTimeoutOrNull(10_000) { playback.state.first(condition) }
            ?: throw AssertionError("Not reached within 10000 ms: $what; last state ${playback.state.value}")
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        return am.getRunningServices(100).any { it.service.className == PlaybackService::class.java.name }
    }

    private fun ownNotifications(): Int = context.getSystemService(NotificationManager::class.java).activeNotifications.size

    @Test
    fun taskRemovalWhilePausedStopsTheServiceReleasesTheBindingAndCancelsTheNotification() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertStarted(runBlocking { playback.play(episodeId) })
            awaitState("playing the episode") { it.episodeId == episodeId && it.isPlaying }
            await("service running and notification posted") { if (serviceRunning() && ownNotifications() == 1) true else null }

            playback.pause()
            awaitState("paused") { it.episodeId == episodeId && !it.isPlaying }

            removeAllTasks()

            await("PlaybackService gone from the running services after task removal") { if (!serviceRunning()) true else null }
            await("notification cancelled after task removal") { if (ownNotifications() == 0) true else null }
            awaitState("controller state cleared (binding released)") { it.episodeId == null }
        }
        // Reconnecting afterwards works: the next play starts a fresh service and session.
        assertStarted(runBlocking { playback.play(episodeId) })
        awaitState("playing again after the service stopped") { it.episodeId == episodeId && it.isPlaying }
        assertTrue(serviceRunning())
    }

    @Test
    fun taskRemovalWhilePlayingKeepsPlayingAndKeepsTheNotification() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertStarted(runBlocking { playback.play(episodeId) })
            awaitState("playing the episode") { it.episodeId == episodeId && it.isPlaying }
            await("notification posted") { if (ownNotifications() == 1) true else null }

            removeAllTasks()

            // Nothing may change; wait for the task to be gone, then check the end state.
            await("tasks removed") { if (appTasks().isEmpty()) true else null }
            assertTrue(playback.state.value.isPlaying)
            assertTrue(serviceRunning())
            assertEquals(1, ownNotifications())
        }
    }

    /** The controller is a process singleton, so an earlier test may have left this Episode loaded. */
    private fun assertStarted(result: PlayResult) {
        assertTrue("play returned $result", result == PlayResult.Started || result == PlayResult.Resumed)
    }

    private fun appTasks() = context.getSystemService(ActivityManager::class.java).appTasks

    private fun removeAllTasks() {
        appTasks().forEach { it.finishAndRemoveTask() }
    }

    /** A valid 8 kHz mono 16-bit PCM WAV of silence. */
    private fun silentWav(seconds: Int): ByteArray {
        val data = seconds * 16_000
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        return b.array()
    }
}
