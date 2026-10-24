package com.podclatch.domain

import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.ResumeRow

/** What to resume: the Episode and where in it to start. */
data class ResumeChoice(val episodeId: Long, val startPositionMs: Long)

/**
 * Which Episode to resume when the system asks and no player state survived (US-16, decision 4):
 *
 * 1. the New Episode with saved progress (`positionSec` above 0) whose `stateUpdatedAt` is greatest, the one
 *    the user last listened to and left unfinished, whatever its Feed or download state (ties: the greater
 *    Episode id, so the answer is fixed);
 * 2. else the first item of the Queue, at 0;
 * 3. else nothing.
 *
 * A Played Episode is never chosen. Pure Kotlin: [inProgress] and [queue] are the Room rows.
 */
fun chooseResume(inProgress: List<ResumeRow>, queue: List<QueueKey>): ResumeChoice? {
    val last = inProgress
        .filter { it.status == EpisodeStatus.NEW && it.positionSec > 0 }
        .maxWithOrNull(compareBy<ResumeRow>({ it.stateUpdatedAt }, { it.episodeId }))
    if (last != null) return ResumeChoice(last.episodeId, last.positionSec * 1000L)
    val first = sortQueue(queue).firstOrNull() ?: return null
    return ResumeChoice(first.episodeId, 0L)
}
