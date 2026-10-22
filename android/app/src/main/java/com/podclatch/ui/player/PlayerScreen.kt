package com.podclatch.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.unit.DpSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.podclatch.R

/** The full-screen player. It pops itself when no Episode is loaded any more. */
@Composable
fun PlayerScreen(onClose: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loaded = state.playback.episodeId != null
    LaunchedEffect(loaded) { if (!loaded) onClose() }
    PlayerContent(
        state = state,
        onClose = onClose,
        onPlayPause = viewModel::playPause,
        onSeek = viewModel::seekTo,
        onSkipBack = viewModel::skipBack,
        onSkipForward = viewModel::skipForward,
        onToggleSpeed = viewModel::toggleSpeed,
        onConfirmSpeed = viewModel::confirmSpeed,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerContent(
    state: PlayerUiState,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onToggleSpeed: () -> Unit,
    onConfirmSpeed: (Float, Float) -> Unit,
) {
    val playback = state.playback
    val duration = playback.durationMs
    var drag by remember { mutableStateOf(SeekDrag()) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    val shownPosition = drag.displayPositionMs(state.positionMs, duration)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag("player_close")) {
                Icon(painterResource(R.drawable.ic_collapse), contentDescription = stringResource(R.string.player_close))
            }
        }
        AsyncImage(
            model = playback.artworkUrl?.ifBlank { null },
            contentDescription = stringResource(R.string.player_artwork_description, playback.feedTitle),
            placeholder = placeholder,
            error = placeholder,
            fallback = placeholder,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .widthIn(max = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 120.dp else 280.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .testTag("player_artwork"),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                playback.title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("player_title"),
            )
            Text(
                playback.feedTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.fillMaxWidth()) {
            val seekDescription = stringResource(R.string.player_seekbar_description)
            val seekInteraction = remember { MutableInteractionSource() }
            // The tag sits on the Box, whose bounds are exactly the bar's layout (the Slider reports a
            // wider box for its thumb), and the narrow thumb keeps the track close to the full width, so a
            // tap at a fraction of the tagged bounds' width lands near that fraction of the Episode.
            Box(Modifier.fillMaxWidth().height(48.dp).testTag("player_seekbar")) {
                Slider(
                    value = drag.displayFraction(state.positionMs, duration),
                    onValueChange = { drag = drag.onChange(it) },
                    onValueChangeFinished = {
                        drag.releaseTarget(duration)?.let(onSeek)
                        drag = SeekDrag()
                    },
                    enabled = duration != null,
                    thumb = { SliderDefaults.Thumb(seekInteraction, thumbSize = DpSize(6.dp, 28.dp), enabled = duration != null) },
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = seekDescription },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(shownPosition), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("player_elapsed"))
                Text(
                    if (duration == null) stringResource(R.string.player_unknown_time) else formatRemaining(shownPosition, duration),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("player_remaining"),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SkipButton(
                iconRes = R.drawable.ic_skip_back,
                seconds = state.skipBackSec,
                description = stringResource(R.string.player_skip_back_description, state.skipBackSec),
                onClick = onSkipBack,
                tag = "player_skip_back",
            )
            IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp).testTag("player_play_pause")) {
                Icon(
                    painterResource(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (playback.isPlaying) R.string.player_pause else R.string.player_play),
                    modifier = Modifier.size(48.dp),
                )
            }
            SkipButton(
                iconRes = R.drawable.ic_skip_forward,
                seconds = state.skipForwardSec,
                description = stringResource(R.string.player_skip_forward_description, state.skipForwardSec),
                onClick = onSkipForward,
                tag = "player_skip_forward",
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SpeedButton(
                speed = state.speed,
                onToggle = onToggleSpeed,
                onAssign = { sheetOpen = true },
                modifier = Modifier.testTag("player_speed"),
            )
            Text(
                stringResource(R.string.speed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("speed_hint"),
            )
        }
    }
    if (sheetOpen) {
        SpeedSheet(initial = state.speed.assigned, onConfirm = onConfirmSpeed, onDismiss = { sheetOpen = false })
    }
}

@Composable
private fun SkipButton(iconRes: Int, seconds: Int, description: String, onClick: () -> Unit, tag: String) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 56.dp).testTag(tag).semantics { contentDescription = description },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(iconRes), contentDescription = null)
            Text("$seconds", style = MaterialTheme.typography.labelSmall)
        }
    }
}
