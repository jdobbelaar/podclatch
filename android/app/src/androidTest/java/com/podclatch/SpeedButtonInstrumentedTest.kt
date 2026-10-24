package com.podclatch

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.repo.Settings
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.debug.DebugEntryPoint
import com.podclatch.domain.PlaybackSpeedUseCase
import com.podclatch.domain.SpeedMode
import com.podclatch.playback.SPEED_COMMAND_ACTION
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The Speed button of the session (US-16 part 2), through a real Media3 controller on the real
 * `PlaybackService` and the real settings store, with #26's harness. The speed setting is saved before
 * each test and put back after it.
 */
class SpeedButtonInstrumentedTest {
    private val h = AutoHarness()
    private val settings: SettingsRepository =
        EntryPointAccessors.fromApplication(h.context.applicationContext, DebugEntryPoint::class.java).settings()
    private val useCase = PlaybackSpeedUseCase(settings)
    private lateinit var saved: Settings

    @Before
    fun startAssignedAtOnePointThree() {
        saved = runBlocking { settings.current() }
        runBlocking { settings.assignSpeed(1.3f) }
        awaitStored(SpeedMode.ASSIGNED, 1.3f)
        h.connect()
        awaitLabel("1.3x")
    }

    @After
    fun putTheSettingsBack() {
        h.release()
        runBlocking {
            h.store.transaction {
                deletePending(EPISODE)
                deleteFeed(FEED)
            }
            settings.assignSpeed(saved.assignedSpeed)
            if (saved.speedMode == SpeedMode.NORMAL) settings.toggleSpeedMode()
        }
    }

    private fun stored(): Settings = runBlocking { settings.current() }

    private fun awaitStored(mode: SpeedMode, assigned: Float) =
        h.awaitTrue("stored mode $mode with assigned speed $assigned", timeoutMs = 2_000) {
            stored().let { it.speedMode == mode && it.assignedSpeed == assigned }
        }

    private fun speedButton(): CommandButton? =
        h.onMain { h.connect().mediaButtonPreferences.firstOrNull { it.sessionCommand?.customAction == SPEED_COMMAND_ACTION } }

    private fun awaitLabel(label: String) =
        h.awaitTrue("the Speed button reads $label", timeoutMs = 2_000) { speedButton()?.displayName?.toString() == label }

    private fun press(): SessionResult {
        val browser = h.connect()
        return h.onMain { browser.sendCustomCommand(SessionCommand(SPEED_COMMAND_ACTION, Bundle.EMPTY), Bundle.EMPTY) }
            .get(5, TimeUnit.SECONDS)
    }

    private fun loadTone() {
        val browser = h.connect()
        val file = h.wav("eng80-tone.wav", 30).path
        runBlocking {
            h.store.transaction {
                insertFeedIfAbsent(autoFeed(FEED, 910_000, ListenOrder.OLDEST_FIRST, "Speed feed"))
                insertEpisodeIfAbsent(autoEpisode(EPISODE, FEED, "Speed tone", 10, DownloadState.DOWNLOADED, durationSec = 30, localPath = file))
            }
        }
        val item = h.children("feed:$FEED").first { it.mediaId == EPISODE.toString() }
        h.onMain {
            browser.setMediaItem(item)
            browser.prepare()
        }
        h.awaitTrue("the tone is ready", main = true) { browser.playbackState == Player.STATE_READY }
    }

    private fun awaitPlayerSpeed(speed: Float) {
        val browser = h.connect()
        h.awaitTrue("the player speed is $speed", timeoutMs = 2_000, main = true) { browser.playbackParameters.speed == speed }
    }

    private companion object {
        const val FEED = 80_000_001L
        const val EPISODE = 80_000_011L
    }

    @Test
    fun theLayoutIsSkipBackSkipForwardThenSpeedAndNothingElse() {
        val browser = h.connect()
        h.onMain {
            val buttons = browser.mediaButtonPreferences
            assertEquals(3, buttons.size)
            assertEquals(Player.COMMAND_SEEK_BACK, buttons[0].playerCommand)
            assertTrue(buttons[0].slots.contains(CommandButton.SLOT_BACK))
            assertEquals(Player.COMMAND_SEEK_FORWARD, buttons[1].playerCommand)
            assertTrue(buttons[1].slots.contains(CommandButton.SLOT_FORWARD))
            val speedButton = buttons[2]
            assertEquals(SPEED_COMMAND_ACTION, speedButton.sessionCommand?.customAction)
            assertEquals("1.3x", speedButton.displayName.toString())
            assertNotNull("the generated icon address", speedButton.iconUri)
            assertFalse(speedButton.slots.contains(CommandButton.SLOT_BACK) || speedButton.slots.contains(CommandButton.SLOT_FORWARD))
            // Exactly one custom command is available: no long-press and no speed assignment.
            val custom = browser.availableSessionCommands.commands.filter { it.commandCode == SessionCommand.COMMAND_CODE_CUSTOM }
            assertEquals(listOf(SPEED_COMMAND_ACTION), custom.map { it.customAction })
        }
    }

    @Test
    fun theSessionStillOffersNoNextOrPrevious() {
        loadTone()
        val browser = h.connect()
        h.onMain {
            assertFalse(browser.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
            assertFalse(browser.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            assertFalse(browser.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
            assertTrue(browser.mediaButtonPreferences.none { it.playerCommand == Player.COMMAND_SEEK_TO_NEXT || it.playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS })
        }
    }

    @Test
    fun pressingTheCommandTogglesTheModeThePlayerAndTheLabel() {
        loadTone()
        awaitPlayerSpeed(1.3f)
        assertEquals(SessionResult.RESULT_SUCCESS, press().resultCode)
        awaitStored(SpeedMode.NORMAL, 1.3f)
        awaitPlayerSpeed(1.0f)
        awaitLabel("1.0x")
        assertEquals(SessionResult.RESULT_SUCCESS, press().resultCode)
        awaitStored(SpeedMode.ASSIGNED, 1.3f)
        awaitPlayerSpeed(1.3f)
        awaitLabel("1.3x")
    }

    @Test
    fun pressingWithNothingLoadedStillFlipsTheMode() {
        assertEquals(SessionResult.RESULT_SUCCESS, press().resultCode)
        awaitStored(SpeedMode.NORMAL, 1.3f)
        awaitLabel("1.0x")
    }

    @Test
    fun aChangeFromAnotherSourceUpdatesTheLabel() {
        // The phone's button uses the same use case.
        runBlocking { useCase.toggle() }
        awaitLabel("1.0x")
        runBlocking { useCase.toggle() }
        awaitLabel("1.3x")
        // A new assigned speed while ASSIGNED.
        runBlocking { useCase.assign(1.5f) }
        awaitLabel("1.5x")
        // Assigning while NORMAL switches to ASSIGNED (spec 5.3), so the label follows it.
        runBlocking { useCase.toggle() }
        awaitLabel("1.0x")
        runBlocking { useCase.assign(2.0f) }
        awaitLabel("2.0x")
    }
}
