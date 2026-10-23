package com.podclatch.playback

import com.podclatch.Config
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.PlaybackWriteOutcome
import com.podclatch.data.repo.PlaybackWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the player is right now: the loaded Episode (null for none), its position, and its duration when known. */
data class PlaybackSnapshot(val episodeId: Long?, val positionMs: Long, val durationMs: Long?)

/** The recorder's view of the player. The service answers on the player's thread. */
fun interface PlaybackSource {
    /** The player's current state, or null if the player is gone. */
    suspend fun snapshot(): PlaybackSnapshot?
}

/**
 * Remembers where the listener is and when an Episode is finished (US-09). Pure Kotlin: the [scope], the
 * [repository], and the player's [source] are parameters, so a unit test drives it with virtual time.
 *
 * Events come from the player's listener (any thread):
 * - [onLoaded] a different Episode (or none) is now loaded; [onPlaying] playback started or stopped moving;
 * - [onPause], [onStop], [onSwitch] the final save for an Episode (a `NEW` one at 95 percent or more becomes
 *   Played), followed by a Sync;
 * - [onEnded] the Episode reached its end: Played, then [onFinished] with its id so the service moves on to the
 *   next Queue item (or stops and clears the player when there is none);
 * - [detach] the state changed under the player, so nothing more is written for the loaded Episode.
 *
 * Every write runs in one worker, in the order the events came, so a late tick cannot overwrite a newer
 * pause save. Only `NEW` Episodes are written (the repository reads the status at write time). A tick
 * writes Room and the outbox only; the network is touched only after a final save, through [sync].
 */
class ProgressRecorder(
    private val scope: CoroutineScope,
    private val repository: EpisodeStateRepository,
    private val source: PlaybackSource,
    private val fallbackDurationSec: suspend (Long) -> Int?,
    private val sync: suspend () -> Unit,
    private val onFinished: (episodeId: Long) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val tickMs: Long = Config.POSITION_SAVE_INTERVAL_MS,
) {
    private val lock = Any()
    private val writeLock = Mutex()
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // Guarded by [lock].
    private var sessionNo = 0
    private var episodeId: Long? = null
    private var generation = 0
    private var ended = false
    private var tickJob: Job? = null
    private val detachedSessions = mutableSetOf<Int>()

    // Guarded by [writeLock]: (Episode id, stamp) of every write the recorder made.
    private val ownStamps = mutableSetOf<Pair<Long, Long>>()

    init {
        scope.launch {
            for (op in queue) {
                try {
                    op()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    /** A different Episode, or none, is now loaded. */
    fun onLoaded(newEpisodeId: Long?) = synchronized(lock) {
        sessionNo++
        episodeId = newEpisodeId
        ended = false
        stopTicking()
    }

    /** Playback started moving (true) or stopped moving (false): ticks run only while it moves. */
    fun onPlaying(playing: Boolean) = synchronized(lock) {
        if (!playing) return@synchronized stopTicking()
        val id = episodeId
        if (id == null || ended || tickJob != null || sessionNo in detachedSessions) return@synchronized
        val gen = generation
        val session = sessionNo
        tickJob = scope.launch {
            while (isActive) {
                delay(tickMs)
                val snapshot = source.snapshot() ?: continue
                if (snapshot.episodeId != id) continue
                synchronized(lock) {
                    if (generation == gen && sessionNo == session) enqueue(session) { saveTick(id, snapshot) }
                }
            }
        }
    }

    /** Playback was paused (`playWhenReady` became false): save at once, then Sync. */
    fun onPause(positionMs: Long, durationMs: Long?) = finalSave(positionMs, durationMs, previousId = null)

    /** The player went idle, or the service is going away: same as a pause. */
    fun onStop(positionMs: Long, durationMs: Long?) = finalSave(positionMs, durationMs, previousId = null)

    /** The Episode [previousId] is being replaced by another: judged at the position it was left at. */
    fun onSwitch(previousId: Long, positionMs: Long, durationMs: Long?) = finalSave(positionMs, durationMs, previousId)

    /** The loaded Episode reached its end. */
    fun onEnded(durationMs: Long?, positionMs: Long) = synchronized(lock) {
        val id = episodeId ?: return@synchronized
        if (ended) return@synchronized
        ended = true
        stopTicking()
        enqueue(sessionNo) { saveEnd(id, positionMs, durationMs) }
    }

    /** The state of the loaded Episode changed under the player: write nothing more for it. */
    fun detach() = synchronized(lock) {
        detachedSessions += sessionNo
        stopTicking()
    }

    /** True when the recorder itself wrote this stamp. Waits for a write in progress to settle first. */
    suspend fun isOwnStamp(episodeId: Long, stamp: Long): Boolean = writeLock.withLock { (episodeId to stamp) in ownStamps }

    /** No more events: the queued writes still run, then the worker ends. */
    fun close() {
        synchronized(lock) { stopTicking() }
        queue.close()
    }

    private fun finalSave(positionMs: Long, durationMs: Long?, previousId: Long?) = synchronized(lock) {
        val id = previousId ?: episodeId ?: return@synchronized
        if (previousId == null) {
            if (ended) return@synchronized
            stopTicking()
        }
        val session = if (id == episodeId) sessionNo else NO_SESSION
        enqueue(session) { saveFinal(id, positionMs, durationMs) }
    }

    private fun stopTicking() {
        generation++
        tickJob?.cancel()
        tickJob = null
    }

    /** Called with [lock] held, so the order of the queue is the order of the events. */
    private fun enqueue(session: Int, op: suspend () -> Unit) {
        queue.trySend {
            val skip = synchronized(lock) { session in detachedSessions }
            if (!skip) writeLock.withLock { op() }
        }
    }

    private suspend fun saveTick(id: Long, snapshot: PlaybackSnapshot) {
        record(id, repository.savePosition(id, secondsOf(snapshot.positionMs)))
    }

    private suspend fun saveFinal(id: Long, positionMs: Long, playerDurationMs: Long?) {
        val duration = effectiveDurationMs(playerDurationMs, fallbackDurationSec(id))
        val result = if (playedAtStop(positionMs, duration)) {
            repository.markPlayedByPlayback(id, secondsOf(minOf(positionMs, duration ?: positionMs)))
        } else {
            repository.savePosition(id, secondsOf(positionMs))
        }
        record(id, result)
        if (result.outcome == PlaybackWriteOutcome.SAVED || result.outcome == PlaybackWriteOutcome.UNCHANGED) launchSync()
    }

    private suspend fun saveEnd(id: Long, positionMs: Long, playerDurationMs: Long?) {
        try {
            val duration = effectiveDurationMs(playerDurationMs, fallbackDurationSec(id))
            // The end was reached, so the position is the duration when it is known.
            val result = repository.markPlayedByPlayback(id, secondsOf(duration ?: positionMs))
            record(id, result)
            if (result.outcome == PlaybackWriteOutcome.SAVED) launchSync()
        } finally {
            onFinished(id)
        }
    }

    private fun record(id: Long, result: PlaybackWriteResult) {
        result.stateUpdatedAt?.let { ownStamps += id to it }
    }

    private fun launchSync() {
        scope.launch {
            try {
                sync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    private fun secondsOf(positionMs: Long): Int =
        (positionMs.coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val NO_SESSION = -1
    }
}
