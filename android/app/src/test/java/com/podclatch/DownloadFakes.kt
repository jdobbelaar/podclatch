package com.podclatch

import androidx.work.NetworkType
import com.podclatch.data.db.DownloadEntry
import com.podclatch.data.db.DownloadProgress
import com.podclatch.data.db.DownloadRow
import com.podclatch.data.db.DownloadState
import com.podclatch.download.DownloadFileSystem
import com.podclatch.download.DownloadScheduler
import com.podclatch.download.DownloadStore
import com.podclatch.download.JobState
import com.podclatch.download.PartialDownloadCleaner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

fun downloadRow(
    id: Long,
    url: String = "http://audio.example.com/$id.mp3",
    state: DownloadState = DownloadState.QUEUED,
    localPath: String? = null,
    bytes: Long = 0,
    total: Long? = null,
    permanent: Boolean = false,
) = DownloadRow(id, state, localPath, url, bytes, total, permanent)

/** The download columns in memory. It keeps the history of state changes and the highest number of simultaneous downloads. */
class FakeDownloadStore : DownloadStore {
    private val lock = Any()
    private val rows = linkedMapOf<Long, DownloadRow>()
    private val version = MutableStateFlow(0)

    /** Every (id, state) the store moved an Episode into, in order, including the one it was put in with. */
    val history = CopyOnWriteArrayList<Pair<Long, DownloadState>>()

    /** The `received` of every progress write, per id, in order. */
    val progressWrites = CopyOnWriteArrayList<Pair<Long, Long>>()

    @Volatile var maxDownloading = 0
        private set

    fun put(row: DownloadRow) = synchronized(lock) {
        rows[row.id] = row
        history += row.id to row.downloadState
        changed()
    }

    fun delete(id: Long) = synchronized(lock) {
        rows.remove(id)
        changed()
    }

    fun row(id: Long): DownloadRow? = synchronized(lock) { rows[id] }

    fun statesOf(id: Long): List<DownloadState> = history.filter { it.first == id }.map { it.second }

    fun overwrite(id: Long, change: (DownloadRow) -> DownloadRow) = synchronized(lock) {
        rows[id] = change(rows.getValue(id))
        changed()
    }

    private fun changed() {
        maxDownloading = maxOf(maxDownloading, rows.values.count { it.downloadState == DownloadState.DOWNLOADING })
        version.value += 1
    }

    private fun update(id: Long, allowed: Set<DownloadState>?, change: (DownloadRow) -> DownloadRow): Boolean =
        synchronized(lock) {
            val row = rows[id] ?: return false
            if (allowed != null && row.downloadState !in allowed) return false
            val next = change(row)
            if (next.downloadState != row.downloadState) history += id to next.downloadState
            rows[id] = next
            changed()
            true
        }

    private val live = setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING)

    override suspend fun get(id: Long) = row(id)

    override fun observe(id: Long): Flow<DownloadProgress?> = version.map {
        row(id)?.let { DownloadProgress(it.downloadState, it.bytesReceived, it.totalBytes, it.downloadPermanentFailure) }
    }.distinctUntilChanged()

    override suspend fun entries(): List<DownloadEntry> = synchronized(lock) {
        rows.values
            .filter { it.downloadState in live || it.downloadState == DownloadState.DOWNLOADED }
            .map { DownloadEntry(it.id, it.downloadState, it.localPath) }
    }

    override suspend fun exists(id: Long) = row(id) != null

    override suspend fun request(id: Long, state: DownloadState, clearPermanent: Boolean) {
        update(id, null) { it.copy(downloadState = state, downloadPermanentFailure = it.downloadPermanentFailure && !clearPermanent) }
    }

    override suspend fun markDownloading(id: Long) = update(id, live) { it.copy(downloadState = DownloadState.DOWNLOADING) }

    override suspend fun saveProgress(id: Long, received: Long, total: Long?) {
        if (update(id, setOf(DownloadState.DOWNLOADING)) { it.copy(bytesReceived = received, totalBytes = total) }) {
            progressWrites += id to received
        }
    }

    override suspend fun markDownloaded(id: Long, path: String, bytes: Long) = update(id, live) {
        it.copy(downloadState = DownloadState.DOWNLOADED, localPath = path, bytesReceived = bytes, totalBytes = bytes, downloadPermanentFailure = false)
    }

    override suspend fun markFailed(id: Long, permanent: Boolean, received: Long, total: Long?) {
        update(id, live) {
            it.copy(downloadState = DownloadState.FAILED, bytesReceived = received, totalBytes = total, downloadPermanentFailure = permanent)
        }
    }

    override suspend fun reset(id: Long) {
        update(id, null) { it.copy(downloadState = DownloadState.NONE, localPath = null, bytesReceived = 0, totalBytes = null) }
    }
}

class FakeDownloadScheduler : DownloadScheduler {
    data class Job(val state: JobState, val network: NetworkType, val manual: Boolean)

    val jobs = linkedMapOf<Long, Job>()
    val enqueued = CopyOnWriteArrayList<Triple<Long, NetworkType, Boolean>>()
    val cancelled = CopyOnWriteArrayList<Long>()

    override suspend fun state(episodeId: Long): JobState? = synchronized(jobs) { jobs[episodeId]?.state }

    override suspend fun enqueue(episodeId: Long, network: NetworkType, manual: Boolean, replace: Boolean) {
        synchronized(jobs) {
            if (episodeId in jobs && !replace) return
            jobs[episodeId] = Job(JobState.WAITING, network, manual)
            enqueued += Triple(episodeId, network, manual)
        }
    }

    override fun cancel(episodeId: Long) {
        synchronized(jobs) { jobs.remove(episodeId) }
        cancelled += episodeId
    }

    fun start(episodeId: Long) = synchronized(jobs) {
        jobs[episodeId] = jobs.getValue(episodeId).copy(state = JobState.RUNNING)
    }
}

/**
 * A directory in the test's temporary folder, or none, and optional failures: [failOpen] makes the directory
 * unwritable, [failWritesAfter] makes a write throw (a full disk) after that many bytes, and [onBytes] sees
 * every write (the byte count of this stream so far) so a test can stop the run or hold it.
 */
class FakeFileSystem(var directory: File?) : DownloadFileSystem {
    var failOpen = false
    var failWritesAfter: Long? = null
    @Volatile var onBytes: ((Long) -> Unit)? = null

    override fun audioDirectory(): File? = directory

    override fun open(file: File, append: Boolean): OutputStream {
        if (failOpen) throw IOException("read-only file system")
        val out = java.io.FileOutputStream(file, append)
        return object : OutputStream() {
            private var count = 0L

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                val limit = failWritesAfter
                if (limit != null && count + len > limit) throw IOException("No space left on device")
                out.write(b, off, len)
                count += len
                onBytes?.invoke(count)
            }

            override fun close() = out.close()
        }
    }
}

class RecordingCleaner : PartialDownloadCleaner {
    val discarded = CopyOnWriteArrayList<Long>()

    override suspend fun discard(episodeIds: Collection<Long>) {
        discarded += episodeIds
    }
}

/** A deterministic body of [size] bytes. */
fun audioBytes(size: Int) = ByteArray(size) { (it % 251).toByte() }
