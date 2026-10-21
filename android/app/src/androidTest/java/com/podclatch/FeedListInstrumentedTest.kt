package com.podclatch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.Episode
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.Feed
import com.podclatch.data.db.FeedListItem
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.db.PodClatchDatabase
import com.podclatch.data.db.RoomCacheStore
import com.podclatch.data.repo.EpisodeStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Feeds tab's query and the reorder write against real SQLite (an in-memory database). */
class FeedListInstrumentedTest {
    private lateinit var db: PodClatchDatabase
    private lateinit var store: RoomCacheStore
    private var now = 10_000L

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

    private fun feed(id: Long, priority: Int, title: String = "Feed $id") = Feed(
        id = id, feedUrl = "https://feeds.example.com/$id.xml", title = title, author = "Author $id", description = "",
        artworkUrl = "https://art.example.com/$id.png", priority = priority, listenOrder = ListenOrder.NEWEST_FIRST,
        lastRefreshedAt = null, subscribedAt = 1, rev = 1,
    )

    private fun episode(id: Long, feedId: Long, status: EpisodeStatus = EpisodeStatus.NEW) = Episode(
        id = id, feedId = feedId, guid = "g$id", title = "E$id", description = "", audioUrl = "https://a.example.com/$id.mp3",
        publishedAt = 1, durationSec = null, artworkUrl = null, status = status, playedAt = null, positionSec = 0,
        stateUpdatedAt = 1, rev = 1,
    )

    private fun insert(feeds: List<Feed>, episodes: List<Episode> = emptyList()) = runBlocking {
        for (f in feeds) db.feedDao().insertIfAbsent(f)
        for (e in episodes) db.episodeDao().insertIfAbsent(e)
    }

    private fun current(): List<FeedListItem> = runBlocking { store.observeFeedList().first() }

    private suspend fun Flow<List<FeedListItem>>.awaitCount(feedId: Long, count: Int) = withTimeout(5_000) {
        first { list -> list.firstOrNull { it.id == feedId }?.unplayedCount == count }
    }

    @Test
    fun orderIsPriorityThenIdAndFeedsWithoutUnplayedEpisodesAreStillListed() {
        insert(
            listOf(feed(5, 1), feed(3, 1), feed(9, 0), feed(4, 2), feed(6, 3)),
            listOf(
                episode(1, 5), episode(2, 5), episode(3, 5, EpisodeStatus.PLAYED),
                episode(4, 4, EpisodeStatus.PLAYED), episode(5, 4, EpisodeStatus.PLAYED),
                episode(6, 3),
            ),
        )
        val list = current()
        assertEquals(listOf(9L, 3L, 5L, 4L, 6L), list.map { it.id })
        assertEquals(mapOf(9L to 0, 3L to 1, 5L to 2, 4L to 0, 6L to 0), list.associate { it.id to it.unplayedCount })
        assertEquals("Author 9", list.first().author)
        assertEquals("https://art.example.com/9.png", list.first().artworkUrl)
    }

    @Test
    fun downloadedAndNotDownloadedNewEpisodesBothCount() {
        insert(listOf(feed(1, 0)), listOf(episode(1, 1), episode(2, 1).copy(downloadState = DownloadState.DOWNLOADED, localPath = "/x")))
        assertEquals(2, current().single().unplayedCount)
    }

    @Test
    fun markPlayedAndMarkNewMoveTheCountByOne() = runBlocking {
        insert(listOf(feed(1, 0)), listOf(episode(1, 1), episode(2, 1), episode(3, 1)))
        val repo = EpisodeStateRepository(store) { now++ }
        val flow = store.observeFeedList()
        flow.awaitCount(1, 3)
        repo.markPlayed(1)
        flow.awaitCount(1, 2)
        repo.markNew(1)
        flow.awaitCount(1, 3)
    }

    @Test
    fun aPulledNewEpisodeRaisesTheCountAndADeletedFeedDisappears() = runBlocking {
        insert(listOf(feed(1, 0), feed(2, 1)), listOf(episode(1, 1)))
        val flow = store.observeFeedList()
        flow.awaitCount(1, 1)
        db.episodeDao().insertIfAbsent(episode(2, 1))
        flow.awaitCount(1, 2)
        store.transaction { deleteFeed(2) }
        withTimeout(5_000) { flow.first { list -> list.map { it.id } == listOf(1L) } }
    }

    @Test
    fun aDownloadStateChangeDoesNotEmitAgain() = runBlocking {
        insert(listOf(feed(1, 0)), listOf(episode(1, 1)))
        val seen = mutableListOf<List<FeedListItem>>()
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { store.observeFeedList().toList(seen) }
        withTimeout(5_000) { while (seen.isEmpty()) kotlinx.coroutines.delay(20) }
        db.openHelper.writableDatabase.execSQL("UPDATE episode SET downloadState = 'QUEUED' WHERE id = 1")
        kotlinx.coroutines.delay(500)
        job.cancel()
        assertEquals(1, seen.size)
    }

    @Test
    fun applyingAReorderChangesPrioritiesOnlyAndNeverInserts() = runBlocking {
        insert(
            listOf(feed(1, 0), feed(2, 1)),
            listOf(episode(1, 1).copy(downloadState = DownloadState.DOWNLOADED, localPath = "/audio/1.mp3"), episode(2, 2)),
        )
        val before = db.openHelper.readableDatabase.query("SELECT id, downloadState, localPath, status FROM episode ORDER BY id")
            .use { c -> generateSequence { if (c.moveToNext()) listOf(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)) else null }.toList() }
        store.transaction {
            for (dto in listOf(feed(2, 0).copy(rev = 7), feed(1, 1).copy(rev = 8), feed(99, 2))) {
                if (feedExists(dto.id)) updateFeedSynced(dto)
            }
        }
        assertEquals(listOf(2L, 1L), current().map { it.id })
        assertTrue(db.feedDao().exists(1) && db.feedDao().exists(2) && !db.feedDao().exists(99))
        val after = db.openHelper.readableDatabase.query("SELECT id, downloadState, localPath, status FROM episode ORDER BY id")
            .use { c -> generateSequence { if (c.moveToNext()) listOf(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)) else null }.toList() }
        assertEquals(before, after)
    }
}
