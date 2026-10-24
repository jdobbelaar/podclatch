package com.podclatch.ui.feeddetail

import com.podclatch.data.db.FeedEpisode
import com.podclatch.data.db.ListenOrder
import com.podclatch.data.network.ApiError
import com.podclatch.data.repo.EpisodeStateRepository
import com.podclatch.data.repo.FeedsRepository
import com.podclatch.data.repo.FeedsResult
import com.podclatch.data.repo.MarkResult
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncResult
import com.podclatch.domain.SyncTrigger
import com.podclatch.download.Downloads
import com.podclatch.domain.changesOnMarkNew
import com.podclatch.domain.changesOnMarkPlayed
import com.podclatch.playback.PlayResult
import com.podclatch.playback.PlaybackController
import com.podclatch.ui.feeds.shouldPopFeedDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Feed's own columns that the screen shows. */
data class FeedDetailHeader(
    val title: String,
    val description: String,
    val artworkUrl: String,
    val listenOrder: ListenOrder,
) {
    override fun toString() = "FeedDetailHeader(listenOrder=$listenOrder)"
}

/** Which confirmation dialog is open, with the numbers it states. */
sealed interface FeedDetailDialog {
    data class MarkAllPlayed(val count: Int, val feedTitle: String) : FeedDetailDialog
    data class MarkAllNew(val count: Int, val feedTitle: String) : FeedDetailDialog
    data class Unsubscribe(val feedTitle: String) : FeedDetailDialog
}

data class FeedDetailUiState(
    /** False until Room has been read once. */
    val loaded: Boolean = false,
    /** Null while not [loaded], and when the Feed is not in the cache. */
    val header: FeedDetailHeader? = null,
    /** In the Feed's Listen Order. */
    val episodes: List<FeedEpisode> = emptyList(),
    /** A server action (Listen Order, Refresh, or Unsubscribe) is running; at most one at a time. */
    val busy: Boolean = false,
    val refreshing: Boolean = false,
    val message: FeedDetailMessage? = null,
    val dialog: FeedDetailDialog? = null,
) {
    /** Mark all Played is enabled only when at least one Episode is New. */
    val canMarkAllPlayed: Boolean get() = episodes.any { changesOnMarkPlayed(it.status) }

    /** Mark all New is enabled only when an Episode is Played or has progress. */
    val canMarkAllNew: Boolean get() = episodes.any { changesOnMarkNew(it.status, it.positionSec) }

    /** The screen goes back: the cache was read and no longer holds the Feed. */
    val feedGone: Boolean get() = shouldPopFeedDetail(feedRead = loaded, feedPresent = header != null)

    /** The big swipe, Listen Order, Refresh, and Unsubscribe are inert while a server action runs. */
    val actionsLocked: Boolean get() = busy

    override fun toString() =
        "FeedDetailUiState(loaded=$loaded, episodes=${episodes.size}, busy=$busy, refreshing=$refreshing, " +
            "message=$message, dialog=$dialog)"
}

/**
 * Feed Detail's logic without any Android type. The server actions (Listen Order, Refresh, Unsubscribe)
 * and the follow-up Syncs run in [scope], which in the app is the application scope: leaving the screen
 * or rotating does not cancel them. [stateScope] only keeps the state flow alive (the ViewModel scope).
 * One server action runs at a time. Everything is read from Room; the API is reached only through the
 * repositories.
 */
