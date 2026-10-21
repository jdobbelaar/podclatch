package com.podclatch.data.db

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The small primitive operations the Sync engine and repositories need. The rules (what a pull may
 * overwrite, how a pending write competes with the server) live in the callers, so a hand-written
 * in-memory fake can stand in for Room in JVM tests.
 */
interface CacheStore {
    /** Runs [block] atomically: if it throws, nothing it did is kept. */
    suspend fun <R> transaction(block: suspend CacheTransaction.() -> R): R

    fun feedCount(): Flow<Int>
    fun episodeCount(): Flow<Int>

    /** The address of every cached Feed (order not defined); emits again whenever a Feed is added or removed. */
    fun feedUrls(): Flow<List<String>>

    /** Feeds in server order (priority, then id) with their unplayed counts; emits only on a visible change. */
    fun observeFeedList(): Flow<List<FeedListItem>>

    suspend fun pendingWrites(): List<PendingStateWrite>
    suspend fun localPathsOfEpisodes(episodeIds: Collection<Long>): List<String>
    suspend fun localPathsOfFeeds(feedIds: Collection<Long>): List<String>
    suspend fun allLocalPaths(): List<String>
}

interface CacheTransaction {
    suspend fun feedExists(id: Long): Boolean
    suspend fun getEpisode(id: Long): Episode?
    suspend fun allPending(): List<PendingStateWrite>
    suspend fun getPending(episodeId: Long): PendingStateWrite?

    /** Writes a new row (device-only columns take their defaults); does nothing if the id exists. */
    suspend fun insertFeedIfAbsent(feed: Feed)

    /** Sets only the synced columns of an existing row. */
    suspend fun updateFeedSynced(feed: Feed)
    suspend fun insertEpisodeIfAbsent(episode: Episode)

    /** Sets the synced content columns (not the state columns, not the device-only columns). */
    suspend fun updateEpisodeContent(episode: Episode)
    suspend fun updateEpisodeState(id: Long, status: EpisodeStatus, playedAt: Long?, positionSec: Int, stateUpdatedAt: Long)

    suspend fun deleteEpisode(id: Long)

    /** Deletes the Feed and its Episodes. */
    suspend fun deleteFeed(id: Long)

    suspend fun putPending(write: PendingStateWrite)

    /** Deletes the outbox row only if it still has [stateUpdatedAt]; returns whether it did. */
    suspend fun deletePendingIfSame(episodeId: Long, stateUpdatedAt: Long): Boolean
    suspend fun deletePending(episodeId: Long)

    /** Removes every Feed, Episode, and outbox row. */
    suspend fun clearAll()
}

@Singleton
class RoomCacheStore @Inject constructor(private val db: PodClatchDatabase) : CacheStore {
    private val feeds = db.feedDao()
    private val episodes = db.episodeDao()
    private val pending = db.pendingStateWriteDao()

    override suspend fun <R> transaction(block: suspend CacheTransaction.() -> R): R =
        db.withTransaction { RoomTransaction().block() }

    override fun feedCount(): Flow<Int> = feeds.count()
    override fun episodeCount(): Flow<Int> = episodes.count()
    override fun feedUrls(): Flow<List<String>> = feeds.feedUrls()
    override fun observeFeedList(): Flow<List<FeedListItem>> = feeds.observeFeedList().distinctUntilChanged()
    override suspend fun pendingWrites(): List<PendingStateWrite> = pending.getAll()

    override suspend fun localPathsOfEpisodes(episodeIds: Collection<Long>): List<String> =
        episodeIds.chunked(SQL_VARIABLE_CHUNK).flatMap { episodes.localPathsByIds(it) }

    override suspend fun localPathsOfFeeds(feedIds: Collection<Long>): List<String> =
        feedIds.chunked(SQL_VARIABLE_CHUNK).flatMap { episodes.localPathsByFeedIds(it) }

    override suspend fun allLocalPaths(): List<String> = episodes.allLocalPaths()

    private inner class RoomTransaction : CacheTransaction {
        override suspend fun feedExists(id: Long) = feeds.exists(id)
        override suspend fun getEpisode(id: Long) = episodes.getById(id)
        override suspend fun allPending() = pending.getAll()
        override suspend fun getPending(episodeId: Long) = pending.getById(episodeId)

        override suspend fun insertFeedIfAbsent(feed: Feed) {
            feeds.insertIfAbsent(feed)
        }

        override suspend fun updateFeedSynced(feed: Feed) = feeds.updateSynced(
            id = feed.id,
            feedUrl = feed.feedUrl,
            title = feed.title,
            author = feed.author,
            description = feed.description,
            artworkUrl = feed.artworkUrl,
            priority = feed.priority,
            listenOrder = feed.listenOrder,
            lastRefreshedAt = feed.lastRefreshedAt,
            subscribedAt = feed.subscribedAt,
            rev = feed.rev,
        )

        override suspend fun insertEpisodeIfAbsent(episode: Episode) {
            episodes.insertIfAbsent(episode)
        }

        override suspend fun updateEpisodeContent(episode: Episode) = episodes.updateContent(
            id = episode.id,
            feedId = episode.feedId,
            guid = episode.guid,
            title = episode.title,
            description = episode.description,
            audioUrl = episode.audioUrl,
            publishedAt = episode.publishedAt,
            durationSec = episode.durationSec,
            artworkUrl = episode.artworkUrl,
            rev = episode.rev,
        )

        override suspend fun updateEpisodeState(
            id: Long,
            status: EpisodeStatus,
            playedAt: Long?,
            positionSec: Int,
            stateUpdatedAt: Long,
        ) = episodes.updateState(id, status, playedAt, positionSec, stateUpdatedAt)

        override suspend fun deleteEpisode(id: Long) = episodes.deleteById(id)

        override suspend fun deleteFeed(id: Long) {
            // The foreign key cascades too; deleting the Episodes first does not depend on it.
            episodes.deleteByFeed(id)
            feeds.deleteById(id)
        }

        override suspend fun putPending(write: PendingStateWrite) = pending.put(write)

        override suspend fun deletePendingIfSame(episodeId: Long, stateUpdatedAt: Long) =
            pending.deleteIfSame(episodeId, stateUpdatedAt) > 0

        override suspend fun deletePending(episodeId: Long) = pending.deleteById(episodeId)

        override suspend fun clearAll() {
            pending.deleteAll()
            episodes.deleteAll()
            feeds.deleteAll()
        }
    }

    private companion object {
        /** Older SQLite builds allow only 999 bound variables per statement. */
        const val SQL_VARIABLE_CHUNK = 500
    }
}
