package com.podclatch

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.repo.EpisodeStateRepository
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
        assertTrue(repo.markPlayed(10))
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
        assertTrue(repo.markNew(10))
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
        assertFalse(repo.markPlayed(999))
        assertFalse(repo.markNew(999))
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
}
