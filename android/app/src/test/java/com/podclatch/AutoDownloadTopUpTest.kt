package com.podclatch

import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.db.TopUpRow
import com.podclatch.data.repo.Settings
import com.podclatch.domain.AutoDownloadTopUp
import com.podclatch.domain.PullSummary
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.clampDownloadAhead
import com.podclatch.domain.planTopUp
import com.podclatch.download.Downloads
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun row(
    id: Long,
    feed: Long = 1,
    state: DownloadState = DownloadState.NONE,
    published: Long = id * 100,
    order: ListenOrder = ListenOrder.NEWEST_FIRST,
    priority: Int = feed.toInt(),
    permanent: Boolean = false,
) = TopUpRow(id, feed, priority, order, published, state, permanent)

/** The pure plan: a table of cases. Ids sort like dates here, so a bigger id is a newer Episode. */
class TopUpPlanTest {
    private val none = DownloadState.NONE

    @Test
    fun belowAtAndAboveTheLimit() {
        val five = (1L..5).map { row(it) }
        assertEquals("below: nothing in use, fill to 3 newest first", listOf(5L, 4L, 3L), planTopUp(five, 3))

        val oneDone = five.map { if (it.episodeId == 5L) it.copy(downloadState = DownloadState.DOWNLOADED) else it }
        assertEquals("one slot used, two to fetch", listOf(4L, 3L), planTopUp(oneDone, 3))

        val threeUsed = five.map {
            when (it.episodeId) {
                5L -> it.copy(downloadState = DownloadState.DOWNLOADED)
                4L -> it.copy(downloadState = DownloadState.DOWNLOADING)
                3L -> it.copy(downloadState = DownloadState.QUEUED)
                else -> it
            }
        }
        assertEquals("at the limit", emptyList<Long>(), planTopUp(threeUsed, 3))

        val fourUsed = threeUsed.map { if (it.episodeId == 2L) it.copy(downloadState = DownloadState.DOWNLOADED) else it }
        assertEquals("above the limit: nothing is cancelled or requested", emptyList<Long>(), planTopUp(fourUsed, 3))
    }

    @Test
    fun limitOneAndLimitTen() {
        val twelve = (1L..12).map { row(it) }
        assertEquals(listOf(12L), planTopUp(twelve, 1))
        assertEquals((12L downTo 3L).toList(), planTopUp(twelve, 10))
    }

    @Test
    fun aFeedWithFewerCandidatesThanMissingSlots() {
        assertEquals(listOf(2L, 1L), planTopUp(listOf(row(1), row(2)), 3))
    }

    @Test
    fun bothListenOrders() {
        val newest = (1L..5).map { row(it, order = ListenOrder.NEWEST_FIRST) }
        val oldest = (1L..5).map { row(it, order = ListenOrder.OLDEST_FIRST) }
        assertEquals(listOf(5L, 4L), planTopUp(newest, 2))
        assertEquals(listOf(1L, 2L), planTopUp(oldest, 2))
    }

    @Test
    fun equalPublishDatesBreakTheTieByIdLikeTheQueue() {
        val newest = listOf(row(1, published = 50), row(2, published = 50), row(3, published = 50))
        assertEquals(listOf(3L, 2L), planTopUp(newest, 2))
        val oldest = newest.map { it.copy(listenOrder = ListenOrder.OLDEST_FIRST) }
        assertEquals(listOf(1L, 2L), planTopUp(oldest, 2))
    }

    @Test
    fun aTransientFailureIsRetriedBeforeNoneEpisodesThatComeLaterInOrder() {
        val rows = listOf(
            row(5, state = DownloadState.FAILED),
            row(4),
            row(3),
            row(2),
        )
        assertEquals(listOf(5L, 4L), planTopUp(rows, 2))
    }

