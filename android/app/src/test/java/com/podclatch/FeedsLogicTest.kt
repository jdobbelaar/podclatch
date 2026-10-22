package com.podclatch

import com.podclatch.data.network.ApiError
import com.podclatch.data.network.RefreshResultDto
import com.podclatch.domain.FailureReason
import com.podclatch.domain.SyncResult
import com.podclatch.ui.feeds.DropDecision
import com.podclatch.ui.feeds.FeedsMessage
import com.podclatch.ui.feeds.RefreshFeedError
import com.podclatch.ui.feeds.badgeText
import com.podclatch.ui.feeds.buildRefreshMessage
import com.podclatch.ui.feeds.moveItem
import com.podclatch.ui.common.pullPercent
import com.podclatch.ui.common.pullProgress
import com.podclatch.ui.feeds.resolveDrop
import com.podclatch.ui.feeds.shouldPopFeedDetail
import com.podclatch.ui.common.shouldRefresh
import com.podclatch.ui.nav.Tab
import com.podclatch.ui.nav.tabForRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedsLogicTest {
    // ---- moveItem ----

    @Test
    fun moveFirstToLastAndBack() {
        assertEquals(listOf(2, 3, 1), moveItem(listOf(1, 2, 3), 0, 2))
        assertEquals(listOf(3, 1, 2), moveItem(listOf(1, 2, 3), 2, 0))
        assertEquals(listOf(1, 3, 2), moveItem(listOf(1, 2, 3), 1, 2))
    }

    @Test
    fun movingToTheSamePositionOrOutOfRangeChangesNothing() {
        val list = listOf(1, 2, 3)
        assertSame(list, moveItem(list, 1, 1))
        assertSame(list, moveItem(list, -1, 1))
        assertSame(list, moveItem(list, 0, 3))
        assertSame(list, moveItem(list, 3, 0))
        val single = listOf(1)
        assertSame(single, moveItem(single, 0, 0))
        assertSame(single, moveItem(single, 0, 1))
    }

    // ---- big swipe ----

    @Test
    fun refreshNeedsAPullStrictlyBeyondHalfTheScreen() {
        assertFalse(shouldRefresh(0f, 2000))
        assertFalse(shouldRefresh(999f, 2000))
        assertFalse(shouldRefresh(1000f, 2000))
        assertTrue(shouldRefresh(1000.5f, 2000))
        assertTrue(shouldRefresh(1500f, 2000))
    }

    @Test
    fun progressFillsToTheThresholdAndClamps() {
        assertEquals(0f, pullProgress(0f, 2000), 0f)
        assertEquals(0.5f, pullProgress(500f, 2000), 1e-6f)
        assertEquals(1f, pullProgress(1000f, 2000), 0f)
        assertEquals(1f, pullProgress(5000f, 2000), 0f)
        assertEquals(0f, pullProgress(-5f, 2000), 0f)
        assertEquals(40, pullPercent(400f, 2000))
        assertEquals(100, pullPercent(1200f, 2000))
        assertEquals(0, pullPercent(0f, 0))
    }

    // ---- badge ----

    @Test
    fun badgeShowsTheNumberAnd99Plus() {
        assertEquals("0", badgeText(0))
        assertEquals("7", badgeText(7))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
        assertEquals("99+", badgeText(1234))
    }

    // ---- drop ----

    @Test
    fun dropDecisions() {
        assertEquals(DropDecision.NoChange, resolveDrop(listOf(1, 2, 3), listOf(1, 2, 3)))
        assertEquals(DropDecision.Send(listOf(2, 1, 3)), resolveDrop(listOf(2, 1, 3), listOf(1, 2, 3)))
        assertEquals(DropDecision.ListChanged, resolveDrop(listOf(2, 1, 3), listOf(1, 2, 3, 4)))
        assertEquals(DropDecision.ListChanged, resolveDrop(listOf(2, 1, 3), listOf(1, 2)))
        assertEquals(DropDecision.ListChanged, resolveDrop(listOf(2, 1, 3), listOf(1, 2, 9)))
    }

    // ---- Feed Detail pop rule ----

    @Test
    fun feedDetailPopsOnlyAfterALoadWithoutTheFeed() {
        assertFalse(shouldPopFeedDetail(feedRead = false, feedPresent = false))
        assertFalse(shouldPopFeedDetail(feedRead = true, feedPresent = true))
        assertTrue(shouldPopFeedDetail(feedRead = true, feedPresent = false))
    }

    // ---- tab mapping ----

    @Test
    fun routesMapToTabs() {
        assertEquals(Tab.QUEUE, tabForRoute("com.podclatch.ui.nav.Queue"))
        assertEquals(Tab.FEEDS, tabForRoute("com.podclatch.ui.nav.Feeds"))
        assertNull(tabForRoute("com.podclatch.ui.nav.Subscribe"))
        assertNull(tabForRoute("com.podclatch.ui.nav.FeedDetail/{feedId}"))
        assertNull(tabForRoute(null))
    }

    // ---- Refresh messages ----

    private val synced = SyncResult.Success
    private val titles = mapOf(1L to "One", 2L to "Two", 3L to "Three", 4L to "Four", 5L to "Five")

    @Test
    fun noMessageWhenEverythingWorked() {
        assertNull(buildRefreshMessage(listOf(RefreshResultDto(1, true)), synced, titles))
        assertNull(buildRefreshMessage(emptyList(), synced, titles))
    }

    @Test
    fun partialFailureListsUpToThreeFeedsAndCountsTheRest() {
        val results = (1L..5L).map { RefreshResultDto(it, ok = it % 2 == 1L, error = if (it % 2 == 1L) null else "bad $it") } +
            RefreshResultDto(9, false, "x".repeat(300)) + RefreshResultDto(10, false, "y") + RefreshResultDto(11, false, "z")
        val message = buildRefreshMessage(results, synced, titles) as FeedsMessage.RefreshPartial
        assertEquals(5, message.failed)
        assertEquals(8, message.total)
        assertEquals(
            listOf(RefreshFeedError("Two", "bad 2"), RefreshFeedError("Four", "bad 4"), RefreshFeedError(null, "x".repeat(200))),
            message.lines,
        )
        assertEquals(2, message.more)
    }

    @Test
    fun aSyncFailureAfterACleanRefreshIsReported() {
        val failed = SyncResult.Failed(FailureReason.Api(ApiError.Unreachable(false)))
        val message = buildRefreshMessage(listOf(RefreshResultDto(1, true)), failed, titles)
        assertEquals(FeedsMessage.RefreshSyncFailed("Cannot reach the server."), message)
    }
}
