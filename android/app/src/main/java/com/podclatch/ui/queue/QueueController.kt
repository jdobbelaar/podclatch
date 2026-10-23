package com.podclatch.ui.queue

import com.podclatch.data.db.QueueRow
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.FeedsRepository
import com.podclatch.data.repo.MarkResult
import com.podclatch.data.repo.QueueRepository
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncState
import com.podclatch.domain.SyncTrigger
import com.podclatch.playback.PlayResult
import com.podclatch.playback.PlaybackController
import com.podclatch.playback.PlaybackPhase
import com.podclatch.ui.feeds.FeedsMessage
import com.podclatch.ui.feeds.refreshAllFeeds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the banner on the Queue tab says. */
sealed interface QueueMessage {
    /** The outcome of a Refresh, worded exactly as on the Feeds tab. */
    data class Refresh(val message: FeedsMessage) : QueueMessage

    data object PlayFailed : QueueMessage
    data object Unexpected : QueueMessage
}

data class QueueUiState(
    /** False until Room has been read once. */
    val loaded: Boolean = false,
    /** In Queue order. */
    val items: List<QueueRow> = emptyList(),
    /** The loaded Episode while playback is not over; null otherwise. Follows the playback service. */
    val nowPlayingId: Long? = null,
    /** The big swipe's Refresh is running; at most one at a time. */
    val refreshing: Boolean = false,
    val message: QueueMessage? = null,
    val sync: SyncState = SyncState(),
) {
    override fun toString() =
        "QueueUiState(loaded=$loaded, items=${items.map { it.episodeId }}, nowPlayingId=$nowPlayingId, " +
            "refreshing=$refreshing, message=$message)"
}

/**
 * The Queue tab's logic without any Android type. Everything shown comes from Room (the derived Queue) and
 * from the playback state; the API is reached only through [FeedsRepository] (the big swipe's Refresh). The
 * Refresh, a Mark with its follow-up Sync, and a play request run in [scope], which in the app is the
 * application scope: leaving the tab or rotating does not cancel them. [stateScope] only keeps the state
 * flow alive (the ViewModel scope).
 */
class QueueController(
    queue: QueueRepository,
    private val feeds: FeedsRepository,
    private val states: EpisodeStateRepository,
    private val engine: SyncEngine,
    private val playback: PlaybackController,
    private val scope: CoroutineScope,
    stateScope: CoroutineScope,
) {
    private data class Local(val refreshing: Boolean = false, val message: QueueMessage? = null)

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<QueueUiState> = combine(queue.queue, playback.state, engine.state, local) { rows, player, sync, l ->
        QueueUiState(
            loaded = true,
            items = rows,
            nowPlayingId = player.episodeId.takeIf { player.phase != PlaybackPhase.ENDED },
            refreshing = l.refreshing,
            message = l.message,
            sync = sync,
        )
    }.stateIn(stateScope, SharingStarted.Eagerly, QueueUiState())

    // ---- Tap to play ----

    /** Calls [PlaybackController.play] once. Nothing is reordered: playing out of order changes nothing. */
    fun play(episodeId: Long) {
        scope.launch {
            val result = try {
                playback.play(episodeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (result != PlayResult.Started && result != PlayResult.Resumed) {
                local.update { it.copy(message = QueueMessage.PlayFailed) }
            }
        }
    }

    // ---- Swipes ----

    fun markPlayed(episodeId: Long) {
        scope.launch { runLocalChange { states.markPlayed(episodeId) } }
    }

    fun markNew(episodeId: Long) {
        scope.launch { runLocalChange { states.markNew(episodeId) } }
    }

    /** Runs a Room change; when it changed anything, a Sync pushes it (right away if online). */
    private suspend fun runLocalChange(change: suspend () -> MarkResult) {
        try {
            if (change() == MarkResult.CHANGED) engine.sync(SyncTrigger.MANUAL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            local.update { it.copy(message = QueueMessage.Unexpected) }
        }
    }

    // ---- The big swipe ----

    /** Starts a Refresh of every Feed unless one runs. Returns whether it started. */
    fun refresh(): Boolean {
        var started = false
        local.update { l ->
            if (l.refreshing) {
                l
            } else {
                started = true
                l.copy(refreshing = true, message = null)
            }
        }
        if (started) scope.launch { runRefresh() }
        return started
    }

    private suspend fun runRefresh() {
        val message = try {
            val titles = feeds.feeds.first().associate { it.id to it.title }
            refreshAllFeeds(feeds, titles)?.let { QueueMessage.Refresh(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            QueueMessage.Unexpected
        }
        local.update { it.copy(refreshing = false, message = message) }
    }

    // ---- Sync now ----

    /** The "Sync now" action: the same MANUAL Sync the placeholder ran. */
    fun syncNow() {
        scope.launch {
            try {
                engine.sync(SyncTrigger.MANUAL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(message = QueueMessage.Unexpected) }
            }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }
}
