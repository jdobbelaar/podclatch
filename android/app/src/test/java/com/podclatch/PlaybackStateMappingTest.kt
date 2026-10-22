package com.podclatch

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.podclatch.playback.PlaybackError
import com.podclatch.playback.PlaybackPhase
import com.podclatch.playback.PlaybackUiState
import com.podclatch.playback.PlayerSnapshot
import com.podclatch.playback.mapPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateMappingTest {
    private fun snapshot(
        mediaId: String? = "7",
        playbackState: Int = Player.STATE_READY,
        isPlaying: Boolean = false,
        errorCode: Int? = null,
        playerDurationMs: Long = -9223372036854775807L, // C.TIME_UNSET
        metadataDurationMs: Long? = null,
    ) = PlayerSnapshot(
        mediaId = mediaId, title = "Title", artist = "Feed", artworkUrl = "https://a.example.com/a.png",
        metadataDurationMs = metadataDurationMs, playerDurationMs = playerDurationMs, isPlaying = isPlaying,
        playbackState = playbackState, errorCode = errorCode,
    )

    @Test
    fun nothingLoadedIsTheEmptyState() {
        assertEquals(PlaybackUiState(), mapPlaybackState(snapshot(mediaId = null)))
        assertNull(mapPlaybackState(snapshot(mediaId = null)).episodeId)
    }

    @Test
    fun idleBufferingReadyAndEndedMapToTheirPhases() {
        assertEquals(PlaybackPhase.IDLE, mapPlaybackState(snapshot(playbackState = Player.STATE_IDLE)).phase)
        assertEquals(PlaybackPhase.BUFFERING, mapPlaybackState(snapshot(playbackState = Player.STATE_BUFFERING)).phase)
        assertEquals(PlaybackPhase.READY, mapPlaybackState(snapshot(playbackState = Player.STATE_READY)).phase)
        assertEquals(PlaybackPhase.ENDED, mapPlaybackState(snapshot(playbackState = Player.STATE_ENDED)).phase)
    }

    @Test
    fun playingAndPausedAreTheIsPlayingFlag() {
        assertTrue(mapPlaybackState(snapshot(isPlaying = true)).isPlaying)
        assertFalse(mapPlaybackState(snapshot(isPlaying = false)).isPlaying)
    }

    @Test
    fun carriesTheEpisodeAndItsDisplayFields() {
        val state = mapPlaybackState(snapshot())
        assertEquals(7L, state.episodeId)
        assertEquals("Title", state.title)
        assertEquals("Feed", state.feedTitle)
        assertEquals("https://a.example.com/a.png", state.artworkUrl)
    }

    @Test
    fun durationComesFromThePlayerElseFromTheFeedElseNull() {
        assertEquals(120_000L, mapPlaybackState(snapshot(playerDurationMs = 120_000, metadataDurationMs = 90_000)).durationMs)
        assertEquals(90_000L, mapPlaybackState(snapshot(metadataDurationMs = 90_000)).durationMs)
        assertNull(mapPlaybackState(snapshot()).durationMs)
    }

    @Test
    fun anErrorIsCoarseCarriesNoAddressAndIsNeverPlaying() {
        val network = mapPlaybackState(
            snapshot(playbackState = Player.STATE_IDLE, isPlaying = true, errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
        assertEquals(PlaybackError.NETWORK, network.error)
        assertFalse(network.isPlaying)
        assertEquals(PlaybackPhase.IDLE, network.phase)
        assertEquals(
            PlaybackError.UNSUPPORTED,
            mapPlaybackState(snapshot(errorCode = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)).error,
        )
        assertEquals(PlaybackError.UNKNOWN, mapPlaybackState(snapshot(errorCode = PlaybackException.ERROR_CODE_UNSPECIFIED)).error)
        assertFalse(network.toString().contains("example.com"))
        assertNull(mapPlaybackState(snapshot()).error)
    }
}
