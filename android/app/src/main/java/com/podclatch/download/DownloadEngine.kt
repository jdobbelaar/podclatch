package com.podclatch.download

import com.podclatch.Config
import com.podclatch.data.db.DownloadState
import com.podclatch.domain.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class DownloadOutcome {
    /** The file is complete (or was already) and the Episode is DOWNLOADED. */
    DONE,

    /** FAILED without the permanent mark; the partial file is kept unless the disk was the problem. */
    FAILED_TRANSIENT,

    /** 404 or 410: FAILED with the permanent mark. */
    FAILED_PERMANENT,

    /** The Episode left the cache: the partial file is gone and nothing was written back. */
    REMOVED,

    /** Nothing to do: the Episode is not QUEUED or DOWNLOADING. */
    SKIPPED,
}

/** Writes progress at most once per [intervalMs]. The first call after creation waits a full interval. */
class ProgressThrottle(private val clock: Clock, private val intervalMs: Long) {
    private var last = clock.nowMs()

    fun due(): Boolean {
        val now = clock.nowMs()
        if (now - last < intervalMs) return false
        last = now
        return true
    }
}

/** A failure of the disk, not the network: unwritable directory or a full disk. */
private class StorageException(cause: Throwable) : IOException(cause)

/**
 * Downloads one Episode's audio into a partial file and finishes it. Plain Kotlin: the HTTP client, the file
 * system, the store, the clock, and the dispatcher are parameters; [DownloadWorker] is a thin wrapper.
 *
 * The client must carry no interceptor that adds `Authorization`: the bearer token goes only to the
 * PodClatch Server. Nothing here logs, and no message or exception text holds an address.
 */
