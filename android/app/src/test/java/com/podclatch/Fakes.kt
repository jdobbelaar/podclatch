package com.podclatch

import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.CacheTransaction
import com.podclatch.data.db.Episode
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.Feed
import com.podclatch.data.db.FeedEpisode
import com.podclatch.data.db.FeedListItem
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.db.PendingStateWrite
import com.podclatch.data.db.PlaybackEpisode
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
import com.podclatch.data.repo.Settings
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.playback.PlayResult
import com.podclatch.playback.PlaybackController
import com.podclatch.playback.PlaybackPhase
import com.podclatch.playback.PlaybackUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.podclatch.domain.SpeedMode
import java.util.concurrent.atomic.AtomicInteger

const val TEST_TOKEN = "test-token"
const val TEST_URL = "https://pod.example.com"

class FakeSettings(initial: Settings = Settings(serverUrl = TEST_URL, authToken = TEST_TOKEN)) : SettingsRepository {
    val flow = MutableStateFlow(initial)
    override val settings: Flow<Settings> = flow

    override suspend fun saveConnection(serverUrl: String, authToken: String) {
        flow.value = flow.value.copy(serverUrl = serverUrl, authToken = authToken)
    }

    override suspend fun clearToken() {
        flow.value = flow.value.copy(authToken = "")
    }

    override suspend fun setLastSyncRev(rev: Long) {
        flow.value = flow.value.copy(lastSyncRev = rev)
    }

    override suspend fun setSyncIntervalHours(hours: Int) {
        flow.value = flow.value.copy(syncIntervalHours = hours)
    }

    override suspend fun setSkipIntervals(backSec: Int, forwardSec: Int) {
        flow.value = flow.value.copy(skipBackSec = backSec, skipForwardSec = forwardSec)
    }

    override suspend fun toggleSpeedMode() {
        flow.update {
            it.copy(speedMode = if (it.speedMode == SpeedMode.ASSIGNED) SpeedMode.NORMAL else SpeedMode.ASSIGNED)
        }
    }

    override suspend fun assignSpeed(speed: Float?) {
        flow.update { it.copy(assignedSpeed = speed ?: it.assignedSpeed, speedMode = SpeedMode.ASSIGNED) }
    }
}

/**
 * A hand-written in-memory stand-in for the Room store. [transaction] is all-or-nothing, a missing Feed
 * fails an Episode insert like the real foreign key, and deleting a Feed cascades to its Episodes.
 */
class FakeCacheStore : CacheStore {
    val feeds = linkedMapOf<Long, Feed>()
    val episodes = linkedMapOf<Long, Episode>()
    val pending = linkedMapOf<Long, PendingStateWrite>()
    private val counts = MutableStateFlow(0 to 0)

    /** Runs before the transaction body, for tests that change state "during" a call. */
    var failNextTransaction: Exception? = null

    private val version = MutableStateFlow(0)

    private fun publish() {
        counts.value = feeds.size to episodes.size
        version.value += 1
    }

    override suspend fun <R> transaction(block: suspend CacheTransaction.() -> R): R {
        failNextTransaction?.let { failNextTransaction = null; throw it }
        val feedsBefore = LinkedHashMap(feeds)
        val episodesBefore = LinkedHashMap(episodes)
        val pendingBefore = LinkedHashMap(pending)
        try {
            return Tx().block()
        } catch (e: Throwable) {
            feeds.clear(); feeds.putAll(feedsBefore)
            episodes.clear(); episodes.putAll(episodesBefore)
            pending.clear(); pending.putAll(pendingBefore)
            throw e
        } finally {
            publish()
        }
    }

    override fun feedCount(): Flow<Int> = counts.map { it.first }
    override fun episodeCount(): Flow<Int> = counts.map { it.second }
    override fun feedUrls(): Flow<List<String>> = version.map { feeds.values.map { f -> f.feedUrl } }.distinctUntilChanged()

    override fun observeFeedList(): Flow<List<FeedListItem>> = version.map {
        feeds.values.sortedWith(compareBy<Feed> { f -> f.priority }.thenBy { f -> f.id }).map { f ->
            FeedListItem(
                f.id, f.title, f.author, f.artworkUrl, f.priority,
                episodes.values.count { e -> e.feedId == f.id && e.status == EpisodeStatus.NEW },
            )
        }
    }.distinctUntilChanged()
    override fun observeFeed(id: Long): Flow<Feed?> = version.map { feeds[id] }.distinctUntilChanged()

