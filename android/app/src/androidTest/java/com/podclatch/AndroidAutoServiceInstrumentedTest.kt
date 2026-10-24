package com.podclatch

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.ListenOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Android Auto browse tree and the play path, through a real Media3 `MediaBrowser` connected to the
 * real `PlaybackService` (US-16, part 1; there is no car to test with). Room is seeded here and read by the
 * service; no server is configured and nothing uses the network. Every id and priority is far from any
 * real data, and the tests read only their own rows, so a database that already holds Feeds is not a problem.
 */
class AndroidAutoServiceInstrumentedTest {
    private val h = AutoHarness()

    private val feedA = BASE + 1
    private val feedB = BASE + 2
    private val feedC = BASE + 3
    private val feedD = BASE + 4
    private val feedP = BASE + 5
    private val ownFeeds = listOf(feedA, feedB, feedC, feedD, feedP)

    private val a1 = BASE + 11
    private val a2 = BASE + 12
    private val a3 = BASE + 13
    private val a4 = BASE + 14
    private val a5 = BASE + 15
    private val a6 = BASE + 16
    private val b1 = BASE + 21
    private val b2 = BASE + 22
    private val b3 = BASE + 23
    private val b4 = BASE + 24
    private val p1 = BASE + 51
    private val p2 = BASE + 52
    private val p3 = BASE + 53

    private val ownEpisodeIds = mutableSetOf<Long>()

    @Before
    fun seed() {
        val p1File = h.wav("eng26-p1.wav", 1).path
        val p2File = h.wav("eng26-p2.wav", 1).path
        val p3File = h.wav("eng26-p3.wav", 30).path
        runBlocking {
            h.store.transaction {
                insertFeedIfAbsent(autoFeed(feedA, PRIORITY, ListenOrder.NEWEST_FIRST, "Auto A", "Author A", "https://art.example.com/a.png"))
                insertFeedIfAbsent(autoFeed(feedB, PRIORITY + 1, ListenOrder.OLDEST_FIRST, "Auto B"))
                insertFeedIfAbsent(autoFeed(feedC, PRIORITY + 2, ListenOrder.OLDEST_FIRST, "Auto C", artwork = "https://art.example.com/c.png"))
                insertFeedIfAbsent(autoFeed(feedD, PRIORITY + 3, ListenOrder.NEWEST_FIRST, "Auto D"))
                insertFeedIfAbsent(autoFeed(feedP, PRIORITY + 4, ListenOrder.OLDEST_FIRST, "Auto P"))
                val episodes = listOf(
                    autoEpisode(a1, feedA, "A one", 100, DownloadState.DOWNLOADED, durationSec = 60, artwork = "https://art.example.com/a1.png"),
                    autoEpisode(a2, feedA, "A two", 200, DownloadState.DOWNLOADING),
                    autoEpisode(a3, feedA, "A three", 300, DownloadState.QUEUED),
                    autoEpisode(a4, feedA, "A four", 400, DownloadState.FAILED),
                    autoEpisode(a5, feedA, "A five", 500, DownloadState.NONE),
                    autoEpisode(a6, feedA, "A six", 600, DownloadState.DOWNLOADED, status = EpisodeStatus.PLAYED),
                    autoEpisode(b1, feedB, "B one", 100, DownloadState.DOWNLOADED),
                    autoEpisode(b2, feedB, "B two", 200, DownloadState.DOWNLOADED),
                    autoEpisode(b3, feedB, "B three", 50, DownloadState.NONE),
                    autoEpisode(b4, feedB, "B four", 10, DownloadState.DOWNLOADED, status = EpisodeStatus.PLAYED),
                    autoEpisode(p1, feedP, "P one", 10, DownloadState.DOWNLOADED, durationSec = 1, localPath = p1File),
                    autoEpisode(p2, feedP, "P two", 20, DownloadState.DOWNLOADED, durationSec = 1, localPath = p2File),
                    autoEpisode(p3, feedP, "P three", 30, DownloadState.DOWNLOADED, durationSec = 30, positionSec = 5, localPath = p3File),
                ) + (1..120L).map { autoEpisode(BASE + 1_000 + it, feedC, "C $it", it, DownloadState.NONE) }
                episodes.forEach { insertEpisodeIfAbsent(it) }
                ownEpisodeIds += episodes.map { it.id }
            }
        }
    }

    @After
    fun cleanUp() {
        h.release()
        runBlocking { h.store.transaction { ownFeeds.forEach { deleteFeed(it) } } }
    }

    private fun List<MediaItem>.ownIds() = map { it.mediaId }.filter { (it.toLongOrNull() ?: -1) in ownEpisodeIds }

    private fun List<MediaItem>.ownFeedIds() = map { it.mediaId }.filter { id -> ownFeeds.any { id == "feed:$it" } }

    // ---- Browse tree ----

