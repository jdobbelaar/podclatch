package com.podclatch.domain

import com.podclatch.Config
import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.CacheTransaction
import com.podclatch.data.db.Episode
import com.podclatch.data.db.Feed
import com.podclatch.data.db.PendingStateWrite
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import com.podclatch.data.network.EpisodeDto
import com.podclatch.data.network.EpisodeStateItem
import com.podclatch.data.network.FeedDto
import com.podclatch.data.network.PodClatchApi
import com.podclatch.data.network.StateOutcome
import com.podclatch.data.network.SyncPageDto
import com.podclatch.data.network.TombstoneEntityType
import com.podclatch.data.repo.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SyncTrigger { LAUNCH_OR_FOREGROUND, PERIODIC, MANUAL }

enum class SkipReason { NotConfigured, AlreadyRunning }

/** Why a run failed. [message] is displayable and never contains the token. */
sealed interface FailureReason {
    val message: String

    data class Api(val error: ApiError) : FailureReason {
        override val message: String get() = error.message
    }

    /** A storage or unexpected error; the text is fixed so no exception message leaks. */
    data object Local : FailureReason {
        override val message: String = "The synced data could not be saved on this device."
    }
}

sealed interface SyncResult {
    data object Success : SyncResult
    data class Failed(val reason: FailureReason) : SyncResult
    data class Skipped(val reason: SkipReason) : SyncResult
}

data class SyncState(
    val running: Boolean = false,
    /** Epoch milliseconds of the last successful run in this process; null before the first. */
    val lastSuccessAt: Long? = null,
    /** The last run's failure; null after a success. */
    val lastFailure: FailureReason? = null,
)

/**
 * The one Sync use case (spec 5.3). Launch, foreground, periodic, and manual triggers all call [sync].
 *
 * Order: push, pull, local upkeep. The pull fetches every page into memory first, applies all of it in
 * ONE Room transaction (tombstones, Feeds, Episodes), and only then stores `lastSyncRev`. Every step is
 * idempotent, so a crash between the commit and the cursor write just pulls the same changes again.
 */
