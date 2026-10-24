package com.podclatch

import com.podclatch.playback.SkipDirection.BACK
import com.podclatch.playback.SkipDirection.FORWARD
import com.podclatch.playback.isSkipKey
import com.podclatch.playback.skipForKeyEvent
import com.podclatch.playback.skipTargetMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipRulesTest {
    private val unknown = -9223372036854775807L // C.TIME_UNSET

    private fun forward(pos: Long, dur: Long, back: Long = 15_000, fwd: Long = 30_000) = skipTargetMs(FORWARD, pos, dur, back, fwd)
    private fun back(pos: Long, dur: Long, back: Long = 15_000, fwd: Long = 30_000) = skipTargetMs(BACK, pos, dur, back, fwd)

    @Test
    fun midEpisodeSkipsByTheAmounts() {
        assertEquals(130_000, forward(100_000, 600_000))
        assertEquals(85_000, back(100_000, 600_000))
    }

    @Test
    fun backClampsAtZero() {
        assertEquals(0, back(10_000, 600_000))
        assertEquals(0, back(15_000, 600_000))
        assertEquals(0, back(0, 600_000))
    }

    @Test
    fun forwardClampsAtTheDurationWhenKnown() {
        assertEquals(60_000, forward(40_000, 60_000))
        assertEquals(60_000, forward(60_000, 60_000))
    }

    @Test
    fun unknownDurationDoesNotClampForward() {
        assertEquals(70_000, forward(40_000, unknown))
        assertEquals(70_000, forward(40_000, 0))
    }

    @Test
    fun changedSkipAmountsApplyToTheNextCall() {
        assertEquals(145_000, forward(100_000, 600_000, back = 10_000, fwd = 45_000))
        assertEquals(90_000, back(100_000, 600_000, back = 10_000, fwd = 45_000))
    }

    @Test
    fun onlyTheFirstKeyDownOfANextOrPreviousPressSkips() {
        assertEquals(FORWARD, skipForKeyEvent(87, 0, 0))
        assertEquals(BACK, skipForKeyEvent(88, 0, 0))
        assertNull(skipForKeyEvent(87, 1, 0)) // key-up
        assertNull(skipForKeyEvent(88, 0, 2)) // repeat of a held button
        assertNull(skipForKeyEvent(85, 0, 0)) // play/pause
        assertNull(skipForKeyEvent(90, 0, 0)) // fast-forward is Media3's own
    }

    @Test
    fun onlyNextAndPreviousAreSkipKeys() {
        assertTrue(isSkipKey(87))
        assertTrue(isSkipKey(88))
        assertFalse(isSkipKey(85))
        assertFalse(isSkipKey(89))
        assertFalse(isSkipKey(90))
    }
}
