package com.podclatch.ui.feeddetail

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.podclatch.Config
import com.podclatch.R
import com.podclatch.data.db.EpisodeStatus
import com.podclatch.data.db.FeedEpisode
import com.podclatch.data.db.ListenOrder
import com.podclatch.ui.BackTopBar
import com.podclatch.ui.common.PullRefreshBox
import com.podclatch.ui.common.SwipeableEpisodeRow
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun FeedDetailScreen(onBack: () -> Unit, vm: FeedDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(state.feedGone) { if (state.feedGone) currentOnBack() }
    val title = state.header?.title.orEmpty().ifBlank { stringResource(R.string.feed_untitled) }

    Scaffold(
        topBar = {
            BackTopBar(title, onBack) {
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("feed_detail_overflow")) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.feed_detail_more_options))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_refresh_this_feed)) },
                        enabled = !state.actionsLocked,
                        onClick = {
                            expanded = false
                            vm.refresh()
                        },
                        modifier = Modifier.testTag("menu_refresh"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_unsubscribe)) },
                        enabled = !state.actionsLocked,
                        onClick = {
                            expanded = false
                            vm.requestUnsubscribe()
                        },
                        modifier = Modifier.testTag("menu_unsubscribe"),
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // A thin bar under the top bar while a server action runs; the space is always reserved.
            Box(Modifier.fillMaxWidth().heightIn(min = 4.dp)) {
                if (state.busy) {
                    val busyDescription = stringResource(R.string.feed_detail_busy_description)
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().semantics { contentDescription = busyDescription }.testTag("feed_detail_busy"),
                    )
                }
            }
            state.message?.let { MessageBanner(it, onDismiss = vm::dismissMessage) }
            if (!state.loaded) {
                val loadingDescription = stringResource(R.string.feed_detail_loading_description)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        Modifier.semantics { contentDescription = loadingDescription }.testTag("feed_detail_loading"),
                    )
                }
            } else {
                PullRefreshBox(
                    locked = state.actionsLocked,
                    refreshing = state.refreshing,
                    onRefresh = { vm.refresh() },
                ) {
                    EpisodeList(state, vm)
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        is FeedDetailDialog.MarkAllPlayed -> MarkAllDialog(
            title = stringResource(R.string.mark_all_played_title),
            message = pluralStringResource(
                R.plurals.mark_all_played_message, dialog.count, dialog.count, displayTitle(dialog.feedTitle),
            ),
            onConfirm = vm::confirmMarkAll,
            onCancel = vm::cancelDialog,
        )
        is FeedDetailDialog.MarkAllNew -> MarkAllDialog(
            title = stringResource(R.string.mark_all_new_title),
            message = pluralStringResource(
                R.plurals.mark_all_new_message, dialog.count, dialog.count, displayTitle(dialog.feedTitle),
            ),
            onConfirm = vm::confirmMarkAll,
            onCancel = vm::cancelDialog,
        )
        is FeedDetailDialog.Unsubscribe -> AlertDialog(
            onDismissRequest = vm::cancelDialog,
            title = { Text(stringResource(R.string.feed_detail_unsubscribe_title)) },
            text = { Text(stringResource(R.string.feed_detail_unsubscribe_message, displayTitle(dialog.feedTitle))) },
            confirmButton = {
                TextButton(onClick = vm::confirmUnsubscribe, modifier = Modifier.testTag("confirm_unsubscribe")) {
                    Text(stringResource(R.string.unsubscribe_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelDialog, modifier = Modifier.testTag("cancel_unsubscribe")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
        null -> Unit
    }
}

@Composable
private fun MarkAllDialog(title: String, message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm_mark_all")) {
                Text(stringResource(R.string.mark_all_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel_mark_all")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun displayTitle(title: String): String = title.ifBlank { stringResource(R.string.feed_untitled) }

// ---- Banner ----

@Composable
private fun MessageBanner(message: FeedDetailMessage, onDismiss: () -> Unit) {
    val text = when (message) {
        is FeedDetailMessage.ListenOrderFailed -> stringResource(R.string.message_listen_order_failed, message.reason)
        is FeedDetailMessage.FeedRefreshFailed -> stringResource(
            R.string.message_feed_refresh_failed,
            message.error.ifBlank { stringResource(R.string.message_feed_refresh_no_reason) },
        )
        is FeedDetailMessage.RefreshRequestFailed ->
            stringResource(R.string.message_feed_refresh_request_failed, message.reason)
        is FeedDetailMessage.RefreshSyncFailed ->
            stringResource(R.string.message_feed_refresh_sync_failed, message.reason)
        is FeedDetailMessage.UnsubscribeFailed ->
            stringResource(R.string.message_feed_unsubscribe_failed, displayTitle(message.title), message.reason)
        is FeedDetailMessage.UnsubscribedSyncFailed ->
            stringResource(R.string.message_feed_unsubscribed_sync_failed, displayTitle(message.title), message.reason)
        FeedDetailMessage.PlayFailed -> stringResource(R.string.message_play_failed)
        FeedDetailMessage.Unexpected -> stringResource(R.string.message_unexpected)
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag("feed_detail_message"),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("message_dismiss")) {
                Text(stringResource(R.string.message_dismiss))
            }
        }
    }
}

// ---- The list ----

@Composable
private fun EpisodeList(state: FeedDetailUiState, vm: FeedDetailViewModel) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { ZoneId.systemDefault() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("feed_detail_list"),
        overscrollEffect = null,
    ) {
        state.header?.let { header ->
            item(key = "header") { Header(header, state, vm) }
        }
        if (state.episodes.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.feed_detail_empty),
                    modifier = Modifier.fillMaxWidth().padding(32.dp).testTag("feed_detail_empty"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            items(state.episodes, key = { it.id }) { episode ->
                SwipeableEpisodeRow(
                    onMarkPlayed = { vm.markPlayed(episode.id) },
                    onMarkNew = { vm.markNew(episode.id) },
                ) {
                    EpisodeRow(
                        episode = episode,
                        locale = locale,
                        zone = zone,
                        onPlay = { vm.play(episode.id) },
                        onMarkPlayed = { vm.markPlayed(episode.id) },
                        onMarkNew = { vm.markNew(episode.id) },
                        onRetryDownload = { vm.retryDownload(episode.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(header: FeedDetailHeader, state: FeedDetailUiState, vm: FeedDetailViewModel) {
    val title = displayTitle(header.title)
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = header.artworkUrl.ifBlank { null },
                contentDescription = stringResource(R.string.feed_artwork_description, title),
                placeholder = placeholder,
                error = placeholder,
                fallback = placeholder,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
            )
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).testTag("feed_detail_title"),
            )
        }
        Description(header.description)
        ListenOrderControl(header.listenOrder, enabled = !state.actionsLocked, onSelect = vm::selectListenOrder)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = vm::requestMarkAllPlayed,
                enabled = state.canMarkAllPlayed,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("mark_all_played"),
            ) { Text(stringResource(R.string.mark_all_played)) }
            OutlinedButton(
                onClick = vm::requestMarkAllNew,
                enabled = state.canMarkAllNew,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("mark_all_new"),
            ) { Text(stringResource(R.string.mark_all_new)) }
        }
    }
}

@Composable
private fun Description(html: String) {
    val text = remember(html) { descriptionToPlainText(html) }
    if (text.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    Column {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else Config.DESCRIPTION_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
            modifier = Modifier.testTag("feed_description"),
        )
        // The control exists only when the text does not fit in the collapsed lines.
        if (expanded || overflowing) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp).testTag("feed_description_toggle"),
            ) {
                Text(stringResource(if (expanded) R.string.description_show_less else R.string.description_show_more))
            }
        }
    }
}

@Composable
private fun ListenOrderControl(selected: ListenOrder, enabled: Boolean, onSelect: (ListenOrder) -> Unit) {
    val options = listOf(
        ListenOrder.NEWEST_FIRST to ("listen_order_newest" to R.string.listen_order_newest),
        ListenOrder.OLDEST_FIRST to ("listen_order_oldest" to R.string.listen_order_oldest),
    )
    val groupDescription = stringResource(R.string.listen_order_label)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = groupDescription }) {
        options.forEachIndexed { index, (order, tagAndLabel) ->
            SegmentedButton(
                selected = selected == order,
                onClick = { onSelect(order) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.heightIn(min = 48.dp).testTag(tagAndLabel.first),
            ) { Text(stringResource(tagAndLabel.second)) }
        }
    }
}

// ---- One row ----

@Composable
private fun EpisodeRow(
    episode: FeedEpisode,
    locale: java.util.Locale,
    zone: ZoneId,
    onPlay: () -> Unit,
    onMarkPlayed: () -> Unit,
    onMarkNew: () -> Unit,
    onRetryDownload: () -> Unit,
) {
    val played = episode.status == EpisodeStatus.PLAYED
    val style = episodeRowStyle(episode.status)
    val colors = MaterialTheme.colorScheme
    val background = when (style.background) {
        RowBackground.NORMAL -> colors.surface
        RowBackground.PLAYED -> colors.surfaceContainerHigh
    }
    val titleColor = when (style.titleColor) {
        TitleColor.NORMAL -> colors.onSurface
        TitleColor.MUTED -> colors.onSurfaceVariant
    }
    val titleWeight = when (style.titleWeight) {
        TitleWeight.SEMIBOLD -> FontWeight.SemiBold
        TitleWeight.NORMAL -> FontWeight.Normal
    }
    val date = remember(episode.publishedAt, locale, zone) { formatEpisodeDate(episode.publishedAt, zone, locale) }
    val duration = formatDuration(episode.durationSec)
    val progress = episodeProgress(episode.status, episode.positionSec, episode.durationSec)
    val statusLabel = stringResource(if (played) R.string.episode_status_played else R.string.episode_status_new)
    val percentPlayed = (progress as? EpisodeProgress.Known)?.percent
    val percentLabel = percentPlayed?.let { stringResource(R.string.episode_percent_played, it) }
    val failure = downloadFailureLabel(episode.status, episode.downloadState, episode.downloadPermanentFailure)
    val failureText = failure?.let {
        stringResource(if (it == DownloadFailureLabel.UNAVAILABLE) R.string.episode_download_unavailable else R.string.episode_download_failed)
    }
    val description = episodeDescription(
        title = episode.title,
        date = date,
        duration = duration,
        statusLabel = statusLabel,
        percentPlayedLabel = percentLabel,
        failureLabel = failureText,
    )

    Row(Modifier.fillMaxWidth().background(background), verticalAlignment = Alignment.Top) {
        Column(
            Modifier
                .weight(1f)
                // Taps anywhere on the row start playback; the swipe above this sees only horizontal drags.
                .pointerInput(onPlay) { detectTapGestures(onTap = { onPlay() }) }
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(
                Modifier
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        role = Role.Button
                        onClick { onPlay(); true }
                    }
                    .testTag("episode_row_${episode.id}"),
            ) {
                Text(
                    episode.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                    fontWeight = titleWeight,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(date, duration).joinToString("  •  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Row(
                Modifier.testTag("episode_status_${episode.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (played) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                } else {
                    Box(Modifier.size(8.dp).background(colors.primary, CircleShape))
                }
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (played) colors.onSurfaceVariant else colors.primary,
                )
            }
            if (failureText != null) {
                Text(
                    failureText,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.error,
                    modifier = Modifier.testTag("episode_download_${episode.id}"),
                )
            }
            when (progress) {
                is EpisodeProgress.Known -> {
                    val progressDescription = percentLabel.orEmpty()
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = progressDescription }
                            .testTag("episode_progress_${episode.id}"),
                    )
                }
                EpisodeProgress.Started -> Text(
                    stringResource(R.string.episode_started),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.testTag("episode_progress_${episode.id}"),
                )
                null -> Unit
            }
        }
        EpisodeMenu(episode, played, onMarkPlayed, onMarkNew, onRetryDownload)
    }
}

@Composable
private fun EpisodeMenu(
    episode: FeedEpisode,
    played: Boolean,
    onMarkPlayed: () -> Unit,
    onMarkNew: () -> Unit,
    onRetryDownload: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val menuDescription = stringResource(R.string.episode_menu_description, episode.title)
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(48.dp).testTag("episode_menu_${episode.id}"),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = menuDescription)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (played) R.string.menu_mark_new else R.string.menu_mark_played)) },
                onClick = {
                    expanded = false
                    if (played) onMarkNew() else onMarkPlayed()
                },
                modifier = Modifier.testTag("menu_toggle_played_${episode.id}"),
            )
            if (canRetryDownload(episode.status, episode.downloadState)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_retry_download)) },
                    onClick = {
                        expanded = false
                        onRetryDownload()
                    },
                    modifier = Modifier.testTag("menu_retry_download_${episode.id}"),
                )
            }
        }
    }
}
