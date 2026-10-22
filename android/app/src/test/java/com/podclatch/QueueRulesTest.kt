package com.podclatch

import com.podclatch.data.db.ListenOrder
import com.podclatch.domain.QueueKey
import com.podclatch.domain.advanceCandidates
import com.podclatch.domain.nextQueueItem
import com.podclatch.domain.sortQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueRulesTest {
    private fun key(
        id: Long,
        feed: Long = 1,
        priority: Int = 0,
        order: ListenOrder = ListenOrder.NEWEST_FIRST,
        published: Long = id,
    ) = QueueKey(id, feed, priority, order, published)

    private fun ids(keys: List<QueueKey>) = keys.map { it.episodeId }

    // ---- order ----

    @Test
    fun newestFirstIsPublishedDescendingThenIdDescending() {
        val keys = listOf(key(1, published = 100), key(2, published = 300), key(3, published = 300), key(4, published = 200))
        assertEquals(listOf(3L, 2L, 4L, 1L), ids(sortQueue(keys)))
    }

    @Test
    fun oldestFirstIsTheExactReverse() {
        val keys = listOf(key(1, published = 100), key(2, published = 300), key(3, published = 300), key(4, published = 200))
        val newest = ids(sortQueue(keys))
        val oldest = ids(sortQueue(keys.map { it.copy(listenOrder = ListenOrder.OLDEST_FIRST) }))
        assertEquals(newest.reversed(), oldest)
        assertEquals(listOf(1L, 4L, 2L, 3L), oldest)
    }

    @Test
    fun feedPriorityComesBeforeAnyListenOrder() {
        val keys = listOf(
            key(1, feed = 1, priority = 1, published = 500),
            key(2, feed = 2, priority = 0, order = ListenOrder.OLDEST_FIRST, published = 100),
            key(3, feed = 2, priority = 0, order = ListenOrder.OLDEST_FIRST, published = 50),
            key(4, feed = 1, priority = 1, published = 600),
        )
        assertEquals(listOf(3L, 2L, 4L, 1L), ids(sortQueue(keys)))
    }

    @Test
    fun twoFeedsWithTheSamePriorityKeepAFixedOrderByFeedId() {
        val keys = listOf(key(1, feed = 9, priority = 0), key(2, feed = 3, priority = 0))
        assertEquals(listOf(2L, 1L), ids(sortQueue(keys)))
        assertEquals(listOf(2L, 1L), ids(sortQueue(keys.reversed())))
    }

    // ---- the next item ----

    private val queue = listOf(key(3), key(2), key(1)) // newest first: 3, 2, 1

    @Test
    fun theNextItemInTheMiddleIsTheOneAfterIt() {
        // 2 finished and was marked Played, so it is already out of the Queue.
        assertEquals(1L, nextQueueItem(listOf(key(3), key(1)), key(2)))
        // The Queue as it was before the mark gives the same answer, and the finished one is never chosen.
        assertEquals(1L, nextQueueItem(queue, key(2)))
    }

    @Test
    fun theLastItemWrapsToTheFirstRemaining() {
        assertEquals(3L, nextQueueItem(listOf(key(3), key(2)), key(1)))
        assertEquals(listOf(3L, 2L), advanceCandidates(listOf(key(3), key(2)), key(1)))
    }

    @Test
    fun theOnlyItemHasNoNext() {
        assertNull(nextQueueItem(listOf(key(1)), key(1)))
        assertNull(nextQueueItem(emptyList(), key(1)))
    }

    @Test
    fun anEmptyQueueHasNoNext() {
        assertEquals(emptyList<Long>(), advanceCandidates(emptyList(), key(5)))
    }

    @Test
    fun aFinishedEpisodeOutsideTheQueueTakesTheFirstItemThatSortsAfterIt() {
        // Queue (newest first): 9, 7, 3. The finished Episode 5 is not in it and sorts between 7 and 3.
        val q = listOf(key(9), key(7), key(3))
        assertEquals(3L, nextQueueItem(q, key(5)))
        assertEquals(listOf(3L, 9L, 7L), advanceCandidates(q, key(5)))
    }

    @Test
    fun aFinishedEpisodeThatSortsBeforeAllTakesTheFirstItem() {
        val q = listOf(key(9), key(7), key(3))
        assertEquals(9L, nextQueueItem(q, key(20)))
    }

    @Test
    fun aFinishedEpisodeThatSortsAfterAllWrapsToTheFirstItem() {
        val q = listOf(key(9), key(7), key(3))
        assertEquals(9L, nextQueueItem(q, key(1)))
        assertEquals(listOf(9L, 7L, 3L), advanceCandidates(q, key(1)))
    }

    @Test
    fun equalPublishDatesAreBrokenById() {
        val q = listOf(key(12, published = 10), key(11, published = 10), key(10, published = 10))
        assertEquals(10L, nextQueueItem(q.filter { it.episodeId != 11L }, key(11, published = 10)))
        assertEquals(listOf(10L, 12L), advanceCandidates(q.filter { it.episodeId != 11L }, key(11, published = 10)))
    }

    @Test
    fun twoFeedsWithDifferentListenOrdersFollowEachFeedsOwnOrder() {
        // Feed 1 (priority 0) newest first: 11, 10. Feed 2 (priority 1) oldest first: 20, 21.
        val a11 = key(11, feed = 1, priority = 0, published = 200)
        val a10 = key(10, feed = 1, priority = 0, published = 100)
        val b20 = key(20, feed = 2, priority = 1, order = ListenOrder.OLDEST_FIRST, published = 100)
        val b21 = key(21, feed = 2, priority = 1, order = ListenOrder.OLDEST_FIRST, published = 200)
        val all = listOf(a11, a10, b20, b21)
        assertEquals(listOf(10L, 20L, 21L), advanceCandidates(all.filter { it != a11 }, a11))
        assertEquals(listOf(20L, 21L, 11L), advanceCandidates(all.filter { it != a10 }, a10))
        assertEquals(listOf(21L, 11L, 10L), advanceCandidates(all.filter { it != b20 }, b20))
        assertEquals(listOf(11L, 10L, 20L), advanceCandidates(all.filter { it != b21 }, b21))
    }

    @Test
    fun aListenOrderChangeBetweenTwoCallsChangesTheAnswer() {
        val newest = listOf(key(3, published = 3), key(1, published = 1))
        assertEquals(1L, nextQueueItem(newest, key(2, published = 2)))
        val oldest = newest.map { it.copy(listenOrder = ListenOrder.OLDEST_FIRST) }
        assertEquals(3L, nextQueueItem(oldest, key(2, published = 2, order = ListenOrder.OLDEST_FIRST)))
    }

    @Test
    fun theFinishedEpisodeIsNeverItsOwnSuccessor() {
        val withItself = listOf(key(3), key(2), key(1))
        for (finished in withItself) {
            assertEquals(false, finished.episodeId in advanceCandidates(withItself, finished))
        }
    }
}
