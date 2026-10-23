package com.podclatch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.Episode
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.Feed
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.db.PodClatchDatabase
import com.podclatch.data.db.RoomCacheStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** The top-up and notification queries against real SQLite (an in-memory database). */
class TopUpQueryInstrumentedTest {
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

    private fun feedRow(id: Long, priority: Int, order: ListenOrder) = Feed(
        id = id, feedUrl = "https://feeds.example.com/$id.xml", title = "Feed $id", author = "", description = "",
        artworkUrl = "", priority = priority, listenOrder = order, lastRefreshedAt = null, subscribedAt = 1, rev = 1,
    )

    private fun episodeRow(id: Long, feedId: Long, status: EpisodeStatus, state: DownloadState, permanent: Boolean = false) = Episode(
        id = id, feedId = feedId, guid = "guid-$id", title = "Episode $id", description = "",
        audioUrl = "https://audio.example.com/$id.mp3", publishedAt = 1_000L + id, durationSec = null, artworkUrl = null,
        status = status, playedAt = null, positionSec = 0, stateUpdatedAt = 1_000, rev = 1, downloadState = state,
        downloadPermanentFailure = permanent,
    )

    @Test
    fun topUpRowsAreTheNewEpisodesOfEveryFeedWithTheirOrderingAndDownloadColumns() = runBlocking<Unit> {
        store.transaction {
            insertFeedIfAbsent(feedRow(1, 4, ListenOrder.OLDEST_FIRST))
            insertFeedIfAbsent(feedRow(2, 0, ListenOrder.NEWEST_FIRST))
            insertEpisodeIfAbsent(episodeRow(10, 1, EpisodeStatus.NEW, DownloadState.NONE))
            insertEpisodeIfAbsent(episodeRow(11, 1, EpisodeStatus.NEW, DownloadState.FAILED, permanent = true))
            insertEpisodeIfAbsent(episodeRow(12, 1, EpisodeStatus.PLAYED, DownloadState.DOWNLOADED))
            insertEpisodeIfAbsent(episodeRow(20, 2, EpisodeStatus.NEW, DownloadState.QUEUED))
        }
        val rows = store.topUpRows().sortedBy { it.episodeId }
        assertEquals(listOf(10L, 11L, 20L), rows.map { it.episodeId })
        assertEquals(listOf(1L, 1L, 2L), rows.map { it.feedId })
        assertEquals(listOf(4, 4, 0), rows.map { it.feedPriority })
        assertEquals(listOf(ListenOrder.OLDEST_FIRST, ListenOrder.OLDEST_FIRST, ListenOrder.NEWEST_FIRST), rows.map { it.listenOrder })
        assertEquals(listOf(1_010L, 1_011L, 1_020L), rows.map { it.publishedAt })
        assertEquals(listOf(DownloadState.NONE, DownloadState.FAILED, DownloadState.QUEUED), rows.map { it.downloadState })
        assertEquals(listOf(false, true, false), rows.map { it.downloadPermanentFailure })
    }

    @Test
    fun feedTitlesAnswersOnlyForCachedFeeds() = runBlocking<Unit> {
        store.transaction {
            insertFeedIfAbsent(feedRow(1, 0, ListenOrder.NEWEST_FIRST))
            insertFeedIfAbsent(feedRow(2, 1, ListenOrder.NEWEST_FIRST))
        }
        val titles = store.feedTitles(listOf(1L, 2L, 99L)).associate { it.id to it.title }
        assertEquals(mapOf(1L to "Feed 1", 2L to "Feed 2"), titles)
        assertEquals(emptyList<Any>(), store.feedTitles(emptyList()))
    }
}
