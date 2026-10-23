package com.podclatch

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.PlaybackWriteOutcome
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.LocalUpkeep
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncResult
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.TokenRejection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.podclatch.playback.ExternalChange
import com.podclatch.playback.ExternalChangeWatcher
import com.podclatch.playback.PlaybackSnapshot
import com.podclatch.playback.ProgressRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The recorder and the external-change rule under virtual time: the scope is a test scope, the clock is a
 * variable, and the player is a pair of variables. Nothing waits for real time.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProgressRecorderTest {
    private class Env(val scope: CoroutineScope, val testScope: TestScope) {
        val store = FakeCacheStore()
        var now = 10_000L
        val repo = EpisodeStateRepository(store) { now }
        var loadedId: Long? = 10
        var positionMs = 0L
        var playerDurationMs: Long? = null
        var snapshots = 0
        val syncs = AtomicInteger()
        val finished = AtomicInteger()
        val finishedIds = java.util.Collections.synchronizedList(mutableListOf<Long>())
        var statusWhenFinished: EpisodeStatus? = null
        var durationSec: Int? = null
        val errors = mutableListOf<Throwable>()

        val recorder = ProgressRecorder(
            scope = scope,
            repository = repo,
            source = { snapshots++; PlaybackSnapshot(loadedId, positionMs, playerDurationMs) },
            fallbackDurationSec = { durationSec },
            sync = { syncs.incrementAndGet() },
            onFinished = { id ->
                finishedIds += id
                statusWhenFinished = store.episodes[id]?.status
                finished.incrementAndGet()
            },
            onError = { errors += it },
        )

        fun seed(status: EpisodeStatus = EpisodeStatus.NEW, playedAt: Long? = null, positionSec: Int = 0, stamp: Long = 1_000) {
            store.feeds[1] = cachedFeed(1)
            store.episodes[10] = cachedEpisode(10, 1, status, playedAt, positionSec, stamp)
        }

        val episode get() = store.episodes.getValue(10)

        /** Moves virtual time forward by [ms] and runs everything that became due. */
        fun advance(ms: Long) {
            testScope.advanceTimeBy(ms)
            testScope.runCurrent()
        }
    }

    private fun env(scope: TestScope) = Env(scope.backgroundScope, scope)

    // ---- saving the position ----

    @Test
    fun afterTwelveSecondsOfPlayThePositionIsSavedAtFiveAndTenSeconds() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.recorder.onPlaying(true)
        e.positionMs = 5_300
        e.advance(5_000)
        assertEquals(5, e.episode.positionSec)
        e.positionMs = 10_900
        e.advance(5_000)
        e.positionMs = 12_700 // the player moved on; no tick is due yet
        e.advance(2_000)
        assertEquals(10, e.episode.positionSec)
        val row = e.store.pending.getValue(10)
        assertEquals(10, row.positionSec)
        assertEquals(e.episode.stateUpdatedAt, row.stateUpdatedAt)
        assertTrue(row.stateUpdatedAt > 1_000)
        assertEquals("a tick never touches the network", 0, e.syncs.get())
        assertTrue(e.errors.isEmpty())
    }

    @Test
    fun ticksUseThePlayersPositionNotElapsedTime() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.recorder.onPlaying(true)
        e.positionMs = 61_000 // the listener seeked ahead
        e.advance(5_000)
        assertEquals(61, e.episode.positionSec)
    }

    @Test
    fun ticksStopWhenPausedAndNothingIsWrittenWhileIdle() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.recorder.onPlaying(true)
        e.positionMs = 5_000
        e.advance(5_000)
        val stamp = e.episode.stateUpdatedAt
        val snapshotsAtPause = e.snapshots
        e.recorder.onPlaying(false)
        e.positionMs = 9_000
        e.advance(15_000)
        assertEquals(stamp, e.episode.stateUpdatedAt)
        assertEquals(5, e.episode.positionSec)
        assertEquals("the ticker is gone, the player is not read", snapshotsAtPause, e.snapshots)
        assertEquals(0, e.syncs.get())
    }

    @Test
    fun aPausePlayCycleDoesNotDuplicateTicks() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.recorder.onPlaying(true)
        e.recorder.onPlaying(true) // a repeated "playing" is not a second ticker
        e.advance(2_000)
        e.recorder.onPlaying(false)
        e.recorder.onPlaying(true)
        e.positionMs = 7_000
        e.advance(10_000) // exactly two ticks of the new ticker (5 s and 10 s after the resume)
        assertEquals(2, e.snapshots)
    }

    @Test
    fun aTickDoesNotWriteWhenAnotherEpisodeIsLoadedInThePlayer() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.recorder.onPlaying(true)
        e.loadedId = 11
        e.positionMs = 5_000
        e.advance(5_000)
        assertEquals(0, e.episode.positionSec)
        assertTrue(e.store.pending.isEmpty())
    }

    // ---- pause, stop, switch ----

    @Test
    fun pauseSavesTheFlooredPositionAtOnceThenSyncs() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.recorder.onPause(12_999, null)
        e.advance(0)
        assertEquals(12, e.episode.positionSec)
        assertEquals(12, e.store.pending.getValue(10).positionSec)
        assertEquals(1, e.syncs.get())
    }

    @Test
    fun aPauseAtTheSavedPositionWritesNothingButStillPushesAnEarlierTick() = runTest {
        val e = env(this)
        e.seed(positionSec = 10, stamp = 1_000)
        e.store.pending[10] = com.podclatch.data.db.PendingStateWrite(10, EpisodeStatus.NEW, null, 10, 1_000)
        e.recorder.onLoaded(10)
        e.recorder.onPause(10_400, null)
        e.advance(0)
        assertEquals(1_000L, e.episode.stateUpdatedAt)
        assertEquals("the tick's outbox row is pushed", 1, e.syncs.get())
    }

    @Test
    fun stopAndSwitchSaveAndSyncToo() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.recorder.onStop(8_100, null)
        e.advance(0)
        assertEquals(8, e.episode.positionSec)
        e.recorder.onSwitch(10, 20_500, null)
        e.advance(0)
        assertEquals(20, e.episode.positionSec)
        assertEquals(2, e.syncs.get())
    }

    @Test
    fun aSwitchJudgesThePreviousEpisodeAtItsOwnPosition() = runTest {
        val e = env(this)
        e.seed()
        e.store.episodes[11] = cachedEpisode(11, 1)
        e.recorder.onLoaded(10)
        e.recorder.onSwitch(10, 96_000, 100_000)
        e.recorder.onLoaded(11)
        e.advance(0)
        assertEquals(EpisodeStatus.PLAYED, e.episode.status)
        assertEquals(EpisodeStatus.NEW, e.store.episodes.getValue(11).status)
        assertEquals(0, e.store.episodes.getValue(11).positionSec)
    }

    // ---- Played ----

    @Test
    fun pausingBelowNinetyFivePercentKeepsItNewWithThePosition() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.recorder.onPause(94_900, 100_000)
        e.advance(0)
        assertEquals(EpisodeStatus.NEW, e.episode.status)
        assertNull(e.episode.playedAt)
        assertEquals(94, e.episode.positionSec)
    }

    @Test
    fun pausingAtNinetyFivePercentMarksPlayedWithPlayedAtNow() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.now = 77_000
        e.recorder.onPause(95_000, 100_000)
        e.advance(0)
        assertEquals(EpisodeStatus.PLAYED, e.episode.status)
        assertEquals(77_000L, e.episode.playedAt)
        assertEquals(95, e.episode.positionSec)
        assertEquals(EpisodeStatus.PLAYED, e.store.pending.getValue(10).status)
        assertEquals(77_000L, e.store.pending.getValue(10).playedAt)
        assertEquals(1, e.syncs.get())
        assertEquals("pause does not stop the player", 0, e.finished.get())
    }

    @Test
    fun stoppingAtNinetyFivePercentMarksPlayed() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.recorder.onStop(29_000, 30_000)
        e.advance(0)
        assertEquals(EpisodeStatus.PLAYED, e.episode.status)
    }

    @Test
    fun theDurationFallsBackToTheFeedsDurationAndAnUnknownOneNeverCounts() = runTest {
        val e = env(this)
        e.seed()
        e.durationSec = 100
        e.recorder.onLoaded(10)
        e.recorder.onPause(96_000, null)
        e.advance(0)
        assertEquals(EpisodeStatus.PLAYED, e.episode.status)

        val unknown = env(this)
        unknown.seed()
        unknown.recorder.onLoaded(10)
        unknown.recorder.onPause(9_000_000, null)
        unknown.advance(0)
        assertEquals(EpisodeStatus.NEW, unknown.episode.status)
        assertEquals(9_000, unknown.episode.positionSec)
    }

    @Test
    fun thePlayersDurationWinsOverTheFeedsDuration() = runTest {
        val e = env(this)
        e.seed()
        e.durationSec = 1_000 // the feed says 1000 s; the player knows it is 100 s
        e.recorder.onLoaded(10)
        e.recorder.onPause(96_000, 100_000)
        e.advance(0)
        assertEquals(EpisodeStatus.PLAYED, e.episode.status)
    }

    @Test
    fun aPlayedEpisodeIsNeverChangedByListeningAgain() = runTest {
        val e = env(this)
        e.seed(EpisodeStatus.PLAYED, playedAt = 2_000, positionSec = 30, stamp = 2_000)
        e.recorder.onLoaded(10)
        e.recorder.onPlaying(true)
        e.positionMs = 10_000
        e.advance(10_000)
        e.recorder.onPause(10_000, 30_000)
        e.recorder.onEnded(30_000, 30_000)
        e.advance(0)
        assertEquals(EpisodeStatus.PLAYED, e.episode.status)
        assertEquals(2_000L, e.episode.playedAt)
        assertEquals(30, e.episode.positionSec)
        assertEquals(2_000L, e.episode.stateUpdatedAt)
        assertTrue(e.store.pending.isEmpty())
        assertEquals(0, e.syncs.get())
    }

    // ---- the end ----

    @Test
    fun reachingTheEndMarksPlayedPushesAndStopsThePlayer() = runTest {
        val e = env(this)
        e.seed(positionSec = 20)
        e.recorder.onLoaded(10)
        e.recorder.onPlaying(true)
        e.now = 90_000
        e.recorder.onEnded(30_500, 30_500)
        e.advance(0)
        assertEquals(EpisodeStatus.PLAYED, e.episode.status)
        assertEquals(90_000L, e.episode.playedAt)
        assertEquals("floor of the duration, and not more", 30, e.episode.positionSec)
        assertTrue(e.episode.stateUpdatedAt > 1_000)
        assertEquals(e.episode.stateUpdatedAt, e.store.pending.getValue(10).stateUpdatedAt)
        assertEquals(1, e.syncs.get())
        assertEquals(1, e.finished.get())
    }

    @Test
    fun theEndStillStopsThePlayerWhenThereIsNothingToWrite() = runTest {
        val e = env(this)
        e.seed(EpisodeStatus.PLAYED, playedAt = 2_000, positionSec = 30, stamp = 2_000)
        e.recorder.onLoaded(10)
        e.recorder.onEnded(30_000, 30_000)
        e.advance(0)
        assertEquals(1, e.finished.get())
        assertEquals(0, e.syncs.get())
    }

    @Test
    fun nothingIsRecordedAfterTheEndAndTheEndCountsOnce() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.recorder.onPlaying(true)
        e.recorder.onEnded(30_000, 30_000)
        e.recorder.onEnded(30_000, 30_000)
        e.recorder.onPause(5_000, 30_000)
        e.recorder.onStop(5_000, 30_000)
        e.positionMs = 7_000
        e.advance(20_000)
        assertEquals(30, e.episode.positionSec)
        assertEquals(1, e.finished.get())
        assertEquals(1, e.syncs.get())
    }

    // ---- ordering and the guard ----

    @Test
    fun aLateTickCannotOverwriteANewerPauseSave() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.recorder.onPlaying(true)
        e.positionMs = 5_000
        e.advance(4_999)
        // The tick is due at 5000; the pause at 4999 ms of play was queued first, and nothing is written twice.
        e.recorder.onPause(4_999, null)
        e.advance(10)
        assertEquals(4, e.episode.positionSec)
        e.positionMs = 4_999
        e.advance(10_000)
        assertEquals(4, e.episode.positionSec)
    }

    @Test
    fun theStampStaysAboveEverythingWhenTheClockIsBehindAndSavesShareAMillisecond() = runTest {
        val e = env(this)
        e.seed(stamp = 50_000)
        e.now = 100
        e.recorder.onLoaded(10)
        e.recorder.onPause(5_000, null)
        e.recorder.onPause(6_000, null)
        e.advance(0)
        assertEquals(50_002L, e.episode.stateUpdatedAt)
        assertEquals(6, e.episode.positionSec)
    }

    @Test
    fun aSaveForAnEpisodeThatIsGoneSettlesSilently() = runTest {
        val e = env(this)
        e.seed()
        e.store.episodes.remove(10)
        e.recorder.onLoaded(10)
        e.recorder.onPause(5_000, null)
        e.recorder.onEnded(30_000, 30_000)
        e.advance(0)
        assertTrue(e.errors.isEmpty())
        assertTrue(e.store.pending.isEmpty())
        assertEquals(0, e.syncs.get())
    }

    // ---- the state changes under the player ----

    private class Watched(val e: Env, scope: CoroutineScope) {
        val external = AtomicInteger()
        val changes = java.util.Collections.synchronizedList(mutableListOf<ExternalChange>())
        val watcher = ExternalChangeWatcher(
            scope = scope,
            observe = e.store::observeEpisode,
            isOwnStamp = e.recorder::isOwnStamp,
            onExternalChange = { change ->
                e.recorder.detach()
                changes += change
                external.incrementAndGet()
            },
        )
    }

    private fun TestScope.watched(): Watched {
        val e = env(this)
        e.seed()
        val w = Watched(e, backgroundScope)
        e.recorder.onLoaded(10)
        w.watcher.onLoaded(10)
        e.recorder.onPlaying(true)
        e.positionMs = 5_000
        e.advance(5_000)
        return w
    }

    @Test
    fun theRecordersOwnWritesNeverTriggerTheExternalChangeRule() = runTest {
        val w = watched()
        val e = w.e
        e.positionMs = 10_000
        e.advance(5_000)
        e.recorder.onPause(10_500, 100_000)
        e.recorder.onEnded(100_000, 100_000)
        e.advance(0)
        assertEquals(EpisodeStatus.PLAYED, e.episode.status)
        assertEquals("not even the recorder's own Played write", 0, w.external.get())
        assertEquals(1, e.finished.get())
    }

    @Test
    fun markingPlayedWhilePlayingStopsAndNoLaterSaveMovesTheMark() = runTest {
        val w = watched()
        val e = w.e
        assertEquals(5, e.episode.positionSec)
        e.now = 40_000
        e.repo.markPlayed(10)
        e.advance(0)
        assertEquals(1, w.external.get())
        // The player winds down: a pause and a stop arrive after the Mark, and a tick is due.
        e.recorder.onPause(33_000, 100_000)
        e.recorder.onStop(33_000, 100_000)
        e.positionMs = 33_000
        e.advance(10_000)
        assertEquals(EpisodeStatus.PLAYED, e.episode.status)
        assertEquals(40_000L, e.episode.playedAt)
        assertEquals("the last saved value", 5, e.episode.positionSec)
        assertEquals(40_000L, e.store.pending.getValue(10).stateUpdatedAt)
    }

    @Test
    fun markingNewWhilePlayingStopsAndThePlayersPositionIsNotWrittenAfterwards() = runTest {
        val w = watched()
        val e = w.e
        e.now = 40_000
        e.repo.markNew(10)
        e.advance(0)
        assertEquals(1, w.external.get())
        e.recorder.onPause(33_000, 100_000)
        e.recorder.onStop(33_000, 100_000)
        e.positionMs = 33_000
        e.advance(10_000)
        assertEquals(EpisodeStatus.NEW, e.episode.status)
        assertNull(e.episode.playedAt)
        assertEquals(0, e.episode.positionSec)
    }

    @Test
    fun markAllNewAndMarkAllPlayedAreExternalChangesToo() = runTest {
        val w = watched()
        w.e.repo.markAllPlayed(1)
        w.e.advance(0)
        assertEquals(1, w.external.get())
        assertEquals(0, w.e.episode.positionSec)
    }

    @Test
    fun unsubscribingStopsPlaybackWithoutAnyWriteForTheVanishedEpisode() = runTest {
        val w = watched()
        val e = w.e
        e.store.transaction { deleteFeed(1) }
        e.advance(0)
        assertEquals(1, w.external.get())
        e.recorder.onPause(33_000, 100_000)
        e.advance(10_000)
        assertTrue(e.errors.isEmpty())
        assertTrue(e.store.pending.isEmpty() || e.store.pending.values.all { it.positionSec == 5 })
        assertEquals(0, e.syncs.get())
    }

    @Test
    fun aPulledChangeIsAnExternalChange() = runTest {
        val w = watched()
        val e = w.e
        // What a pull of newer server state does: the state columns change, no outbox row is involved.
        e.store.transaction {
            updateEpisodeState(10, EpisodeStatus.PLAYED, 90_000, 77, 90_000)
        }
        e.advance(0)
        assertEquals(1, w.external.get())
    }

    @Test
    fun theWatcherTellsWhichEpisodeAndItsNewStateSoThePlayedMarkCanAdvance() = runTest {
        val w = watched()
        w.e.repo.markPlayed(10)
        w.e.advance(0)
        val change = w.changes.single()
        assertEquals(10L, change.episodeId)
        assertEquals(EpisodeStatus.PLAYED, change.progress!!.status)
    }

    @Test
    fun aMarkNewReportsANewEpisodeAndAnUnsubscribeReportsNone() = runTest {
        val w = watched()
        w.e.repo.markNew(10)
        w.e.advance(0)
        assertEquals(EpisodeStatus.NEW, w.changes.single().progress!!.status)

        val gone = watched()
        gone.e.store.transaction { deleteFeed(1) }
        gone.e.advance(0)
        assertNull(gone.changes.single().progress)
    }

    @Test
    fun theEndHandsTheEpisodeToTheAdvanceAfterItIsMarkedPlayed() = runTest {
        val e = env(this)
        e.seed(positionSec = 3)
        e.recorder.onLoaded(10)
        e.recorder.onEnded(60_000, 60_000)
        e.advance(0)
        assertEquals(listOf(10L), e.finishedIds.toList())
        assertEquals("already Played when the advance starts", EpisodeStatus.PLAYED, e.statusWhenFinished)
    }

    @Test
    fun stoppingOrSwitchingAtNinetyFivePercentMarksPlayedButNeverAdvances() = runTest {
        val e = env(this)
        e.seed()
        e.recorder.onLoaded(10)
        e.recorder.onSwitch(10, 96_000, 100_000)
        e.advance(0)
        assertEquals(EpisodeStatus.PLAYED, e.episode.status)
        assertEquals(0, e.finished.get())

        val f = env(this)
        f.seed()
        f.recorder.onLoaded(10)
        f.recorder.onStop(99_000, 100_000)
        f.advance(0)
        assertEquals(EpisodeStatus.PLAYED, f.episode.status)
        assertEquals(0, f.finished.get())
    }

    // ---- on real threads (the shape the service uses: the application scope on a background dispatcher) ----

    @Test
    fun onRealThreadsTheEndIsRecordedPushedAndStopsThePlayerExactlyOnce() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FakeCacheStore()
            store.feeds[1] = cachedFeed(1)
            store.episodes[10] = cachedEpisode(10, 1, positionSec = 3)
            val finished = AtomicInteger()
            val syncs = AtomicInteger()
            val recorder = ProgressRecorder(
                scope = scope,
                repository = EpisodeStateRepository(store) { 10_000L },
                source = { PlaybackSnapshot(10, 0, null) },
                fallbackDurationSec = { null },
                sync = { syncs.incrementAndGet() },
                onFinished = { finished.incrementAndGet() },
                tickMs = 20,
            )
            recorder.onLoaded(10)
            recorder.onPlaying(true)
            recorder.onEnded(30_000, 30_000)
            awaitCondition("the Episode is Played, pushed once, and the player was told to stop once") {
                finished.get() == 1 && syncs.get() == 1
            }
            assertEquals(EpisodeStatus.PLAYED, store.episodes.getValue(10).status)
            assertEquals(30, store.episodes.getValue(10).positionSec)
        } finally {
            scope.cancel()
        }
    }

    // ---- a Sync pull during playback ----

    @Test
    fun aPullCarryingOlderServerStateDoesNotRevertTheSavedPosition() = runTest {
        val e = env(this)
        e.seed(positionSec = 0, stamp = 1_000)
        val api = FakeApi()
        // The tick lands while the pull is in flight (the push already finished), then the pull reports older state.
        api.pullHandler = { _, _ ->
            assertEquals(PlaybackWriteOutcome.SAVED, e.repo.savePosition(10, 5).outcome)
            page(
                episodes = listOf(episodeDto(10, 1, status = EpisodeStatus.NEW, positionSec = 2, stateUpdatedAt = e.now - 1)),
                nextRev = 9,
            )
        }
        val settings = FakeSettings()
        val engine = SyncEngine(
            api, settings, e.store, LocalUpkeep { }, { e.now }, TokenRejection(settings), DiskFileDeleter(),
            Dispatchers.Unconfined,
        )
        assertEquals(SyncResult.Success, engine.sync(SyncTrigger.MANUAL))
        assertEquals("the local position survives", 5, e.episode.positionSec)
        assertEquals(e.now, e.episode.stateUpdatedAt)
        assertEquals(5, e.store.pending.getValue(10).positionSec)
    }
}
