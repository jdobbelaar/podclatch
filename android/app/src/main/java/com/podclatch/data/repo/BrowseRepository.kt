package com.podclatch.data.repo

import com.podclatch.data.db.BrowseRow
import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.FeedListItem
import com.podclatch.data.db.ResumeRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the Android Auto browse tree and resumption read, from the Room cache only. It has no network
 * dependency: the tree never calls the PodClatch Server. The flows are change signals for the tree's
 * "children changed" notifications; they emit again only when something the tree shows has changed.
 */
@Singleton
class BrowseRepository @Inject constructor(private val store: CacheStore) {
    /** The Queue's rows; the caller sorts them with `QueueOrder`. */
    suspend fun queue(): List<BrowseRow> = store.browseQueue()

    /** Every Feed in Feed Priority order (ties by Feed id). */
    suspend fun feeds(): List<FeedListItem> = store.observeFeedList().first()

    /** Every New Episode of the Feed; the caller sorts and caps. Empty for a Feed that does not exist. */
    suspend fun newEpisodesOfFeed(feedId: Long): List<BrowseRow> = store.browseFeedEpisodes(feedId)

    /** New Episodes with saved progress, for resumption. */
    suspend fun inProgress(): List<ResumeRow> = store.inProgressRows()

    /** Changes with the Queue's members and what a Queue row shows. */
    fun queueSignature(): Flow<Any> =
        store.observeQueue().map { rows -> rows.map { it.episodeId to it.title } }.distinctUntilChanged()

    /** Changes with the list of Feeds and what a Feed row shows (not with the unplayed counts). */
    fun feedsSignature(): Flow<Any> = store.observeFeedList()
        .map { items -> items.map { listOf(it.id, it.title, it.author, it.artworkUrl) } }
        .distinctUntilChanged()

    /** Per Feed, the ids of its New Episodes; a Feed without New Episodes has no entry. */
    fun newEpisodesByFeed(): Flow<Map<Long, List<Long>>> = store.observeNewEpisodeRefs()
        .map { refs -> refs.groupBy({ it.feedId }, { it.episodeId }).mapValues { (_, ids) -> ids.sorted() } }
        .distinctUntilChanged()
}
