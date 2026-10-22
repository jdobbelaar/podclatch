package com.podclatch.download

import androidx.work.NetworkType
import com.podclatch.data.db.DownloadEntry
import com.podclatch.data.db.DownloadProgress
import com.podclatch.data.db.DownloadRow
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.OutputStream

/**
 * The one door to downloads. US-12 (play), US-14 (top-up), and US-15 (sweep) call only this. Nothing
 * here throws for an unknown Episode or a repeated call, and an Episode is only ever named by its id.
 */
interface Downloads {
    /** Starts a download that honours "Wi-Fi only" (the top-up). A permanently failed Episode is skipped. */
    suspend fun requestAutomatic(episodeId: Long)

    /** Starts a download now, on any network (the user played it); it clears a permanent-failure mark. */
    suspend fun requestManual(episodeId: Long)

    /** Cancels the job and deletes the partial or final file, leaving the Episode `NONE` with no `localPath`. */
    suspend fun remove(episodeId: Long)
}

/** Stops the jobs and deletes the partial files of Episodes that left the cache (a pull's Tombstones). Never throws. */
interface PartialDownloadCleaner {
    suspend fun discard(episodeIds: Collection<Long>)

    object None : PartialDownloadCleaner {
        override suspend fun discard(episodeIds: Collection<Long>) = Unit
    }
}

/** The download columns of the Room cache. Every write is an UPDATE of an existing row. */
interface DownloadStore {
    suspend fun get(id: Long): DownloadRow?

    /** Emits null when the Episode is not (or no longer) cached. */
    fun observe(id: Long): Flow<DownloadProgress?>

    /** QUEUED, DOWNLOADING, and DOWNLOADED Episodes. */
    suspend fun entries(): List<DownloadEntry>

    suspend fun exists(id: Long): Boolean

    /** Sets [state]; [clearPermanent] also clears the permanent-failure mark. */
    suspend fun request(id: Long, state: com.podclatch.data.db.DownloadState, clearPermanent: Boolean)

    /** QUEUED or DOWNLOADING to DOWNLOADING; false when the Episode is gone or in another state. */
    suspend fun markDownloading(id: Long): Boolean

    /** Only while DOWNLOADING; a no-op otherwise. */
    suspend fun saveProgress(id: Long, received: Long, total: Long?)

    /** The final step: DOWNLOADED and the absolute path in one write; false when the Episode is gone or was reset. */
    suspend fun markDownloaded(id: Long, path: String, bytes: Long): Boolean

    /** Only from QUEUED or DOWNLOADING; a no-op otherwise. */
    suspend fun markFailed(id: Long, permanent: Boolean, received: Long, total: Long?)

    /** NONE, no `localPath`, no progress. The permanent-failure mark stays. */
    suspend fun reset(id: Long)
}

enum class JobState {
    /** Enqueued or blocked: has not started. */
    WAITING,
    RUNNING,
}

/** WorkManager, reached through an interface so JVM tests use a fake. One unique job per Episode. */
interface DownloadScheduler {
    /** The live job of the Episode, or null when there is none (never enqueued, finished, failed, or cancelled). */
    suspend fun state(episodeId: Long): JobState?

    /** Enqueues the Episode's job. With [replace] an existing job is cancelled and replaced; otherwise it is kept. */
    suspend fun enqueue(episodeId: Long, network: NetworkType, manual: Boolean, replace: Boolean)

    fun cancel(episodeId: Long)
}

/** Where audio lives. A fake stands in for unavailable storage and a full disk. */
interface DownloadFileSystem {
    /** The directory for audio files, created if needed; null when external storage is unavailable or not writable. */
    fun audioDirectory(): File?

    /** Opens [file] for writing: [append] keeps the bytes already there, otherwise it truncates. */
    fun open(file: File, append: Boolean): OutputStream
}

/**
 * The WorkManager network constraint of a request: an automatic one waits for an unmetered network when
 * "Wi-Fi only" is on; everything else only needs a connection.
 */
fun downloadNetworkType(wifiOnly: Boolean, manual: Boolean): NetworkType =
    if (wifiOnly && !manual) NetworkType.UNMETERED else NetworkType.CONNECTED

/** File names inside the audio directory, by Episode id only (never a title or an address). */
object DownloadPaths {
    private const val PART_SUFFIX = ".part"
    private const val FINAL_SUFFIX = ".audio"

    fun partial(dir: File, episodeId: Long) = File(dir, "$episodeId$PART_SUFFIX")

    fun finalFile(dir: File, episodeId: Long) = File(dir, "$episodeId$FINAL_SUFFIX")

    /** The Episode id of a partial file name, or null when [name] is not one. */
    fun partialEpisodeId(name: String): Long? =
        if (name.endsWith(PART_SUFFIX)) name.removeSuffix(PART_SUFFIX).toLongOrNull() else null
}
