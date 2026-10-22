package com.podclatch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.podclatch.data.db.Episode
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.Feed
import com.podclatch.data.db.FeedEpisode
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.db.PodClatchDatabase
import com.podclatch.data.db.RoomCacheStore
import com.podclatch.data.repo.EpisodeStateRepository
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

/** `observeFeed`, `observeEpisodes`, and `episodesOfFeed` against real SQLite (an in-memory database). */
class FeedDetailQueryInstrumentedTest {
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

    private fun seed() = runBlocking {
        store.transaction {
            insertFeedIfAbsent(cachedFeed(1).copy(title = "One", listenOrder = ListenOrder.OLDEST_FIRST))
            insertFeedIfAbsent(cachedFeed(2).copy(title = "Two"))
            insertEpisodeIfAbsent(cachedEpisode(10, 1).copy(publishedAt = 100, title = "Oldest"))
            insertEpisodeIfAbsent(cachedEpisode(11, 1).copy(publishedAt = 300, title = "Tie low id"))
            insertEpisodeIfAbsent(cachedEpisode(12, 1).copy(publishedAt = 300, title = "Tie high id", durationSec = 90))
            insertEpisodeIfAbsent(cachedEpisode(13, 1, EpisodeStatus.PLAYED, playedAt = 5, positionSec = 7).copy(publishedAt = 200))
            insertEpisodeIfAbsent(cachedEpisode(20, 2).copy(publishedAt = 999))
        }
    }

    private suspend fun awaitEpisodes(feedId: Long, what: String, condition: (List<FeedEpisode>) -> Boolean) =
        try {
            withTimeout(5_000) { store.observeEpisodes(feedId).first(condition) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Not reached within 5000 ms: $what")
        }

    @Test
    fun observeFeedEmitsTheFeedAndThenNullWhenItIsDeleted() = runBlocking {
        seed()
        val feed = store.observeFeed(1).first()
        assertNotNull(feed)
        assertEquals("One", feed!!.title)
        assertEquals(ListenOrder.OLDEST_FIRST, feed.listenOrder)
        assertNull(store.observeFeed(99).first())
        store.transaction { deleteFeed(1) }
        assertNull(withTimeout(5_000) { store.observeFeed(1).first() })
    }

    @Test
    fun observeEpisodesOrdersNewestFirstWithTiesByIdDescendingAndOnlyForThatFeed() = runBlocking {
        seed()
        val rows = store.observeEpisodes(1).first()
        assertEquals(listOf(12L, 11L, 13L, 10L), rows.map { it.id })
        val tie = rows.first()
        assertEquals("Tie high id", tie.title)
        assertEquals(90, tie.durationSec)
        assertEquals(300L, tie.publishedAt)
        val played = rows.single { it.id == 13L }
        assertEquals(EpisodeStatus.PLAYED, played.status)
        assertEquals(7, played.positionSec)
        assertEquals(listOf(20L), store.observeEpisodes(2).first().map { it.id })
        assertEquals(emptyList<FeedEpisode>(), store.observeEpisodes(99).first())
    }

    @Test
    fun observeEpisodesEmitsAgainWhenAnEpisodesStateChanges() = runBlocking {
        seed()
        val repo = EpisodeStateRepository(store) { 10_000L }
        assertEquals(EpisodeStatus.NEW, store.observeEpisodes(1).first().single { it.id == 10L }.status)
        repo.markPlayed(10)
        val after = awaitEpisodes(1, "episode 10 Played") { rows -> rows.single { it.id == 10L }.status == EpisodeStatus.PLAYED }
        assertEquals(EpisodeStatus.PLAYED, after.single { it.id == 10L }.status)
        repo.markNew(13)
        val reset = awaitEpisodes(1, "episode 13 New") { rows -> rows.single { it.id == 13L }.status == EpisodeStatus.NEW }
        assertEquals(0, reset.single { it.id == 13L }.positionSec)
    }

    @Test
    fun episodesOfFeedReturnsEveryEpisodeOfThatFeedInsideATransaction() = runBlocking {
        seed()
        val ids = store.transaction { episodesOfFeed(1).map { it.id } }
        assertEquals(listOf(10L, 11L, 12L, 13L), ids)
        assertEquals(emptyList<Long>(), store.transaction { episodesOfFeed(99).map { it.id } })
    }

    @Test
    fun markAllInOneTransactionIsVisibleToTheListAtOnce() = runBlocking {
        seed()
        val repo = EpisodeStateRepository(store) { 10_000L }
        assertEquals(3, repo.markAllPlayed(1))
        val rows = awaitEpisodes(1, "all Played") { list -> list.all { it.status == EpisodeStatus.PLAYED } }
        assertEquals(4, rows.size)
        assertEquals(3, db.pendingStateWriteDao().getAll().size)
    }
}

private fun cachedFeed(id: Long) = Feed(
    id = id, feedUrl = "https://feeds.example.com/$id.xml", title = "Feed $id", author = "", description = "",
    artworkUrl = "", priority = 0, listenOrder = ListenOrder.NEWEST_FIRST, lastRefreshedAt = null, subscribedAt = 1, rev = 1,
)

private fun cachedEpisode(
    id: Long,
    feedId: Long,
    status: EpisodeStatus = EpisodeStatus.NEW,
    playedAt: Long? = null,
    positionSec: Int = 0,
) = Episode(
    id = id, feedId = feedId, guid = "guid-$id", title = "Episode $id", description = "",
    audioUrl = "https://audio.example.com/$id.mp3", publishedAt = 2_000, durationSec = null, artworkUrl = null,
    status = status, playedAt = playedAt, positionSec = positionSec, stateUpdatedAt = 1_000, rev = 1,
)
