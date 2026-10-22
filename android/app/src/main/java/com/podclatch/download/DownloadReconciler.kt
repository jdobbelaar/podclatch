package com.podclatch.download

import com.podclatch.data.db.DownloadState
import com.podclatch.data.repo.SettingsRepository
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs once at process start (shared decision 6) and brings the database and the disk back in line:
 * a QUEUED or DOWNLOADING row with no live job is enqueued again (as an automatic job, so it honours
 * Wi-Fi-only), a DOWNLOADED row whose file is missing or empty goes back to NONE, and a partial file with
 * no Episode row is deleted. Idempotent, and it never throws.
 */
@Singleton
class DownloadReconciler @Inject constructor(
    private val store: DownloadStore,
    private val scheduler: DownloadScheduler,
    private val files: DownloadFileSystem,
    private val settings: SettingsRepository,
) {
    suspend fun run() {
        try {
            for (entry in store.entries()) {
                when (entry.downloadState) {
                    DownloadState.QUEUED, DownloadState.DOWNLOADING -> reenqueueIfOrphaned(entry.id)
                    DownloadState.DOWNLOADED -> forgetMissingFile(entry.id, entry.localPath)
                    else -> Unit
                }
            }
            deleteOrphanPartials()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing to recover from; the next start tries again.
        }
    }

    private suspend fun reenqueueIfOrphaned(id: Long) {
        if (scheduler.state(id) != null) return
        val wifiOnly = settings.current().downloadWifiOnly
        scheduler.enqueue(id, downloadNetworkType(wifiOnly, manual = false), manual = false, replace = false)
    }

    private suspend fun forgetMissingFile(id: Long, path: String?) {
        val file = path?.let(::File)
        if (file == null || !file.isFile || file.length() == 0L) store.reset(id)
    }

    private suspend fun deleteOrphanPartials() {
        val dir = files.audioDirectory() ?: return
        for (file in dir.listFiles().orEmpty()) {
            val id = DownloadPaths.partialEpisodeId(file.name) ?: continue
            if (!store.exists(id)) file.delete()
        }
    }
}
