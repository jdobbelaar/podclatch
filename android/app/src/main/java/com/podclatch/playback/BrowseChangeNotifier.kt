package com.podclatch.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Turns changes to the Queue, the Feeds, and a Feed's New Episodes into "the children of this node
 * changed" calls, at most one per node per burst: a change waits [windowMs] for a quieter moment, and
 * every node that changed meanwhile is told once. The first value of each flow is the state at the start,
 * not a change. Pure Kotlin on flows; the service turns [notify] into `notifyChildrenChanged`.
 */
class BrowseChangeNotifier(
    private val scope: CoroutineScope,
    private val queue: Flow<Any>,
    private val feeds: Flow<Any>,
    private val newEpisodesByFeed: Flow<Map<Long, List<Long>>>,
    private val windowMs: Long,
    private val notify: (String) -> Unit,
) {
    private val lock = Any()
    private val pending = linkedSetOf<String>()
    private var flush: Job? = null
    private val jobs = mutableListOf<Job>()

    fun start() {
        jobs += scope.launch { queue.drop(1).collect { mark(BrowseIds.QUEUE) } }
        jobs += scope.launch { feeds.drop(1).collect { mark(BrowseIds.FEEDS) } }
        jobs += scope.launch {
            var previous: Map<Long, List<Long>>? = null
            newEpisodesByFeed.collect { current ->
                val before = previous
                previous = current
                if (before != null) {
                    (before.keys + current.keys).filter { before[it] != current[it] }.forEach { mark(BrowseIds.feed(it)) }
                }
            }
        }
    }

    fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        synchronized(lock) {
            flush?.cancel()
            pending.clear()
        }
    }

    private fun mark(node: String) {
        synchronized(lock) {
            pending += node
            flush?.cancel()
            flush = scope.launch {
                delay(windowMs)
                val batch = synchronized(lock) { pending.toList().also { pending.clear() } }
                batch.forEach(notify)
            }
        }
    }
}
