package com.podclatch

import com.podclatch.ui.queue.PlayedBar
import com.podclatch.ui.queue.PlayedBarDark
import com.podclatch.ui.queue.PlayedBarLight
import com.podclatch.ui.queue.QueueTime
import com.podclatch.ui.queue.contrastRatio
import com.podclatch.ui.queue.downloadPercent
import com.podclatch.ui.queue.playedBar
import com.podclatch.ui.queue.queueRowDescription
import com.podclatch.ui.queue.queueTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueLogicTest {
    // ---- the time on a row ----

    @Test
    fun withoutProgressTheRowShowsTheDuration() {
        assertEquals(QueueTime("<1 min", left = false), queueTime(durationSec = 59, positionSec = 0))
        assertEquals(QueueTime("12 min", left = false), queueTime(durationSec = 12 * 60 + 30, positionSec = 0))
        assertEquals(QueueTime("1 h 05 min", left = false), queueTime(durationSec = 65 * 60, positionSec = 0))
    }

    @Test
    fun withProgressTheRowShowsTheTimeLeft() {
        assertEquals(QueueTime("10 min", left = true), queueTime(durationSec = 20 * 60, positionSec = 10 * 60))
        assertEquals(QueueTime("1 h 00 min", left = true), queueTime(durationSec = 90 * 60, positionSec = 30 * 60))
        assertEquals(QueueTime("<1 min", left = true), queueTime(durationSec = 600, positionSec = 570))
    }

    @Test
    fun theTimeLeftIsNeverBelowZero() {
        assertEquals(QueueTime("<1 min", left = true), queueTime(durationSec = 600, positionSec = 600))
        assertEquals(QueueTime("<1 min", left = true), queueTime(durationSec = 600, positionSec = 5_000))
    }

    @Test
    fun anUnknownDurationShowsNothing() {
        assertNull(queueTime(durationSec = null, positionSec = 0))
        assertNull(queueTime(durationSec = null, positionSec = 300))
    }

    // ---- the download bar ----

    @Test
    fun theDownloadPercentIsTheFloorLimitedToZeroToHundred() {
        assertEquals(0, downloadPercent(0, 1_000))
        assertEquals(49, downloadPercent(499, 1_000))
        assertEquals(50, downloadPercent(500, 1_000))
        assertEquals(99, downloadPercent(999, 1_000))
        assertEquals(100, downloadPercent(1_000, 1_000))
        assertEquals(100, downloadPercent(2_000, 1_000))
        assertEquals(0, downloadPercent(-5, 1_000))
    }

    @Test
    fun anUnknownTotalMakesTheBarIndeterminate() {
        assertNull(downloadPercent(500, null))
        assertNull(downloadPercent(500, 0))
    }

    @Test
    fun theDownloadPercentDoesNotOverflowForHugeFiles() {
        assertEquals(50, downloadPercent(Long.MAX_VALUE / 2, Long.MAX_VALUE))
    }

    // ---- the playback bar ----

    @Test
    fun theFillIsPositionOverDurationLimitedToZeroToOne() {
        val half = playedBar(positionSec = 300, durationSec = 600)
        assertEquals(PlayedBar.Known(50, 0.5f), half)
        assertEquals(PlayedBar.Known(0, 0f), playedBar(positionSec = 0, durationSec = 600))
        assertEquals(PlayedBar.Known(100, 1f), playedBar(positionSec = 600, durationSec = 600))
        assertEquals(PlayedBar.Known(100, 1f), playedBar(positionSec = 9_999, durationSec = 600))
    }

    @Test
    fun thePercentIsTheFloor() {
        assertEquals(33, (playedBar(positionSec = 1, durationSec = 3) as PlayedBar.Known).percent)
        assertEquals(66, (playedBar(positionSec = 2, durationSec = 3) as PlayedBar.Known).percent)
    }

    @Test
    fun anUnknownDurationLeavesTheBarUnfilled() {
        assertEquals(PlayedBar.Started, playedBar(positionSec = 40, durationSec = null))
        assertEquals(0f, playedBar(positionSec = 40, durationSec = null).fraction)
        assertEquals(PlayedBar.NotStarted, playedBar(positionSec = 0, durationSec = null))
        assertEquals(PlayedBar.Started, playedBar(positionSec = 40, durationSec = 0))
    }

    @Test
    fun theTwoBarColoursContrastAtLeastThreeToOneInBothThemes() {
        for ((name, palette) in listOf("light" to PlayedBarLight, "dark" to PlayedBarDark)) {
            val ratio = contrastRatio(palette.unplayed, palette.played)
            assertTrue("$name contrast was $ratio", ratio >= 3.0)
        }
    }

    @Test
    fun theContrastRatioMatchesTheKnownExtremes() {
        assertEquals(21.0, contrastRatio(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, contrastRatio(0xFF123456, 0xFF123456), 0.0001)
    }

    // ---- the row description ----

    @Test
    fun theDescriptionJoinsTitleFeedTimeAndPlayedText() {
        assertEquals(
            "Tone A, Tones, 12 min, Not started",
            queueRowDescription("Tone A", "Tones", "12 min", "Not started", nowPlayingText = null),
        )
    }

    @Test
    fun theDescriptionLeavesOutAnUnknownTimeAndAddsNowPlaying() {
        assertEquals(
            "Tone A, Tones, Started, now playing",
            queueRowDescription("Tone A", "Tones", timeText = null, playedText = "Started", nowPlayingText = "now playing"),
        )
    }
}
