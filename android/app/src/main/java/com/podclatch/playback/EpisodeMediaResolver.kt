package com.podclatch.playback

import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.PlaybackEpisode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Where the audio of an Episode comes from. [toString] leaves out the address. */
sealed interface AudioSource {
    /** A finished download: the absolute path of the file. */
    data class LocalFile(val path: String) : AudioSource {
        override fun toString() = "LocalFile"
    }

    /** The publisher's address. */
    data class Stream(val url: String) : AudioSource {
        override fun toString() = "Stream"
    }
}

/** An Episode ready to hand to the player. [toString] leaves out every address. */
data class ResolvedEpisode(
    val mediaId: String,
    val source: AudioSource,
    val title: String,
    val feedTitle: String,
    /** The Episode's artwork, else the Feed's; null when both are blank. */
    val artworkUrl: String?,
    val durationMs: Long?,
) {
    override fun toString() = "ResolvedEpisode(mediaId=$mediaId, source=$source)"
}

/**
 * Turns an Episode id into what the player needs (shared decisions 2 and 3). The data lookup and the file
 * check are parameters so that a plain unit test can drive it. The file is played only if the Episode is
 * `DOWNLOADED`, has a `localPath`, and the file exists and is not empty; every other case streams.
 */
class EpisodeMediaResolver(
    private val lookup: suspend (Long) -> PlaybackEpisode?,
    private val fileUsable: (String) -> Boolean,
    private val fileDispatcher: CoroutineDispatcher,
) {
    suspend fun resolve(episodeId: Long): ResolvedEpisode? {
        val episode = lookup(episodeId) ?: return null
        val file = episode.localPath?.takeIf { path ->
            episode.downloadState == DownloadState.DOWNLOADED && withContext(fileDispatcher) { fileUsable(path) }
        }
        return ResolvedEpisode(
            mediaId = episode.id.toString(),
            source = if (file != null) AudioSource.LocalFile(file) else AudioSource.Stream(episode.audioUrl),
            title = episode.title,
            feedTitle = episode.feedTitle,
            artworkUrl = episode.artworkUrl?.takeIf { it.isNotBlank() } ?: episode.feedArtworkUrl.takeIf { it.isNotBlank() },
            durationMs = episode.durationSec?.let { it * 1000L },
        )
    }
}
