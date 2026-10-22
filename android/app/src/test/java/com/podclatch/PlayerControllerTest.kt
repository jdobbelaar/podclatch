package com.podclatch

import com.podclatch.domain.PlaybackSpeedState
import com.podclatch.domain.PlaybackSpeedUseCase
import com.podclatch.domain.SpeedMode
import com.podclatch.playback.PlaybackPhase
import com.podclatch.playback.PlaybackUiState
import com.podclatch.ui.player.PlayerController
import com.podclatch.ui.player.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player screens' logic with a fake playback controller, fake settings, and hand-driven ticks. */
class PlayerControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = FakeSettings()
    private val playback = FakePlaybackController(knownIds = setOf(1L, 2L))
    private val ticks = Channel<Unit>(Channel.UNLIMITED)
    private val controller = PlayerController(playback, PlaybackSpeedUseCase(settings), settings, scope, scope, ticks.receiveAsFlow())

    init {
        // Keeps the state flow hot, as the screen does.
        scope.launch { controller.uiState.collect { } }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun PlayerController.await(what: String, condition: (PlayerUiState) -> Boolean) =
        awaitState(uiState, what, condition = condition)

    private fun loaded(durationMs: Long? = 120_000) = playback.setState(
        PlaybackUiState(episodeId = 1, isPlaying = true, phase = PlaybackPhase.READY, title = "Episode 1", durationMs = durationMs),
    )

    @Test
    fun theStateStartsWithTheDefaultsAndNothingLoaded() {
        val state = controller.await("defaults") { it.skipBackSec == 15 && it.skipForwardSec == 30 }
        assertEquals(null, state.playback.episodeId)
        assertEquals(PlaybackSpeedState(1.25f, SpeedMode.ASSIGNED), state.speed)
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun thePositionIsReadOnEveryTickWhileAnEpisodeIsLoaded() {
        playback.positionMs = 5_000
        loaded()
        controller.await("position 5000 read on start") { it.positionMs == 5_000L }

        playback.positionMs = 6_000
        ticks.trySend(Unit)
        controller.await("position 6000 after a tick") { it.positionMs == 6_000L }
    }

    @Test
    fun thePositionIsZeroWhenNothingIsLoadedAnyMore() {
        playback.positionMs = 7_000
        loaded()
        controller.await("position 7000") { it.positionMs == 7_000L }

        playback.setState(PlaybackUiState())
        controller.await("unloaded: position 0") { it.playback.episodeId == null && it.positionMs == 0L }
    }

    @Test
    fun aSeekIsSentOnceAndTheNewPositionShowsWithoutWaitingForATick() {
        loaded()
        controller.await("loaded") { it.playback.episodeId == 1L }

        controller.seekTo(115_200)

        assertEquals(listOf(115_200L), playback.seeks.toList())
        controller.await("position 115200 shown") { it.positionMs == 115_200L }
    }

    @Test
    fun skipsGoThroughTheControllerAndRefreshThePosition() {
        playback.positionMs = 20_000
        loaded()
        controller.await("position 20000") { it.positionMs == 20_000L }

        playback.positionMs = 5_000
        controller.skipBack()
        controller.await("position 5000 after skip back") { it.positionMs == 5_000L }
        playback.positionMs = 50_000
        controller.skipForward()
        controller.await("position 50000 after skip forward") { it.positionMs == 50_000L }

        assertEquals(1, playback.skipBacks.get())
        assertEquals(1, playback.skipForwards.get())
    }

    @Test
    fun playPauseFollowsTheSession() {
        loaded()
        controller.await("playing") { it.playback.isPlaying }
        controller.playPause()
        controller.await("paused") { !it.playback.isPlaying }
        controller.playPause()
        controller.await("playing again") { it.playback.isPlaying }
    }

    @Test
    fun theSkipSecondsFollowTheSettingsLive() {
        controller.await("defaults") { it.skipBackSec == 15 }
        runBlocking { settings.setSkipIntervals(10, 45) }
        val state = controller.await("10 and 45") { it.skipBackSec == 10 && it.skipForwardSec == 45 }
        assertTrue(state.skipBackSec < state.skipForwardSec)
    }

    @Test
    fun aTapOnTheSpeedButtonTogglesBetweenAssignedAndNormal() {
        controller.await("assigned 1.25") { it.speed == PlaybackSpeedState(1.25f, SpeedMode.ASSIGNED) }
        controller.toggleSpeed()
        controller.await("normal: current speed 1.0") { it.speed.mode == SpeedMode.NORMAL && it.speed.currentSpeed == 1.0f }
        controller.toggleSpeed()
        controller.await("assigned: current speed 1.25") { it.speed.mode == SpeedMode.ASSIGNED && it.speed.currentSpeed == 1.25f }
    }

    @Test
    fun whenTheAssignedSpeedIsOneATapFlipsTheModeAndTheSpeedStaysOne() {
        runBlocking { settings.assignSpeed(1.0f) }
        controller.await("assigned 1.0") { it.speed == PlaybackSpeedState(1.0f, SpeedMode.ASSIGNED) }
        controller.toggleSpeed()
        val state = controller.await("normal at 1.0") { it.speed.mode == SpeedMode.NORMAL }
        assertEquals(1.0f, state.speed.currentSpeed, 0f)
    }

    @Test
    fun setAssignsTheChosenSpeedAndLeavesNormalMode() {
        controller.toggleSpeed()
        controller.await("normal") { it.speed.mode == SpeedMode.NORMAL }

        controller.confirmSpeed(2.0f, 1.25f)

        controller.await("assigned 2.0") { it.speed == PlaybackSpeedState(2.0f, SpeedMode.ASSIGNED) }
    }

    @Test
    fun setWithoutTouchingKeepsTheOddDefaultAndLeavesNormalMode() {
        controller.toggleSpeed()
        controller.await("normal") { it.speed.mode == SpeedMode.NORMAL }

        controller.confirmSpeed(1.25f, 1.25f)

        controller.await("assigned 1.25 exactly") { it.speed == PlaybackSpeedState(1.25f, SpeedMode.ASSIGNED) }
    }
}
