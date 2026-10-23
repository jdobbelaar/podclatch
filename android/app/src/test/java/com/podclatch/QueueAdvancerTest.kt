package com.podclatch

import com.podclatch.data.db.ListenOrder
import com.podclatch.domain.QueueKey
import com.podclatch.playback.AudioSource
import com.podclatch.playback.QueueAdvancer
import com.podclatch.playback.ResolvedEpisode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueAdvancerTest {
    private fun key(id: Long) = QueueKey(id, feedId = 1, feedPriority = 0, listenOrder = ListenOrder.NEWEST_FIRST, publishedAt = id)

    private fun resolved(id: Long) = ResolvedEpisode(id.toString(), AudioSource.Stream("https://audio.example.com/$id.mp3"), "T$id", "F", null, null)

    private class Env(
        var queue: List<QueueKey>,
        val known: MutableSet<Long>,
        val keys: Map<Long, QueueKey>,
        val resolver: (Long) -> Unit = {},
    ) {
        val resolveCalls = mutableListOf<Long>()
        val advancer = QueueAdvancer(
            queue = { queue },
            keyOf = { keys[it] },
            resolve = { id ->
                resolveCalls += id
                resolver(id)
                if (id in known) ResolvedEpisode(id.toString(), AudioSource.Stream("https://audio.example.com/$id.mp3"), "T$id", "F", null, null) else null
            },
            startPositionMs = { id -> id * 1_000 },
        )
    }

    private fun env(queueIds: List<Long>, known: Set<Long> = queueIds.toSet(), finishedKnown: List<Long> = emptyList()): Env {
        val keys = (queueIds + finishedKnown).associateWith { key(it) }
        return Env(queueIds.map(::key).sortedByDescending { it.publishedAt }, known.toMutableSet(), keys)
    }

    @Test
    fun theNextItemAfterTheFinishedOneIsChosenWithItsStartPosition() = runBlocking {
        // Queue after episode 2 was marked Played: 3 and 1 (newest first).
        val e = env(listOf(3, 1), finishedKnown = listOf(2))
        val target = e.advancer.next(2)!!
        assertEquals("1", target.episode.mediaId)
        assertEquals(1_000L, target.startPositionMs)
    }

    @Test
    fun theLastItemWrapsToTheFirst() = runBlocking {
        val e = env(listOf(3, 2), finishedKnown = listOf(1))
        assertEquals("3", e.advancer.next(1)!!.episode.mediaId)
    }

    @Test
    fun anEmptyQueueStops() = runBlocking {
        val e = env(emptyList(), finishedKnown = listOf(1))
        assertNull(e.advancer.next(1))
        assertEquals(emptyList<Long>(), e.resolveCalls)
    }

    @Test
    fun anEpisodeThatCannotBeResolvedIsSkipped() = runBlocking {
        val e = env(listOf(5, 4, 3), known = setOf(5, 3), finishedKnown = listOf(6))
        // After 6 comes 5 (resolvable) ... make 5 vanish: then 4 (gone) and 3.
        e.known.remove(5)
        val target = e.advancer.next(6)!!
        assertEquals("3", target.episode.mediaId)
        assertEquals(listOf(5L, 4L, 3L), e.resolveCalls)
    }

    @Test
    fun nothingResolvableStopsInsteadOfLooping() = runBlocking {
        val e = env(listOf(3, 2), known = emptySet(), finishedKnown = listOf(1))
        assertNull(e.advancer.next(1))
        assertEquals(listOf(3L, 2L), e.resolveCalls)
    }

    @Test
    fun aResolverThatThrowsIsSkippedToo() = runBlocking {
        val keys = mapOf(2L to key(2), 3L to key(3), 1L to key(1))
        val advancer = QueueAdvancer(
            queue = { listOf(key(3), key(1)) },
            keyOf = { keys[it] },
            resolve = { id -> if (id == 1L) throw IllegalStateException("broken") else resolved(id) },
            startPositionMs = { 0 },
        )
        // After 2: 1 throws, so the wrap reaches 3.
        assertEquals("3", advancer.next(2)!!.episode.mediaId)
    }

    @Test
    fun aFinishedEpisodeThatIsGoneStartsFromTheFirstItem() = runBlocking {
        val e = env(listOf(3, 2, 1))
        // Episode 9 has no key (its row is gone).
        assertEquals("3", e.advancer.next(9)!!.episode.mediaId)
    }

    @Test
    fun aFinishedEpisodeNotInTheQueueTakesTheFirstItemThatSortsAfterIt() = runBlocking {
        // 5 is New but was never downloaded (so it is not in the Queue); the Queue holds 9, 7, 3.
        val e = env(listOf(9, 7, 3), finishedKnown = listOf(5))
        assertEquals("3", e.advancer.next(5)!!.episode.mediaId)
    }

    @Test
    fun theQueueIsReadAfreshForEveryCall() = runBlocking {
        val e = env(listOf(3, 1), finishedKnown = listOf(2))
        assertEquals("1", e.advancer.next(2)!!.episode.mediaId)
        e.queue = listOf(key(3))
        assertEquals("3", e.advancer.next(2)!!.episode.mediaId)
    }
}
