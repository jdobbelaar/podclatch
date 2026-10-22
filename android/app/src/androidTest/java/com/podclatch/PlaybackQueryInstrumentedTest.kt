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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** `CacheStore.episodeForPlayback` against real SQLite (an in-memory database). */
class PlaybackQueryInstrumentedTest {
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

    @Test
    fun returnsTheEpisodeWithItsFeedsTitleAndArtwork() = runBlocking {
        store.transaction {
            insertFeedIfAbsent(cachedFeed(1).copy(title = "The Feed", artworkUrl = "https://art.example.com/feed.png"))
            insertEpisodeIfAbsent(
                cachedEpisode(10, 1, downloadState = DownloadState.DOWNLOADED, localPath = "/data/ep10.mp3")
                    .copy(title = "The Episode", durationSec = 90, artworkUrl = "https://art.example.com/ep.png"),
            )
        }
        val found = store.episodeForPlayback(10)
        assertNotNull(found)
        found!!
        assertEquals(10L, found.id)
        assertEquals("The Episode", found.title)
        assertEquals("The Feed", found.feedTitle)
        assertEquals("https://audio.example.com/10.mp3", found.audioUrl)
        assertEquals(90, found.durationSec)
        assertEquals("https://art.example.com/ep.png", found.artworkUrl)
        assertEquals("https://art.example.com/feed.png", found.feedArtworkUrl)
        assertEquals(DownloadState.DOWNLOADED, found.downloadState)
        assertEquals("/data/ep10.mp3", found.localPath)
    }

    @Test
    fun anEpisodeWithoutOwnArtworkOrDurationKeepsThemNullAndTheFeedsArtworkAvailable() = runBlocking {
        store.transaction {
            insertFeedIfAbsent(cachedFeed(1).copy(artworkUrl = "https://art.example.com/feed.png"))
            insertEpisodeIfAbsent(cachedEpisode(10, 1))
        }
        val found = store.episodeForPlayback(10)!!
        assertNull(found.artworkUrl)
        assertNull(found.durationSec)
        assertNull(found.localPath)
        assertEquals(DownloadState.NONE, found.downloadState)
        assertEquals("https://art.example.com/feed.png", found.feedArtworkUrl)
    }

    @Test
    fun observeEpisodeEmitsTheStateColumnsThenNullOnceTheFeedIsRemoved() = runBlocking {
        store.transaction {
            insertFeedIfAbsent(cachedFeed(1))
            insertEpisodeIfAbsent(cachedEpisode(10, 1))
        }
        val first = store.observeEpisode(10).first()!!
        assertEquals(EpisodeStatus.NEW, first.status)
        assertEquals(0, first.positionSec)
        assertEquals(1_000L, first.stateUpdatedAt)
        store.transaction { updateEpisodeState(10, EpisodeStatus.NEW, null, 42, 2_000) }
        assertEquals(42, store.observeEpisode(10).first()!!.positionSec)
        store.transaction { deleteFeed(1) }
        assertNull(store.observeEpisode(10).first())
    }

    @Test
    fun anUnknownIdIsNull() = runBlocking {
        store.transaction {
            insertFeedIfAbsent(cachedFeed(1))
            insertEpisodeIfAbsent(cachedEpisode(10, 1))
        }
        assertNull(store.episodeForPlayback(11))
    }
}

private fun cachedFeed(id: Long) = Feed(
    id = id, feedUrl = "https://feeds.example.com/$id.xml", title = "Feed $id", author = "", description = "",
    artworkUrl = "", priority = 0, listenOrder = ListenOrder.NEWEST_FIRST, lastRefreshedAt = null, subscribedAt = 1, rev = 1,
)

private fun cachedEpisode(
    id: Long,
    feedId: Long,
    downloadState: DownloadState = DownloadState.NONE,
    localPath: String? = null,
) = Episode(
    id = id, feedId = feedId, guid = "guid-$id", title = "Episode $id", description = "",
    audioUrl = "https://audio.example.com/$id.mp3", publishedAt = 2_000, durationSec = null, artworkUrl = null,
    status = EpisodeStatus.NEW, playedAt = null, positionSec = 0, stateUpdatedAt = 1_000, rev = 1,
    downloadState = downloadState, localPath = localPath,
)