    @Test
    fun theRootIsBrowsableAndItsChildrenAreExactlyQueueAndFeeds() {
        val root = h.call { it.getLibraryRoot(null) }
        assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
        assertEquals("root", root.value!!.mediaId)
        assertTrue(root.value!!.mediaMetadata.isBrowsable == true)
        assertFalse(root.value!!.mediaMetadata.isPlayable == true)
        assertNull("no params go back, so nothing enables search", root.params)
        val tabs = h.children("root")
        assertEquals(listOf("queue", "feeds"), tabs.map { it.mediaId })
        assertEquals(listOf("Queue", "Feeds"), tabs.map { it.mediaMetadata.title.toString() })
        assertTrue(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable != true })
    }

    @Test
    fun theQueueIsTheDerivedQueueInQueueOrderWithTheItemFields() {
        val queue = h.children("queue")
        // Feed A is newest first, B and P oldest first; A has the higher priority.
        assertEquals(listOf(a2, a1, b1, b2, p1, p2, p3).map { it.toString() }, queue.ownIds())
        val byId = queue.associateBy { it.mediaId }
        val one = byId[a1.toString()]!!.mediaMetadata
        assertEquals("A one", one.title.toString())
        assertEquals("Auto A", one.artist.toString())
        assertEquals("https://art.example.com/a1.png", one.artworkUri.toString())
        assertEquals(60_000L, one.durationMs)
        assertTrue(one.isPlayable == true)
        assertFalse(one.isBrowsable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE, one.mediaType)
        val two = byId[a2.toString()]!!.mediaMetadata
        assertEquals("https://art.example.com/a.png", two.artworkUri.toString())
        assertNull(two.durationMs)
        assertNull("no artwork anywhere gives none", byId[b1.toString()]!!.mediaMetadata.artworkUri)
        assertTrue("an id-only item: the service resolves it", byId[a1.toString()]!!.localConfiguration == null)
    }

    @Test
    fun theFeedsAreInFeedPriorityOrderWithAuthorAndArtwork() {
        val feeds = h.children("feeds")
        assertEquals(ownFeeds.map { "feed:$it" }, feeds.ownFeedIds())
        val byId = feeds.associateBy { it.mediaId }
        val a = byId["feed:$feedA"]!!.mediaMetadata
        assertEquals("Auto A", a.title.toString())
        assertEquals("Author A", a.subtitle.toString())
        assertEquals("https://art.example.com/a.png", a.artworkUri.toString())
        assertTrue(a.isBrowsable == true)
        assertFalse(a.isPlayable == true)
        assertNull(byId["feed:$feedD"]!!.mediaMetadata.artworkUri)
    }

    @Test
    fun aFeedListsItsNewEpisodesInItsListenOrderCappedAtOneHundredWithPaging() {
        assertEquals(listOf(a5, a4, a3, a2, a1).map { it.toString() }, h.children("feed:$feedA").map { it.mediaId })
        assertEquals(listOf(b3, b1, b2).map { it.toString() }, h.children("feed:$feedB").map { it.mediaId })
        assertTrue(h.children("feed:$feedA").all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable != true })

        val expected = (1..100L).map { (BASE + 1_000 + it).toString() }
        val all = h.children("feed:$feedC").map { it.mediaId }
        assertEquals(100, all.size)
        assertEquals(expected, all)
        assertEquals(expected.subList(50, 100), h.children("feed:$feedC", page = 1, pageSize = 50).map { it.mediaId })
        assertEquals(emptyList<String>(), h.children("feed:$feedC", page = 2, pageSize = 50).map { it.mediaId })
    }

    @Test
    fun aMissingFeedIsEmptyAnUnknownParentIsABadValueAndTheServiceStaysUp() {
        assertEquals(emptyList<MediaItem>(), h.children("feed:${BASE + 999}"))
        for (bad in listOf("nonsense", "feed:abc", "feed:", "12")) {
            assertEquals("parent '$bad'", LibraryResult.RESULT_ERROR_BAD_VALUE, h.call { it.getChildren(bad, 0, 10, null) }.resultCode)
        }
        assertEquals(LibraryResult.RESULT_SUCCESS, h.call { it.getLibraryRoot(null) }.resultCode)
    }

    @Test
    fun searchIsRefused() {
        assertTrue(h.call { it.search("anything", null) }.resultCode != LibraryResult.RESULT_SUCCESS)
        assertTrue(h.call { it.getSearchResult("anything", 0, 10, null) }.resultCode != LibraryResult.RESULT_SUCCESS)
    }

    @Test
    fun theTreeIsShallowPlainTextAndOnlyUsesTheFixedIds() {
        val seen = mutableListOf<MediaItem>()
        fun walk(parent: String, depth: Int) {
            assertTrue("depth $depth below the root", depth <= 3)
            for (child in h.children(parent)) {
                seen += child
                if (child.mediaMetadata.isBrowsable == true) walk(child.mediaId, depth + 1)
            }
        }
        walk("root", 1)
        val fixed = setOf("queue", "feeds")
        assertTrue(seen.all { it.mediaId in fixed || it.mediaId.startsWith("feed:") || it.mediaId.toLongOrNull() != null })
        val texts = seen.flatMap { listOfNotNull(it.mediaMetadata.title, it.mediaMetadata.subtitle, it.mediaMetadata.artist) }
        assertTrue(texts.none { t -> t.any { it in "<>&" } })
    }

    // ---- Live updates ----

    @Test
    fun aChangeToTheQueueIsToldOncePerBurstAndTheNewChildrenReflectIt() {
        assertEquals(LibraryResult.RESULT_SUCCESS, h.call { it.subscribe("queue", null) }.resultCode)
        // Marking the first Queue Episode Played removes it from the Queue.
        runBlocking { h.store.transaction { updateEpisodeState(a2, EpisodeStatus.PLAYED, 5_000, 0, 5_000) } }
        h.awaitTrue("queue told after a Mark") { "queue" in h.childrenChanged }
        h.awaitTrue("the new children drop the marked Episode") { a2.toString() !in h.children("queue").ownIds() }
        h.childrenChanged.clear()

        // An Episode becoming DOWNLOADING joins the Queue.
        runBlocking {
            h.store.transaction {
                deleteEpisode(a3)
                insertEpisodeIfAbsent(autoEpisode(a3, feedA, "A three", 300, DownloadState.DOWNLOADING))
            }
        }
        h.awaitTrue("queue told after a download starts") { "queue" in h.childrenChanged }
        h.awaitTrue("the new children hold the downloading Episode") { a3.toString() in h.children("queue").ownIds() }
        h.awaitTrue("this burst is over") { h.childrenChanged.size == 1 }
        h.childrenChanged.clear()

        // Ten changes in a burst: fewer than ten tellings, and the last state is what the children show.
        repeat(10) { i ->
            val status = if (i % 2 == 0) EpisodeStatus.PLAYED else EpisodeStatus.NEW
            runBlocking { h.store.transaction { updateEpisodeState(a1, status, if (i % 2 == 0) 7_000 else null, 0, 7_000L + i) } }
        }
        h.awaitTrue("queue told for the burst") { "queue" in h.childrenChanged }
        h.awaitTrue("the children show the last state (a1 is New again)") { a1.toString() in h.children("queue").ownIds() }
        assertTrue("fewer than ten tellings, saw ${h.childrenChanged.size}", h.childrenChanged.size < 10)
    }

    // ---- Playing from the car ----

    @Test
    fun aBrowsedQueueItemPlaysFromRoomAtItsSavedPosition() {
        val browser = h.connect()
        val item = h.children("queue").first { it.mediaId == p3.toString() }
        h.onMain {
            browser.setMediaItem(item)
            browser.prepare()
            browser.play()
        }
        h.awaitTrue("playing P three, started at its saved 5 s or later", main = true) {
            browser.isPlaying && browser.currentMediaItem?.mediaId == p3.toString() && browser.currentPosition >= 5_000
        }
        h.onMain {
            val loaded = browser.currentMediaItem!!.mediaMetadata
            assertEquals("P three", loaded.title.toString())
            assertEquals("Auto P", loaded.artist.toString())
        }
    }

    @Test
    fun playingAFeedItemToItsEndMarksItPlayedAndStartsTheNextQueueItem() {
        val browser = h.connect()
        val item = h.children("feed:$feedP").first { it.mediaId == p1.toString() }
        h.onMain {
            browser.setMediaItem(item)
            browser.prepare()
            browser.play()
        }
        h.awaitTrue("P two is playing after P one ended", timeoutMs = 15_000, main = true) {
            browser.isPlaying && browser.currentMediaItem?.mediaId == p2.toString()
        }
        h.awaitTrue("P one is Played", timeoutMs = 15_000) {
            runBlocking { h.store.observeEpisode(p1).first() }?.status == EpisodeStatus.PLAYED
        }
    }

    // ---- Nothing else broke ----

    @Test
    fun theButtonLayoutHasSkipBackAndSkipForwardAndNoNextOrPrevious() {
        val browser = h.connect()
        val item = h.children("queue").first { it.mediaId == p3.toString() }
        h.onMain {
            browser.setMediaItem(item)
            browser.prepare()
        }
        h.awaitTrue("P three loaded and ready", main = true) {
            browser.currentMediaItem?.mediaId == p3.toString() && browser.playbackState == Player.STATE_READY
        }
        h.onMain {
            assertTrue(browser.isCommandAvailable(Player.COMMAND_SEEK_BACK))
            assertTrue(browser.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
            assertFalse(browser.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
            assertFalse(browser.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
            val buttons = browser.mediaButtonPreferences
            assertEquals(listOf(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD), buttons.map { it.playerCommand })
            assertTrue("no custom command button", buttons.all { it.sessionCommand == null })
        }
    }

    private companion object {
        const val BASE = 26_000_000L
        const val PRIORITY = 900_000
    }
}
