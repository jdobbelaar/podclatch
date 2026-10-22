package com.podclatch.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.podclatch.R

/** The mini player above the bottom navigation: collects the polled state, so only compose it while visible. */
@Composable
fun MiniPlayer(viewModel: PlayerViewModel, onOpenPlayer: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MiniPlayerContent(state, onOpenPlayer, viewModel::playPause)
}

@Composable
fun MiniPlayerContent(state: PlayerUiState, onOpenPlayer: () -> Unit, onPlayPause: () -> Unit) {
    val playback = state.playback
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().testTag("mini_player").clickable(onClick = onOpenPlayer),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    model = playback.artworkUrl?.ifBlank { null },
                    contentDescription = null,
                    placeholder = placeholder,
                    error = placeholder,
                    fallback = placeholder,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        playback.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val failed = playback.error != null
                    Text(
                        if (failed) stringResource(R.string.player_playback_failed) else playback.feedTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.size(48.dp).testTag("mini_play_pause")) {
                    Icon(
                        painterResource(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = stringResource(if (playback.isPlaying) R.string.player_pause else R.string.player_play),
                    )
                }
            }
            val duration = playback.durationMs
            if (duration != null) {
                LinearProgressIndicator(
                    progress = { sliderFraction(state.positionMs, duration) },
                    modifier = Modifier.fillMaxWidth().height(2.dp).testTag("mini_progress"),
                )
            }
        }
    }
}
