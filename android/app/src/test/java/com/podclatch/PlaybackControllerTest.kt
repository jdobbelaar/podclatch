package com.podclatch

import android.content.Context
import android.content.ContextWrapper
import com.podclatch.playback.EpisodeMediaResolver
import com.podclatch.playback.MediaControllerPlaybackController
import com.podclatch.playback.PlayResult
import com.podclatch.playback.PlaybackPhase
import com.podclatch.playback.PlaybackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackControllerTest {
    /** A context that records whether the controller ever used it (connecting to the service needs it). */
    private class WatchedContext : ContextWrapper(null) {
        var touched = false
        override fun getPackageName(): String {
            touched = true
            return "com.podclatch"
        }
        override fun getApplicationContext(): Context {
            touched = true
            return this
        }
    }

    @Test
    fun playOfAnUnknownIdReturnsNotFoundAndNeverReachesForTheService() = runBlocking {
        val context = WatchedContext()
        val resolver = EpisodeMediaResolver({ null }, { false }, Dispatchers.Unconfined)
        val controller = MediaControllerPlaybackController(context, resolver)

        assertEquals(PlayResult.NotFound, controller.play(404))

        assertFalse("no connection to the service, so no session", context.touched)
        assertEquals(PlaybackUiState(), controller.state.value)
        assertNull(controller.state.value.episodeId)
        assertEquals(PlaybackPhase.IDLE, controller.state.value.phase)
    }

    @Test
    fun theFakeControllerBehavesLikeTheInterface() = runBlocking {
        val fake = FakePlaybackController(knownIds = setOf(1L, 2L))
        assertEquals(PlayResult.NotFound, fake.play(9))
        assertNull(fake.state.value.episodeId)

        assertEquals(PlayResult.Started, fake.play(1))
        assertEquals(1L, fake.state.value.episodeId)
        assertEquals(true, fake.state.value.isPlaying)

        fake.pause()
        assertFalse(fake.state.value.isPlaying)
        assertEquals(PlayResult.Resumed, fake.play(1))
        assertEquals(true, fake.state.value.isPlaying)

        assertEquals(PlayResult.Started, fake.play(2))
        assertEquals(2L, fake.state.value.episodeId)
        assertEquals(listOf(9L, 1L, 1L, 2L), fake.played)
    }
}
