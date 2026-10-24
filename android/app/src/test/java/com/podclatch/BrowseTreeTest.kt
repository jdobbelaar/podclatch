package com.podclatch

import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.Episode
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.network.PodClatchApi
import com.podclatch.data.repo.BrowseRepository
import com.podclatch.playback.BrowseChangeNotifier
import com.podclatch.playback.BrowseIds
import com.podclatch.playback.BrowseItem
import com.podclatch.playback.BrowseKind
import com.podclatch.playback.BrowseTree
import com.podclatch.playback.PlaybackResumer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android Auto browse tree over the Room-shaped fake store (US-16, decision 1). */
class BrowseTreeTest {
    private val store = FakeCacheStore()
    private val repository = BrowseRepository(store)
    private val tree = BrowseTree(
        queue = repository::queue,
        feeds = repository::feeds,
        newEpisodesOfFeed = repository::newEpisodesOfFeed,
        rootTitle = "PodClatch",
        queueTitle = "Queue",
        feedsTitle = "Feeds",
        feedEpisodeCap = 100,
    )

    private fun feed(id: Long, priority: Int, order: ListenOrder, title: String = "Feed $id", art: String = "", author: String = "") {
        store.feeds[id] = cachedFeed(id).copy(title = title, priority = priority, listenOrder = order, artworkUrl = art, author = author)
    }

    private fun episode(
        id: Long,
        feedId: Long,
        published: Long = id,
        status: EpisodeStatus = EpisodeStatus.NEW,
        state: DownloadState = DownloadState.NONE,
        art: String? = null,
        durationSec: Int? = null,
        title: String = "Episode $id",
    ) {
        store.episodes[id] = cachedEpisode(id, feedId, status = status, downloadState = state)
            .copy(title = title, publishedAt = published, artworkUrl = art, durationSec = durationSec)
    }

    private fun children(parent: String, page: Int = 0, size: Int = Int.MAX_VALUE) =
        runBlocking { tree.children(parent, page, size) }

    private fun ids(items: List<BrowseItem>?) = items!!.map { it.mediaId }

    @Test
    fun theRootIsBrowsableAndHasExactlyTheTwoTabsInOrder() {
        val root = tree.root()
        assertEquals(BrowseIds.ROOT, root.mediaId)
        assertTrue(root.browsable)
        assertFalse(root.playable)
        val tabs = children(BrowseIds.ROOT)!!
        assertEquals(listOf("queue", "feeds"), tabs.map { it.mediaId })
        assertEquals(listOf("Queue", "Feeds"), tabs.map { it.title })
        assertTrue(tabs.all { it.browsable && !it.playable })
    }

    @Test
    fun theQueueHoldsOnlyNewDownloadedOrDownloadingEpisodesInQueueOrder() {
        feed(1, 0, ListenOrder.NEWEST_FIRST)
        feed(2, 1, ListenOrder.OLDEST_FIRST)
        episode(10, 1, published = 100, state = DownloadState.DOWNLOADED)
        episode(11, 1, published = 200, state = DownloadState.DOWNLOADING)
        episode(12, 1, published = 300, state = DownloadState.QUEUED)
        episode(13, 1, published = 400, state = DownloadState.FAILED)
        episode(14, 1, published = 500, state = DownloadState.NONE)
        episode(15, 1, published = 600, state = DownloadState.DOWNLOADED, status = EpisodeStatus.PLAYED)
        episode(20, 2, published = 100, state = DownloadState.DOWNLOADED)
        episode(21, 2, published = 200, state = DownloadState.DOWNLOADED)
        // Feed 1 is newest first, feed 2 oldest first; feed 1 has the higher priority.
        assertEquals(listOf("11", "10", "20", "21"), ids(children(BrowseIds.QUEUE)))
        assertTrue(children(BrowseIds.QUEUE)!!.all { it.playable && !it.browsable })
    }

