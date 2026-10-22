package com.podclatch

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.podclatch.data.repo.DataStoreSettingsRepository
import com.podclatch.data.repo.Settings
import com.podclatch.domain.PlaybackSpeedState
import com.podclatch.domain.PlaybackSpeedUseCase
import com.podclatch.domain.SpeedMode
import com.podclatch.domain.formatSpeed
import com.podclatch.domain.normalizeSpeed
import com.podclatch.domain.stepSpeed
import com.podclatch.playback.PlaybackSpeedApplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/** The speed rules (spec 5.3): the use case on a real DataStore, the pure helpers, and the applier. */
class PlaybackSpeedTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun repo(file: File = File(tmp.root, "settings.preferences_pb")) =
        DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })

    // ---- defaults and persistence ----

    @Test
    fun theDefaultsAreOnePointTwoFiveAndAssigned() = runBlocking {
        val settings = repo().current()
        assertEquals(1.25f, settings.assignedSpeed, 0f)
        assertEquals(SpeedMode.ASSIGNED, settings.speedMode)
        assertEquals(1.25f, Settings().assignedSpeed, 0f)
        assertEquals(SpeedMode.ASSIGNED, Settings().speedMode)
    }

    @Test
    fun theAssignedSpeedAndModeAreStoredInDataStoreAndReadBack() = runBlocking {
        // One store per file (DataStore forbids two at once), so persistence is the stored round trip.
        val repo = repo()
        PlaybackSpeedUseCase(repo).apply { assign(2.0f); toggle() }
        val stored = repo.current()
        assertEquals(2.0f, stored.assignedSpeed, 0f)
        assertEquals(SpeedMode.NORMAL, stored.speedMode)
    }

    @Test
    fun toStringStillRedactsTheTokenAndShowsTheSpeed() {
        val text = Settings(authToken = "secret-token-value", assignedSpeed = 1.5f, speedMode = SpeedMode.NORMAL).toString()
        assertFalse(text.contains("secret-token-value"))
        assertTrue(text.contains("authToken=***"))
        assertTrue(text.contains("assignedSpeed=1.5"))
        assertTrue(text.contains("speedMode=NORMAL"))
    }

    // ---- the use case ----

    @Test
    fun toggleFlipsTheModeAndChangesNothingElse() = runBlocking {
        val repo = repo()
        repo.saveConnection("https://pod.example.com", "secret-token-value")
        val useCase = PlaybackSpeedUseCase(repo)
        val before = repo.current()

        useCase.toggle()
        val flipped = repo.current()
        assertEquals(SpeedMode.NORMAL, flipped.speedMode)
        assertEquals(before.copy(speedMode = SpeedMode.NORMAL), flipped)

        useCase.toggle()
        assertEquals(before, repo.current())
    }

    @Test
    fun toggleFlipsEvenWhenTheAssignedSpeedIsOne() = runBlocking {
        val repo = repo()
        val useCase = PlaybackSpeedUseCase(repo)
        useCase.assign(1.0f)
        useCase.toggle()
        assertEquals(SpeedMode.NORMAL, repo.current().speedMode)
        assertEquals(1.0f, repo.current().assignedSpeed, 0f)
        useCase.toggle()
        assertEquals(SpeedMode.ASSIGNED, repo.current().speedMode)
    }

    @Test
    fun twoQuickTogglesAreNotLost() = runBlocking {
        val repo = repo()
        val useCase = PlaybackSpeedUseCase(repo)
        (1..2).map { async(Dispatchers.IO) { useCase.toggle() } }.awaitAll()
        assertEquals(SpeedMode.ASSIGNED, repo.current().speedMode)
        (1..3).map { async(Dispatchers.IO) { useCase.toggle() } }.awaitAll()
        assertEquals(SpeedMode.NORMAL, repo.current().speedMode)
    }

    @Test
    fun assignStoresTheSpeedAndSwitchesToAssignedInOneStep() = runBlocking {
        val repo = repo()
        val useCase = PlaybackSpeedUseCase(repo)
        useCase.toggle()
        assertEquals(SpeedMode.NORMAL, repo.current().speedMode)

        useCase.assign(2.0f)

        assertEquals(PlaybackSpeedState(2.0f, SpeedMode.ASSIGNED), useCase.stateNow())
    }

    @Test
    fun assignRoundsToTenthsAndLimitsToTheRange() = runBlocking {
        val repo = repo()
        val useCase = PlaybackSpeedUseCase(repo)
        useCase.assign(3.04f)
        assertEquals(3.0f, repo.current().assignedSpeed, 0f)
        useCase.assign(0.44f)
        assertEquals(0.5f, repo.current().assignedSpeed, 0f)
        useCase.assign(1.26f)
        assertEquals(1.3f, repo.current().assignedSpeed, 0f)
        useCase.assign(99f)
        assertEquals(3.0f, repo.current().assignedSpeed, 0f)
        useCase.assign(-4f)
        assertEquals(0.5f, repo.current().assignedSpeed, 0f)
    }

    @Test
    fun keepAssignedSwitchesTheModeAndKeepsTheOddDefaultExactly() = runBlocking {
        val repo = repo()
        val useCase = PlaybackSpeedUseCase(repo)
        useCase.toggle()
        useCase.keepAssigned()
        assertEquals(PlaybackSpeedState(1.25f, SpeedMode.ASSIGNED), useCase.stateNow())
    }

    @Test
    fun currentSpeedIsOneInNormalModeAndTheAssignedSpeedOtherwise() {
        assertEquals(1.0f, PlaybackSpeedState(1.25f, SpeedMode.NORMAL).currentSpeed, 0f)
        assertEquals(1.25f, PlaybackSpeedState(1.25f, SpeedMode.ASSIGNED).currentSpeed, 0f)
        assertEquals(1.25f, PlaybackSpeedState(1.25f, SpeedMode.NORMAL).otherSpeed, 0f)
        assertEquals(1.0f, PlaybackSpeedState(1.25f, SpeedMode.ASSIGNED).otherSpeed, 0f)
    }

    @Test
    fun theStateFlowFollowsTheSettings() {
        val fake = FakeSettings()
        val useCase = PlaybackSpeedUseCase(fake)
        runBlocking { useCase.toggle() }
        assertEquals(PlaybackSpeedState(1.25f, SpeedMode.NORMAL), runBlocking { useCase.stateNow() })
    }

    private suspend fun PlaybackSpeedUseCase.stateNow() = state.first()

    // ---- formatting and stepping ----

    @Test
    fun formatSpeedIsExactAndNeverShowsFloatNoise() {
        assertEquals("1.25x", formatSpeed(1.25f))
        assertEquals("1.0x", formatSpeed(1.0f))
        assertEquals("1.5x", formatSpeed(1.5f))
        assertEquals("3.0x", formatSpeed(3.0f))
        assertEquals("0.5x", formatSpeed(0.5f))
        assertEquals("1.3x", formatSpeed(1.2999999f))
    }

    @Test
    fun formatSpeedDoesNotDependOnTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.25x", formatSpeed(1.25f))
            assertEquals("1.0x", formatSpeed(1.0f))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun stepSpeedGoesToTheNextMultipleOfTenthsInTheDirectionPressed() {
        assertEquals(1.3f, stepSpeed(1.25f, 1), 0f)
        assertEquals(1.2f, stepSpeed(1.25f, -1), 0f)
        assertEquals(1.4f, stepSpeed(1.3f, 1), 0f)
        assertEquals(1.2f, stepSpeed(1.3f, -1), 0f)
        assertEquals(1.4f, stepSpeed(1.3000001f, 1), 0f)
        assertEquals(1.2f, stepSpeed(1.2999999f, -1), 0f)
    }

    @Test
    fun stepSpeedStopsAtTheEnds() {
        assertEquals(3.0f, stepSpeed(3.0f, 1), 0f)
        assertEquals(2.9f, stepSpeed(3.0f, -1), 0f)
        assertEquals(0.5f, stepSpeed(0.5f, -1), 0f)
        assertEquals(0.6f, stepSpeed(0.5f, 1), 0f)
    }

    @Test
    fun normalizeSpeedRoundsAndLimits() {
        assertEquals(3.0f, normalizeSpeed(3.04f), 0f)
        assertEquals(0.5f, normalizeSpeed(0.44f), 0f)
        assertEquals(1.3f, normalizeSpeed(1.26f), 0f)
        assertEquals(1.3f, normalizeSpeed(1.25f), 0f)
        assertEquals(1.0f, normalizeSpeed(1.0f), 0f)
    }

    // ---- the applier ----

    @Test
    fun theApplierAppliesTheInitialValueOnceAndEveryDistinctChangeInOrder() {
        val speeds = MutableStateFlow(PlaybackSpeedState(1.25f, SpeedMode.ASSIGNED))
        val applied = java.util.Collections.synchronizedList(mutableListOf<Float>())
        val applierScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            PlaybackSpeedApplier(speeds, applierScope) { applied += it }.start()
            awaitCondition("the initial speed 1.25 applied") { applied.toList() == listOf(1.25f) }

            speeds.value = PlaybackSpeedState(2.0f, SpeedMode.ASSIGNED)
            awaitCondition("2.0 applied") { applied.toList() == listOf(1.25f, 2.0f) }

            speeds.value = PlaybackSpeedState(2.0f, SpeedMode.NORMAL)
            awaitCondition("1.0 applied") { applied.toList() == listOf(1.25f, 2.0f, 1.0f) }

            // Another assigned speed while NORMAL: the current speed is still 1.0, so nothing is applied.
            speeds.value = PlaybackSpeedState(1.5f, SpeedMode.NORMAL)
            speeds.value = PlaybackSpeedState(1.5f, SpeedMode.ASSIGNED)
            awaitCondition("1.5 applied, with no second 1.0") { applied.toList() == listOf(1.25f, 2.0f, 1.0f, 1.5f) }
        } finally {
            applierScope.cancel()
        }
    }
}
