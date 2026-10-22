package com.podclatch.download

import android.content.Context
import android.os.Environment
import com.podclatch.Config
import com.podclatch.data.db.DownloadEntry
import com.podclatch.data.db.DownloadProgress
import com.podclatch.data.db.DownloadRow
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.PodClatchDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** [DownloadStore] on Room. */
@Singleton
class RoomDownloadStore @Inject constructor(db: PodClatchDatabase) : DownloadStore {
    private val episodes = db.episodeDao()

    override suspend fun get(id: Long): DownloadRow? = episodes.downloadRow(id)

    override fun observe(id: Long): Flow<DownloadProgress?> = episodes.observeDownload(id).distinctUntilChanged()

    override suspend fun entries(): List<DownloadEntry> = episodes.downloadEntries()

    override suspend fun exists(id: Long): Boolean = episodes.downloadRow(id) != null

    override suspend fun request(id: Long, state: DownloadState, clearPermanent: Boolean) {
        episodes.requestDownload(id, state, clearPermanent)
    }

    override suspend fun markDownloading(id: Long): Boolean = episodes.markDownloading(id) > 0

    override suspend fun saveProgress(id: Long, received: Long, total: Long?) {
        episodes.saveDownloadProgress(id, received, total)
    }

    override suspend fun markDownloaded(id: Long, path: String, bytes: Long): Boolean =
        episodes.markDownloaded(id, path, bytes) > 0

    override suspend fun markFailed(id: Long, permanent: Boolean, received: Long, total: Long?) {
        episodes.markDownloadFailed(id, permanent, received, total)
    }

    override suspend fun reset(id: Long) {
        episodes.resetDownload(id)
    }
}

/**
 * Audio goes under the app-specific external files directory (`Android/data/<package>/files/audio`). If the
 * storage is not mounted, or the directory cannot be created or written, there is no directory.
 */
@Singleton
class ExternalDownloadFileSystem @Inject constructor(
    @ApplicationContext private val context: Context,
) : DownloadFileSystem {
    override fun audioDirectory(): File? {
        return try {
            val base = context.getExternalFilesDir(null) ?: return null
            if (Environment.getExternalStorageState(base) != Environment.MEDIA_MOUNTED) return null
            val dir = File(base, Config.DOWNLOAD_DIR_NAME)
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null
            if (!dir.canWrite()) return null
            dir
        } catch (e: SecurityException) {
            null
        }
    }

    override fun open(file: File, append: Boolean): OutputStream = FileOutputStream(file, append)
}