class SyncEngine(
    private val api: PodClatchApi,
    private val settings: SettingsRepository,
    private val store: CacheStore,
    private val upkeep: LocalUpkeep,
    private val clock: Clock,
    private val tokenRejection: TokenRejection,
    private val fileDeleter: FileDeleter,
    private val ioDispatcher: CoroutineDispatcher,
    private val pageLimit: Int = Config.SYNC_PAGE_LIMIT,
    private val pushBatchLimit: Int = Config.PUSH_BATCH_LIMIT,
) {
    private val mutex = Mutex()
    private val pendingLock = Any()
    private var pendingManual: CompletableDeferred<SyncResult>? = null

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Runs a Sync unless one is in progress. LAUNCH_OR_FOREGROUND and PERIODIC are dropped during a run.
     * MANUAL waits for the run to finish and then performs its own full run, so the caller is guaranteed
     * a pull that started after the call; manual calls made during that wait share one run (and result).
     */
    suspend fun sync(trigger: SyncTrigger): SyncResult {
        if (mutex.tryLock()) {
            try {
                return runLocked()
            } finally {
                mutex.unlock()
            }
        }
        if (trigger != SyncTrigger.MANUAL) return SyncResult.Skipped(SkipReason.AlreadyRunning)

        val (waiting, leader) = synchronized(pendingLock) {
            val existing = pendingManual
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<SyncResult>().also { pendingManual = it } to true
            }
        }
        if (!leader) return waiting.await()

        var result: SyncResult = SyncResult.Skipped(SkipReason.AlreadyRunning)
        try {
            result = mutex.withLock {
                // Callers arriving from here on started after this run begins, so they queue a new one.
                synchronized(pendingLock) { pendingManual = null }
                runLocked()
            }
            return result
        } finally {
            synchronized(pendingLock) { if (pendingManual === waiting) pendingManual = null }
            waiting.complete(result)
        }
    }

    /** Runs [block] under the same lock as Sync, so it cannot overlap a run (used to wipe the cache). */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    private suspend fun runLocked(): SyncResult {
        val current = settings.current()
        if (!current.isConfigured) return SyncResult.Skipped(SkipReason.NotConfigured)

        _state.update { it.copy(running = true) }
        val result = try {
            push()
            pull()
            upkeep.run()
            SyncResult.Success
        } catch (e: CancellationException) {
            _state.update { it.copy(running = false) }
            throw e
        } catch (e: ApiException) {
            if (e.error is ApiError.Unauthorized) {
                try {
                    tokenRejection.onTokenRejected()
                } catch (_: Exception) {
                    // The run is still reported as Unauthorized.
                }
            }
            SyncResult.Failed(FailureReason.Api(e.error))
        } catch (e: Exception) {
            SyncResult.Failed(FailureReason.Local)
        }
        _state.update {
            when (result) {
                is SyncResult.Success -> SyncState(running = false, lastSuccessAt = clock.nowMs(), lastFailure = null)
                is SyncResult.Failed -> it.copy(running = false, lastFailure = result.reason)
                is SyncResult.Skipped -> it.copy(running = false)
            }
        }
        return result
    }

    // ---- Push ----

    private suspend fun push() {
        val pending = store.pendingWrites()
        for (batch in pending.chunked(pushBatchLimit)) {
            val items = batch.map { it.toItem() }
            val results = api.pushEpisodeState(items)
            if (results.size != items.size || results.indices.any { results[it].episodeId != items[it].episodeId }) {
                throw ApiException(ApiError.Malformed)
            }
            store.transaction {
                for (i in items.indices) {
                    val sent = items[i]
                    val result = results[i]
                    // APPLIED, STALE, and UNKNOWN_EPISODE all settle the row, but only if no newer
                    // local write replaced it while the call was in flight.
                    val settled = deletePendingIfSame(sent.episodeId, sent.stateUpdatedAt)
                    if (settled && result.outcome == StateOutcome.STALE) {
                        val status = result.status
                        val positionSec = result.positionSec
                        val stateUpdatedAt = result.stateUpdatedAt
                        if (status != null && positionSec != null && stateUpdatedAt != null) {
                            updateEpisodeState(sent.episodeId, status, result.playedAt, positionSec, stateUpdatedAt)
                        }
                    }
                }
            }
        }
    }

    private fun PendingStateWrite.toItem() = EpisodeStateItem(
        episodeId = episodeId,
        status = status,
        playedAt = playedAt,
        positionSec = positionSec,
        stateUpdatedAt = stateUpdatedAt,
    )

    // ---- Pull ----

    private suspend fun pull() {
        val startRev = settings.current().lastSyncRev
        val pages = mutableListOf<SyncPageDto>()
        var since = startRev
        while (true) {
            val page = api.pullSync(since, pageLimit)
            pages += page
            if (!page.hasMore) break
            // Guard against an endless loop on a server that claims more but does not advance.
            if (page.nextRev <= since) throw ApiException(ApiError.Malformed)
            since = page.nextRev
        }
        val finalRev = pages.last().nextRev

        apply(pages)

        // Only after the apply transaction has committed. An empty pull leaves the cursor alone.
        if (finalRev > startRev) settings.setLastSyncRev(finalRev)
    }

    private suspend fun apply(pages: List<SyncPageDto>) {
        val deletedEpisodes = HashSet<Long>()
        val deletedFeeds = HashSet<Long>()
        for (page in pages) {
            for (t in page.tombstones) {
                when (t.entityType) {
                    TombstoneEntityType.EPISODE -> deletedEpisodes += t.entityId
                    TombstoneEntityType.FEED -> deletedFeeds += t.entityId
                }
            }
        }
        // Files first: a crash before the commit leaves rows pointing at missing files, which the next
        // pull deletes again; it can never leave an orphaned file.
        val paths = store.localPathsOfEpisodes(deletedEpisodes) + store.localPathsOfFeeds(deletedFeeds)
        if (paths.isNotEmpty()) withContext(ioDispatcher) { paths.forEach { fileDeleter.delete(it) } }

        store.transaction {
            for (id in deletedEpisodes) {
                deleteEpisode(id)
                deletePending(id)
            }
            for (id in deletedFeeds) deleteFeed(id)

            // An entity that is upserted on one page and tombstoned on a later one must stay deleted;
            // ids are never reused, so a tombstoned id is skipped.
            val pending = allPending().associateBy { it.episodeId }.toMutableMap()
            for (page in pages) {
                for (feed in page.feeds) {
                    if (feed.id in deletedFeeds) continue
                    val entity = feed.toEntity()
                    insertFeedIfAbsent(entity)
                    updateFeedSynced(entity)
                }
                for (episode in page.episodes) {
                    if (episode.id in deletedEpisodes || episode.feedId in deletedFeeds) continue
                    // Foreign key: skip an Episode whose Feed is not cached rather than fail the pull forever.
                    if (!feedExists(episode.feedId)) continue
                    applyEpisode(episode, pending)
                }
            }
        }
    }

    private suspend fun CacheTransaction.applyEpisode(dto: EpisodeDto, pending: MutableMap<Long, PendingStateWrite>) {
        val entity = dto.toEntity()
        insertEpisodeIfAbsent(entity)
        updateEpisodeContent(entity)
        val local = pending[dto.id]
        if (local == null || dto.stateUpdatedAt >= local.stateUpdatedAt) {
            // The server wins ties because it would call an equal timestamp STALE.
            updateEpisodeState(dto.id, dto.status, dto.playedAt, dto.positionSec, dto.stateUpdatedAt)
            if (local != null) {
                deletePending(dto.id)
                pending.remove(dto.id)
            }
        }
        // Otherwise the local write is newer: leave the Episode's state and the outbox row for the next push.
    }

    private fun FeedDto.toEntity() = Feed(
        id = id,
        feedUrl = feedUrl,
        title = title,
        author = author,
        description = description,
        artworkUrl = artworkUrl,
        priority = priority,
        listenOrder = listenOrder,
        lastRefreshedAt = lastRefreshedAt,
        subscribedAt = subscribedAt,
        rev = rev,
    )

    private fun EpisodeDto.toEntity() = Episode(
        id = id,
        feedId = feedId,
        guid = guid,
        title = title,
        description = description,
        audioUrl = audioUrl,
        publishedAt = publishedAt,
        durationSec = durationSec,
        artworkUrl = artworkUrl,
        status = status,
        playedAt = playedAt,
        positionSec = positionSec,
        stateUpdatedAt = stateUpdatedAt,
        rev = rev,
    )
}
