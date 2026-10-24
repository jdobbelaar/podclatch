package com.podclatch

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.network.EpisodeDto
import com.podclatch.data.network.EpisodeStateItem
import com.podclatch.data.network.EpisodeStateResult
import com.podclatch.data.network.FeedDto
import com.podclatch.data.network.PodClatchApi
import com.podclatch.data.network.RefreshResultDto
import com.podclatch.data.network.SearchResultDto
import com.podclatch.data.network.ServerTarget
import com.podclatch.data.network.StateOutcome
import com.podclatch.data.network.SyncPageDto
import com.podclatch.data.network.TombstoneDto
import com.podclatch.data.network.TombstoneEntityType
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-memory [PodClatchApi] for the UI tests (US-20): the whole server in a few lists, with a revision
 * counter like the real one. It never touches the network. Subscribing to any address creates one Feed
 * with three Episodes whose audio addresses point at [audioBase] (the test's local audio server).
 *
 * Feed N has Episodes N*10+1 (the oldest) to N*10+3 (the newest), titled "Episode One" to "Episode Three".
 */
class FakeServerApi(private val audioBase: () -> String) : PodClatchApi {
    private val lock = Any()
    private var rev = 0L
    private var nextFeedId = 1L
    private val feeds = mutableListOf<FeedDto>()
    private val episodes = mutableListOf<EpisodeDto>()
    private val tombstones = mutableListOf<TombstoneDto>()

    /** Every call by name, for assertions ("subscribe", "pull", "push", "refresh", "refreshFeed", ...). */
    val calls = CopyOnWriteArrayList<String>()

    /** When true, Episodes created from now on point at an audio address that answers 404. */
    @Volatile var brokenAudio = false

    /** When set, every call fails with it (a stopped server). */
    @Volatile var failure: Throwable? = null

    private fun enter(name: String) {
        calls += name
        failure?.let { throw it }
    }

    /** Subscribes server-side without a call from the app, as if another device did; returns the Feed id. */
    fun seedFeed(title: String = "Smoke Feed"): Long = synchronized(lock) { addFeed("https://feeds.example.com/$title.xml", title).id }

    fun episodeIds(): List<Long> = synchronized(lock) { episodes.map { it.id } }

    fun feedOrder(): List<Long> = synchronized(lock) { feeds.sortedBy { it.priority }.map { it.id } }

    private fun addFeed(url: String, title: String): FeedDto {
        val id = nextFeedId++
        val feed = FeedDto(
            id = id, feedUrl = url, title = title, author = "Fake Author", description = "A feed for the UI tests.",
            artworkUrl = "", priority = feeds.size, listenOrder = ListenOrder.NEWEST_FIRST, lastRefreshedAt = 1_000,
            subscribedAt = 1_000, rev = ++rev,
        )
        feeds += feed
        val names = listOf("One", "Two", "Three")
        names.forEachIndexed { i, name ->
            val eid = id * 10 + 1 + i
            episodes += EpisodeDto(
                id = eid, feedId = id, guid = "guid-$eid", title = "Episode $name", description = "", audioUrl = "${audioBase()}${if (brokenAudio) "/missing" else ""}/e$eid.wav",
                publishedAt = 1_760_000_000_000L + i * 86_400_000L, durationSec = 90, artworkUrl = null, status = EpisodeStatus.NEW, playedAt = null,
                positionSec = 0, stateUpdatedAt = 1_000, rev = ++rev,
            )
        }
        return feed
    }

    override suspend fun health(target: ServerTarget?) = enter("health")

    override suspend fun pullSync(since: Long, limit: Int, target: ServerTarget?): SyncPageDto {
        enter("pull")
        return synchronized(lock) {
            SyncPageDto(
                feeds = feeds.filter { it.rev > since },
                episodes = episodes.filter { it.rev > since },
                tombstones = tombstones.filter { it.rev > since },
                nextRev = rev,
                hasMore = false,
            )
        }
    }

    override suspend fun pushEpisodeState(items: List<EpisodeStateItem>): List<EpisodeStateResult> {
        enter("push")
        return synchronized(lock) {
            items.map { item ->
                val index = episodes.indexOfFirst { it.id == item.episodeId }
                if (index < 0) {
                    EpisodeStateResult(item.episodeId, StateOutcome.UNKNOWN_EPISODE)
                } else {
                    episodes[index] = episodes[index].copy(
                        status = item.status, playedAt = item.playedAt, positionSec = item.positionSec,
                        stateUpdatedAt = item.stateUpdatedAt, rev = ++rev,
                    )
                    EpisodeStateResult(item.episodeId, StateOutcome.APPLIED, item.status, item.playedAt, item.positionSec, item.stateUpdatedAt)
                }
            }
        }
    }

    override suspend fun reorderFeeds(feedIds: List<Long>): List<FeedDto> {
        enter("reorder")
        return synchronized(lock) {
            feedIds.forEachIndexed { priority, id ->
                val i = feeds.indexOfFirst { it.id == id }
                if (i >= 0) feeds[i] = feeds[i].copy(priority = priority, rev = ++rev)
            }
            feeds.sortedBy { it.priority }
        }
    }

    override suspend fun unsubscribeFeed(feedId: Long) {
        enter("unsubscribe")
        synchronized(lock) {
            feeds.removeAll { it.id == feedId }
            val gone = episodes.filter { it.feedId == feedId }
            episodes.removeAll(gone.toSet())
            tombstones += TombstoneDto(TombstoneEntityType.FEED, feedId, ++rev)
            gone.forEach { tombstones += TombstoneDto(TombstoneEntityType.EPISODE, it.id, ++rev) }
        }
    }

    override suspend fun subscribeFeed(feedUrl: String): FeedDto {
        enter("subscribe")
        return synchronized(lock) { addFeed(feedUrl, "Smoke Feed") }
    }

    override suspend fun searchPodcasts(q: String): List<SearchResultDto> {
        enter("search")
        return listOf(SearchResultDto("https://feeds.example.com/found.xml", "Found Podcast", "Some Author", ""))
    }

    override suspend fun updateFeedListenOrder(feedId: Long, listenOrder: ListenOrder): FeedDto {
        enter("listenOrder")
        return synchronized(lock) {
            val i = feeds.indexOfFirst { it.id == feedId }
            feeds[i] = feeds[i].copy(listenOrder = listenOrder, rev = ++rev)
            feeds[i]
        }
    }

    override suspend fun refreshFeed(feedId: Long): List<RefreshResultDto> {
        enter("refreshFeed")
        return listOf(RefreshResultDto(feedId, true))
    }

    override suspend fun refreshAllFeeds(): List<RefreshResultDto> {
        enter("refresh")
        return synchronized(lock) { feeds.map { RefreshResultDto(it.id, true) } }
    }
}
