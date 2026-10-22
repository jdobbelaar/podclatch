package com.podclatch

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.MarkResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeStateRepositoryTest {
    private val store = FakeCacheStore()
    private var now = 10_000L
    private val repo = EpisodeStateRepository(store) { now }

    private fun seed(status: EpisodeStatus = EpisodeStatus.NEW, playedAt: Long? = null, positionSec: Int = 0, stamp: Long = 1_000) {
        store.feeds[1] = cachedFeed(1)
        store.episodes[10] = cachedEpisode(10, 1, status, playedAt, positionSec, stamp)
    }

    @Test
    fun markPlayedSetsPlayedAtKeepsThePositionAndWritesTheOutbox() = runBlocking {
        seed(positionSec = 321)
        assertEquals(MarkResult.CHANGED, repo.markPlayed(10))
        val ep = store.episodes.getValue(10)
        assertEquals(EpisodeStatus.PLAYED, ep.status)
        assertEquals(10_000L, ep.playedAt)
        assertEquals(321, ep.positionSec)
        assertEquals(10_000L, ep.stateUpdatedAt)
        val row = store.pending.getValue(10)
        assertEquals(EpisodeStatus.PLAYED, row.status)
        assertEquals(10_000L, row.playedAt)
        assertEquals(321, row.positionSec)
        assertEquals(10_000L, row.stateUpdatedAt)
    }

    @Test
    fun markNewClearsPlayedAtAndResetsThePosition() = runBlocking {
        seed(EpisodeStatus.PLAYED, playedAt = 2_000, positionSec = 900, stamp = 2_000)
        assertEquals(MarkResult.CHANGED, repo.markNew(10))
        val ep = store.episodes.getValue(10)
        assertEquals(EpisodeStatus.NEW, ep.status)
        assertNull(ep.playedAt)
        assertEquals(0, ep.positionSec)
        assertEquals(10_000L, ep.stateUpdatedAt)
        assertEquals(store.pending.getValue(10).stateUpdatedAt, ep.stateUpdatedAt)
        assertNull(store.pending.getValue(10).playedAt)
    }

    @Test
    fun anUnknownEpisodeChangesNothingAndReturnsFalse() = runBlocking {
        seed()
        assertEquals(MarkResult.NOT_FOUND, repo.markPlayed(999))
        assertEquals(MarkResult.NOT_FOUND, repo.markNew(999))
        assertTrue(store.pending.isEmpty())
        assertEquals(1_000L, store.episodes.getValue(10).stateUpdatedAt)
    }

    @Test
    fun successiveWritesStrictlyIncreaseEvenWhenTheClockStandsStillOrGoesBack() = runBlocking {
        seed()
        repo.markPlayed(10)
        val first = store.episodes.getValue(10).stateUpdatedAt
        repo.markNew(10) // same millisecond
        val second = store.episodes.getValue(10).stateUpdatedAt
        now = 5_000 // clock moved backwards
        repo.markPlayed(10)
        val third = store.episodes.getValue(10).stateUpdatedAt
        assertTrue(second > first)
        assertTrue(third > second)
        assertEquals(10_002L, third)
        assertEquals(1, store.pending.size)
        assertEquals(third, store.pending.getValue(10).stateUpdatedAt)
    }

    @Test
    fun aStateNewerThanTheClockStillGetsAHigherStamp() = runBlocking {
        seed(stamp = 50_000)
        repo.markPlayed(10)
        assertEquals(50_001L, store.episodes.getValue(10).stateUpdatedAt)
    }

    // ---- single Marks that would change nothing ----

    @Test
    fun markPlayedOnAPlayedEpisodeChangesNothing() = runBlocking {
        seed(EpisodeStatus.PLAYED, playedAt = 2_000, positionSec = 40, stamp = 2_000)
        assertEquals(MarkResult.UNCHANGED, repo.markPlayed(10))
        val ep = store.episodes.getValue(10)
        assertEquals(2_000L, ep.playedAt)
        assertEquals(2_000L, ep.stateUpdatedAt)
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun markNewOnANewEpisodeWithoutProgressChangesNothing() = runBlocking {
        seed()
        assertEquals(MarkResult.UNCHANGED, repo.markNew(10))
        assertEquals(1_000L, store.episodes.getValue(10).stateUpdatedAt)
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun markNewOnANewEpisodeWithProgressResetsTheProgress() = runBlocking {
        seed(positionSec = 300)
        assertEquals(MarkResult.CHANGED, repo.markNew(10))
        val ep = store.episodes.getValue(10)
        assertEquals(EpisodeStatus.NEW, ep.status)
        assertEquals(0, ep.positionSec)
        assertEquals(10_000L, ep.stateUpdatedAt)
        assertEquals(0, store.pending.getValue(10).positionSec)
    }

    // ---- Mark all ----

    private fun seedFeed() {
        store.feeds[1] = cachedFeed(1)
        store.feeds[2] = cachedFeed(2)
        store.episodes[1] = cachedEpisode(1, 1, EpisodeStatus.NEW, positionSec = 0, stateUpdatedAt = 1_000)
        store.episodes[2] = cachedEpisode(2, 1, EpisodeStatus.NEW, positionSec = 250, stateUpdatedAt = 1_000)
        store.episodes[3] = cachedEpisode(3, 1, EpisodeStatus.PLAYED, playedAt = 3_000, positionSec = 90, stateUpdatedAt = 3_000)
        store.episodes[4] = cachedEpisode(4, 2, EpisodeStatus.NEW, stateUpdatedAt = 1_000)
    }

    @Test
    fun markAllPlayedChangesOnlyNewEpisodesOfThatFeedAndResetsTheirPosition() = runBlocking {
        seedFeed()
        assertEquals(2, repo.markAllPlayed(1))
        for (id in listOf(1L, 2L)) {
            val ep = store.episodes.getValue(id)
            assertEquals(EpisodeStatus.PLAYED, ep.status)
            assertEquals(10_000L, ep.playedAt)
            assertEquals(0, ep.positionSec)
            assertEquals(10_000L, ep.stateUpdatedAt)
            assertEquals(10_000L, store.pending.getValue(id).stateUpdatedAt)
        }
        // The already-Played Episode keeps its playedAt and stamp, and gets no outbox row.
        val played = store.episodes.getValue(3)
        assertEquals(3_000L, played.playedAt)
        assertEquals(3_000L, played.stateUpdatedAt)
        assertEquals(90, played.positionSec)
        assertFalse(3L in store.pending)
        // Another Feed is untouched.
        assertEquals(EpisodeStatus.NEW, store.episodes.getValue(4).status)
        assertEquals(setOf(1L, 2L), store.pending.keys)
    }

    @Test
    fun markAllNewChangesPlayedEpisodesAndNewOnesWithProgress() = runBlocking {
        seedFeed()
        assertEquals(2, repo.markAllNew(1))
        val fromPlayed = store.episodes.getValue(3)
        assertEquals(EpisodeStatus.NEW, fromPlayed.status)
        assertNull(fromPlayed.playedAt)
        assertEquals(0, fromPlayed.positionSec)
        assertEquals(10_000L, fromPlayed.stateUpdatedAt)
        assertEquals(0, store.episodes.getValue(2).positionSec)
        // New without progress is untouched: no new stamp, no outbox row.
        assertEquals(1_000L, store.episodes.getValue(1).stateUpdatedAt)
        assertFalse(1L in store.pending)
        assertEquals(setOf(2L, 3L), store.pending.keys)
        assertEquals(EpisodeStatus.NEW, store.episodes.getValue(4).status)
    }

    @Test
    fun markAllWithNothingToChangeWritesNothing() = runBlocking {
        store.feeds[1] = cachedFeed(1)
        store.episodes[1] = cachedEpisode(1, 1, EpisodeStatus.PLAYED, playedAt = 500, stateUpdatedAt = 500)
        assertEquals(0, repo.markAllPlayed(1))
        store.episodes[1] = cachedEpisode(1, 1, EpisodeStatus.NEW, stateUpdatedAt = 500)
        assertEquals(0, repo.markAllNew(1))
        assertEquals(0, repo.markAllNew(99))
        assertTrue(store.pending.isEmpty())
        assertEquals(500L, store.episodes.getValue(1).stateUpdatedAt)
    }

    @Test
    fun markAllGivesEachEpisodeAStampAbovePendingAndStoredOnes() = runBlocking {
        seedFeed()
        store.pending[1] = com.podclatch.data.db.PendingStateWrite(1, EpisodeStatus.NEW, null, 0, 70_000)
        store.episodes[2] = store.episodes.getValue(2).copy(stateUpdatedAt = 80_000)
        assertEquals(2, repo.markAllPlayed(1))
        assertEquals(70_001L, store.episodes.getValue(1).stateUpdatedAt)
        assertEquals(80_001L, store.episodes.getValue(2).stateUpdatedAt)
        assertEquals(70_001L, store.pending.getValue(1).stateUpdatedAt)
    }

    @Test
    fun markAllHandlesAFeedOf500Episodes() = runBlocking {
        store.feeds[1] = cachedFeed(1)
        for (id in 1L..500L) {
            store.episodes[id] = cachedEpisode(id, 1, if (id % 2 == 0L) EpisodeStatus.PLAYED else EpisodeStatus.NEW, playedAt = if (id % 2 == 0L) 100 else null)
        }
        assertEquals(250, repo.markAllPlayed(1))
        assertEquals(250, store.pending.size)
        assertEquals(500, store.episodes.values.count { it.status == EpisodeStatus.PLAYED })
        assertEquals(500, repo.markAllNew(1))
        assertEquals(500, store.pending.size)
    }
}
