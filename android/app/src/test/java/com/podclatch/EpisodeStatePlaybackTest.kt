package com.podclatch

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.PlaybackWriteOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two playback writes of [EpisodeStateRepository]. */
class EpisodeStatePlaybackTest {
    private val store = FakeCacheStore()
    private var now = 10_000L
    private val repo = EpisodeStateRepository(store) { now }

    private fun seed(status: EpisodeStatus = EpisodeStatus.NEW, playedAt: Long? = null, positionSec: Int = 0, stamp: Long = 1_000) {
        store.feeds[1] = cachedFeed(1)
        store.episodes[10] = cachedEpisode(10, 1, status, playedAt, positionSec, stamp)
    }

    @Test
    fun savePositionWritesTheEpisodeRowAndTheOutboxRowTogether() = runBlocking {
        seed(positionSec = 3)
        val result = repo.savePosition(10, 42)
        assertEquals(PlaybackWriteOutcome.SAVED, result.outcome)
        val ep = store.episodes.getValue(10)
        assertEquals(EpisodeStatus.NEW, ep.status)
        assertNull(ep.playedAt)
        assertEquals(42, ep.positionSec)
        assertEquals(10_000L, ep.stateUpdatedAt)
        assertEquals(10_000L, result.stateUpdatedAt)
        val row = store.pending.getValue(10)
        assertEquals(EpisodeStatus.NEW, row.status)
        assertNull(row.playedAt)
        assertEquals(42, row.positionSec)
        assertEquals(10_000L, row.stateUpdatedAt)
    }

    @Test
    fun anEqualPositionWritesNothing() = runBlocking {
        seed(positionSec = 42)
        assertEquals(PlaybackWriteOutcome.UNCHANGED, repo.savePosition(10, 42).outcome)
        assertTrue(store.pending.isEmpty())
        assertEquals(1_000L, store.episodes.getValue(10).stateUpdatedAt)
    }

    @Test
    fun aPlayedEpisodeIsNeverWrittenByPlayback() = runBlocking {
        seed(EpisodeStatus.PLAYED, playedAt = 2_000, positionSec = 90, stamp = 2_000)
        assertEquals(PlaybackWriteOutcome.NOT_NEW, repo.savePosition(10, 5).outcome)
        assertEquals(PlaybackWriteOutcome.NOT_NEW, repo.markPlayedByPlayback(10, 100).outcome)
        val ep = store.episodes.getValue(10)
        assertEquals(2_000L, ep.playedAt)
        assertEquals(90, ep.positionSec)
        assertEquals(2_000L, ep.stateUpdatedAt)
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun anEpisodeThatIsGoneIsReportedNotThrown() = runBlocking {
        seed()
        assertEquals(PlaybackWriteOutcome.UNKNOWN_EPISODE, repo.savePosition(999, 5).outcome)
        assertEquals(PlaybackWriteOutcome.UNKNOWN_EPISODE, repo.markPlayedByPlayback(999, 5).outcome)
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun markPlayedByPlaybackSetsPlayedAtTogetherWithPlayed() = runBlocking {
        seed(positionSec = 3)
        val result = repo.markPlayedByPlayback(10, 30)
        assertEquals(PlaybackWriteOutcome.SAVED, result.outcome)
        val ep = store.episodes.getValue(10)
        assertEquals(EpisodeStatus.PLAYED, ep.status)
        assertEquals(10_000L, ep.playedAt)
        assertEquals(30, ep.positionSec)
        val row = store.pending.getValue(10)
        assertEquals(EpisodeStatus.PLAYED, row.status)
        assertEquals(10_000L, row.playedAt)
        assertEquals(ep.stateUpdatedAt, row.stateUpdatedAt)
    }

    @Test
    fun noPlaybackWriteCanBreakThePlayedAtRule() = runBlocking {
        seed()
        repo.savePosition(10, 7)
        assertEquals(EpisodeStatus.NEW, store.pending.getValue(10).status)
        assertNull(store.pending.getValue(10).playedAt)
        repo.markPlayedByPlayback(10, 8)
        assertEquals(EpisodeStatus.PLAYED, store.pending.getValue(10).status)
        assertTrue(store.pending.getValue(10).playedAt != null)
    }

    @Test
    fun theStampIsStrictlyAboveTheStoredAndThePendingStampEvenWhenTheClockIsBehindOrEqual() = runBlocking {
        seed(stamp = 50_000)
        now = 100 // behind the stored stamp
        val first = repo.savePosition(10, 1).stateUpdatedAt!!
        assertEquals(50_001L, first)
        now = 100 // two saves in the same millisecond
        val second = repo.savePosition(10, 2).stateUpdatedAt!!
        assertEquals(50_002L, second)
        // A pending stamp above the stored one (a Mark that never reached the Episode row) is respected too.
        store.pending[10] = store.pending.getValue(10).copy(stateUpdatedAt = 60_000)
        val third = repo.savePosition(10, 3).stateUpdatedAt!!
        assertEquals(60_001L, third)
        assertEquals(third, store.pending.getValue(10).stateUpdatedAt)
    }

    @Test
    fun aNegativePositionIsSavedAsZero() = runBlocking {
        seed(positionSec = 9)
        repo.savePosition(10, -5)
        assertEquals(0, store.episodes.getValue(10).positionSec)
    }
}
