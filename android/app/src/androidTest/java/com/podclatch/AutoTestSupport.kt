package com.podclatch

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.Episode
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.Feed
import com.podclatch.data.db.ListenOrder
import com.podclatch.debug.DebugEntryPoint
import com.podclatch.playback.PlaybackService
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * The harness for the Android Auto service tests (US-16): a Media3 `MediaBrowser` (which is also a
 * `MediaController`) connected to the real `PlaybackService`, the real Room store as the cache, and short
 * WAV files written into the app's files folder and marked `DOWNLOADED`. Nothing here touches the network
 * or needs the host's audio fixture. Later stories reuse it.
 *
 * All Media3 calls run on the main thread (the application looper); results are awaited off it, with an
 * explicit timeout and the exact end state named in the failure message.
 */
class AutoHarness {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    val store: CacheStore =
        EntryPointAccessors.fromApplication(context.applicationContext, DebugEntryPoint::class.java).store()

    /** Every `onChildrenChanged(parentId)` the browser was told about, in order. */
    val childrenChanged = ConcurrentLinkedQueue<String>()

    private var browser: MediaBrowser? = null
    private val files = mutableListOf<File>()

    init {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** The one connection to `PlaybackService`, made on first use. */
    fun connect(): MediaBrowser {
        browser?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val listener = object : MediaBrowser.Listener {
            override fun onChildrenChanged(
                browser: MediaBrowser,
                parentId: String,
                itemCount: Int,
                params: MediaLibraryService.LibraryParams?,
            ) {
                childrenChanged.add(parentId)
            }
        }
        return MediaBrowser.Builder(context, token)
            .setListener(listener)
            .setApplicationLooper(Looper.getMainLooper())
            .buildAsync()
            .get(15, TimeUnit.SECONDS)
            .also { browser = it }
    }

    fun <T> call(block: (MediaBrowser) -> ListenableFuture<LibraryResult<T>>): LibraryResult<T> {
        val b = connect()
        return onMain { block(b) }.get(10, TimeUnit.SECONDS)
    }

    fun children(parent: String, page: Int = 0, pageSize: Int = Int.MAX_VALUE) =
        call { it.getChildren(parent, page, pageSize, null) }.also {
            check(it.resultCode == LibraryResult.RESULT_SUCCESS) { "getChildren($parent) failed with ${it.resultCode}" }
        }.value!!.toList()

    /** Polls [condition] (run on the main thread when [main]) until it holds; fails with [what] after [timeoutMs]. */
    fun awaitTrue(what: String, timeoutMs: Long = 10_000, main: Boolean = false, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            if (if (main) onMain(condition) else condition()) return
            if (System.nanoTime() > deadline) throw AssertionError("Not reached within $timeoutMs ms: $what")
            Thread.sleep(25)
        }
    }

    /** Stops playback and clears the player, so a test leaves nothing loaded. */
    fun resetPlayer() {
        val b = browser ?: return
        onMain {
            b.stop()
            b.clearMediaItems()
        }
    }

    fun release() {
        resetPlayer()
        browser?.let { b -> onMain { b.release() } }
        browser = null
        files.forEach { it.delete() }
        files.clear()
    }

    /** A short silent WAV (8 kHz, mono, 16-bit) in the app's files folder; deleted by [release]. */
    fun wav(name: String, seconds: Int): File {
        val data = seconds * 16_000
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        return File(context.filesDir, name).also {
            it.writeBytes(b.array())
            files += it
        }
    }
}

/** A Feed for a test; the address is a made-up one that is never fetched. */
fun autoFeed(
    id: Long,
    priority: Int,
    order: ListenOrder,
    title: String,
    author: String = "",
    artwork: String = "",
) = Feed(id, "https://feeds.example.com/$id.xml", title, author, "", artwork, priority, order, null, 1, 1)

/** An Episode for a test. */
fun autoEpisode(
    id: Long,
    feedId: Long,
    title: String,
    published: Long,
    state: DownloadState = DownloadState.NONE,
    status: EpisodeStatus = EpisodeStatus.NEW,
    durationSec: Int? = null,
    artwork: String? = null,
    positionSec: Int = 0,
    localPath: String? = null,
    stateUpdatedAt: Long = 1_000,
) = Episode(
    id, feedId, "guid-$id", title, "", "https://audio.example.com/$id.mp3", published, durationSec, artwork, status,
    if (status == EpisodeStatus.PLAYED) 1_000 else null, positionSec, stateUpdatedAt, 1, state, localPath,
)
