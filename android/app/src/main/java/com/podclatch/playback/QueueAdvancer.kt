package com.podclatch.playback

import com.podclatch.domain.QueueKey
import com.podclatch.domain.advanceCandidates
import com.podclatch.domain.sortQueue
import kotlinx.coroutines.CancellationException

/** The Episode to play next and where in it to begin. */
data class AdvanceTarget(val episode: ResolvedEpisode, val startPositionMs: Long)

/**
 * Chooses what plays after an Episode finished or was marked Played (spec 5.3 Queue). Pure Kotlin: the
 * Queue, the order key of the finished Episode, and the resolver are parameters, so a unit test drives it
 * without Android.
 *
 * It walks [advanceCandidates] (the Episodes after the finished one in Queue order, then the wrap) and takes
 * the first that can be resolved, so an Episode that has disappeared is skipped, never a dead end. It
 * returns null when nothing can be played; the service then stops and clears the player.
 */
class QueueAdvancer(
    private val queue: suspend () -> List<QueueKey>,
    private val keyOf: suspend (Long) -> QueueKey?,
    private val resolve: suspend (Long) -> ResolvedEpisode?,
    private val startPositionMs: suspend (Long) -> Long,
) {
    suspend fun next(finishedId: Long): AdvanceTarget? {
        val current = queue()
        val finished = keyOf(finishedId)
        val candidates = if (finished != null) {
            advanceCandidates(current, finished)
        } else {
            // The finished Episode is gone: the Queue from its start is all that is left to go on.
            sortQueue(current).map { it.episodeId }.filter { it != finishedId }
        }
        for (id in candidates) {
            val resolved = try {
                resolve(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            return AdvanceTarget(resolved, startPositionMs(id))
        }
        return null
    }
}
