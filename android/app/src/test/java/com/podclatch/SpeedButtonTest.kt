package com.podclatch

import com.podclatch.domain.LabelCache
import com.podclatch.domain.PlaybackSpeedState
import com.podclatch.domain.SpeedIconSpec
import com.podclatch.domain.SpeedMode
import com.podclatch.domain.speedButtonLabel
import com.podclatch.domain.speedIconResName
import com.podclatch.playback.SPEED_COMMAND_ACTION
import com.podclatch.playback.SpeedCommandHandler
import com.podclatch.playback.isSpeedAction
import com.podclatch.playback.isSpeedIconLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the session's Speed button (US-16 part 2); the JVM tests use no Android runtime. */
class SpeedButtonTest {
    private fun label(assigned: Float, mode: SpeedMode) = speedButtonLabel(PlaybackSpeedState(assigned, mode))

    @Test
    fun theLabelReadsTheCurrentSpeed() {
        assertEquals("1.25x", label(1.25f, SpeedMode.ASSIGNED))
        assertEquals("1.0x", label(1.25f, SpeedMode.NORMAL))
        assertEquals("0.5x", label(0.5f, SpeedMode.ASSIGNED))
        assertEquals("3.0x", label(3.0f, SpeedMode.ASSIGNED))
        assertEquals("1.3x", label(1.3f, SpeedMode.ASSIGNED))
    }

    @Test
    fun whileNormalTheLabelIgnoresTheAssignedSpeed() {
        assertEquals(label(1.5f, SpeedMode.NORMAL), label(2.0f, SpeedMode.NORMAL))
    }

    @Test
    fun theIconIsASensibleSquareWithEnoughContrast() {
        assertEquals(96, SpeedIconSpec.SIZE_PX)
        assertTrue(SpeedIconSpec.contrast(SpeedIconSpec.TEXT_COLOR, SpeedIconSpec.SURFACE_COLOR) >= 3.0)
        assertEquals(21.0, SpeedIconSpec.contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.001)
        assertEquals(1.0, SpeedIconSpec.contrast(0xFF808080.toInt(), 0xFF808080.toInt()), 0.001)
    }

    @Test
    fun theTextFitsTheIconForTheWidestLabel() {
        // Every label has at most five characters; a bold character is about 0.55 em wide on average.
        val widest = "1.25x"
        assertTrue(widest.length * SpeedIconSpec.textSizePx(widest) * 0.55f <= SpeedIconSpec.SIZE_PX)
        assertTrue("1.0x".length * SpeedIconSpec.textSizePx("1.0x") * 0.55f <= SpeedIconSpec.SIZE_PX)
        assertTrue(SpeedIconSpec.textSizePx("1.0x") > SpeedIconSpec.textSizePx(widest))
    }

    @Test
    fun theIconIsBuiltOnlyWhenTheTextChanges() {
        val cache = LabelCache { "icon for $it" }
        val first = cache.get("1.25x")
        assertSame(first, cache.get("1.25x"))
        assertEquals(1, cache.builds)
        assertEquals("icon for 1.0x", cache.get("1.0x"))
        assertEquals(2, cache.builds)
        cache.get("1.25x")
        assertEquals(3, cache.builds)
    }

    @Test
    fun onlyTheSpeedActionIsTheSpeedCommand() {
        assertTrue(isSpeedAction(SPEED_COMMAND_ACTION))
        assertFalse(isSpeedAction("com.podclatch.session.OTHER"))
        assertTrue(SPEED_COMMAND_ACTION.startsWith("com.podclatch."))
    }

    @Test
    fun theIconProviderAnswersOnlyForASpeedLabel() {
        listOf("1.25x", "1.0x", "0.5x", "3.0x", "1.3x").forEach { assertTrue(it, isSpeedIconLabel(it)) }
        listOf("", "x", "1x", "../1.0x", "1.0", "1.234x", "abc", "1.0x/../../x").forEach { assertFalse(it, isSpeedIconLabel(it)) }
        // Every label the speed rules can produce is one the provider serves.
        var tenths = 5
        while (tenths <= 30) {
            assertTrue(isSpeedIconLabel(speedButtonLabel(PlaybackSpeedState(tenths / 10f, SpeedMode.ASSIGNED))))
            tenths++
        }
    }

    @Test
    fun everySpeedLabelHasADrawableForControllersThatOnlyTakeAResource() {
        val dir = java.io.File("src/main/res/drawable-nodpi")
        val labels = (5..30).map { speedButtonLabel(PlaybackSpeedState(it / 10f, SpeedMode.ASSIGNED)) } + "1.25x"
        assertEquals(27, labels.toSet().size)
        labels.forEach { assertTrue(it, java.io.File(dir, speedIconResName(it) + ".png").isFile) }
        assertEquals("ic_speed_1_25x", speedIconResName("1.25x"))
    }

    @Test
    fun theSpeedCommandTogglesOnceAndAnotherCommandDoesNothing() = runBlocking<Unit> {
        var toggles = 0
        val handler = SpeedCommandHandler { toggles++ }
        assertTrue(handler.handleAction(SPEED_COMMAND_ACTION))
        assertEquals(1, toggles)
        assertFalse(handler.handleAction("com.podclatch.session.OTHER"))
        assertEquals(1, toggles)
        assertTrue(handler.handleAction(SPEED_COMMAND_ACTION))
        assertEquals(2, toggles)
    }
}