    @Test
    fun aPermanentlyFailedEpisodeIsSkippedAndDoesNotUseASlot() {
        val rows = listOf(
            row(5, state = DownloadState.FAILED, permanent = true),
            row(4, state = none, permanent = true),
            row(3),
            row(2),
            row(1),
        )
        assertEquals(listOf(3L, 2L, 1L), planTopUp(rows, 3))
    }

    @Test
    fun aFailedEpisodeDoesNotCountAsInUse() {
        val rows = listOf(row(3, state = DownloadState.FAILED), row(2, state = DownloadState.FAILED), row(1))
        assertEquals(listOf(3L, 2L, 1L), planTopUp(rows, 3))
    }

    @Test
    fun twoFeedsAreFilledInFeedPriorityOrder() {
        val rows = listOf(
            row(10, feed = 2, priority = 0),
            row(11, feed = 2, priority = 0),
            row(20, feed = 1, priority = 5),
            row(21, feed = 1, priority = 5),
        )
        assertEquals("Feed 2 has the better priority", listOf(11L, 10L, 21L, 20L), planTopUp(rows, 2))
    }

    @Test
    fun eachFeedIsCountedOnItsOwn() {
        val rows = listOf(
            row(10, feed = 1, state = DownloadState.DOWNLOADED),
            row(11, feed = 1),
            row(20, feed = 2),
            row(21, feed = 2),
        )
        assertEquals(listOf(11L, 21L, 20L), planTopUp(rows, 2))
    }

    @Test
    fun noRowsMeansNothingToDo() {
        assertEquals(emptyList<Long>(), planTopUp(emptyList(), 3))
    }

    @Test
    fun theLimitIsClampedToOneToTen() {
        assertEquals(1, clampDownloadAhead(0))
        assertEquals(1, clampDownloadAhead(-4))
        assertEquals(1, clampDownloadAhead(1))
        assertEquals(3, clampDownloadAhead(3))
        assertEquals(10, clampDownloadAhead(10))
        assertEquals(10, clampDownloadAhead(99))
    }
}

/** Stands in for the download engine: it records the kind of every request and marks the Episode QUEUED like the real one. */
internal class RecordingDownloads(private val store: FakeCacheStore) : Downloads {
    val automatic = mutableListOf<Long>()
    val manual = mutableListOf<Long>()
    val removed = mutableListOf<Long>()

    override suspend fun requestAutomatic(episodeId: Long) {
        automatic += episodeId
        store.episodes[episodeId]?.let { store.episodes[episodeId] = it.copy(downloadState = DownloadState.QUEUED) }
    }

    override suspend fun requestManual(episodeId: Long) {
        manual += episodeId
    }

    override suspend fun remove(episodeId: Long) {
        removed += episodeId
    }
}

/** The step against the in-memory store: the wiring, and the rules that need state across runs. */
class AutoDownloadTopUpTest {
    private val store = FakeCacheStore()
    private val settings = FakeSettings(Settings(serverUrl = TEST_URL, authToken = TEST_TOKEN, downloadAheadPerFeed = 3))
    private val downloads = RecordingDownloads(store)
    private val topUp = AutoDownloadTopUp(store, settings, downloads)
    private val pull = PullSummary(SyncTrigger.MANUAL, firstPull = false)

    private fun feed(id: Long, order: ListenOrder = ListenOrder.NEWEST_FIRST) {
        store.feeds[id] = cachedFeed(id).copy(priority = id.toInt(), listenOrder = order)
    }

    private fun episode(id: Long, feedId: Long = 1, state: DownloadState = DownloadState.NONE, status: EpisodeStatus = EpisodeStatus.NEW) {
        store.episodes[id] = cachedEpisode(id, feedId, status = status, downloadState = state)
            .copy(publishedAt = id * 100)
    }

    private fun run() = runBlocking { topUp.run(pull) }

    private fun state(id: Long) = store.episodes.getValue(id).downloadState

    @Test
    fun requestsAutomaticDownloadsOnlyNeverManualSoWifiOnlyApplies() {
        feed(1)
        (1L..5).forEach { episode(it) }
        run()
        assertEquals(listOf(5L, 4L, 3L), downloads.automatic)
        assertTrue(downloads.manual.isEmpty())
        assertTrue(downloads.removed.isEmpty())
    }

