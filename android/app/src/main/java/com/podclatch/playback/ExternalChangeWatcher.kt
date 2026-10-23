package com.podclatch.playback

import com.podclatch.data.db.EpisodeProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The loaded Episode [episodeId] changed under the player; [progress] is its new state, null when the row is gone. */
data class ExternalChange(val episodeId: Long, val progress: EpisodeProgress?)

/**
 * The "state changed under the player" rule (spec 5.3 Mark Played / New). It watches the loaded Episode's
 * row. When the row disappears (unsubscribe), or its `stateUpdatedAt` changes to a stamp the recorder did
 * not write (a Mark, a mark-all, or a pulled change), [onExternalChange] runs once: the service detaches
 * the recorder (so no late save overwrites the Mark), then advances to the next Queue item when the Episode
 * was marked Played, and otherwise stops and clears the player. The recorder's own writes (positions, its
 * Played) never trigger it.
 */
class ExternalChangeWatcher(
    private val scope: CoroutineScope,
    private val observe: (Long) -> Flow<EpisodeProgress?>,
    private val isOwnStamp: suspend (episodeId: Long, stamp: Long) -> Boolean,
    private val onExternalChange: (ExternalChange) -> Unit,
) {
    private var job: Job? = null

    /** A different Episode, or none (null), is now loaded. Call from one thread. */
    fun onLoaded(episodeId: Long?) {
        job?.cancel()
        job = null
        if (episodeId == null) return
        job = scope.launch {
            var known: Long? = null
            var changed: EpisodeProgress? = null
            observe(episodeId).first { progress ->
                changed = progress
                when {
                    progress == null -> true
                    known == null -> {
                        known = progress.stateUpdatedAt
                        false
                    }
                    progress.stateUpdatedAt == known -> false
                    isOwnStamp(episodeId, progress.stateUpdatedAt) -> {
                        known = progress.stateUpdatedAt
                        false
                    }
                    else -> true
                }
            }
            onExternalChange(ExternalChange(episodeId, changed))
        }
    }
}