    @Test
    fun theQueueOrderIsTheSameAsSortQueueForTheSameRows() {
        feed(1, 0, ListenOrder.NEWEST_FIRST)
        feed(2, 1, ListenOrder.NEWEST_FIRST)
        (1..6L).forEach { episode(it, if (it % 2 == 0L) 2 else 1, published = 100 + it % 3, state = DownloadState.DOWNLOADED) }
        val expected = com.podclatch.domain.sortQueue(runBlocking { store.queue() }.map { it.key }).map { it.episodeId.toString() }
        assertEquals(expected, ids(children(BrowseIds.QUEUE)))
    }

    @Test
    fun anEpisodeItemHasTheTitleTheFeedTitleAsArtistTheArtworkRuleAndTheDuration() {
        feed(1, 0, ListenOrder.NEWEST_FIRST, title = "The Feed", art = "https://art.example.com/feed.png")
        feed(2, 1, ListenOrder.NEWEST_FIRST, title = "No Art")
        episode(10, 1, state = DownloadState.DOWNLOADED, art = "https://art.example.com/ep.png", durationSec = 90, title = "Own art")
        episode(11, 1, state = DownloadState.DOWNLOADED, art = "   ", title = "Feed art")
        episode(12, 2, state = DownloadState.DOWNLOADED, art = null, title = "None")
        val byId = children(BrowseIds.QUEUE)!!.associateBy { it.mediaId }
        assertEquals("Own art", byId["10"]!!.title)
        assertEquals("The Feed", byId["10"]!!.artist)
        assertEquals("https://art.example.com/ep.png", byId["10"]!!.artworkUrl)
        assertEquals(90_000L, byId["10"]!!.durationMs)
        assertEquals("https://art.example.com/feed.png", byId["11"]!!.artworkUrl)
        assertNull(byId["11"]!!.durationMs)
        assertNull(byId["12"]!!.artworkUrl)
        assertEquals(BrowseKind.EPISODE, byId["12"]!!.kind)
    }

    @Test
    fun theFeedsAreInFeedPriorityOrderWithAuthorAndArtwork() {
        feed(5, 2, ListenOrder.NEWEST_FIRST, title = "Last")
        feed(3, 0, ListenOrder.NEWEST_FIRST, title = "First", art = "https://art.example.com/a.png", author = "Ann")
        feed(4, 0, ListenOrder.NEWEST_FIRST, title = "Tie")
        val feeds = children(BrowseIds.FEEDS)!!
        assertEquals(listOf("feed:3", "feed:4", "feed:5"), feeds.map { it.mediaId })
        assertTrue(feeds.all { it.browsable && !it.playable })
        assertEquals("Ann", feeds[0].subtitle)
        assertEquals("https://art.example.com/a.png", feeds[0].artworkUrl)
        assertNull(feeds[1].subtitle)
        assertNull(feeds[1].artworkUrl)
    }

    @Test
    fun aFeedListsItsNewEpisodesInItsListenOrderInAnyDownloadStateAndNotThePlayedOnes() {
        feed(1, 0, ListenOrder.OLDEST_FIRST)
        episode(10, 1, published = 300, state = DownloadState.FAILED)
        episode(11, 1, published = 100, state = DownloadState.NONE)
        episode(12, 1, published = 200, state = DownloadState.DOWNLOADED)
        episode(13, 1, published = 50, status = EpisodeStatus.PLAYED)
        assertEquals(listOf("11", "12", "10"), ids(children("feed:1")))
        feed(1, 0, ListenOrder.NEWEST_FIRST)
        assertEquals(listOf("10", "12", "11"), ids(children("feed:1")))
    }