    override fun observeEpisodes(feedId: Long): Flow<List<FeedEpisode>> = version.map {
        episodes.values.filter { it.feedId == feedId }
            .sortedWith(compareByDescending<Episode> { it.publishedAt }.thenByDescending { it.id })
            .map { FeedEpisode(it.id, it.title, it.publishedAt, it.durationSec, it.status, it.positionSec) }
    }.distinctUntilChanged()

    override suspend fun pendingWrites(): List<PendingStateWrite> = pending.values.sortedBy { it.episodeId }

    override suspend fun localPathsOfEpisodes(episodeIds: Collection<Long>): List<String> =
        episodeIds.mapNotNull { episodes[it]?.localPath }

    override suspend fun localPathsOfFeeds(feedIds: Collection<Long>): List<String> =
        episodes.values.filter { it.feedId in feedIds }.mapNotNull { it.localPath }

    override suspend fun allLocalPaths(): List<String> = episodes.values.mapNotNull { it.localPath }

    override suspend fun episodeForPlayback(id: Long): PlaybackEpisode? {
        val e = episodes[id] ?: return null
        val f = feeds[e.feedId] ?: return null
        return PlaybackEpisode(
            e.id, e.title, f.title, e.audioUrl, e.durationSec, e.artworkUrl, f.artworkUrl, e.downloadState, e.localPath,
        )
    }

    private inner class Tx : CacheTransaction {
        override suspend fun feedExists(id: Long) = id in feeds
        override suspend fun getEpisode(id: Long) = episodes[id]
        override suspend fun episodesOfFeed(feedId: Long) = episodes.values.filter { it.feedId == feedId }.sortedBy { it.id }
        override suspend fun allPending() = pending.values.toList()
        override suspend fun getPending(episodeId: Long) = pending[episodeId]

        override suspend fun insertFeedIfAbsent(feed: Feed) {
            feeds.putIfAbsent(feed.id, feed)
        }

        override suspend fun updateFeedSynced(feed: Feed) {
            if (feed.id in feeds) feeds[feed.id] = feed
        }

        override suspend fun insertEpisodeIfAbsent(episode: Episode) {
            check(episode.feedId in feeds) { "FOREIGN KEY constraint failed" }
            episodes.putIfAbsent(episode.id, episode)
        }

        override suspend fun updateEpisodeContent(episode: Episode) {
            val old = episodes[episode.id] ?: return
            episodes[episode.id] = old.copy(
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
        }

        override suspend fun updateEpisodeState(
            id: Long,
            status: EpisodeStatus,
            playedAt: Long?,
            positionSec: Int,
            stateUpdatedAt: Long,
        ) {
            val old = episodes[id] ?: return
            episodes[id] = old.copy(
                status = status,
                playedAt = playedAt,
                positionSec = positionSec,
                stateUpdatedAt = stateUpdatedAt,
            )
        }

        override suspend fun deleteEpisode(id: Long) {
            episodes.remove(id)
        }

        override suspend fun deleteFeed(id: Long) {
            feeds.remove(id)
            episodes.values.removeAll { it.feedId == id }
        }

        override suspend fun putPending(write: PendingStateWrite) {
            pending[write.episodeId] = write
        }

        override suspend fun deletePendingIfSame(episodeId: Long, stateUpdatedAt: Long): Boolean {
            val row = pending[episodeId] ?: return false
            if (row.stateUpdatedAt != stateUpdatedAt) return false
            pending.remove(episodeId)
            return true
        }

        override suspend fun deletePending(episodeId: Long) {
            pending.remove(episodeId)
        }

        override suspend fun clearAll() {
            pending.clear()
            episodes.clear()
            feeds.clear()
        }
    }
}

/** Records the order of calls and lets a test script each answer. */
class FakeApi : PodClatchApi {
    val calls = mutableListOf<String>()
    val pullRequests = mutableListOf<Pair<Long, Int>>()
    val pushRequests = mutableListOf<List<EpisodeStateItem>>()
    val healthTargets = mutableListOf<ServerTarget?>()

