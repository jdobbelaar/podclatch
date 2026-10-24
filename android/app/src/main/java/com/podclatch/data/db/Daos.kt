package com.podclatch.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * Deliberately no REPLACE and no full-entity @Upsert on Feed or Episode: either would wipe the
 * device-only columns, and REPLACE on a Feed would cascade-delete its Episodes. A pull uses
 * insert-if-absent plus an UPDATE of the synced columns only.
 */

@Dao
interface FeedDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(feed: Feed): Long

    @Query(
        "UPDATE feed SET feedUrl = :feedUrl, title = :title, author = :author, description = :description, " +
            "artworkUrl = :artworkUrl, priority = :priority, listenOrder = :listenOrder, " +
            "lastRefreshedAt = :lastRefreshedAt, subscribedAt = :subscribedAt, rev = :rev WHERE id = :id",
    )
    suspend fun updateSynced(
        id: Long,
        feedUrl: String,
        title: String,
        author: String,
        description: String,
        artworkUrl: String,
        priority: Int,
        listenOrder: ListenOrder,
        lastRefreshedAt: Long?,
        subscribedAt: Long,
        rev: Long,
    )

    @Query("DELETE FROM feed WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM feed WHERE id = :id)")
    suspend fun exists(id: Long): Boolean

    @Query("SELECT id, title FROM feed WHERE id IN (:ids)")
    suspend fun titlesByIds(ids: List<Long>): List<FeedTitle>

    @Query("SELECT COUNT(*) FROM feed")
    fun count(): Flow<Int>

    /** The address of every cached Feed, for the Subscribe view's "Subscribed" marker. */
    @Query("SELECT feedUrl FROM feed")
    fun feedUrls(): Flow<List<String>>

    /** One Feed for Feed Detail; emits null when it is not (or no longer) cached. */
    @Query("SELECT * FROM feed WHERE id = :id")
    fun observeFeed(id: Long): Flow<Feed?>

    /** The Feeds tab: per Feed the count of NEW Episodes (downloaded or not), in server order. */
    @Query(
        "SELECT f.id AS id, f.title AS title, f.author AS author, f.artworkUrl AS artworkUrl, " +
            "f.priority AS priority, " +
            "(SELECT COUNT(*) FROM episode e WHERE e.feedId = f.id AND e.status = 'NEW') AS unplayedCount " +
            "FROM feed f ORDER BY f.priority ASC, f.id ASC",
    )
    fun observeFeedList(): Flow<List<FeedListItem>>

    @Query("DELETE FROM feed")
    suspend fun deleteAll()
}

/** The columns of a [QueueRow]; the WHERE and ORDER BY are added by each query. */
private const val QUEUE_SELECT =
    "SELECT e.id AS episodeId, e.feedId AS feedId, f.priority AS feedPriority, f.listenOrder AS listenOrder, " +
        "e.publishedAt AS publishedAt, e.title AS title, f.title AS feedTitle, f.artworkUrl AS feedArtworkUrl, " +
        "e.durationSec AS durationSec, e.positionSec AS positionSec, e.downloadState AS downloadState, " +
        "e.bytesReceived AS bytesReceived, e.totalBytes AS totalBytes " +
        "FROM episode e INNER JOIN feed f ON f.id = e.feedId "

/**
 * The Queue (spec 5.3): New Episodes that are downloaded or downloading. Order: Feed Priority ascending,
 * then inside a Feed its Listen Order (`NEWEST_FIRST` is `publishedAt` then `id` descending, `OLDEST_FIRST`
 * the exact reverse). The same order as `QueueOrder` in `domain/`.
 */
private const val QUEUE_FILTER = "WHERE e.status = 'NEW' AND e.downloadState IN ('DOWNLOADED', 'DOWNLOADING') "

private const val QUEUE_ORDER =
    "ORDER BY f.priority ASC, f.id ASC, " +
        "CASE WHEN f.listenOrder = 'NEWEST_FIRST' THEN -e.publishedAt ELSE e.publishedAt END ASC, " +
        "CASE WHEN f.listenOrder = 'NEWEST_FIRST' THEN -e.id ELSE e.id END ASC"

/** The columns of a [BrowseRow]; the WHERE is added by each query (the order is applied in Kotlin by `QueueOrder`). */
private const val BROWSE_SELECT =
    "SELECT e.id AS episodeId, e.feedId AS feedId, f.priority AS feedPriority, f.listenOrder AS listenOrder, " +
        "e.publishedAt AS publishedAt, e.title AS title, f.title AS feedTitle, e.artworkUrl AS artworkUrl, " +
        "f.artworkUrl AS feedArtworkUrl, e.durationSec AS durationSec " +
        "FROM episode e INNER JOIN feed f ON f.id = e.feedId "

