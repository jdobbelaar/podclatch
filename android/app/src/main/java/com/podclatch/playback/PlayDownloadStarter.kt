package com.podclatch.playback

import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.download.Downloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** What the play path needs to know about an Episode to decide on its download. Never holds an address. */
data class PlayDownloadInfo(
    val status: EpisodeStatus,
    val downloadState: DownloadState,
    val localPath: String?,
)

/**
 * "Playing an Episode also downloads it" (spec 5.3, decision 15). The service calls [onPlayRequested] once for
 * every Episode it resolves for playback, so a tap in Feed Detail, the debug `PLAY` action, and later Android Auto
 * all start the download the same way. The lookup and the file check are parameters so a plain unit test drives it;
 * the request goes to the [Downloads] interface (a manual one: it ignores "Wi-Fi only" and promotes a waiting
 * automatic job).
 *
 * - New and not downloaded (`NONE`, `QUEUED`, `FAILED`, or `DOWNLOADED` with the file gone): a manual request.
 * - `DOWNLOADING`, or `DOWNLOADED` with a usable file: nothing (no second download).
 * - Played: nothing (the sweep would delete it again, and it is not in the Queue).
 * - Unknown Episode: nothing.
 *
 * Never throws (except cancellation): a failed request must not break playback.
 */
class PlayDownloadStarter(
    private val lookup: suspend (Long) -> PlayDownloadInfo?,
    private val downloads: Downloads,
    private val fileUsable: (String) -> Boolean,
    private val fileDispatcher: CoroutineDispatcher,
) {
    suspend fun onPlayRequested(episodeId: Long) {
        try {
            val info = lookup(episodeId) ?: return
            if (info.status != EpisodeStatus.NEW) return
            when (info.downloadState) {
                DownloadState.DOWNLOADING -> return
                DownloadState.DOWNLOADED -> {
                    val path = info.localPath
                    if (path != null && withContext(fileDispatcher) { fileUsable(path) }) return
                }
                DownloadState.NONE, DownloadState.QUEUED, DownloadState.FAILED -> Unit
            }
            downloads.requestManual(episodeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Playback goes on by streaming; the next play request tries again. No message: it could hold an address.
        }
    }
}