    var pushHandler: suspend (List<EpisodeStateItem>) -> List<EpisodeStateResult> = { items ->
        items.map { EpisodeStateResult(it.episodeId, StateOutcome.APPLIED, it.status, it.playedAt, it.positionSec, it.stateUpdatedAt) }
    }
    var pullHandler: suspend (Long, Int) -> SyncPageDto = { since, _ -> SyncPageDto(emptyList(), emptyList(), emptyList(), since, false) }

    val reorderRequests = mutableListOf<List<Long>>()
    val unsubscribeRequests = mutableListOf<Long>()
    var reorderHandler: suspend (List<Long>) -> List<FeedDto> = { ids -> ids.mapIndexed { i, id -> feedDto(id, priority = i) } }
    var unsubscribeHandler: suspend (Long) -> Unit = { }
    val subscribeRequests = mutableListOf<String>()
    var subscribeHandler: suspend (String) -> FeedDto = { feedDto(100, title = "New Feed") }
    val searchRequests = mutableListOf<String>()
    var searchHandler: suspend (String) -> List<SearchResultDto> = { emptyList() }
    var refreshHandler:suspend () -> List<RefreshResultDto> = { emptyList() }
    val listenOrderRequests = mutableListOf<Pair<Long, ListenOrder>>()
    val refreshFeedRequests = mutableListOf<Long>()
    var listenOrderHandler: suspend (Long, ListenOrder) -> FeedDto = { id, order -> feedDto(id).copy(listenOrder = order) }
    var refreshFeedHandler: suspend (Long) -> List<RefreshResultDto> = { id -> listOf(RefreshResultDto(id, true)) }

    private val inFlight = AtomicInteger(0)
    val maxInFlight = AtomicInteger(0)

