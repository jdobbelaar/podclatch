package com.podclatch

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.FeedEpisode
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.RefreshResultDto
import com.podclatch.domain.FailureReason
import com.podclatch.domain.SyncResult
import com.podclatch.domain.changesOnMarkNew
import com.podclatch.domain.changesOnMarkPlayed
import com.podclatch.ui.feeddetail.EpisodeProgress
import com.podclatch.ui.feeddetail.FeedDetailMessage
import com.podclatch.ui.feeddetail.buildFeedRefreshMessage
import com.podclatch.ui.feeddetail.descriptionToPlainText
import com.podclatch.ui.feeddetail.episodeDescription
import com.podclatch.ui.feeddetail.episodeProgress
import com.podclatch.ui.feeddetail.episodeRowStyle
import com.podclatch.ui.feeddetail.formatDuration
import com.podclatch.ui.feeddetail.formatEpisodeDate
import com.podclatch.ui.feeddetail.sortEpisodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class FeedDetailLogicTest {
    private fun ep(id: Long, publishedAt: Long, status: EpisodeStatus = EpisodeStatus.NEW, positionSec: Int = 0, durationSec: Int? = null) =
        FeedEpisode(id, "Episode $id", publishedAt, durationSec, status, positionSec)

    // ---- sort ----

    @Test
    fun newestFirstIsPublishedAtThenIdDescending() {
        val list = listOf(ep(1, 100), ep(2, 300), ep(3, 300), ep(4, 200))
        assertEquals(listOf(3L, 2L, 4L, 1L), sortEpisodes(list, ListenOrder.NEWEST_FIRST).map { it.id })
    }

    @Test
    fun oldestFirstIsTheExactReverse() {
        val list = listOf(ep(1, 100), ep(2, 300), ep(3, 300), ep(4, 200))
        val newest = sortEpisodes(list, ListenOrder.NEWEST_FIRST).map { it.id }
        assertEquals(newest.reversed(), sortEpisodes(list, ListenOrder.OLDEST_FIRST).map { it.id })
        assertEquals(listOf(1L, 4L, 2L, 3L), sortEpisodes(list, ListenOrder.OLDEST_FIRST).map { it.id })
    }

    @Test
    fun theSortDoesNotDependOnTheState() {
        val a = listOf(ep(1, 100), ep(2, 200), ep(3, 300))
        val b = listOf(ep(1, 100, EpisodeStatus.PLAYED), ep(2, 200, positionSec = 5), ep(3, 300, EpisodeStatus.PLAYED))
        for (order in ListenOrder.values()) {
            assertEquals(sortEpisodes(a, order).map { it.id }, sortEpisodes(b, order).map { it.id })
        }
    }

    @Test
    fun sortingAnEmptyListIsEmpty() {
        assertTrue(sortEpisodes(emptyList(), ListenOrder.OLDEST_FIRST).isEmpty())
    }

    // ---- description ----

    @Test
    fun tagsAreRemovedAndBreaksBecomeLineBreaks() {
        assertEquals("One\nTwo", descriptionToPlainText("One<br>Two"))
        assertEquals("One\nTwo", descriptionToPlainText("One<BR/>Two"))
        assertEquals("One\nTwo", descriptionToPlainText("<p>One</p><p>Two</p>"))
        assertEquals("Bold and link", descriptionToPlainText("<b>Bold</b> and <a href=\"https://x.example/?a=1\">link</a>"))
    }

    @Test
    fun commonEntitiesAreDecoded() {
        assertEquals("Tom & Jerry <3 \"quoted\" it's a b", descriptionToPlainText("Tom &amp; Jerry &lt;3 &quot;quoted&quot; it&#39;s a&nbsp;b"))
    }

    @Test
    fun anEncodedTagIsTextNotATag() {
        assertEquals("<b>", descriptionToPlainText("&lt;b&gt;"))
        assertEquals("&lt;", descriptionToPlainText("&amp;lt;"))
    }

    @Test
    fun extraBlankLinesCollapseAndTheTextIsTrimmed() {
        assertEquals("A\n\nB", descriptionToPlainText("  <p>A</p>\n\n\n\n<p>B</p>  "))
        assertEquals("", descriptionToPlainText("   "))
        assertEquals("", descriptionToPlainText(""))
        assertEquals("plain text", descriptionToPlainText("plain text"))
    }

    // ---- date and duration ----

    @Test
    fun theDateUsesTheMediumFormatOfTheLocaleInTheGivenZone() {
        // 2025-10-08 23:30 UTC is already 9 October in Sydney.
        val ms = 1_759_966_200_000L
        assertEquals("Oct 8, 2025", formatEpisodeDate(ms, ZoneId.of("UTC"), Locale.US))
        assertEquals("Oct 9, 2025", formatEpisodeDate(ms, ZoneId.of("Australia/Sydney"), Locale.US))
        assertEquals("8 Oct 2025", formatEpisodeDate(ms, ZoneId.of("UTC"), Locale.UK))
    }

    @Test
    fun durationReadsLessThanAMinuteMinutesOrHoursAndMinutes() {
        assertNull(formatDuration(null))
        assertEquals("<1 min", formatDuration(0))
        assertEquals("<1 min", formatDuration(59))
        assertEquals("1 min", formatDuration(60))
        assertEquals("1 min", formatDuration(119))
        assertEquals("59 min", formatDuration(3_599))
        assertEquals("1 h 00 min", formatDuration(3_600))
        assertEquals("1 h 05 min", formatDuration(3_900 + 59))
        assertEquals("2 h 45 min", formatDuration(2 * 3_600 + 45 * 60 + 30))
    }

    // ---- progress ----

    @Test
    fun progressIsTheFloorPercentLimitedToZeroToHundred() {
        assertEquals(EpisodeProgress.Known(33), episodeProgress(EpisodeStatus.NEW, 1, 3))
        assertEquals(EpisodeProgress.Known(0), episodeProgress(EpisodeStatus.NEW, 1, 1_000))
        assertEquals(EpisodeProgress.Known(50), episodeProgress(EpisodeStatus.NEW, 300, 600))
        assertEquals(EpisodeProgress.Known(99), episodeProgress(EpisodeStatus.NEW, 599, 600))
        assertEquals(EpisodeProgress.Known(100), episodeProgress(EpisodeStatus.NEW, 900, 600))
        assertEquals(EpisodeProgress.Known(100), episodeProgress(EpisodeStatus.NEW, Int.MAX_VALUE, 10))
    }

    @Test
    fun anUnknownDurationShowsStartedWithoutABar() {
        assertEquals(EpisodeProgress.Started, episodeProgress(EpisodeStatus.NEW, 10, null))
        assertEquals(EpisodeProgress.Started, episodeProgress(EpisodeStatus.NEW, 10, 0))
    }

    @Test
    fun noProgressForPlayedOrUnstartedEpisodes() {
        assertNull(episodeProgress(EpisodeStatus.PLAYED, 100, 600))
        assertNull(episodeProgress(EpisodeStatus.NEW, 0, 600))
        assertNull(episodeProgress(EpisodeStatus.NEW, 0, null))
    }

    // ---- row style and description ----

    @Test
    fun playedAndNewRowsDifferInColourWeightAndBackground() {
        val new = episodeRowStyle(EpisodeStatus.NEW)
        val played = episodeRowStyle(EpisodeStatus.PLAYED)
        assertNotEquals(new.titleColor, played.titleColor)
        assertNotEquals(new.titleWeight, played.titleWeight)
        assertNotEquals(new.background, played.background)
    }

    @Test
    fun theRowReadsTitleDateDurationStatusAndProgress() {
        assertEquals(
            "Ep, Oct 8, 2025, 45 min, New, 33 percent played",
            episodeDescription("Ep", "Oct 8, 2025", "45 min", "New", "33 percent played"),
        )
        assertEquals("Ep, Oct 8, 2025, Played", episodeDescription("Ep", "Oct 8, 2025", null, "Played", null))
    }

    // ---- Mark rules ----

    @Test
    fun markPlayedChangesOnlyNewEpisodes() {
        assertTrue(changesOnMarkPlayed(EpisodeStatus.NEW))
        assertFalse(changesOnMarkPlayed(EpisodeStatus.PLAYED))
    }

    @Test
    fun markNewChangesPlayedEpisodesAndNewOnesWithProgress() {
        assertTrue(changesOnMarkNew(EpisodeStatus.PLAYED, 0))
        assertTrue(changesOnMarkNew(EpisodeStatus.PLAYED, 50))
        assertTrue(changesOnMarkNew(EpisodeStatus.NEW, 1))
        assertFalse(changesOnMarkNew(EpisodeStatus.NEW, 0))
    }

    // ---- Refresh message ----

    private val syncOk = SyncResult.Success
    private val syncFailed = SyncResult.Failed(FailureReason.Api(ApiError.Unreachable(false)))

    @Test
    fun aSuccessfulRefreshAndSyncShowsNoMessage() {
        assertNull(buildFeedRefreshMessage(7, listOf(RefreshResultDto(7, true)), syncOk))
    }

    @Test
    fun aFailedFeedShowsTheServersTextCutToTheLimit() {
        val long = "x".repeat(500)
        val message = buildFeedRefreshMessage(7, listOf(RefreshResultDto(7, false, long)), syncOk)
        assertEquals(FeedDetailMessage.FeedRefreshFailed("x".repeat(200)), message)
        assertEquals(
            FeedDetailMessage.FeedRefreshFailed(""),
            buildFeedRefreshMessage(7, listOf(RefreshResultDto(7, false)), syncOk),
        )
    }

    @Test
    fun aFailedFeedWinsOverAFailedSyncAndASyncFailureAloneIsReported() {
        assertTrue(buildFeedRefreshMessage(7, listOf(RefreshResultDto(7, false, "bad")), syncFailed) is FeedDetailMessage.FeedRefreshFailed)
        assertEquals(
            FeedDetailMessage.RefreshSyncFailed(ApiError.Unreachable(false).message),
            buildFeedRefreshMessage(7, listOf(RefreshResultDto(7, true)), syncFailed),
        )
        assertEquals(
            FeedDetailMessage.RefreshSyncFailed(ApiError.Unreachable(false).message),
            buildFeedRefreshMessage(7, emptyList(), syncFailed),
        )
    }
}
