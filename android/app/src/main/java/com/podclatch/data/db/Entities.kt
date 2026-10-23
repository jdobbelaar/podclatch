package com.podclatch.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.podclatch.domain.QueueKey
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
    /** Bytes of the audio received so far; a cache of the partial file's size, written about once a second. */
    @ColumnInfo(defaultValue = "0") val bytesReceived: Long = 0,
    /** The size the publisher announced, or null when unknown. */
    val totalBytes: Long? = null,
    /** Set by a 404 or 410: only a manual download clears it, an automatic one never retries. */
    @ColumnInfo(defaultValue = "0") val downloadPermanentFailure: Boolean = false,
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

/** What the download engine needs to know about one Episode. [toString] leaves out the audio address. */
data class DownloadRow(
    val id: Long,
    val downloadState: DownloadState,
    val localPath: String?,
    val audioUrl: String,
    val bytesReceived: Long,
    val totalBytes: Long?,
    val downloadPermanentFailure: Boolean,
) {
    override fun toString() = "DownloadRow(id=$id, downloadState=$downloadState)"
}

/** The download columns of one Episode, for a progress bar; it leaves out every address. */
data class DownloadProgress(
    val downloadState: DownloadState,
    val bytesReceived: Long,
    val totalBytes: Long?,
    val downloadPermanentFailure: Boolean,
)

/** An Episode that has, or is getting, a download: its state and where the final file is. */
data class DownloadEntry(
    val id: Long,
    val downloadState: DownloadState,
    val localPath: String?,
)

/**
 * One Queue row: an Episode joined with its Feed, with the columns that order it and the ones the Queue
 * screen shows. [toString] leaves out the artwork address.
 */
data class QueueRow(
    val episodeId: Long,
    val feedId: Long,
    val feedPriority: Int,
    val listenOrder: ListenOrder,
    val publishedAt: Long,
    val title: String,
    val feedTitle: String,
    val feedArtworkUrl: String,
    val durationSec: Int?,
    val positionSec: Int,
    val downloadState: DownloadState,
    val bytesReceived: Long,
    val totalBytes: Long?,
) {
    val key: QueueKey get() = QueueKey(episodeId, feedId, feedPriority, listenOrder, publishedAt)

    override fun toString() = "QueueRow(episodeId=$episodeId, downloadState=$downloadState)"
}
