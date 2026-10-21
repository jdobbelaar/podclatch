package com.podclatch.ui.feeds

import com.podclatch.data.db.FeedListItem
import com.podclatch.data.network.ApiError
import com.podclatch.data.repo.FeedsRepository
import com.podclatch.data.repo.FeedsResult
import com.podclatch.di.ApplicationScope
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncResult
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
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** The Feed an Unsubscribe confirmation is asking about. */
data class UnsubscribeTarget(val feedId: Long, val title: String)

data class FeedsUiState(
    /** The bars to show, in display order; null until the cache has been read once. */
    val items: List<FeedListItem>? = null,
    val dragging: Boolean = false,
    /** A server action (reorder, unsubscribe, or Refresh) is running; at most one at a time. */
    val busy: Boolean = false,
    val refreshing: Boolean = false,
    val message: FeedsMessage? = null,
    val unsubscribeTarget: UnsubscribeTarget? = null,
    val syncRunning: Boolean = false,
) {
    /** Drag handles, swipes, and the big swipe are inert in these states. */
    val gesturesLocked: Boolean get() = busy || dragging
}

/**
 * The Feeds tab's logic without any Android type: the working order of a drag (kept until Room's
 * emission matches it), the single server action at a time, and the banner. The scope is a parameter so
 * tests run it without `coroutines-test`; in the app it is the application scope, which keeps a running
 * Refresh, reorder, or unsubscribe alive across tab switches and rotation.
 */
@Singleton
class FeedsController @Inject constructor(
    private val repo: FeedsRepository,
    engine: SyncEngine,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val latest = MutableStateFlow<List<FeedListItem>?>(null)

    private data class Local(
        /** The order on screen during a drag and after the drop, until Room matches; null otherwise. */
        val working: List<Long>? = null,
        val frozen: Map<Long, FeedListItem> = emptyMap(),
        val dragging: Boolean = false,
        val busy: Boolean = false,
        val refreshing: Boolean = false,
        val message: FeedsMessage? = null,
        val target: UnsubscribeTarget? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<FeedsUiState> = combine(latest, local, engine.state) { items, l, sync ->
        val shown = if (items != null && l.working != null) {
            val byId = items.associateBy { it.id }
            l.working.mapNotNull { byId[it] ?: l.frozen[it] }
        } else {
            items
        }
        FeedsUiState(
            items = shown,
            dragging = l.dragging,
            busy = l.busy,
            refreshing = l.refreshing,
            message = l.message,
            unsubscribeTarget = l.target,
            syncRunning = sync.running,
        )
    }.stateIn(scope, SharingStarted.Eagerly, FeedsUiState())

    init {
        scope.launch {
            repo.feeds.collect { items ->
                latest.value = items
                local.update { l ->
                    // Room caught up with the dropped order: the screen can follow Room again.
                    if (l.working != null && !l.dragging && l.working == items.map { it.id }) l.copy(working = null) else l
                }
            }
        }
    }

    // ---- Reorder ----

    fun onDragStarted() {
        val items = latest.value ?: return
        local.update {
            if (it.busy) it else it.copy(working = items.map { f -> f.id }, frozen = items.associateBy { f -> f.id }, dragging = true)
        }
    }

    /** The library's `onMove`: the row with id [fromId] now sits where the row with id [toId] was. */
    fun onMove(fromId: Long, toId: Long) {
        local.update { l ->
            val working = l.working ?: return@update l
            val from = working.indexOf(fromId)
            val to = working.indexOf(toId)
            l.copy(working = moveItem(working, from, to))
        }
    }

    fun onDragStopped() {
        val l = local.value
        val working = l.working
        if (!l.dragging || working == null) return
        val serverIds = latest.value.orEmpty().map { it.id }
        when (val decision = resolveDrop(working, serverIds)) {
            DropDecision.NoChange -> local.update { it.copy(working = null, dragging = false) }
            DropDecision.ListChanged ->
                local.update { it.copy(working = null, dragging = false, message = FeedsMessage.ListChanged) }
            is DropDecision.Send -> {
                local.update { it.copy(dragging = false, busy = true, message = null) }
                scope.launch { sendOrder(decision.ids) }
            }
        }
    }

    private suspend fun sendOrder(ids: List<Long>) {
        val message: FeedsMessage? = try {
            when (val result = repo.reorder(ids)) {
                is FeedsResult.Ok -> {
                    // Keep the dropped order until Room's emission matches it, so nothing flickers back.
                    withTimeoutOrNull(MATCH_WAIT_MS) { latest.first { items -> items?.map { it.id } == ids } }
                    null
                }
                is FeedsResult.Failed -> failureMessage(result.error) { FeedsMessage.MoveFailed(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FeedsMessage.Unexpected
        }
        local.update { it.copy(working = null, busy = false, message = message) }
    }

    // ---- Refresh ----

    /** Starts a Refresh unless a gesture or a server action is running. Returns whether it started. */
    fun refresh(): Boolean {
        var started = false
        local.update { l ->
            if (l.busy || l.dragging) {
                started = false
                l
            } else {
                started = true
                l.copy(busy = true, refreshing = true, message = null)
            }
        }
        if (started) scope.launch { runRefresh() }
        return started
    }

    private suspend fun runRefresh() {
        val titles = latest.value.orEmpty().associate { it.id to it.title }
        val message: FeedsMessage? = try {
            when (val result = repo.refreshAll()) {
                is FeedsResult.Ok -> buildRefreshMessage(result.value.results, result.value.sync, titles)
                is FeedsResult.Failed -> failureMessage(result.error) { FeedsMessage.RefreshFailed(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FeedsMessage.Unexpected
        }
        local.update { it.copy(busy = false, refreshing = false, message = message) }
    }

    // ---- Unsubscribe ----

    fun requestUnsubscribe(feedId: Long) {
        val item = latest.value.orEmpty().firstOrNull { it.id == feedId } ?: return
        local.update { if (it.busy || it.dragging) it else it.copy(target = UnsubscribeTarget(item.id, item.title)) }
    }

    fun cancelUnsubscribe() = local.update { it.copy(target = null) }

    fun confirmUnsubscribe() {
        var target: UnsubscribeTarget? = null
        local.update { l ->
            val t = l.target
            target = null
            if (t == null) {
                l
            } else if (l.busy || l.dragging) {
                l.copy(target = null)
            } else {
                target = t
                l.copy(target = null, busy = true, message = null)
            }
        }
        target?.let { t -> scope.launch { runUnsubscribe(t) } }
    }

    private suspend fun runUnsubscribe(target: UnsubscribeTarget) {
        val message: FeedsMessage? = try {
            when (val result = repo.unsubscribe(target.feedId)) {
                is FeedsResult.Ok -> (result.value as? SyncResult.Failed)?.let {
                    FeedsMessage.UnsubscribedSyncFailed(target.title, it.reason.message)
                }
                is FeedsResult.Failed ->
                    failureMessage(result.error) { FeedsMessage.UnsubscribeFailed(target.title, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FeedsMessage.Unexpected
        }
        local.update { it.copy(busy = false, message = message) }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    /** A rejected token shows the connection screen instead of a banner. */
    private fun failureMessage(error: ApiError, build: (String) -> FeedsMessage): FeedsMessage? =
        if (error is ApiError.Unauthorized) null else build(error.message)

    private companion object {
        const val MATCH_WAIT_MS = 1_500L
    }
}
