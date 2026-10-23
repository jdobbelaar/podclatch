package com.podclatch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.Episode
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.Feed
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.db.PodClatchDatabase
import com.podclatch.data.db.QueueRow
import com.podclatch.data.db.RoomCacheStore
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.domain.sortQueue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The Queue query against real SQLite (an in-memory database): who is in it, and in which order. */
class QueueQueryInstrumentedTest {
    private lateinit var db: PodClatchDatabase
    private lateinit var store: RoomCacheStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, PodClatchDatabase::class.java).build()
        store = RoomCacheStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insert(feeds: List<Feed>, episodes: List<Episode>) = runBlocking {
        store.transaction {
            feeds.forEach { insertFeedIfAbsent(it) }
            episodes.forEach { insertEpisodeIfAbsent(it) }
        }
    }

    private fun ids(rows: List<QueueRow>) = rows.map { it.episodeId }

    private suspend fun awaitQueue(what: String, condition: (List<QueueRow>) -> Boolean): List<QueueRow> =
        try {
            withTimeout(5_000) { store.observeQueue().first(condition) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Not reached within 5000 ms: $what")
        }

    @Test
    fun theQueueHoldsExactlyTheNewEpisodesThatAreDownloadedOrDownloading() = runBlocking<Unit> {
        // One Episode for every combination of status and download state; id = 100 * status + state.
        val feed = feed(1)
        val episodes = buildList {
            for ((s, status) in EpisodeStatus.entries.withIndex()) {
                for ((d, state) in DownloadState.entries.withIndex()) {
                    add(episode(100L * (s + 1) + d, 1, status = status, downloadState = state, publishedAt = 1_000L + d))
                }
            }
        }
        insert(listOf(feed), episodes)
        val expected = episodes
            .filter { it.status == EpisodeStatus.NEW && (it.downloadState == DownloadState.DOWNLOADED || it.downloadState == DownloadState.DOWNLOADING) }
            .map { it.id }
            .toSet()
        assertEquals(2, expected.size)
        val rows = store.observeQueue().first()
        assertEquals(expected, ids(rows).toSet())
        assertEquals(ids(rows), ids(store.queue()))
    }

    @Test
    fun feedPriorityComesFirstThenEachFeedsListenOrder() = runBlocking<Unit> {
        val newest = feed(1, priority = 1, order = ListenOrder.NEWEST_FIRST)
        val oldest = feed(2, priority = 0, order = ListenOrder.OLDEST_FIRST)
        insert(
            listOf(newest, oldest),
            listOf(
                episode(10, 1, publishedAt = 100),
                episode(11, 1, publishedAt = 300),
                episode(12, 1, publishedAt = 300),
                episode(13, 1, publishedAt = 200),
                episode(20, 2, publishedAt = 100),
                episode(21, 2, publishedAt = 300),
                episode(22, 2, publishedAt = 300),
                episode(23, 2, publishedAt = 200),
            ),
        )
        val rows = store.observeQueue().first()
        // Feed 2 has priority 0: oldest first (ties by id ascending). Feed 1 follows: newest first (ties by id descending).
        assertEquals(listOf(20L, 23L, 21L, 22L, 12L, 11L, 13L, 10L), ids(rows))
        // The pure rules in domain/ agree with the SQL.
        assertEquals(ids(rows), sortQueue(rows.shuffled().map { it.key }).map { it.episodeId })
    }

    @Test
    fun changingAFeedsListenOrderResortsOnlyThatFeedLive() = runBlocking<Unit> {
        insert(
            listOf(feed(1, priority = 0), feed(2, priority = 1)),
            listOf(episode(10, 1, publishedAt = 100), episode(11, 1, publishedAt = 200), episode(20, 2, publishedAt = 100), episode(21, 2, publishedAt = 200)),
        )
        assertEquals(listOf(11L, 10L, 21L, 20L), ids(store.observeQueue().first()))
        store.transaction {
            updateFeedSynced(feed(1, priority = 0, order = ListenOrder.OLDEST_FIRST))
        }
        val after = awaitQueue("feed 1 oldest first") { ids(it) == listOf(10L, 11L, 21L, 20L) }
        assertEquals(listOf(10L, 11L, 21L, 20L), ids(after))
    }

    @Test
    fun reorderingFeedsResortsTheQueueLive() = runBlocking<Unit> {
        insert(
            listOf(feed(1, priority = 0), feed(2, priority = 1)),
            listOf(episode(10, 1), episode(20, 2)),
        )
        assertEquals(listOf(10L, 20L), ids(store.observeQueue().first()))
        store.transaction {
            updateFeedSynced(feed(1, priority = 1))
            updateFeedSynced(feed(2, priority = 0))
        }
        awaitQueue("feed 2 first") { ids(it) == listOf(20L, 10L) }
    }

    @Test
    fun theQueueIsLiveForMarksDownloadsAndRemovals() = runBlocking<Unit> {
        insert(listOf(feed(1)), listOf(episode(10, 1, publishedAt = 300), episode(11, 1, publishedAt = 200), episode(12, 1, downloadState = DownloadState.NONE, publishedAt = 100)))
        val repo = EpisodeStateRepository(store) { 10_000L }
        assertEquals(listOf(10L, 11L), ids(store.observeQueue().first()))

        repo.markPlayed(10)
        awaitQueue("10 left after Played") { ids(it) == listOf(11L) }

        repo.markNew(10)
        awaitQueue("10 back after New") { ids(it) == listOf(10L, 11L) }

        db.episodeDao().requestDownload(12, DownloadState.DOWNLOADING, clearPermanent = false)
        val withDownload = awaitQueue("12 downloading joins") { ids(it) == listOf(10L, 11L, 12L) }
        assertEquals(DownloadState.DOWNLOADING, withDownload.last().downloadState)

        db.episodeDao().saveDownloadProgress(12, received = 500, total = 1_000)
        val progress = awaitQueue("12 shows its progress") { rows -> rows.last().bytesReceived == 500L }
        assertEquals(1_000L, progress.last().totalBytes)

        store.transaction { deleteEpisode(11) }
        awaitQueue("11 gone") { ids(it) == listOf(10L, 12L) }
    }

    @Test
    fun anEpisodeThatIsNotInTheQueueStillHasItsOrderKey() = runBlocking<Unit> {
        insert(listOf(feed(1, priority = 4, order = ListenOrder.OLDEST_FIRST)), listOf(episode(10, 1, status = EpisodeStatus.PLAYED, downloadState = DownloadState.NONE, publishedAt = 77)))
        assertEquals(emptyList<Long>(), ids(store.queue()))
        val row = store.queueRowOf(10)
        assertNotNull(row)
        assertEquals(4, row!!.feedPriority)
        assertEquals(ListenOrder.OLDEST_FIRST, row.listenOrder)
        assertEquals(77L, row.publishedAt)
        assertNull(store.queueRowOf(99))
    }
}

private fun feed(id: Long, priority: Int = 0, order: ListenOrder = ListenOrder.NEWEST_FIRST) = Feed(
    id = id, feedUrl = "https://feeds.example.com/$id.xml", title = "Feed $id", author = "", description = "",
    artworkUrl = "", priority = priority, listenOrder = order, lastRefreshedAt = null, subscribedAt = 1, rev = 1,
)

private fun episode(
    id: Long,
    feedId: Long,
    status: EpisodeStatus = EpisodeStatus.NEW,
    downloadState: DownloadState = DownloadState.DOWNLOADED,
    publishedAt: Long = 2_000,
) = Episode(
    id = id, feedId = feedId, guid = "guid-$id", title = "Episode $id", description = "",
    audioUrl = "https://audio.example.com/$id.mp3", publishedAt = publishedAt, durationSec = null, artworkUrl = null,
    status = status, playedAt = null, positionSec = 0, stateUpdatedAt = 1_000, rev = 1, downloadState = downloadState,
)