    private suspend fun <T> tracked(block: suspend () -> T): T {
        val now = inFlight.incrementAndGet()
        maxInFlight.updateAndGet { maxOf(it, now) }
        try {
            return block()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override suspend fun health(target: ServerTarget?) {
        calls += "health"
        healthTargets += target
    }

    override suspend fun pullSync(since: Long, limit: Int, target: ServerTarget?): SyncPageDto = tracked {
        calls += "pull"
        pullRequests += since to limit
        pullHandler(since, limit)
    }

    override suspend fun reorderFeeds(feedIds: List<Long>): List<FeedDto> = tracked {
        calls += "reorder"
        reorderRequests += feedIds
        reorderHandler(feedIds)
    }

    override suspend fun unsubscribeFeed(feedId: Long) = tracked {
        calls += "unsubscribe"
        unsubscribeRequests += feedId
        unsubscribeHandler(feedId)
    }

    override suspend fun subscribeFeed(feedUrl: String): FeedDto = tracked {
        calls += "subscribe"
        subscribeRequests += feedUrl
        subscribeHandler(feedUrl)
    }

    override suspend fun searchPodcasts(q: String): List<SearchResultDto> = tracked {
        calls += "search"
        searchRequests += q
        searchHandler(q)
    }

    override suspend fun updateFeedListenOrder(feedId: Long, listenOrder: ListenOrder): FeedDto = tracked {
        calls += "listenOrder"
        listenOrderRequests += feedId to listenOrder
        listenOrderHandler(feedId, listenOrder)
    }

    override suspend fun refreshFeed(feedId: Long): List<RefreshResultDto> = tracked {
        calls += "refreshFeed"
        refreshFeedRequests += feedId
        refreshFeedHandler(feedId)
    }

    override suspend fun refreshAllFeeds(): List<RefreshResultDto> = tracked {
        calls += "refresh"
        refreshHandler()
    }

    override suspend fun pushEpisodeState(items: List<EpisodeStateItem>): List<EpisodeStateResult> = tracked {
        calls += "push"
        pushRequests += items
        pushHandler(items)
    }
}

fun feedDto(id: Long, rev: Long = id, title: String = "Feed $id", priority: Int = 0) = FeedDto(
    id = id, feedUrl = "https://feeds.example.com/$id.xml", title = title, author = "Author", description = "Desc",
    artworkUrl = "", priority = priority, listenOrder = ListenOrder.NEWEST_FIRST, lastRefreshedAt = null,
    subscribedAt = 1_000, rev = rev,
)

fun episodeDto(
    id: Long,
    feedId: Long,
    rev: Long = id,
    status: EpisodeStatus = EpisodeStatus.NEW,
    playedAt: Long? = null,
    positionSec: Int = 0,
    stateUpdatedAt: Long = 1_000,
    title: String = "Episode $id",
) = EpisodeDto(
    id = id, feedId = feedId, guid = "guid-$id", title = title, description = "", audioUrl = "https://audio.example.com/$id.mp3",
    publishedAt = 2_000, durationSec = null, artworkUrl = null, status = status, playedAt = playedAt,
    positionSec = positionSec, stateUpdatedAt = stateUpdatedAt, rev = rev,
)

fun tombstone(type: TombstoneEntityType, id: Long, rev: Long) = TombstoneDto(type, id, rev)

fun page(
    feeds: List<FeedDto> = emptyList(),
    episodes: List<EpisodeDto> = emptyList(),
    tombstones: List<TombstoneDto> = emptyList(),
    nextRev: Long,
    hasMore: Boolean = false,
) = SyncPageDto(feeds, episodes, tombstones, nextRev, hasMore)

fun cachedEpisode(
    id: Long,
    feedId: Long,
    status: EpisodeStatus = EpisodeStatus.NEW,
    playedAt: Long? = null,
    positionSec: Int = 0,
    stateUpdatedAt: Long = 1_000,
    downloadState: com.podclatch.data.db.DownloadState = com.podclatch.data.db.DownloadState.NONE,
    localPath: String? = null,
) = Episode(
    id = id, feedId = feedId, guid = "guid-$id", title = "Old title", description = "", audioUrl = "https://audio.example.com/$id.mp3",
    publishedAt = 2_000, durationSec = null, artworkUrl = null, status = status, playedAt = playedAt,
    positionSec = positionSec, stateUpdatedAt = stateUpdatedAt, rev = 1, downloadState = downloadState, localPath = localPath,
)

fun cachedFeed(id: Long) = Feed(
    id = id, feedUrl = "https://feeds.example.com/$id.xml", title = "Old", author = "", description = "", artworkUrl = "",
    priority = 0, listenOrder = ListenOrder.NEWEST_FIRST, lastRefreshedAt = null, subscribedAt = 1, rev = 1,
)

/** A stand-in for the playback service: [knownIds] play, every other id is NotFound. */
class FakePlaybackController(private val knownIds: Set<Long> = emptySet()) : PlaybackController {
    private val flow = MutableStateFlow(PlaybackUiState())
    override val state: StateFlow<PlaybackUiState> = flow

    /** Every id passed to [play], in order. */
    val played = mutableListOf<Long>()

    override suspend fun play(episodeId: Long): PlayResult {
        played += episodeId
        if (episodeId !in knownIds) return PlayResult.NotFound
        val loaded = flow.value.episodeId == episodeId
        flow.value = PlaybackUiState(
            episodeId = episodeId,
            isPlaying = true,
            phase = PlaybackPhase.READY,
            title = "Episode $episodeId",
        )
        return if (loaded) PlayResult.Resumed else PlayResult.Started
    }

    override fun pause() {
        flow.value = flow.value.copy(isPlaying = false)
    }

    override fun resume() {
        if (flow.value.episodeId != null) flow.value = flow.value.copy(isPlaying = true)
    }

    /** What [currentPositionMs] answers; a test sets it to move the playhead. */
    @Volatile var positionMs: Long = 0

    /** Every position passed to [seekTo], in order. */
    val seeks = java.util.Collections.synchronizedList(mutableListOf<Long>())
    val skipBacks = java.util.concurrent.atomic.AtomicInteger()
    val skipForwards = java.util.concurrent.atomic.AtomicInteger()

    /** Replaces the whole state, for tests that need a duration, an artwork, or an error. */
    fun setState(state: PlaybackUiState) {
        flow.value = state
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        this.positionMs = positionMs
    }

    override fun skipBack() {
        skipBacks.incrementAndGet()
    }

    override fun skipForward() {
        skipForwards.incrementAndGet()
    }

    override fun currentPositionMs(): Long = positionMs
}
