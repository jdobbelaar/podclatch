package com.podclatch.playback

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi

/**
 * The Media3 item for a browse row. An Episode is an id-only item (no address): the service resolves it
 * from Room when it is played, exactly as for a tap on the phone. Nothing here is HTML.
 */
@OptIn(UnstableApi::class)
internal fun BrowseItem.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setArtist(artist)
        .setArtworkUri(artworkUrl?.toUri())
        .setDurationMs(durationMs)
        .setIsBrowsable(browsable)
        .setIsPlayable(playable)
        .setMediaType(
            when (kind) {
                BrowseKind.FOLDER -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                BrowseKind.FEED -> MediaMetadata.MEDIA_TYPE_PODCAST
                BrowseKind.EPISODE -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
            },
        )
        .build()
    return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build()
}