    @Test
    fun runningTwiceRequestsNothingTheSecondTime() {
        feed(1)
        (1L..5).forEach { episode(it) }
        run()
        val first = downloads.automatic.toList()
        run()
        assertEquals(first, downloads.automatic)
    }

    @Test
    fun theLimitComesFromSettingsAndIsClamped() {
        feed(1)
        (1L..12).forEach { episode(it) }
        settings.flow.value = settings.flow.value.copy(downloadAheadPerFeed = 1)
        run()
        assertEquals(listOf(12L), downloads.automatic)

        downloads.automatic.clear()
        settings.flow.value = settings.flow.value.copy(downloadAheadPerFeed = 500)
        run()
        assertEquals("clamped to ten in total, one already queued", (11L downTo 3L).toList(), downloads.automatic)
    }

    @Test
    fun aListenOrderChangeNeverTouchesDownloadsAndThePlayedOneIsReplacedInTheNewOrder() {
        feed(1)
        (1L..5).forEach { episode(it) }
        run()
        assertEquals(listOf(5L, 4L, 3L), downloads.automatic)

        store.feeds[1] = store.feeds.getValue(1).copy(listenOrder = ListenOrder.OLDEST_FIRST)
        downloads.automatic.clear()
        run()
        assertTrue("the count is still 3, so nothing is requested", downloads.automatic.isEmpty())
        assertTrue(downloads.removed.isEmpty())
        assertEquals(listOf(5L, 4L, 3L), listOf(5L, 4L, 3L).filter { state(it) == DownloadState.QUEUED })

        // One of the three is played: the next one in the new order (oldest) is fetched.
        store.episodes[4] = store.episodes.getValue(4).copy(status = EpisodeStatus.PLAYED)
        run()
        assertEquals(listOf(1L), downloads.automatic)
    }

    @Test
    fun markNewBringsBackAnEpisodeWhoseFileWasDeletedAndItIsFetchedAgain() {
        feed(1)
        (1L..4).forEach { episode(it) }
        run()
        assertEquals(listOf(4L, 3L, 2L), downloads.automatic)

        // 4 is played and its file is deleted by the sweep: it is back to NONE, and 1 takes the free slot.
        store.episodes[4] = store.episodes.getValue(4).copy(status = EpisodeStatus.PLAYED, downloadState = DownloadState.NONE)
        downloads.automatic.clear()
        run()
        assertEquals(listOf(1L), downloads.automatic)

        // Mark New again: the Feed is full again (3, 2, and 1 are in use), so nothing is fetched until there is room.
        store.episodes[4] = store.episodes.getValue(4).copy(status = EpisodeStatus.NEW)
        downloads.automatic.clear()
        run()
        assertTrue(downloads.automatic.isEmpty())

        // Room appears (2 is played): 4 is fetched again because it comes first in Listen Order.
        store.episodes[2] = store.episodes.getValue(2).copy(status = EpisodeStatus.PLAYED)
        run()
        assertEquals(listOf(4L), downloads.automatic)
    }

    @Test
    fun playedEpisodesNeverCountAndAreNeverRequested() {
        feed(1)
        episode(1, state = DownloadState.DOWNLOADED, status = EpisodeStatus.PLAYED)
        episode(2, status = EpisodeStatus.PLAYED)
        episode(3)
        run()
        assertEquals(listOf(3L), downloads.automatic)
    }

    @Test
    fun twoFeedsAreEachToppedUp() {
        feed(1)
        feed(2)
        episode(10, feedId = 1)
        episode(20, feedId = 2)
        episode(21, feedId = 2)
        settings.flow.value = settings.flow.value.copy(downloadAheadPerFeed = 1)
        run()
        assertEquals(listOf(10L, 21L), downloads.automatic)
    }
}