    @Test
    fun aFeedIsCappedAtOneHundredAndPagingWorks() {
        feed(3, 0, ListenOrder.OLDEST_FIRST)
        (1..120L).forEach { episode(1_000 + it, 3, published = it) }
        val all = ids(children("feed:3"))
        assertEquals(100, all.size)
        assertEquals((1..100L).map { (1_000 + it).toString() }, all)
        assertEquals(all.subList(50, 100), ids(children("feed:3", page = 1, size = 50)))
        assertEquals(emptyList<String>(), ids(children("feed:3", page = 2, size = 50)))
        assertEquals(all.subList(0, 50), ids(children("feed:3", page = 0, size = 50)))
    }

    @Test
    fun theQueueHasNoCapAndHonoursPaging() {
        feed(1, 0, ListenOrder.OLDEST_FIRST)
        (1..130L).forEach { episode(it, 1, published = it, state = DownloadState.DOWNLOADED) }
        assertEquals(130, children(BrowseIds.QUEUE)!!.size)
        assertEquals(30, children(BrowseIds.QUEUE, page = 1, size = 100)!!.size)
    }

    @Test
    fun aFeedThatDoesNotExistIsEmptyAndAnUnknownParentIsNull() {
        assertEquals(emptyList<BrowseItem>(), children("feed:999"))
        listOf("", "nope", "feed:", "feed:abc", "feed:-1", "feed:1:2", "12", "Queue", "root ").forEach {
            assertNull("parent '$it'", children(it))
        }
    }

    @Test
    fun anItemIsFoundByItsIdAndAnUnknownIdIsNull() = runBlocking<Unit> {
        feed(1, 0, ListenOrder.NEWEST_FIRST, title = "F")
        episode(10, 1, state = DownloadState.DOWNLOADED)
        assertEquals("root", tree.item("root")!!.mediaId)
        assertEquals("queue", tree.item("queue")!!.mediaId)
        assertEquals("F", tree.item("feed:1")!!.title)
        assertEquals("10", tree.item("10")!!.mediaId)
        assertNull(tree.item("feed:2"))
        assertNull(tree.item("11"))
        assertNull(tree.item("junk"))
    }

    @Test
    fun theTreeIsShallowAndPlainTextAndOnlyUsesTheFixedIds() {
        feed(1, 0, ListenOrder.NEWEST_FIRST, title = "Plain Feed", author = "Author")
        episode(10, 1, state = DownloadState.DOWNLOADED, title = "Plain Episode")
        val nodes = mutableListOf<BrowseItem>()
        fun walk(parent: String, depth: Int) {
            assertTrue("depth $depth", depth <= 3)
            for (child in children(parent)!!) {
                nodes += child
                if (child.browsable) walk(child.mediaId, depth + 1)
            }
        }
        walk(BrowseIds.ROOT, 1)
        assertTrue(nodes.all { it.mediaId in setOf("queue", "feeds", "feed:1", "10") })
        assertTrue(nodes.none { listOf(it.title, it.subtitle, it.artist).filterNotNull().any { s -> s.any { c -> c in "<>&" } } })
    }

    @Test
    fun browsingChangesNothingInTheStore() {
        feed(1, 0, ListenOrder.NEWEST_FIRST)
        episode(10, 1, state = DownloadState.DOWNLOADED)
        episode(11, 1, state = DownloadState.NONE)
        val before = store.episodes.toMap() to store.feeds.toMap()
        children(BrowseIds.ROOT); children(BrowseIds.QUEUE); children(BrowseIds.FEEDS); children("feed:1")
        assertEquals(before, store.episodes.toMap() to store.feeds.toMap())
    }

    @Test
    fun noBrowseClassHoldsTheNetworkApiOrAnHttpClient() {
        val banned = setOf(PodClatchApi::class.java, OkHttpClient::class.java)
        listOf(BrowseTree::class.java, BrowseRepository::class.java, PlaybackResumer::class.java, BrowseChangeNotifier::class.java)
            .forEach { cls ->
                val types = cls.declaredFields.map { it.type } + cls.declaredConstructors.flatMap { it.parameterTypes.toList() }
                assertTrue("${cls.simpleName} has no network type", types.none { it in banned })
            }
        assertNotNull(tree)
    }
}
