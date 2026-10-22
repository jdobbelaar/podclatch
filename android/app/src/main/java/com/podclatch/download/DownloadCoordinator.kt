package com.podclatch.download

import com.podclatch.data.db.DownloadState
import com.podclatch.data.repo.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides what a download request means (shared decisions 1 and 5 in the M6 split) and starts or cancels
 * the Episode's one WorkManager job through [DownloadScheduler]. One lock makes "ask twice" safe.
 */
@Singleton
class DownloadCoordinator @Inject constructor(
    private val store: DownloadStore,
    private val scheduler: DownloadScheduler,
    private val files: DownloadFileSystem,
    private val settings: SettingsRepository,
) : Downloads, PartialDownloadCleaner {
    private val lock = Mutex()

    override suspend fun requestAutomatic(episodeId: Long) = request(episodeId, manual = false)

    override suspend fun requestManual(episodeId: Long) = request(episodeId, manual = true)

    private suspend fun request(id: Long, manual: Boolean) = lock.withLock {
        var row = store.get(id) ?: return@withLock
        if (row.downloadState == DownloadState.DOWNLOADED) {
            if (fileIsUsable(row.localPath)) return@withLock
            // The file is gone: forget it and download again.
            store.reset(id)
            row = store.get(id) ?: return@withLock
        }
        val job = scheduler.state(id)
        val live = row.downloadState == DownloadState.QUEUED || row.downloadState == DownloadState.DOWNLOADING
        if (live && job != null) {
            // Already on its way. Only a manual request changes anything: it takes over an automatic job that
            // still waits (for the network or for a free slot) so it can run now on any network.
            if (manual && (job == JobState.WAITING || row.downloadState == DownloadState.QUEUED)) {
                store.request(id, DownloadState.DOWNLOADING, clearPermanent = true)
                scheduler.enqueue(id, downloadNetworkType(wifiOnly = false, manual = true), manual = true, replace = true)
            }
            return@withLock
        }
        if (!manual && row.downloadPermanentFailure) return@withLock
        val wifiOnly = settings.current().downloadWifiOnly
        store.request(id, if (manual) DownloadState.DOWNLOADING else DownloadState.QUEUED, clearPermanent = manual)
        // A leftover job that is only finishing (the row already says FAILED) is replaced, not kept.
        scheduler.enqueue(id, downloadNetworkType(wifiOnly, manual), manual, replace = job != null)
    }

    override suspend fun remove(episodeId: Long) = lock.withLock {
        val row = store.get(episodeId) ?: return@withLock
        when (row.downloadState) {
            DownloadState.NONE -> Unit
            DownloadState.DOWNLOADED -> {
                val path = row.localPath
                if (path != null && !deleteFile(File(path))) return@withLock // stays DOWNLOADED; the caller retries
                store.reset(episodeId)
            }
            DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.FAILED -> {
                scheduler.cancel(episodeId)
                deletePartial(episodeId)
                store.reset(episodeId)
            }
        }
    }

    override suspend fun discard(episodeIds: Collection<Long>) {
        for (id in episodeIds) {
            try {
                scheduler.cancel(id)
                deletePartial(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The startup cleanup deletes whatever is left.
            }
        }
    }

    private fun fileIsUsable(path: String?): Boolean {
        if (path == null) return false
        val file = File(path)
        return file.isFile && file.length() > 0
    }

    /** True when the file is gone afterwards (it may not have existed). */
    private fun deleteFile(file: File): Boolean = try {
        !file.exists() || file.delete()
    } catch (e: SecurityException) {
        false
    }

    private fun deletePartial(id: Long) {
        val dir = files.audioDirectory() ?: return
        DownloadPaths.partial(dir, id).delete()
    }
}
