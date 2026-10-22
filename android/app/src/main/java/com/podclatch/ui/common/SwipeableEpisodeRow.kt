package com.podclatch.ui.common

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.podclatch.Config
import com.podclatch.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The Episode-row swipe (decision #21), shared by Feed Detail and the Queue so both behave identically.
 */

/**
 * Shows [content] and lets it be dragged sideways. Releasing beyond [Config.SWIPE_EPISODE_FRACTION] of
 * the width to the left calls [onMarkPlayed], to the right [onMarkNew]; in every case the content then
 * animates back to rest. The row is never removed here, and a no-op swipe is decided by the repository.
 * Velocity is ignored on purpose, so a short fast swipe cannot trigger it.
 */
@Composable
fun SwipeableEpisodeRow(onMarkPlayed: () -> Unit, onMarkNew: () -> Unit, content: @Composable () -> Unit) {
    var width by remember { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val currentOnMarkPlayed by rememberUpdatedState(onMarkPlayed)
    val currentOnMarkNew by rememberUpdatedState(onMarkNew)

    fun snapBack() {
        val from = dragX
        scope.launch { animate(from, 0f) { value, _ -> dragX = value } }
    }

    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        if (dragX != 0f) {
            val toLeft = dragX < 0f
            Row(
                Modifier.matchParentSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 24.dp),
                horizontalArrangement = if (toLeft) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (toLeft) {
                    Text(stringResource(R.string.swipe_label_played), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                } else {
                    Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.swipe_label_new), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(dragX.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val threshold = Config.SWIPE_EPISODE_FRACTION * width
                            if (width > 0 && -dragX > threshold) currentOnMarkPlayed()
                            if (width > 0 && dragX > threshold) currentOnMarkNew()
                            snapBack()
                        },
                        onDragCancel = { snapBack() },
                    ) { change, delta ->
                        change.consume()
                        dragX = (dragX + delta).coerceIn(-width.toFloat(), width.toFloat())
                    }
                },
        ) {
            Surface(color = Color.Transparent) { content() }
        }
    }
}