class FeedDetailController(
    private val feedId: Long,
    private val feeds: FeedsRepository,
    private val states: EpisodeStateRepository,
    private val engine: SyncEngine,
    private val playback: PlaybackController,
    private val scope: CoroutineScope,
    stateScope: CoroutineScope,
    private val downloads: Downloads? = null,
) {
    private data class Local(
        val busy: Boolean = false,
        val refreshing: Boolean = false,
        val message: FeedDetailMessage? = null,
        val dialog: FeedDetailDialog? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<FeedDetailUiState> = combine(
        feeds.observeFeed(feedId),
        feeds.observeEpisodes(feedId),
        local,
    ) { feed, episodes, l ->
        val header = feed?.let { FeedDetailHeader(it.title, it.description, it.artworkUrl, it.listenOrder) }
        FeedDetailUiState(
            loaded = true,
            header = header,
            episodes = sortEpisodes(episodes, header?.listenOrder ?: ListenOrder.NEWEST_FIRST),
            busy = l.busy,
            refreshing = l.refreshing,
            message = l.message,
            dialog = l.dialog,
        )
    }.stateIn(stateScope, SharingStarted.Eagerly, FeedDetailUiState())

    /** Marks the start of a server action unless another one runs. Returns whether it may start. */
    private fun tryBegin(refreshing: Boolean = false): Boolean {
        var started = false
        local.update { l ->
            if (l.busy) {
                l
            } else {
                started = true
                l.copy(busy = true, refreshing = refreshing, message = null)
            }
        }
        return started
    }

    private fun end(message: FeedDetailMessage?) =
        local.update { it.copy(busy = false, refreshing = false, message = message) }

    // ---- Listen Order ----

    /** Sends the change once; selecting the Listen Order the Feed already has sends nothing. */
    fun selectListenOrder(order: ListenOrder) {
        if (uiState.value.header?.listenOrder == order) return
        if (!tryBegin()) return
        scope.launch {
            val message: FeedDetailMessage? = try {
                when (val r = feeds.updateListenOrder(feedId, order)) {
                    is FeedsResult.Ok -> null
                    is FeedsResult.Failed -> failureMessage(r.error) { FeedDetailMessage.ListenOrderFailed(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FeedDetailMessage.Unexpected
            }
            end(message)
        }
    }

    // ---- Refresh (the big swipe) ----

    /** Starts a Refresh of this Feed unless a server action runs. Returns whether it started. */
    fun refresh(): Boolean {
        if (!tryBegin(refreshing = true)) return false
        scope.launch {
            val message: FeedDetailMessage? = try {
                when (val r = feeds.refreshFeed(feedId)) {
                    is FeedsResult.Ok -> buildFeedRefreshMessage(feedId, r.value.results, r.value.sync)
                    is FeedsResult.Failed -> failureMessage(r.error) { FeedDetailMessage.RefreshRequestFailed(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FeedDetailMessage.Unexpected
            }
            end(message)
        }
        return true
    }

    // ---- Unsubscribe ----

    fun requestUnsubscribe() {
        val title = uiState.value.header?.title ?: return
        local.update { if (it.busy) it else it.copy(dialog = FeedDetailDialog.Unsubscribe(title)) }
    }

    fun confirmUnsubscribe() {
        val dialog = local.value.dialog as? FeedDetailDialog.Unsubscribe ?: return
        local.update { it.copy(dialog = null) }
        if (!tryBegin()) return
        scope.launch {
            val message: FeedDetailMessage? = try {
                when (val r = feeds.unsubscribe(feedId)) {
                    is FeedsResult.Ok ->
                        (r.value as? SyncResult.Failed)?.let {
                            FeedDetailMessage.UnsubscribedSyncFailed(dialog.feedTitle, it.reason.message)
                        }
                    is FeedsResult.Failed ->
                        failureMessage(r.error) { FeedDetailMessage.UnsubscribeFailed(dialog.feedTitle, it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FeedDetailMessage.Unexpected
            }
            end(message)
        }
    }

    // ---- Mark all Played / New ----

    fun requestMarkAllPlayed() {
        val state = uiState.value
        val count = state.episodes.count { changesOnMarkPlayed(it.status) }
        if (count == 0) return
        local.update { it.copy(dialog = FeedDetailDialog.MarkAllPlayed(count, state.header?.title.orEmpty())) }
    }

    fun requestMarkAllNew() {
        val state = uiState.value
        val count = state.episodes.count { changesOnMarkNew(it.status, it.positionSec) }
        if (count == 0) return
        local.update { it.copy(dialog = FeedDetailDialog.MarkAllNew(count, state.header?.title.orEmpty())) }
    }

    /** Applies the open Mark all dialog; Room changes first, then a Sync is started in [scope]. */
    fun confirmMarkAll() {
        val dialog = local.value.dialog
        if (dialog !is FeedDetailDialog.MarkAllPlayed && dialog !is FeedDetailDialog.MarkAllNew) return
        local.update { it.copy(dialog = null) }
        scope.launch {
            runLocalChange {
                if (dialog is FeedDetailDialog.MarkAllPlayed) states.markAllPlayed(feedId) else states.markAllNew(feedId)
            }
        }
    }

    /** Closes whichever confirmation dialog is open; nothing changes. */
    fun cancelDialog() = local.update { it.copy(dialog = null) }

    // ---- One Episode ----

    fun markPlayed(episodeId: Long) {
        scope.launch { runLocalChange { if (states.markPlayed(episodeId) == MarkResult.CHANGED) 1 else 0 } }
    }

    fun markNew(episodeId: Long) {
        scope.launch { runLocalChange { if (states.markNew(episodeId) == MarkResult.CHANGED) 1 else 0 } }
    }

    /** Runs a Room change; when it changed anything, a Sync pushes it (right away if online). */
    private suspend fun runLocalChange(change: suspend () -> Int) {
        try {
            if (change() > 0) engine.sync(SyncTrigger.MANUAL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            local.update { it.copy(message = FeedDetailMessage.Unexpected) }
        }
    }

    // ---- Play ----

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
                local.update { it.copy(message = FeedDetailMessage.PlayFailed) }
            }
        }
    }

    // ---- Retry download ----

    /**
     * "Retry download" of a New Episode whose last download ended `FAILED`: one manual download request,
     * exactly what playing the Episode asks for (it also clears the permanent mark). Anything else is ignored.
     */
    fun retryDownload(episodeId: Long) {
        val episode = uiState.value.episodes.firstOrNull { it.id == episodeId } ?: return
        if (!canRetryDownload(episode.status, episode.downloadState)) return
        val downloads = downloads ?: return
        scope.launch {
            try {
                downloads.requestManual(episodeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(message = FeedDetailMessage.Unexpected) }
            }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    /** A rejected token shows the connection screen instead of a banner. */
    private fun failureMessage(error: ApiError, build: (String) -> FeedDetailMessage): FeedDetailMessage? =
        if (error is ApiError.Unauthorized) null else build(error.message)
}

