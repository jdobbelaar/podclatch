package com.podclatch

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.db.ResumeRow
import com.podclatch.domain.QueueKey
import com.podclatch.domain.ResumeChoice
import com.podclatch.domain.chooseResume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which Episode to resume after the process died (US-16, decision 4), as a table of cases. */
class ResumeRulesTest {
    private fun row(id: Long, position: Int, stamp: Long, status: EpisodeStatus = EpisodeStatus.NEW) =
        ResumeRow(id, status, position, stamp)

    private fun key(id: Long, priority: Int = 0, published: Long = id) =
        QueueKey(id, feedId = 1, feedPriority = priority, listenOrder = ListenOrder.OLDEST_FIRST, publishedAt = published)

    @Test
    fun oneInProgressEpisodeIsChosenAtItsSavedPositionInMilliseconds() {
        assertEquals(ResumeChoice(7, 123_000), chooseResume(listOf(row(7, 123, 50)), listOf(key(1))))
    }

    @Test
    fun theGreatestStateUpdatedAtWinsWhateverItsFeedOrDownloadState() {
        val rows = listOf(row(1, 10, 100), row(2, 20, 300), row(3, 30, 200))
        assertEquals(ResumeChoice(2, 20_000), chooseResume(rows, listOf(key(1), key(3))))
    }

    @Test
    fun anEpisodeThatBecamePlayedIsNeverChosen() {
        val rows = listOf(row(1, 10, 100), row(2, 20, 900, EpisodeStatus.PLAYED))
        assertEquals(ResumeChoice(1, 10_000), chooseResume(rows, emptyList()))
    }

    @Test
    fun whenEveryInProgressEpisodeIsPlayedTheFirstQueueItemStartsAtZero() {
        val rows = listOf(row(1, 10, 100, EpisodeStatus.PLAYED))
        val queue = listOf(key(5, priority = 1), key(6, priority = 0, published = 9), key(7, priority = 0, published = 3))
        assertEquals(ResumeChoice(7, 0), chooseResume(rows, queue))
    }

    @Test
    fun aZeroPositionIsNotProgress() {
        assertEquals(ResumeChoice(4, 0), chooseResume(listOf(row(9, 0, 500)), listOf(key(4))))
    }

    @Test
    fun anEmptyQueueAndNothingInProgressGivesNoChoice() {
        assertNull(chooseResume(emptyList(), emptyList()))
        assertNull(chooseResume(listOf(row(1, 10, 100, EpisodeStatus.PLAYED), row(2, 0, 100)), emptyList()))
    }

    @Test
    fun aTieOnStateUpdatedAtIsBrokenByTheGreaterEpisodeIdSoTheAnswerIsFixed() {
        val rows = listOf(row(3, 30, 100), row(8, 80, 100), row(5, 50, 100))
        assertEquals(ResumeChoice(8, 80_000), chooseResume(rows, emptyList()))
        assertEquals(ResumeChoice(8, 80_000), chooseResume(rows.reversed(), emptyList()))
    }
}
