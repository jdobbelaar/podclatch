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
