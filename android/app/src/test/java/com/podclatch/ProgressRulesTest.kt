package com.podclatch

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.playback.effectiveDurationMs
import com.podclatch.playback.playedAtStop
import com.podclatch.playback.recordsPlayerEvents
import com.podclatch.playback.startPositionMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressRulesTest {
    @Test
    fun ninetyFourPointNinePercentIsNotPlayed() {
        assertFalse(playedAtStop(positionMs = 94_900, durationMs = 100_000))
    }

    @Test
    fun exactlyNinetyFivePercentIsPlayed() {
        assertTrue(playedAtStop(positionMs = 95_000, durationMs = 100_000))
    }

    @Test
    fun theEndAndBeyondArePlayed() {
        assertTrue(playedAtStop(positionMs = 100_000, durationMs = 100_000))
        assertTrue(playedAtStop(positionMs = 100_500, durationMs = 100_000))
    }

    @Test
    fun anUnknownDurationNeverCountsAtStop() {
        assertFalse(playedAtStop(positionMs = 10_000_000, durationMs = null))
        assertFalse(playedAtStop(positionMs = 10_000_000, durationMs = 0))
    }

    @Test
    fun theArithmeticIsExactForLongEpisodes() {
        // 3 hours: 95 percent is 10_260_000 ms; one millisecond less is not enough.
        assertFalse(playedAtStop(10_259_999, 10_800_000))
        assertTrue(playedAtStop(10_260_000, 10_800_000))
    }

    @Test
    fun thePlayersDurationWinsOverTheFeedsDuration() {
        assertEquals(120_000L, effectiveDurationMs(playerDurationMs = 120_000, durationSec = 30))
        assertEquals(30_000L, effectiveDurationMs(playerDurationMs = null, durationSec = 30))
        assertEquals(30_000L, effectiveDurationMs(playerDurationMs = 0, durationSec = 30))
        assertNull(effectiveDurationMs(playerDurationMs = null, durationSec = null))
        assertNull(effectiveDurationMs(playerDurationMs = null, durationSec = 0))
    }

    @Test
    fun aNewEpisodeStartsAtItsSavedPosition() {
        assertEquals(12_000L, startPositionMs(EpisodeStatus.NEW, 12, 600_000))
        assertEquals(12_000L, startPositionMs(EpisodeStatus.NEW, 12, null))
    }

    @Test
    fun aZeroPositionAndAPlayedEpisodeStartAtZero() {
        assertEquals(0L, startPositionMs(EpisodeStatus.NEW, 0, 600_000))
        assertEquals(0L, startPositionMs(EpisodeStatus.PLAYED, 500, 600_000))
    }

    @Test
    fun aPositionAtOrBeyondTheDurationStartsAtZero() {
        assertEquals(0L, startPositionMs(EpisodeStatus.NEW, 30, 30_000))
        assertEquals(0L, startPositionMs(EpisodeStatus.NEW, 31, 30_000))
        assertEquals(29_000L, startPositionMs(EpisodeStatus.NEW, 29, 30_000))
    }

    @Test
    fun anErrorOrNoItemMeansNoRecording() {
        assertTrue(recordsPlayerEvents(hasItem = true, hasError = false))
        assertFalse(recordsPlayerEvents(hasItem = true, hasError = true))
        assertFalse(recordsPlayerEvents(hasItem = false, hasError = false))
    }
}
