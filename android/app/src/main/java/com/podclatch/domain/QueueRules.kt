package com.podclatch.domain

import com.podclatch.data.db.ListenOrder

/*
 * The Queue's order and the "next item" rule (spec 5.3, shared decision 7 of the M6 split). Pure Kotlin:
 * the Queue query, the Queue screen, the playback service, and later Android Auto all use these functions,
 * so they cannot disagree about what comes next.
 */

/**
 * What the order of the Queue depends on, for one Episode. The Episode need not be in the Queue: the
 * "next item" rule also works for an Episode that was just marked Played.
 */
data class QueueKey(
    val episodeId: Long,
    val feedId: Long,
    val feedPriority: Int,
    val listenOrder: ListenOrder,
    val publishedAt: Long,
)

/**
 * Queue order: Feed Priority ascending (then the Feed id, so two Feeds that briefly share a priority keep
 * a fixed order), then inside a Feed its Listen Order. `NEWEST_FIRST` is `publishedAt` descending then
 * `id` descending; `OLDEST_FIRST` is the exact reverse.
 */
val QueueOrder: Comparator<QueueKey> = Comparator { a, b ->
    val byFeed = compareValuesBy(a, b, { it.feedPriority }, { it.feedId })
    if (byFeed != 0) {
        byFeed
    } else {
        val newestFirst = compareValuesBy(b, a, { it.publishedAt }, { it.episodeId })
        if (a.listenOrder == ListenOrder.NEWEST_FIRST) newestFirst else -newestFirst
    }
}

fun sortQueue(keys: List<QueueKey>): List<QueueKey> = keys.sortedWith(QueueOrder)

/**
 * The Episodes to try, in order, when [finished] ends or is marked Played: first those that follow it in
 * Queue order, then (the wrap) those before it. [finished] itself is never in the answer. [queue] is the
 * Queue as it is now, so it normally does not hold [finished] any more; if the finished Episode is not in
 * the Queue (for example it was streamed and its download failed), "follows" means "sorts after it by the
 * same order". The answer is the same as walking the Queue from just before the mark, because removing
 * the finished Episode does not change the order of the others.
 */
fun advanceCandidates(queue: List<QueueKey>, finished: QueueKey): List<Long> {
    val others = sortQueue(queue.filter { it.episodeId != finished.episodeId })
    val after = others.filter { QueueOrder.compare(it, finished) > 0 }
    val before = others.filter { QueueOrder.compare(it, finished) < 0 }
    return (after + before).map { it.episodeId }
}

/** The first of [advanceCandidates], or null when no other Episode is in the Queue. */
fun nextQueueItem(queue: List<QueueKey>, finished: QueueKey): Long? = advanceCandidates(queue, finished).firstOrNull()
