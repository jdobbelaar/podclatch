package com.podclatch.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.podclatch.Config
import com.podclatch.R
import com.podclatch.domain.PlaybackSpeedState
import com.podclatch.domain.SpeedMode
import com.podclatch.domain.formatSpeed
import com.podclatch.domain.normalizeSpeed
import com.podclatch.domain.stepSpeed

/**
 * Always shows the current speed. Filled in ASSIGNED mode, outlined in NORMAL mode (more than text
 * tells them apart). A tap toggles; a long-press opens the assign sheet (with a label for TalkBack).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeedButton(speed: PlaybackSpeedState, onToggle: () -> Unit, onAssign: () -> Unit, modifier: Modifier = Modifier) {
    val current = formatSpeed(speed.currentSpeed)
    val description = stringResource(R.string.speed_button_description, current, formatSpeed(speed.otherSpeed))
    val filled = speed.mode == SpeedMode.ASSIGNED
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        border = if (filled) null else BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        modifier = modifier
            .defaultMinSize(minWidth = 96.dp, minHeight = 48.dp)
            .semantics { contentDescription = description }
            .combinedClickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.speed_button_click_label),
                onClick = onToggle,
                onLongClickLabel = stringResource(R.string.speed_button_long_click_label),
                onLongClick = onAssign,
            ),
    ) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(current, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The assign sheet: the value, a 0.5 to 3.0 slider in 0.1 steps, minus and plus, Set and Cancel. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SpeedSheet(initial: Float, onConfirm: (value: Float, initial: Float) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableFloatStateOf(initial) }
    val minusDescription = stringResource(R.string.speed_minus_description)
    val plusDescription = stringResource(R.string.speed_plus_description)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // The sheet is its own window, so the activity's testTagsAsResourceId does not reach it.
        Column(
            Modifier
                .fillMaxWidth()
                .semantics { testTagsAsResourceId = true }
                .testTag("speed_sheet")
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.speed_sheet_title), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = { value = stepSpeed(value, -1) },
                    enabled = value > Config.SPEED_MIN,
                    modifier = Modifier.size(48.dp).testTag("speed_minus").semantics { contentDescription = minusDescription },
                ) { Text("−", style = MaterialTheme.typography.headlineMedium) }
                Text(
                    formatSpeed(value),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.defaultMinSize(minWidth = 112.dp).testTag("speed_value"),
                )
                IconButton(
                    onClick = { value = stepSpeed(value, 1) },
                    enabled = value < Config.SPEED_MAX,
                    modifier = Modifier.size(48.dp).testTag("speed_plus").semantics { contentDescription = plusDescription },
                ) { Text("+", style = MaterialTheme.typography.headlineMedium) }
            }
            val sliderDescription = stringResource(R.string.speed_slider_description)
            val sliderInteraction = remember { MutableInteractionSource() }
            Slider(
                value = value,
                onValueChange = { value = normalizeSpeed(it) },
                valueRange = Config.SPEED_MIN..Config.SPEED_MAX,
                steps = SPEED_SLIDER_STEPS,
                interactionSource = sliderInteraction,
                // The default thumb is 44 dp tall; this one is laid out 48 dp tall around the same drawn bar.
                thumb = {
                    Box(Modifier.size(width = 4.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                        SliderDefaults.Thumb(sliderInteraction, thumbSize = DpSize(4.dp, 44.dp))
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speed_slider").semantics { contentDescription = sliderDescription },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("speed_cancel"),
                ) { Text(stringResource(R.string.speed_cancel)) }
                Button(
                    onClick = { onConfirm(value, initial); onDismiss() },
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("speed_confirm"),
                ) { Text(stringResource(R.string.speed_set)) }
            }
        }
    }
}

/** 0.5 to 3.0 in 0.1 steps is 26 values, so 24 steps between the two ends. */
private const val SPEED_SLIDER_STEPS = 24
