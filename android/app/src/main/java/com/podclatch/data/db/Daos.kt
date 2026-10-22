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

@Dao
interface EpisodeDao {
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
        "SELECT id, title, publishedAt, durationSec, status, positionSec FROM episode " +
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

    @Query("SELECT localPath FROM episode WHERE id IN (:ids) AND localPath IS NOT NULL")
    suspend fun localPathsByIds(ids: List<Long>): List<String>

    @Query("SELECT localPath FROM episode WHERE feedId IN (:feedIds) AND localPath IS NOT NULL")
    suspend fun localPathsByFeedIds(feedIds: List<Long>): List<String>

    @Query("SELECT localPath FROM episode WHERE localPath IS NOT NULL")
    suspend fun allLocalPaths(): List<String>

    @Query("SELECT COUNT(*) FROM episode")
    fun count(): Flow<Int>

    @Query("DELETE FROM episode")
    suspend fun deleteAll()
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
