package com.podclatch.ui.subscribe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podclatch.R
import com.podclatch.ui.BackTopBar

/**
 * The Subscribe destination. [onSubscribed] receives the outcome of a successful subscribe once; the
 * caller hands it to the Feeds tab and leaves this screen.
 */
@Composable
fun SubscribeScreen(
    onBack: () -> Unit,
    onSubscribed: (SubscribeOutcome) -> Unit,
    vm: SubscribeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val outcome = state.outcome
    LaunchedEffect(outcome) {
        if (outcome != null) {
            vm.consumeOutcome()
            onSubscribed(outcome)
        }
    }
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.subscribe_title), onBack) },
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier.testTag("subscribe_screen"),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SubscribeByUrlSection(
                state = state,
                onTextChange = vm::onTextChange,
                onSubscribe = vm::subscribe,
            )
        }
    }
}

/** The minimal address field and Subscribe button, and the busy indicator and message under them. */
@Composable
fun SubscribeByUrlSection(
    state: SubscribeUiState,
    onTextChange: (String) -> Unit,
    onSubscribe: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChange,
            label = { Text(stringResource(R.string.subscribe_url_label)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("subscribe_url_field"),
        )
        Button(
            onClick = onSubscribe,
            enabled = state.canSubscribe,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("subscribe_url_button"),
        ) {
            Text(stringResource(R.string.subscribe_button))
        }
        if (state.busy) {
            val busyDescription = stringResource(R.string.subscribe_busy_description)
            Row(
                Modifier.testTag("subscribe_busy"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    Modifier.size(24.dp).semantics { contentDescription = busyDescription },
                )
                Text(stringResource(R.string.subscribe_busy))
            }
        }
        state.message?.let { message ->
            Text(
                subscribeMessageText(message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("subscribe_message"),
            )
        }
    }
}

@Composable
private fun subscribeMessageText(message: SubscribeMessage): String = when (message) {
    SubscribeMessage.AlreadySubscribed -> stringResource(R.string.subscribe_error_already)
    is SubscribeMessage.NotReadable -> stringResource(R.string.subscribe_error_failed, message.detail)
    SubscribeMessage.NotReadableNoDetail -> stringResource(R.string.subscribe_error_no_detail)
    is SubscribeMessage.Failed -> stringResource(R.string.subscribe_error_failed, message.reason)
    SubscribeMessage.Unexpected -> stringResource(R.string.subscribe_error_unexpected)
}
