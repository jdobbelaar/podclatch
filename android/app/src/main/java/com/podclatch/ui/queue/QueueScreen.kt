package com.podclatch.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.podclatch.R
import com.podclatch.data.db.DownloadState
import com.podclatch.data.db.QueueRow
import com.podclatch.ui.TabTopBar
import com.podclatch.ui.common.FeedsMessageBanner
import com.podclatch.ui.common.MessageBanner
import com.podclatch.ui.common.PullRefreshBox
import com.podclatch.ui.common.SwipeableEpisodeRow
import com.podclatch.ui.syncStatusText

/**
 * The Queue tab, the app's home screen (US-13): the derived Queue as rows, tap to play, swipe to mark, the
 * big swipe to Refresh every Feed. There is no control that adds, reorders, or plays next (spec section 8).
 * The Sync status line and the Sync-now action of the old placeholder stay in the top bar.
 */
@Composable
fun QueueScreen(onOpenSettings: () -> Unit, vm: QueueViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TabTopBar(
                title = stringResource(R.string.tab_queue),
                onOpenSettings = onOpenSettings,
                subtitle = {
                    Text(
                        syncStatusText(state.sync),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("sync_status"),
                    )
                },
                actions = {
                    IconButton(onClick = vm::syncNow, modifier = Modifier.testTag("sync_now")) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.queue_sync_now_description))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.message?.let { QueueBanner(it, onDismiss = vm::dismissMessage) }
            if (!state.loaded) {
                val loading = stringResource(R.string.queue_loading_description)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.semantics { contentDescription = loading }.testTag("queue_loading"))
                }
            } else {
                QueueList(state, vm)
            }
        }
    }
}

@Composable
private fun QueueBanner(message: QueueMessage, onDismiss: () -> Unit) {
    when (message) {
        is QueueMessage.Refresh -> FeedsMessageBanner(message.message, tag = "queue_message", onDismiss = onDismiss)
        QueueMessage.PlayFailed ->
            MessageBanner(listOf(stringResource(R.string.message_play_failed)), tag = "queue_message", onDismiss = onDismiss)
        QueueMessage.Unexpected ->
            MessageBanner(listOf(stringResource(R.string.message_unexpected)), tag = "queue_message", onDismiss = onDismiss)
    }
}

@Composable
private fun QueueList(state: QueueUiState, vm: QueueViewModel) {
    PullRefreshBox(locked = false, refreshing = state.refreshing, onRefresh = { vm.refresh() }) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("queue_list"),
            overscrollEffect = null,
        ) {
            if (state.items.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillParentMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.queue_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag("queue_empty"),
                        )
                    }
                }
            } else {
                items(state.items, key = { it.episodeId }) { row ->
                    SwipeableEpisodeRow(
                        onMarkPlayed = { vm.markPlayed(row.episodeId) },
                        onMarkNew = { vm.markNew(row.episodeId) },
                    ) {
                        QueueItemRow(row, nowPlaying = row.episodeId == state.nowPlayingId, onPlay = { vm.play(row.episodeId) })
                    }
                }
            }
        }
    }
}

// ---- One row ----

@Composable
private fun QueueItemRow(row: QueueRow, nowPlaying: Boolean, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val feedName = row.feedTitle.ifBlank { stringResource(R.string.feed_untitled) }
    val time = queueTime(row.durationSec, row.positionSec)
    val timeText = time?.let { if (it.left) stringResource(R.string.queue_time_left, it.text) else it.text }
    val played = playedBar(row.positionSec, row.durationSec)
    val playedText = when (played) {
        is PlayedBar.Known -> stringResource(R.string.episode_percent_played, played.percent)
        PlayedBar.Started -> stringResource(R.string.episode_started)
        PlayedBar.NotStarted -> stringResource(R.string.queue_not_started)
    }
    val nowPlayingText = if (nowPlaying) stringResource(R.string.queue_now_playing_suffix) else null
    val description = queueRowDescription(row.title, feedName, timeText, playedText, nowPlayingText)
    val placeholder = ColorPainter(colors.surfaceVariant)
    val id = row.episodeId

    Column(Modifier.background(if (nowPlaying) colors.secondaryContainer else colors.surface)) {
        // One element for a screen reader: the row carries the whole description. Its parts keep their own
        // test tags so a test can check each of them.
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    onClick { onPlay(); true }
                }
                .testTag("queue_item_$id")
                .pointerInput(onPlay) { detectTapGestures(onTap = { onPlay() }) },
            verticalAlignment = Alignment.Top,
        ) {
            AsyncImage(
                model = row.feedArtworkUrl.ifBlank { null },
                contentDescription = stringResource(R.string.feed_artwork_description, feedName),
                placeholder = placeholder,
                error = placeholder,
                fallback = placeholder,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp).size(56.dp).clip(RoundedCornerShape(8.dp)),
            )
            Column(
                Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("queue_title_$id"),
                )
                Text(
                    feedName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("queue_feed_$id"),
                )
                if (timeText != null) {
                    Text(
                        timeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.testTag("queue_time_$id"),
                    )
                }
                if (nowPlaying) {
                    Row(Modifier.testTag("queue_playing_$id"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.primary)
                        Text(
                            stringResource(R.string.queue_now_playing),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                        )
                    }
                }
                if (row.downloadState == DownloadState.DOWNLOADING) DownloadBar(row)
            }
            PlayedBarView(played, id)
        }
        HorizontalDivider()
    }
}

@Composable
private fun DownloadBar(row: QueueRow) {
    val percent = downloadPercent(row.bytesReceived, row.totalBytes)
    val tag = "queue_download_${row.episodeId}"
    if (percent != null) {
        val description = stringResource(R.string.queue_download_percent, percent)
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }.testTag(tag),
        )
    } else {
        val description = stringResource(R.string.queue_downloading)
        LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = description }.testTag(tag))
    }
}

/** The narrow vertical bar at the trailing edge: the played part fills from the top downward. */
@Composable
private fun PlayedBarView(played: PlayedBar, episodeId: Long) {
    val palette = if (isSystemInDarkTheme()) PlayedBarDark else PlayedBarLight
    val description = when (played) {
        is PlayedBar.Known -> stringResource(R.string.episode_percent_played, played.percent)
        PlayedBar.Started -> stringResource(R.string.episode_started)
        PlayedBar.NotStarted -> stringResource(R.string.queue_not_started)
    }
    Box(
        Modifier
            .width(8.dp)
            .fillMaxHeight()
            .background(Color(palette.unplayed))
            .semantics { contentDescription = description }
            .testTag("queue_progress_$episodeId"),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (played.fraction > 0f) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(played.fraction).background(Color(palette.played)))
        }
    }
}
