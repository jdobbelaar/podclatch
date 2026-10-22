package com.podclatch.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class ListenOrder { NEWEST_FIRST, OLDEST_FIRST }

@Serializable
enum class EpisodeStatus { NEW, PLAYED }

/** Device-only; the server knows nothing about it. */
enum class DownloadState { NONE, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }

/*
 * No unique index on Feed.priority or Feed.feedUrl: a pull can pass through states where they briefly
 * collide, and the server owns uniqueness.
 */
@Entity(tableName = "feed")
data class Feed(
    @PrimaryKey val id: Long,
    val feedUrl: String,
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String,
    val priority: Int,
    val listenOrder: ListenOrder,
    val lastRefreshedAt: Long?,
    val subscribedAt: Long,
    val rev: Long,
)

@Entity(
    tableName = "episode",
    foreignKeys = [
        ForeignKey(
            entity = Feed::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("feedId")],
)
data class Episode(
    @PrimaryKey val id: Long,
    val feedId: Long,
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val publishedAt: Long,
    val durationSec: Int?,
    val artworkUrl: String?,
    val status: EpisodeStatus,
    val playedAt: Long?,
    val positionSec: Int,
    val stateUpdatedAt: Long,
    val rev: Long,
    // Device-only columns: a pull must never write these.
    @ColumnInfo(defaultValue = "NONE") val downloadState: DownloadState = DownloadState.NONE,
    @ColumnInfo(defaultValue = "NULL") val localPath: String? = null,
)

/** The Sync outbox: one row per Episode, the latest local write wins. */
@Entity(tableName = "pending_state_write")
data class PendingStateWrite(
    @PrimaryKey val episodeId: Long,
    val status: EpisodeStatus,
    val playedAt: Long?,
    val positionSec: Int,
    val stateUpdatedAt: Long,
)

/**
 * What playback needs to know about one Episode, joined with its Feed. [toString] leaves out every
 * address: an Episode's own `toString` would print `audioUrl`.
 */
data class PlaybackEpisode(
    val id: Long,
    val title: String,
    val feedTitle: String,
    val audioUrl: String,
    val durationSec: Int?,
    val artworkUrl: String?,
    val feedArtworkUrl: String,
    val downloadState: DownloadState,
    val localPath: String?,
) {
    override fun toString() = "PlaybackEpisode(id=$id, downloadState=$downloadState)"
}

/** The state columns of one Episode, for the playback recorder; it leaves out every address. */
data class EpisodeProgress(
    val status: EpisodeStatus,
    val playedAt: Long?,
    val positionSec: Int,
    val stateUpdatedAt: Long,
)

/**
 * One row of Feed Detail: the visible columns of an Episode. It leaves out the audio address and the
 * description, so a long list stays small and nothing here can print an address.
 */
data class FeedEpisode(
    val id: Long,
    val title: String,
    val publishedAt: Long,
    val durationSec: Int?,
    val status: EpisodeStatus,
    val positionSec: Int,
) {
    override fun toString() = "FeedEpisode(id=$id, status=$status)"
}

/** One bar of the Feeds tab: the visible columns of a Feed plus its live unplayed count. */
data class FeedListItem(
    val id: Long,
    val title: String,
    val author: String,
    val artworkUrl: String,
    val priority: Int,
    val unplayedCount: Int,
)
