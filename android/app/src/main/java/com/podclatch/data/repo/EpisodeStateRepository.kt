package com.podclatch.data.repo

import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.CacheTransaction
import com.podclatch.data.db.Episode
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.PendingStateWrite
import com.podclatch.domain.Clock
import com.podclatch.domain.changesOnMarkNew
import com.podclatch.domain.changesOnMarkPlayed
import javax.inject.Inject
import javax.inject.Singleton

/** What a single Mark did. */
enum class MarkResult {
    /** The Episode row and the outbox row were written. */
    CHANGED,

    /** The Episode already was in that state: nothing was written. */
    UNCHANGED,

    /** No such Episode. */
    NOT_FOUND,
}

/** What a playback write did. */
enum class PlaybackWriteOutcome {
    /** The Episode row and the outbox row were written. */
    SAVED,

    /** The Episode is not New (a Mark made it Played), so the write was refused. */
    NOT_NEW,

    /** No such Episode (for example its Feed was unsubscribed); nothing was written. */
    UNKNOWN_EPISODE,

    /** Nothing to change: the saved position already is the same. */
    UNCHANGED,
}

/** The [outcome] of a playback write and, when it was SAVED, the `stateUpdatedAt` it stamped. */
data class PlaybackWriteResult(val outcome: PlaybackWriteOutcome, val stateUpdatedAt: Long? = null)

/**
 * Records local New / Played changes (spec 5.3 Mark Played / New). Each change updates the Episode row
 * and writes the outbox row in one Room transaction. Callers that want the change pushed right away
 * follow up with `SyncEngine.sync(MANUAL)`.
 */
@Singleton
class EpisodeStateRepository @Inject constructor(
    private val store: CacheStore,
    private val clock: Clock,
) {
    /**
     * `status = PLAYED`, `playedAt = now`, `positionSec` kept. An Episode that already is Played is left
     * untouched ([MarkResult.UNCHANGED]), so its `playedAt` stays.
     */
    suspend fun markPlayed(episodeId: Long): MarkResult = store.transaction {
        val episode = getEpisode(episodeId) ?: return@transaction MarkResult.NOT_FOUND
        if (!changesOnMarkPlayed(episode.status)) return@transaction MarkResult.UNCHANGED
        writePlayed(episode, clock.nowMs())
        MarkResult.CHANGED
    }

    /**
     * `status = NEW`, `playedAt = null`, `positionSec = 0`. A New Episode without progress is left
     * untouched ([MarkResult.UNCHANGED]).
     */
    suspend fun markNew(episodeId: Long): MarkResult = store.transaction {
        val episode = getEpisode(episodeId) ?: return@transaction MarkResult.NOT_FOUND
        if (!changesOnMarkNew(episode.status, episode.positionSec)) return@transaction MarkResult.UNCHANGED
        writeNew(episode, clock.nowMs())
        MarkResult.CHANGED
    }

    /**
     * Mark all Played for a Feed: every New Episode becomes Played with `positionSec = 0` (spec: reset for
     * mark-all); Played Episodes are untouched. All in one transaction. Returns how many Episodes changed.
     */
    suspend fun markAllPlayed(feedId: Long): Int = store.transaction {
        val now = clock.nowMs()
        var changed = 0
        for (episode in episodesOfFeed(feedId)) {
            if (!changesOnMarkPlayed(episode.status)) continue
            writePlayed(episode, now, positionSec = 0)
            changed++
        }
        changed
    }

    /**
     * Mark all New for a Feed: every Played Episode, and every New one with progress, becomes New with
     * `positionSec = 0`. All in one transaction. Returns how many Episodes changed.
     */
    suspend fun markAllNew(feedId: Long): Int = store.transaction {
        val now = clock.nowMs()
        var changed = 0
        for (episode in episodesOfFeed(feedId)) {
            if (!changesOnMarkNew(episode.status, episode.positionSec)) continue
            writeNew(episode, now)
            changed++
        }
        changed
    }

    /**
     * Saves where playback is (floor of the player's position in seconds) for an Episode that is `NEW` at
     * this moment. Played Episodes are never touched (a replay changes nothing), an equal position writes
     * nothing, and an Episode that is gone is reported, not thrown. One Room transaction: Episode row and
     * outbox row, stamped strictly above the stored and pending stamps.
     */
    suspend fun savePosition(episodeId: Long, positionSec: Int): PlaybackWriteResult = store.transaction {
        val episode = getEpisode(episodeId) ?: return@transaction PlaybackWriteResult(PlaybackWriteOutcome.UNKNOWN_EPISODE)
        if (episode.status != EpisodeStatus.NEW) return@transaction PlaybackWriteResult(PlaybackWriteOutcome.NOT_NEW)
        val position = positionSec.coerceAtLeast(0)
        if (position == episode.positionSec) return@transaction PlaybackWriteResult(PlaybackWriteOutcome.UNCHANGED)
        val stamp = write(episode, EpisodeStatus.NEW, playedAt = null, positionSec = position, now = clock.nowMs())
        PlaybackWriteResult(PlaybackWriteOutcome.SAVED, stamp)
    }

    /**
     * Playback finished a `NEW` Episode: `PLAYED`, `playedAt = now`, `positionSec` as given. Refuses an
     * Episode that is not New (`playedAt` of a Played Episode never changes).
     */
    suspend fun markPlayedByPlayback(episodeId: Long, positionSec: Int): PlaybackWriteResult = store.transaction {
        val episode = getEpisode(episodeId) ?: return@transaction PlaybackWriteResult(PlaybackWriteOutcome.UNKNOWN_EPISODE)
        if (episode.status != EpisodeStatus.NEW) return@transaction PlaybackWriteResult(PlaybackWriteOutcome.NOT_NEW)
        val now = clock.nowMs()
        val stamp = write(episode, EpisodeStatus.PLAYED, playedAt = now, positionSec = positionSec.coerceAtLeast(0), now = now)
        PlaybackWriteResult(PlaybackWriteOutcome.SAVED, stamp)
    }

    private suspend fun CacheTransaction.writePlayed(episode: Episode, now: Long, positionSec: Int = episode.positionSec) =
        write(episode, EpisodeStatus.PLAYED, playedAt = now, positionSec = positionSec, now = now)

    private suspend fun CacheTransaction.writeNew(episode: Episode, now: Long) =
        write(episode, EpisodeStatus.NEW, playedAt = null, positionSec = 0, now = now)

    private suspend fun CacheTransaction.write(
        episode: Episode,
        status: EpisodeStatus,
        playedAt: Long?,
        positionSec: Int,
        now: Long,
    ): Long {
        val previousStamp = maxOf(episode.stateUpdatedAt, getPending(episode.id)?.stateUpdatedAt ?: Long.MIN_VALUE)
        // Strictly increasing: the server treats an equal timestamp as STALE.
        val stamp = maxOf(now, previousStamp + 1)
        updateEpisodeState(episode.id, status, playedAt, positionSec, stamp)
        putPending(PendingStateWrite(episode.id, status, playedAt, positionSec, stamp))
        return stamp
    }
}
