package com.podclatch

import com.podclatch.playback.PlaybackPhase
import com.podclatch.playback.PlaybackUiState
import com.podclatch.ui.player.SeekDrag
import com.podclatch.ui.player.fractionToMs
import com.podclatch.ui.player.formatClock
import com.podclatch.ui.player.formatRemaining
import com.podclatch.ui.player.miniPlayerVisible
import com.podclatch.ui.player.positionTicks
import com.podclatch.ui.player.sliderFraction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerLogicTest {
    @Test
    fun theMiniPlayerIsVisibleExactlyWhileAnEpisodeIsLoaded() {
        assertFalse(miniPlayerVisible(PlaybackUiState()))
        assertTrue(miniPlayerVisible(PlaybackUiState(episodeId = 4, isPlaying = true, phase = PlaybackPhase.READY)))
        // Paused after a stop: still loaded, still visible.
        assertTrue(miniPlayerVisible(PlaybackUiState(episodeId = 4, isPlaying = false, phase = PlaybackPhase.IDLE)))
    }

    @Test
    fun formatClockUsesMinutesThenHours() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:09", formatClock(9_999))
        assertEquals("1:05", formatClock(65_000))
        assertEquals("59:59", formatClock(3_599_000))
        assertEquals("1:00:00", formatClock(3_600_000))
        assertEquals("2:03:04", formatClock(7_384_000))
        assertEquals("0:00", formatClock(-5_000))
    }

    @Test
    fun theRemainingTimeIsNegativeOrUnknown() {
        assertEquals("-1:55", formatRemaining(5_000, 120_000))
        assertEquals("-1:00:00", formatRemaining(0, 3_600_000))
        assertEquals("-0:00", formatRemaining(130_000, 120_000))
        assertEquals("--:--", formatRemaining(5_000, null))
        assertEquals("--:--", formatRemaining(5_000, 0))
    }

    @Test
    fun elapsedPlusRemainingStaysWithinOneSecondOfTheDuration() {
        val duration = 600_000L
        for (position in listOf(0L, 999L, 1_000L, 59_999L, 123_456L, 599_999L)) {
            val elapsed = parse(formatClock(position))
            val remaining = parse(formatRemaining(position, duration).removePrefix("-"))
            assertTrue("$position: ${elapsed + remaining}", duration / 1000 - (elapsed + remaining) in 0..1)
        }
    }

    private fun parse(clock: String): Long = clock.split(":").map { it.toLong() }.fold(0L) { acc, n -> acc * 60 + n }

    @Test
    fun theSliderFractionAndItsInverse() {
        assertEquals(0.5f, sliderFraction(60_000, 120_000), 0.0001f)
        assertEquals(0f, sliderFraction(60_000, null), 0f)
        assertEquals(1f, sliderFraction(200_000, 120_000), 0f)
        assertEquals(115_200L, fractionToMs(0.96f, 120_000))
        assertEquals(0L, fractionToMs(0.5f, null))
        assertEquals(120_000L, fractionToMs(2f, 120_000))
    }

    @Test
    fun draggingShowsTheDraggedTimeAndSeeksOnceOnRelease() {
        val duration = 120_000L
        var drag = SeekDrag()
        assertFalse(drag.dragging)
        assertEquals(0.25f, drag.displayFraction(30_000, duration), 0.0001f)
        assertNull(drag.releaseTarget(duration))

        drag = drag.onChange(0.5f)
        assertTrue(drag.dragging)
        // The polled position moves on but the thumb and the time stay where the finger is.
        assertEquals(0.5f, drag.displayFraction(31_000, duration), 0f)
        assertEquals(60_000L, drag.displayPositionMs(31_000, duration))

        drag = drag.onChange(0.96f)
        assertEquals(0.96f, drag.displayFraction(32_000, duration), 0f)
        assertEquals(115_200L, drag.releaseTarget(duration))

        drag = SeekDrag()
        assertEquals(33_000L, drag.displayPositionMs(33_000, duration))
    }

    @Test
    fun theDragFractionIsLimitedToTheBar() {
        assertEquals(1f, SeekDrag().onChange(3f).fraction!!, 0f)
        assertEquals(0f, SeekDrag().onChange(-1f).fraction!!, 0f)
    }

    @Test
    fun thePositionTicksComeEveryPollPeriodOnVirtualTime() = runTest {
        val seen = mutableListOf<Long>()
        positionTicks(500).take(3).collect { seen += currentTime }
        assertEquals(listOf(500L, 1_000L, 1_500L), seen)
    }
}