@Dao
interface EpisodeDao {
    /** The Queue in the shape of the browse tree (no order is promised; the caller sorts). */
    @Query(BROWSE_SELECT + QUEUE_FILTER)
    suspend fun browseQueue(): List<BrowseRow>

    /** Every New Episode of one Feed (no order is promised; the caller sorts and caps). */
    @Query(BROWSE_SELECT + "WHERE e.status = 'NEW' AND e.feedId = :feedId")
    suspend fun browseFeedEpisodes(feedId: Long): List<BrowseRow>

    /** New Episodes with saved progress, for resumption. */
    @Query("SELECT id AS episodeId, status, positionSec, stateUpdatedAt FROM episode WHERE status = 'NEW' AND positionSec > 0")
    suspend fun inProgressRows(): List<ResumeRow>

    @Query("SELECT id AS episodeId, feedId FROM episode WHERE status = 'NEW'")
    fun observeNewEpisodeRefs(): Flow<List<NewEpisodeRef>>

    @Query(QUEUE_SELECT + QUEUE_FILTER + QUEUE_ORDER)
    fun observeQueue(): Flow<List<QueueRow>>

    @Query(QUEUE_SELECT + QUEUE_FILTER + QUEUE_ORDER)
    suspend fun queue(): List<QueueRow>

    /** One Episode in the shape of a Queue row, whether or not it is in the Queue; null for an unknown id. */
    @Query(QUEUE_SELECT + "WHERE e.id = :id")
    suspend fun queueRowOf(id: Long): QueueRow?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(episode: Episode): Long

    @Query(
        "UPDATE episode SET feedId = :feedId, guid = :guid, title = :title, description = :description, " +
            "audioUrl = :audioUrl, publishedAt = :publishedAt, durationSec = :durationSec, " +
            "artworkUrl = :artworkUrl, rev = :rev WHERE id = :id",
    )
    suspend fun updateContent(
        id: Long,
        feedId: Long,
        guid: String,
        title: String,
        description: String,
        audioUrl: String,
        publishedAt: Long,
        durationSec: Int?,
        artworkUrl: String?,
        rev: Long,
    )

    @Query(
        "UPDATE episode SET status = :status, playedAt = :playedAt, positionSec = :positionSec, " +
            "stateUpdatedAt = :stateUpdatedAt WHERE id = :id",
    )
    suspend fun updateState(id: Long, status: EpisodeStatus, playedAt: Long?, positionSec: Int, stateUpdatedAt: Long)

    @Query("SELECT * FROM episode WHERE id = :id")
    suspend fun getById(id: Long): Episode?

    /** The state columns of one Episode; emits null when it is not cached. Emits again on any change to them. */
    @Query("SELECT status, playedAt, positionSec, stateUpdatedAt FROM episode WHERE id = :id")
    fun observeProgress(id: Long): Flow<EpisodeProgress?>

    /** The Episode joined with its Feed's title and artwork, or null for an unknown id. */
    @Query(
        "SELECT e.id AS id, e.title AS title, f.title AS feedTitle, e.audioUrl AS audioUrl, " +
            "e.durationSec AS durationSec, e.artworkUrl AS artworkUrl, f.artworkUrl AS feedArtworkUrl, " +
            "e.downloadState AS downloadState, e.localPath AS localPath " +
            "FROM episode e INNER JOIN feed f ON f.id = e.feedId WHERE e.id = :id",
    )
    suspend fun episodeForPlayback(id: Long): PlaybackEpisode?

    /**
     * Feed Detail's rows, newest first (`publishedAt`, then `id`, descending). Oldest first is the exact
     * reverse, applied by the screen's logic. Emits again whenever any of the rows changes.
     */
    @Query(
        "SELECT id, title, publishedAt, durationSec, status, positionSec, downloadState, downloadPermanentFailure FROM episode " +
            "WHERE feedId = :feedId ORDER BY publishedAt DESC, id DESC",
    )
    fun observeEpisodes(feedId: Long): Flow<List<FeedEpisode>>

    /** Every Episode of the Feed, for Mark all. */
    @Query("SELECT * FROM episode WHERE feedId = :feedId ORDER BY id")
    suspend fun episodesOfFeed(feedId: Long): List<Episode>

    @Query("DELETE FROM episode WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM episode WHERE feedId = :feedId")
    suspend fun deleteByFeed(feedId: Long)

    /** Every New Episode of every Feed with the columns the top-up decides on (no order is promised). */
    @Query(
        "SELECT e.id AS episodeId, e.feedId AS feedId, f.priority AS feedPriority, f.listenOrder AS listenOrder, " +
            "e.publishedAt AS publishedAt, e.downloadState AS downloadState, " +
            "e.downloadPermanentFailure AS downloadPermanentFailure " +
            "FROM episode e JOIN feed f ON f.id = e.feedId WHERE e.status = 'NEW'",
    )
    suspend fun topUpRows(): List<TopUpRow>

