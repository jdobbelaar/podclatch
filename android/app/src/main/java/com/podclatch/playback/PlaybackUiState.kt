package com.podclatch.playback

import androidx.media3.common.Player

enum class PlaybackPhase { IDLE, BUFFERING, READY, ENDED }

/** Why playback failed, in coarse terms. Never carries an address. */
enum class PlaybackError { NETWORK, UNSUPPORTED, UNKNOWN }

/**
 * What the UI knows about playback (shared decision 1). [episodeId] is null when nothing is loaded.
 * [toString] leaves out the artwork address.
 */
data class PlaybackUiState(
    val episodeId: Long? = null,
    val isPlaying: Boolean = false,
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val title: String = "",
    val feedTitle: String = "",
    val artworkUrl: String? = null,
    /** From the media container when the player knows it, else the feed's duration; null when unknown. */
    val durationMs: Long? = null,
    val error: PlaybackError? = null,
) {
    override fun toString() =
        "PlaybackUiState(episodeId=$episodeId, isPlaying=$isPlaying, phase=$phase, error=$error)"
}

sealed interface PlayResult {
    /** The Episode was loaded and playback was requested. */
    data object Started : PlayResult

    /** The Episode was already loaded: it continues where it is. */
    data object Resumed : PlayResult

    /** No such Episode; nothing was started and no session was created. */
    data object NotFound : PlayResult

    /** The playback service could not be reached. */
    data object Unavailable : PlayResult
}

/** The flags of the player, as the plain values the mapping needs. */
data class PlayerSnapshot(
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val artworkUrl: String?,
    val metadataDurationMs: Long?,
    /** The player's own duration, [androidx.media3.common.C.TIME_UNSET] or a negative value when unknown. */
    val playerDurationMs: Long,
    val isPlaying: Boolean,
    val playbackState: Int,
    /** A [PlaybackException] error code, null when there is no error. */
    val errorCode: Int?,
)

fun mapPlaybackState(s: PlayerSnapshot): PlaybackUiState {
    val episodeId = s.mediaId?.toLongOrNull() ?: return PlaybackUiState()
    val phase = when (s.playbackState) {
        Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
        Player.STATE_READY -> PlaybackPhase.READY
        Player.STATE_ENDED -> PlaybackPhase.ENDED
        else -> PlaybackPhase.IDLE
    }
    return PlaybackUiState(
        episodeId = episodeId,
        isPlaying = s.isPlaying && s.errorCode == null,
        phase = phase,
        title = s.title.orEmpty(),
        feedTitle = s.artist.orEmpty(),
        artworkUrl = s.artworkUrl,
        durationMs = s.playerDurationMs.takeIf { it > 0 } ?: s.metadataDurationMs?.takeIf { it > 0 },
        error = s.errorCode?.let(::errorOf),
    )
}

private fun errorOf(code: Int): PlaybackError = when (code) {
    in 2000..2999 -> PlaybackError.NETWORK
    in 3000..4999 -> PlaybackError.UNSUPPORTED
    else -> PlaybackError.UNKNOWN
}
