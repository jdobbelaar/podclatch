package com.podclatch

import com.podclatch.playback.BrowseChangeNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Coalesced "children changed" calls (US-16, decision 1, Live). */
class BrowseChangeNotifierTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = MutableStateFlow<Any>(listOf(1L))
    private val feeds = MutableStateFlow<Any>(listOf("a"))
    private val byFeed = MutableStateFlow<Map<Long, List<Long>>>(mapOf(1L to listOf(10L)))
    private val calls = CopyOnWriteArrayList<String>()

    /** How many start values the notifier's collectors have taken; each flow delivers its start value first. */
    private val startValues = AtomicInteger()

    private fun <T> counted(flow: Flow<T>): Flow<T> {
        val first = AtomicInteger()
        return flow.onEach { if (first.getAndIncrement() == 0) startValues.incrementAndGet() }
    }

    private val notifier = BrowseChangeNotifier(scope, counted(queue), counted(feeds), counted(byFeed), windowMs = 150, notify = { calls += it })

    @After
    fun tearDown() {
        notifier.close()
        scope.cancel()
    }

    private fun startAndWaitForStartValues() {
        notifier.start()
        awaitCondition("the three start values taken") { startValues.get() == 3 }
    }

    @Test
    fun theStartStateIsNotAChange() {
        startAndWaitForStartValues()
        queue.value = listOf(99L)
        // Only the queue is told: had a start value counted as a change, feeds and a feed would be in the list too.
        awaitCondition("queue told alone") { calls == listOf("queue") }
    }

    @Test
    fun aBurstOfQueueChangesIsToldOnce() {
        startAndWaitForStartValues()
        (2L..11L).forEach { queue.value = listOf(it) }
        awaitCondition("queue told once") { calls == listOf("queue") }
    }

    @Test
    fun aFeedsChangeIsToldForFeedsOnly() {
        startAndWaitForStartValues()
        feeds.value = listOf("a", "b")
        awaitCondition("feeds told") { calls == listOf("feeds") }
    }

    @Test
    fun onlyTheFeedWhoseNewEpisodesChangedIsTold() {
        startAndWaitForStartValues()
        byFeed.value = mapOf(1L to listOf(10L), 2L to listOf(20L))
        awaitCondition("feed 2 told") { calls == listOf("feed:2") }
        calls.clear()
        byFeed.value = mapOf(1L to listOf(10L, 11L), 2L to listOf(20L))
        awaitCondition("feed 1 told") { calls == listOf("feed:1") }
        calls.clear()
        byFeed.value = mapOf(2L to listOf(20L))
        awaitCondition("feed 1 told after its last New Episode went") { calls == listOf("feed:1") }
    }

    @Test
    fun changesToSeveralNodesInOneBurstAreToldOncePerNode() {
        startAndWaitForStartValues()
        queue.value = listOf(5L)
        byFeed.value = mapOf(1L to listOf(10L, 12L))
        queue.value = listOf(6L)
        awaitCondition("each node told once") { calls.sorted() == listOf("feed:1", "queue") }
        assertEquals(2, calls.size)
    }
}
