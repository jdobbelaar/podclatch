package com.podclatch.domain

import com.podclatch.Config
import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.TopUpRow
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.download.Downloads

private val InUse = setOf(DownloadState.DOWNLOADED, DownloadState.DOWNLOADING, DownloadState.QUEUED)

/** The limit the top-up works with: the stored value clamped to 1 to 10 (the Settings screen enforces it later). */
fun clampDownloadAhead(stored: Int): Int =
    stored.coerceIn(Config.MIN_DOWNLOAD_AHEAD_PER_FEED, Config.MAX_DOWNLOAD_AHEAD_PER_FEED)

/**
 * Which Episodes to request (spec 5.3 "Auto-download top-up"), in the order to request them. [rows] are the
 * New Episodes of every Feed. For each Feed, in Feed Priority order, count those that are DOWNLOADED,
 * DOWNLOADING, or QUEUED; if fewer than [limit], take the next candidates (NONE, or FAILED without the
 * permanent mark) in the Feed's Listen Order until the limit is met. Played Episodes are not in [rows], so
 * they never count and are never candidates. Pure: the Listen Order only sorts, it never cancels anything.
 */
fun planTopUp(rows: List<TopUpRow>, limit: Int): List<Long> {
    val perFeed = rows.groupBy { it.feedId }.values.sortedWith(
        compareBy<List<TopUpRow>> { it.first().feedPriority }.thenBy { it.first().feedId },
    )
    val wanted = mutableListOf<Long>()
    for (feedRows in perFeed) {
        val missing = limit - feedRows.count { it.downloadState in InUse }
        if (missing <= 0) continue
        feedRows
            .filter { isCandidate(it) }
            .sortedWith { a, b -> QueueOrder.compare(a.key, b.key) }
            .take(missing)
            .mapTo(wanted) { it.episodeId }
    }
    return wanted
}

private fun isCandidate(row: TopUpRow) =
    !row.downloadPermanentFailure &&
        (row.downloadState == DownloadState.NONE || row.downloadState == DownloadState.FAILED)

/**
 * Keeps each Feed topped up with downloaded New Episodes after a Sync. It always asks for an AUTOMATIC
 * download, so the engine applies "Wi-Fi only". It makes no network call.
 */
class AutoDownloadTopUp(
    private val store: CacheStore,
    private val settings: SettingsRepository,
    private val downloads: Downloads,
) : UpkeepStep {
    override suspend fun run(pull: PullSummary) {
        val limit = clampDownloadAhead(settings.current().downloadAheadPerFeed)
        for (id in planTopUp(store.topUpRows(), limit)) downloads.requestAutomatic(id)
    }
}
