package com.podclatch.ui.feeds

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.podclatch.Config
import com.podclatch.R
import com.podclatch.data.db.FeedListItem
import com.podclatch.ui.TabTopBar
import com.podclatch.ui.common.FeedsMessageBanner
import com.podclatch.ui.common.PullRefreshBox
import com.podclatch.ui.subscribe.SubscribeOutcome
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

@Composable
fun FeedsScreen(
    onOpenFeed: (Long) -> Unit,
    onSubscribe: () -> Unit,
    onOpenSettings: () -> Unit,
    subscribeOutcome: SubscribeOutcome? = null,
    onSubscribeOutcomeHandled: () -> Unit = {},
    vm: FeedsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // A Feed was just subscribed: show the top of the list, and say so if the follow-up Sync failed.
    var scrollToTopRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(subscribeOutcome) {
        if (subscribeOutcome != null) {
            subscribeOutcomeMessage(subscribeOutcome.syncFailure)?.let(vm::showMessage)
            scrollToTopRequests += 1
            onSubscribeOutcomeHandled()
        }
    }
    Scaffold(
        topBar = { TabTopBar(stringResource(R.string.tab_feeds), onOpenSettings) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onSubscribe,
                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp).testTag("subscribe_fab"),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.subscribe_fab_description))
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // A thin bar under the top bar while a server action runs; the space is always reserved.
            Box(Modifier.fillMaxWidth().heightIn(min = 4.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("feeds_busy"))
            }
            state.message?.let { FeedsMessageBanner(it, tag = "feeds_message", onDismiss = vm::dismissMessage) }
            FeedsList(
                state = state,
                onOpenFeed = onOpenFeed,
                onDragStarted = vm::onDragStarted,
                onMove = vm::onMove,
                onDragStopped = vm::onDragStopped,
                onRefresh = vm::refresh,
                onRequestUnsubscribe = vm::requestUnsubscribe,
                scrollToTopRequests = scrollToTopRequests,
            )
        }
    }
    state.unsubscribeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = vm::cancelUnsubscribe,
            title = { Text(stringResource(R.string.unsubscribe_title, target.title.ifBlank { stringResource(R.string.feed_untitled) })) },
            text = { Text(stringResource(R.string.unsubscribe_message)) },
            confirmButton = {
                TextButton(onClick = vm::confirmUnsubscribe, modifier = Modifier.testTag("confirm_unsubscribe")) {
                    Text(stringResource(R.string.unsubscribe_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelUnsubscribe, modifier = Modifier.testTag("cancel_unsubscribe")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun displayTitle(title: String): String = title.ifBlank { stringResource(R.string.feed_untitled) }

// ---- The list, the big swipe, and the drag ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedsList(
    state: FeedsUiState,
    onOpenFeed: (Long) -> Unit,
    onDragStarted: () -> Unit,
    onMove: (Long, Long) -> Unit,
    onDragStopped: () -> Unit,
    onRefresh: () -> Boolean,
    onRequestUnsubscribe: (Long) -> Unit,
    scrollToTopRequests: Int,
) {
    val locked = state.gesturesLocked

    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTopRequests) {
        if (scrollToTopRequests > 0) listState.scrollToItem(0)
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.key as Long, to.key as Long)
    }
    val items = state.items.orEmpty()

    PullRefreshBox(locked = locked, refreshing = state.refreshing, onRefresh = { onRefresh() }) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Room for the Subscribe button below the last bar.
            contentPadding = PaddingValues(bottom = 96.dp),
            overscrollEffect = null,
        ) {
            if (items.isEmpty()) {
                item(key = "empty") {
                    EmptyState(Modifier.fillParentMaxSize(), loaded = state.items != null, syncing = state.syncRunning)
                }
            } else {
                items(items, key = { it.id }) { feed ->
                    ReorderableItem(reorderState, key = feed.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "drag-elevation")
                        Surface(shadowElevation = elevation, tonalElevation = elevation) {
                            SwipeToUnsubscribe(enabled = !locked, onTrigger = { onRequestUnsubscribe(feed.id) }) {
                                FeedBar(
                                    feed = feed,
                                    onOpen = { onOpenFeed(feed.id) },
                                    handle = {
                                        DragHandle(
                                            feed = feed,
                                            modifier = Modifier.longPressDraggableHandle(
                                                enabled = !state.busy,
                                                onDragStarted = { onDragStarted() },
                                                onDragStopped = { onDragStopped() },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, loaded: Boolean, syncing: Boolean) {
    Box(
        modifier.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !loaded -> Unit
            syncing -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text(stringResource(R.string.feeds_syncing), modifier = Modifier.testTag("feeds_syncing"))
            }
            else -> Text(stringResource(R.string.feeds_empty), modifier = Modifier.testTag("feeds_empty"))
        }
    }
}

// ---- One bar ----

@Composable
private fun FeedBar(feed: FeedListItem, onOpen: () -> Unit, handle: @Composable () -> Unit) {
    val title = displayTitle(feed.title)
    val description = if (feed.author.isBlank()) {
        stringResource(R.string.feed_row_description_no_author, title, feed.unplayedCount)
    } else {
        stringResource(R.string.feed_row_description, title, feed.author, feed.unplayedCount)
    }
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 72.dp)
                .clickable(onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .testTag("feed_row_${feed.id}")
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = feed.artworkUrl.ifBlank { null },
                contentDescription = stringResource(R.string.feed_artwork_description, title),
                placeholder = placeholder,
                error = placeholder,
                fallback = placeholder,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (feed.author.isNotBlank()) {
                    Text(
                        feed.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            UnplayedBadge(feed.unplayedCount)
        }
        handle()
    }
}

@Composable
private fun UnplayedBadge(count: Int) {
    val muted = count == 0
    Surface(
        shape = CircleShape,
        color = if (muted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
        contentColor = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.padding(start = 4.dp),
    ) {
        Text(
            badgeText(count),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.sizeIn(minWidth = 28.dp).padding(horizontal = 8.dp, vertical = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DragHandle(feed: FeedListItem, modifier: Modifier) {
    val description = stringResource(R.string.feed_reorder_description, displayTitle(feed.title))
    Box(
        modifier
            .size(width = 56.dp, height = 72.dp)
            .semantics { contentDescription = description }
            .testTag("drag_handle_${feed.id}"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Swipe left to unsubscribe ----

/**
 * Shows [content] and lets it be dragged to the left only. Releasing beyond
 * [Config.SWIPE_UNSUBSCRIBE_FRACTION] of the width calls [onTrigger]; in every case the content then
 * animates back to rest. The row is never removed here: it leaves the list only when Sync applies the
 * Tombstone. Velocity is ignored on purpose, so a short fast swipe cannot trigger it.
 */
@Composable
private fun SwipeToUnsubscribe(enabled: Boolean, onTrigger: () -> Unit, content: @Composable () -> Unit) {
    var width by remember { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val currentOnTrigger by rememberUpdatedState(onTrigger)

    fun snapBack() {
        val from = dragX
        scope.launch { animate(from, 0f) { value, _ -> dragX = value } }
    }

    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        if (dragX < 0f) {
            Box(
                Modifier.matchParentSize().padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    stringResource(R.string.unsubscribe_swipe_label),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(dragX.roundToInt(), 0) }
                .pointerInput(enabled) {
                    if (enabled) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (width > 0 && -dragX > Config.SWIPE_UNSUBSCRIBE_FRACTION * width) currentOnTrigger()
                                snapBack()
                            },
                            onDragCancel = { snapBack() },
                        ) { change, delta ->
                            change.consume()
                            dragX = (dragX + delta).coerceIn(-width.toFloat(), 0f)
                        }
                    }
                },
        ) {
            Surface { content() }
        }
    }
}
