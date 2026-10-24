package com.podclatch

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.db.ResumeRow
import com.podclatch.domain.QueueKey
import com.podclatch.playback.AudioSource
import com.podclatch.playback.PlaybackResumer
import com.podclatch.playback.ResolvedEpisode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackResumerTest {
    private val requested = mutableListOf<Long>()
    private var rows = listOf<ResumeRow>()
    private var queue = listOf<QueueKey>()
    private var known = setOf<Long>()

    private fun resumer() = PlaybackResumer(
        inProgress = { rows },
        queue = { queue },
        resolve = { id ->
            if (id in known) ResolvedEpisode(id.toString(), AudioSource.Stream("https://audio.example.com/$id.mp3"), "T$id", "F", null, null) else null
        },
        onPlayRequested = { requested += it },
    )

    private fun key(id: Long) = QueueKey(id, 1, 0, ListenOrder.OLDEST_FIRST, id)

    @Test
    fun forPlaybackTheInProgressEpisodeStartsAtItsPositionAndTheDownloadRuleRunsOnce() = runBlocking<Unit> {
        rows = listOf(ResumeRow(3, EpisodeStatus.NEW, 600, 10))
        known = setOf(3)
        val target = resumer().resume(isForPlayback = true)
        assertNotNull(target)
        assertEquals("3", target!!.episode.mediaId)
        assertEquals(600_000L, target.startPositionMs)
        assertEquals(listOf(3L), requested)
    }

    @Test
    fun notForPlaybackNothingIsRequested() = runBlocking<Unit> {
        rows = listOf(ResumeRow(3, EpisodeStatus.NEW, 600, 10))
        known = setOf(3)
        assertNotNull(resumer().resume(isForPlayback = false))
        assertEquals(emptyList<Long>(), requested)
    }

    @Test
    fun withNothingToResumeTheAnswerIsNullAndNothingIsRequested() = runBlocking<Unit> {
        assertNull(resumer().resume(isForPlayback = true))
        assertEquals(emptyList<Long>(), requested)
    }

    @Test
    fun anEpisodeThatCannotBeResolvedAnyMoreEndsWithoutErrorOrRequest() = runBlocking<Unit> {
        queue = listOf(key(9))
        assertNull(resumer().resume(isForPlayback = true))
        assertEquals(emptyList<Long>(), requested)
    }

    @Test
    fun withoutProgressTheFirstQueueItemStartsAtZero() = runBlocking<Unit> {
        queue = listOf(key(5), key(4))
        known = setOf(4, 5)
        val target = resumer().resume(isForPlayback = true)!!
        assertEquals("4", target.episode.mediaId)
        assertEquals(0L, target.startPositionMs)
        assertEquals(listOf(4L), requested)
    }
}
