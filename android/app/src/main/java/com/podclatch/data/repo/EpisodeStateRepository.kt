package com.podclatch.data.repo

import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.PendingStateWrite
import com.podclatch.domain.Clock
import javax.inject.Inject
import javax.inject.Singleton

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
    /** `status = PLAYED`, `playedAt = now`, `positionSec` kept. Returns false for an unknown Episode. */
    suspend fun markPlayed(episodeId: Long): Boolean = record(episodeId) { now, previous ->
        Change(EpisodeStatus.PLAYED, playedAt = now, positionSec = previous.positionSec)
    }

    /** `status = NEW`, `playedAt = null`, `positionSec = 0`. Returns false for an unknown Episode. */
    suspend fun markNew(episodeId: Long): Boolean = record(episodeId) { _, _ ->
        Change(EpisodeStatus.NEW, playedAt = null, positionSec = 0)
    }

    private class Change(val status: EpisodeStatus, val playedAt: Long?, val positionSec: Int)

    private suspend fun record(
        episodeId: Long,
        change: (now: Long, previous: com.podclatch.data.db.Episode) -> Change,
    ): Boolean = store.transaction {
        val episode = getEpisode(episodeId) ?: return@transaction false
        val now = clock.nowMs()
        val previousStamp = maxOf(episode.stateUpdatedAt, getPending(episodeId)?.stateUpdatedAt ?: Long.MIN_VALUE)
        // Strictly increasing: the server treats an equal timestamp as STALE.
        val stamp = maxOf(now, previousStamp + 1)
        val c = change(now, episode)
        updateEpisodeState(episodeId, c.status, c.playedAt, c.positionSec, stamp)
        putPending(PendingStateWrite(episodeId, c.status, c.playedAt, c.positionSec, stamp))
        true
    }
}