class DownloadEngine(
    private val store: DownloadStore,
    private val files: DownloadFileSystem,
    private val http: Call.Factory,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val automaticSlots: Semaphore = Semaphore(Config.MAX_CONCURRENT_AUTOMATIC_DOWNLOADS),
    private val progressIntervalMs: Long = Config.DOWNLOAD_PROGRESS_INTERVAL_MS,
    private val chunkBytes: Int = Config.DOWNLOAD_CHUNK_BYTES,
) {
    /**
     * Runs the Episode's download to its end. An automatic run waits for a free slot first (the Episode stays
     * QUEUED); a manual run never waits. Cancellation (the system stopped the worker) keeps the partial file
     * and the DOWNLOADING state, and is rethrown. Anything else is turned into an outcome, never thrown.
     */
    suspend fun run(episodeId: Long, manual: Boolean): DownloadOutcome =
        if (manual) watched(episodeId) else automaticSlots.withPermit { watched(episodeId) }

    /** Runs [transfer] and stops it, within a moment, when the Episode's row disappears. */
    private suspend fun watched(id: Long): DownloadOutcome = coroutineScope {
        val rowGone = AtomicBoolean(false)
        val work = async(io) { transfer(id) }
        val watcher = launch {
            store.observe(id).first { it == null }
            rowGone.set(true)
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            if (!rowGone.get()) throw e
            withContext(NonCancellable) { deletePartial(id) }
            DownloadOutcome.REMOVED
        } finally {
            watcher.cancel()
        }
    }

    private fun deletePartial(id: Long) {
        val dir = files.audioDirectory() ?: return
        DownloadPaths.partial(dir, id).delete()
    }

    /** What the failure handlers need to know about the run so far. */
    private class Session(val id: Long, val url: String, val part: File) {
        var total: Long? = null
        lateinit var throttle: ProgressThrottle
    }

    private suspend fun transfer(id: Long): DownloadOutcome {
        val row = store.get(id)
        if (row == null) {
            deletePartial(id)
            return DownloadOutcome.REMOVED
        }
        if (row.downloadState == DownloadState.DOWNLOADED) {
            val file = row.localPath?.let(::File)
            return if (file != null && file.isFile && file.length() > 0) DownloadOutcome.DONE else DownloadOutcome.SKIPPED
        }
        if (!store.markDownloading(id)) return DownloadOutcome.SKIPPED

        val dir = files.audioDirectory()
        if (dir == null) {
            store.markFailed(id, permanent = false, received = 0, total = null)
            return DownloadOutcome.FAILED_TRANSIENT
        }
        val session = Session(id, row.audioUrl, DownloadPaths.partial(dir, id))
        session.total = row.totalBytes
        return try {
            attempts(session, dir)
        } catch (e: CancellationException) {
            // Stopped by the system or removed: keep the partial file, record how far it got.
            withContext(NonCancellable) { store.saveProgress(id, sizeOf(session.part), session.total) }
            throw e
        } catch (e: StorageException) {
            session.part.delete()
            store.markFailed(id, permanent = false, received = 0, total = null)
            DownloadOutcome.FAILED_TRANSIENT
        } catch (e: Exception) {
            store.markFailed(id, permanent = false, received = sizeOf(session.part), total = session.total)
            DownloadOutcome.FAILED_TRANSIENT
        }
    }

    private fun sizeOf(file: File): Long = if (file.isFile) file.length() else 0L

    /** At most two requests: the second restarts from zero after a Range that could not be used. */
    private suspend fun attempts(s: Session, dir: File): DownloadOutcome {
        var restarted = false
        while (true) {
            val existing = sizeOf(s.part)
            val outcome = request(s, dir, existing)
            if (outcome != null) return outcome
            if (restarted) {
                store.markFailed(s.id, permanent = false, received = sizeOf(s.part), total = s.total)
                return DownloadOutcome.FAILED_TRANSIENT
            }
            restarted = true
            s.part.delete()
        }
    }

    /** One request. Returns the outcome, or null when the partial file was unusable and must restart from zero. */
    private suspend fun request(s: Session, dir: File, existing: Long): DownloadOutcome? {
        val builder = Request.Builder()
            .url(s.url)
            .header("User-Agent", Config.PLAYBACK_USER_AGENT)
            // The bytes are counted as sent: never let the client decode a compressed body.
            .header("Accept-Encoding", "identity")
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        val call = http.newCall(builder.build())
        return withContext(io) {
            withCall(call) {
                call.execute().use { response -> handle(s, dir, response, existing) }
            }
        }
    }

    private suspend fun handle(s: Session, dir: File, response: Response, existing: Long): DownloadOutcome? {
        val body = response.body
        return when (response.code) {
            404, 410 -> {
                s.part.delete()
                store.markFailed(s.id, permanent = true, received = 0, total = null)
                DownloadOutcome.FAILED_PERMANENT
            }
            200 -> {
                val length = body.contentLength().takeIf { it >= 0 }
                s.total = length
                receive(s, dir, response, startAt = 0, append = false, expected = length)
            }
            206 -> {
                val range = ContentRange.parse(response.header("Content-Range"))
                if (range == null || range.start != existing) return null
                val length = body.contentLength().takeIf { it >= 0 }
                val expected = range.total ?: length?.let { existing + it }
                s.total = expected
                receive(s, dir, response, startAt = existing, append = existing > 0, expected = expected)
            }
            416 -> {
                val known = ContentRange.parse(response.header("Content-Range"))?.total ?: s.total
                if (known != null && existing > 0 && existing == known) {
                    s.total = known
                    complete(s, dir, existing, known)
                } else {
                    null
                }
            }
            else -> {
                store.markFailed(s.id, permanent = false, received = existing, total = s.total)
                DownloadOutcome.FAILED_TRANSIENT
            }
        }
    }

    private suspend fun receive(
        s: Session,
        dir: File,
        response: Response,
        startAt: Long,
        append: Boolean,
        expected: Long?,
    ): DownloadOutcome {
        val job = currentCoroutineContext()
        val out: OutputStream = try {
            files.open(s.part, append)
        } catch (e: IOException) {
            throw StorageException(e)
        }
        var written = startAt
        store.saveProgress(s.id, written, expected)
        s.throttle = ProgressThrottle(clock, progressIntervalMs)
        try {
            val input = response.body.byteStream()
            val buffer = ByteArray(chunkBytes)
            while (true) {
                job.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                try {
                    out.write(buffer, 0, n)
                } catch (e: IOException) {
                    throw StorageException(e)
                }
                written += n
                if (s.throttle.due()) store.saveProgress(s.id, written, expected)
            }
        } finally {
            try {
                out.close()
            } catch (e: IOException) {
                // A close that fails after a write error is the same disk problem; the size check below decides.
            }
        }
        store.saveProgress(s.id, written, expected)
        return complete(s, dir, sizeOf(s.part), expected)
    }

    /** The file is all here: check it, rename it, and in the same step record DOWNLOADED and its path. */
    private suspend fun complete(s: Session, dir: File, size: Long, expected: Long?): DownloadOutcome {
        if (size == 0L) {
            s.part.delete()
            store.markFailed(s.id, permanent = false, received = 0, total = s.total)
            return DownloadOutcome.FAILED_TRANSIENT
        }
        if (expected != null && size != expected) {
            // Fewer bytes: keep them to resume. More bytes: the file changed on the publisher, start over next time.
            if (size > expected) s.part.delete()
            store.markFailed(s.id, permanent = false, received = if (size > expected) 0 else size, total = s.total)
            return DownloadOutcome.FAILED_TRANSIENT
        }
        val target = DownloadPaths.finalFile(dir, s.id)
        if (!moveReplacing(s.part, target)) {
            store.markFailed(s.id, permanent = false, received = size, total = s.total)
            return DownloadOutcome.FAILED_TRANSIENT
        }
        if (!store.markDownloaded(s.id, target.absolutePath, size)) {
            // The Episode left the cache (or was reset) while the last bytes arrived.
            target.delete()
            return DownloadOutcome.REMOVED
        }
        return DownloadOutcome.DONE
    }

    private fun moveReplacing(from: File, to: File): Boolean = try {
        java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (e: IOException) {
        false
    }

    /** Cancels the blocking [call] the moment the coroutine is cancelled, instead of at the next read timeout. */
    private suspend fun <T> withCall(call: Call, block: suspend () -> T): T = coroutineScope {
        val canceller = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            block()
        } finally {
            canceller.cancel()
        }
    }
}

/** A parsed `Content-Range: bytes <start>-<end>/<total>` (or `bytes * / <total>` on a 416). */
internal data class ContentRange(val start: Long?, val total: Long?) {
    companion object {
        private val PATTERN = Regex("""^bytes\s+(?:(\d+)-(\d+)|\*)/(\d+|\*)$""")

        fun parse(header: String?): ContentRange? {
            val match = PATTERN.matchEntire(header?.trim() ?: return null) ?: return null
            val start = match.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull()
            val total = match.groupValues[3].toLongOrNull()
            return ContentRange(start, total)
        }
    }
}