    /** Every Played Episode that still has a download of any kind (the auto-delete sweep decides which to remove). */
    @Query(
        "SELECT id AS episodeId, playedAt, downloadState, localPath FROM episode " +
            "WHERE status = 'PLAYED' AND (downloadState != 'NONE' OR localPath IS NOT NULL)",
    )
    suspend fun sweepRows(): List<SweepRow>

    @Query("SELECT localPath FROM episode WHERE id IN (:ids) AND localPath IS NOT NULL")
    suspend fun localPathsByIds(ids: List<Long>): List<String>

    @Query("SELECT localPath FROM episode WHERE feedId IN (:feedIds) AND localPath IS NOT NULL")
    suspend fun localPathsByFeedIds(feedIds: List<Long>): List<String>

    @Query("SELECT localPath FROM episode WHERE localPath IS NOT NULL")
    suspend fun allLocalPaths(): List<String>

    @Query("SELECT id FROM episode WHERE feedId IN (:feedIds)")
    suspend fun idsByFeedIds(feedIds: List<Long>): List<Long>

    @Query("SELECT COUNT(*) FROM episode")
    fun count(): Flow<Int>

    @Query("DELETE FROM episode")
    suspend fun deleteAll()

    // ---- Downloads. Every write is an UPDATE of an existing row, so a download never re-creates a removed Episode. ----

    @Query(
        "SELECT id, downloadState, localPath, audioUrl, bytesReceived, totalBytes, downloadPermanentFailure " +
            "FROM episode WHERE id = :id",
    )
    suspend fun downloadRow(id: Long): DownloadRow?

    /** Emits null when the Episode is not (or no longer) cached. */
    @Query("SELECT downloadState, bytesReceived, totalBytes, downloadPermanentFailure FROM episode WHERE id = :id")
    fun observeDownload(id: Long): Flow<DownloadProgress?>

    @Query("SELECT id, downloadState, localPath FROM episode WHERE downloadState IN ('QUEUED', 'DOWNLOADING', 'DOWNLOADED')")
    suspend fun downloadEntries(): List<DownloadEntry>

    @Query(
        "UPDATE episode SET downloadState = :state, " +
            "downloadPermanentFailure = CASE WHEN :clearPermanent THEN 0 ELSE downloadPermanentFailure END WHERE id = :id",
    )
    suspend fun requestDownload(id: Long, state: DownloadState, clearPermanent: Boolean): Int

    @Query("UPDATE episode SET downloadState = 'DOWNLOADING' WHERE id = :id AND downloadState IN ('QUEUED', 'DOWNLOADING')")
    suspend fun markDownloading(id: Long): Int

    @Query(
        "UPDATE episode SET bytesReceived = :received, totalBytes = :total " +
            "WHERE id = :id AND downloadState = 'DOWNLOADING'",
    )
    suspend fun saveDownloadProgress(id: Long, received: Long, total: Long?): Int

    @Query(
        "UPDATE episode SET downloadState = 'DOWNLOADED', localPath = :path, bytesReceived = :bytes, totalBytes = :bytes, " +
            "downloadPermanentFailure = 0 WHERE id = :id AND downloadState IN ('QUEUED', 'DOWNLOADING')",
    )
    suspend fun markDownloaded(id: Long, path: String, bytes: Long): Int

    @Query(
        "UPDATE episode SET downloadState = 'FAILED', bytesReceived = :received, totalBytes = :total, " +
            "downloadPermanentFailure = :permanent WHERE id = :id AND downloadState IN ('QUEUED', 'DOWNLOADING')",
    )
    suspend fun markDownloadFailed(id: Long, permanent: Boolean, received: Long, total: Long?): Int

    @Query(
        "UPDATE episode SET downloadState = 'NONE', localPath = NULL, bytesReceived = 0, totalBytes = NULL WHERE id = :id",
    )
    suspend fun resetDownload(id: Long): Int
}

@Dao
interface PendingStateWriteDao {
    @Query("SELECT * FROM pending_state_write ORDER BY episodeId")
    suspend fun getAll(): List<PendingStateWrite>

    @Query("SELECT * FROM pending_state_write WHERE episodeId = :episodeId")
    suspend fun getById(episodeId: Long): PendingStateWrite?

    /** REPLACE is right here: the outbox keeps one row per Episode and the latest write wins. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(write: PendingStateWrite)

    /** Compare-and-delete: removes the row only if it still holds the `stateUpdatedAt` that was sent. */
    @Query("DELETE FROM pending_state_write WHERE episodeId = :episodeId AND stateUpdatedAt = :stateUpdatedAt")
    suspend fun deleteIfSame(episodeId: Long, stateUpdatedAt: Long): Int

    @Query("DELETE FROM pending_state_write WHERE episodeId = :episodeId")
    suspend fun deleteById(episodeId: Long)

    @Query("DELETE FROM pending_state_write")
    suspend fun deleteAll()
}
