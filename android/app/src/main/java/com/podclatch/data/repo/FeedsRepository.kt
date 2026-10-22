package com.podclatch.data.repo

import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.Feed
import com.podclatch.data.db.FeedEpisode
import com.podclatch.data.db.FeedListItem
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.PodClatchApi
import com.podclatch.data.network.RefreshResultDto
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncResult
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.TokenRejection
import com.podclatch.domain.toFeed
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of a server action. A failure carries the [ApiError] (which never holds the token). */
sealed interface FeedsResult<out T> {
    data class Ok<T>(val value: T) : FeedsResult<T>
    data class Failed(val error: ApiError) : FeedsResult<Nothing>
}

/** The per-Feed results of `POST /v1/refresh` together with the Sync that followed. */
data class RefreshOutcome(val results: List<RefreshResultDto>, val sync: SyncResult)

/**
 * The Feeds tab's door to the data layer: the list from the Room cache, and the three server actions
 * that change it (reorder, unsubscribe, Refresh). Every action that succeeds on the server is followed by
 * a Sync, which brings the cache in line (including the Tombstone of an unsubscribed Feed).
 */
@Singleton
class FeedsRepository @Inject constructor(
    private val api: PodClatchApi,
    private val store: CacheStore,
    private val engine: SyncEngine,
    private val tokenRejection: TokenRejection,
) {
    /** The Feeds in server order with their unplayed counts; changes by Sync or an action show up here. */
    val feeds: Flow<List<FeedListItem>> get() = store.observeFeedList()

    /**
     * Sends [orderedIds] (every subscribed Feed once, highest priority first). On success the returned
     * Feeds' priorities are written to Room under the Sync lock, then a Sync runs; the value is that Sync's
     * result, whose failure is not a reorder failure. On failure nothing in Room changes, except that a
     * 422 (a stale list) is followed by a Sync so the list catches up.
     */
    suspend fun reorder(orderedIds: List<Long>): FeedsResult<SyncResult> {
        val feeds = when (val r = call { api.reorderFeeds(orderedIds) }) {
            is FeedsResult.Ok -> r.value
            is FeedsResult.Failed -> {
                if ((r.error as? ApiError.Http)?.code == 422) engine.sync(SyncTrigger.MANUAL)
                return r
            }
        }
        // Under the Sync lock, so a pull that was already running cannot overwrite the new priorities
        // with older data afterwards. Only Feeds already cached are updated; nothing is inserted.
        engine.exclusive {
            store.transaction {
                for (dto in feeds) if (feedExists(dto.id)) updateFeedSynced(dto.toFeed())
            }
        }
        return FeedsResult.Ok(engine.sync(SyncTrigger.MANUAL))
    }

    /**
     * Deletes the Feed on the server, then runs a Sync; the Feed leaves Room only when Sync applies its
     * Tombstone. A 404 (already gone) counts as success. The value is the follow-up Sync's result.
     */
    suspend fun unsubscribe(feedId: Long): FeedsResult<SyncResult> {
        when (val r = call { api.unsubscribeFeed(feedId) }) {
            is FeedsResult.Ok -> Unit
            is FeedsResult.Failed -> if ((r.error as? ApiError.Http)?.code != 404) return r
        }
        return FeedsResult.Ok(engine.sync(SyncTrigger.MANUAL))
    }

    /** One Feed from the Room cache for Feed Detail; null when it is not (or no longer) cached. */
    fun observeFeed(feedId: Long): Flow<Feed?> = store.observeFeed(feedId)

    /** The Feed's Episodes from the Room cache, newest first; Listen Order is applied by the screen. */
    fun observeEpisodes(feedId: Long): Flow<List<FeedEpisode>> = store.observeEpisodes(feedId)

    /**
     * Sends the new Listen Order for a Feed. On success the returned Feed is written to Room under the
     * Sync lock (so a running pull cannot overwrite it), then a Sync runs; the value is that Sync's
     * result, whose failure is not a Listen Order failure. On failure nothing in Room changes, except
     * that a 404 (the Feed is gone on the server) is followed by a Sync so the Feed leaves the cache.
     */
    suspend fun updateListenOrder(feedId: Long, listenOrder: ListenOrder): FeedsResult<SyncResult> {
        val feed = when (val r = call { api.updateFeedListenOrder(feedId, listenOrder) }) {
            is FeedsResult.Ok -> r.value
            is FeedsResult.Failed -> {
                if (r.isNotFound()) engine.sync(SyncTrigger.MANUAL)
                return r
            }
        }
        engine.exclusive {
            store.transaction { if (feedExists(feed.id)) updateFeedSynced(feed.toFeed()) }
        }
        return FeedsResult.Ok(engine.sync(SyncTrigger.MANUAL))
    }

    /**
     * Refreshes one Feed on the server, then always syncs (the Feed may have new Episodes even when
     * its result says it failed). If the call itself fails, no Sync runs, except after a 404 so the Feed
     * leaves the cache.
     */
    suspend fun refreshFeed(feedId: Long): FeedsResult<RefreshOutcome> {
        val results = when (val r = call { api.refreshFeed(feedId) }) {
            is FeedsResult.Ok -> r.value
            is FeedsResult.Failed -> {
                if (r.isNotFound()) engine.sync(SyncTrigger.MANUAL)
                return r
            }
        }
        return FeedsResult.Ok(RefreshOutcome(results, engine.sync(SyncTrigger.MANUAL)))
    }

    private fun FeedsResult.Failed.isNotFound() = (error as? ApiError.Http)?.code == 404

    /**
     * Refreshes every Feed on the server, then always syncs (some Feeds may have new Episodes even if
     * others failed). If the call itself fails, no Sync runs.
     */
    suspend fun refreshAll(): FeedsResult<RefreshOutcome> {
        val results = when (val r = call { api.refreshAllFeeds() }) {
            is FeedsResult.Ok -> r.value
            is FeedsResult.Failed -> return r
        }
        return FeedsResult.Ok(RefreshOutcome(results, engine.sync(SyncTrigger.MANUAL)))
    }

    private suspend fun <T> call(block: suspend () -> T): FeedsResult<T> = try {
        FeedsResult.Ok(block())
    } catch (e: ApiException) {
        if (e.error is ApiError.Unauthorized) {
            try {
                tokenRejection.onTokenRejected()
            } catch (_: Exception) {
                // Still reported as Unauthorized.
            }
        }
        FeedsResult.Failed(e.error)
    }
}
