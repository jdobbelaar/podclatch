package com.podclatch.domain

import com.podclatch.Config
import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.SweepRow
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.download.Downloads
import kotlinx.coroutines.CancellationException

/** Which Episode the player has loaded (playing or paused), or null when it has none. Read-only. */
fun interface LoadedEpisodeSource {
    fun loadedEpisodeId(): Long?
}

/**
 * Which Episodes the sweep removes (spec 5.3 "Auto-delete sweep"). [rows] are the Played Episodes that still
 * have a download. A complete download (DOWNLOADED, or a `localPath`) goes once `playedAt <= now - delay`;
 * one that is not complete (QUEUED, DOWNLOADING, FAILED) goes at once, because nothing exists to replay.
 * The loaded Episode is never removed, and an Episode with no `playedAt` is skipped.
 */
fun planSweep(rows: List<SweepRow>, nowMs: Long, loadedId: Long?, delayMs: Long): List<Long> =
    rows.filter { row ->
        val playedAt = row.playedAt
        when {
            row.episodeId == loadedId || playedAt == null -> false
            row.downloadState == DownloadState.DOWNLOADED || row.localPath != null -> playedAt <= nowMs - delayMs
            else -> row.downloadState != DownloadState.NONE
        }
    }.map { it.episodeId }

/**
 * Deletes the downloads of Played Episodes after a Sync. It makes no network call; the file work is done by
 * [Downloads.remove], which leaves the row DOWNLOADED when a file cannot be deleted, so the next Sync retries.
 * One failing Episode does not stop the others.
 */
class AutoDeleteSweep(
    private val store: CacheStore,
    private val settings: SettingsRepository,
    private val downloads: Downloads,
    private val loaded: LoadedEpisodeSource,
    private val clock: Clock,
    private val delayMs: Long = Config.PLAYED_DELETE_DELAY.inWholeMilliseconds,
) : UpkeepStep {
    override suspend fun run(pull: PullSummary) {
        if (!settings.current().autoDeletePlayedDownloads) return
        val ids = planSweep(store.sweepRows(), clock.nowMs(), loaded.loadedEpisodeId(), delayMs)
        for (id in ids) {
            try {
                downloads.remove(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // This Episode stays as it is and the next Sync tries again; the others go on.
            }
        }
    }
}
