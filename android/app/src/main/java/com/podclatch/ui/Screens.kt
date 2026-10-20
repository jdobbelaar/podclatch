package com.podclatch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.podclatch.R
import com.podclatch.data.repo.ConnectionTestResult
import com.podclatch.domain.FailureReason
import com.podclatch.domain.SyncState
import com.podclatch.domain.TokenError
import com.podclatch.domain.UrlError
import java.text.DateFormat
import java.util.Date

/**
 * Chooses between nothing (settings not read yet), the connection screen (not configured, or opened
 * from the placeholder home), and the placeholder home. A plain switch; navigation arrives with US-02.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(main: MainViewModel, connection: ConnectionViewModel, home: HomeViewModel) {
    val settings by main.settings.collectAsState()
    val connectionState by connection.state.collectAsState()
    val rejected by connection.tokenRejected.collectAsState()
    val current = settings
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .semantics { testTagsAsResourceId = true },
    ) {
        when {
            current == null -> Unit
            !current.isConfigured || connectionState.fromHome ->
                ConnectionScreen(connection, connectionState, showRejectedNotice = rejected && !current.isConfigured)
            else -> HomeScreen(home, onEditConnection = connection::openFromHome)
        }
    }
}

@Composable
fun ConnectionScreen(vm: ConnectionViewModel, state: ConnectionUiState, showRejectedNotice: Boolean) {
    BackHandler(enabled = state.fromHome) { vm.cancel() }
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.headlineSmall)
        if (showRejectedNotice) {
            Text(
                stringResource(R.string.notice_token_rejected),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("connection_notice"),
            )
        }
        OutlinedTextField(
            value = state.url,
            onValueChange = vm::onUrlChange,
            label = { Text(stringResource(R.string.server_url_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("server_url"),
        )
        val toggleDescription = stringResource(if (state.showToken) R.string.token_hide else R.string.token_show)
        OutlinedTextField(
            value = state.token,
            onValueChange = vm::onTokenChange,
            label = { Text(stringResource(R.string.token_label)) },
            singleLine = true,
            visualTransformation = if (state.showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(
                    onClick = vm::toggleShowToken,
                    modifier = Modifier.semantics { contentDescription = toggleDescription }.testTag("token_toggle"),
                ) {
                    Text(stringResource(if (state.showToken) R.string.token_hide_action else R.string.token_show_action))
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("auth_token"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = vm::testConnection,
                enabled = state.canTest,
                modifier = Modifier.testTag("test_connection"),
            ) { Text(stringResource(R.string.test_connection)) }
            if (state.testing) {
                val description = stringResource(R.string.testing_connection)
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp).semantics { contentDescription = description },
                )
            }
        }
        // One visible message at a time; a live region so a screen reader announces it.
        Text(
            text = state.message?.let { messageText(it) } ?: "",
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("connection_message"),
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = vm::save,
                enabled = state.canSave,
                modifier = Modifier.testTag("save_continue"),
            ) { Text(stringResource(R.string.save_continue)) }
            if (state.fromHome) {
                OutlinedButton(onClick = vm::cancel, modifier = Modifier.testTag("connection_cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
    if (state.confirmSwitch) {
        AlertDialog(
            onDismissRequest = vm::dismissSwitch,
            title = { Text(stringResource(R.string.switch_server_title)) },
            text = { Text(stringResource(R.string.switch_server_message)) },
            confirmButton = {
                TextButton(onClick = vm::confirmSwitch, modifier = Modifier.testTag("confirm_switch")) {
                    Text(stringResource(R.string.switch_server_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissSwitch) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun messageText(result: ConnectionTestResult): String = when (result) {
    is ConnectionTestResult.Success -> stringResource(R.string.result_connected, result.host)
    is ConnectionTestResult.InvalidUrl -> stringResource(
        when (result.error) {
            UrlError.BLANK -> R.string.url_blank
            UrlError.BAD_SCHEME -> R.string.url_bad_scheme
            UrlError.INVALID -> R.string.url_invalid
            UrlError.USERINFO -> R.string.url_userinfo
            UrlError.QUERY -> R.string.url_query
            UrlError.FRAGMENT -> R.string.url_fragment
            UrlError.HTTPS_REQUIRED -> R.string.url_https_required
        },
    )
    is ConnectionTestResult.InvalidToken -> stringResource(
        when (result.error) {
            TokenError.BLANK -> R.string.token_blank
            TokenError.INVALID_CHARACTERS -> R.string.token_invalid_characters
        },
    )
    ConnectionTestResult.Unreachable -> stringResource(R.string.result_unreachable)
    ConnectionTestResult.Timeout -> stringResource(R.string.result_timeout)
    ConnectionTestResult.TokenRejected -> stringResource(R.string.result_token_rejected)
    ConnectionTestResult.NotPodClatch -> stringResource(R.string.result_not_podclatch)
    is ConnectionTestResult.HttpError ->
        if (result.detail.isNullOrEmpty()) {
            stringResource(R.string.result_http_error, result.code)
        } else {
            stringResource(R.string.result_http_error_detail, result.code, result.detail)
        }
}

/** The placeholder home; US-02's tabs replace it. */
@Composable
fun HomeScreen(vm: HomeViewModel, onEditConnection: () -> Unit) {
    val state by vm.state.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.home_connected, state.host),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("home_connected"),
        )
        Text(syncStatusText(state.sync), modifier = Modifier.testTag("sync_status"))
        Text(stringResource(R.string.feed_count, state.feeds), modifier = Modifier.testTag("feed_count"))
        Text(stringResource(R.string.episode_count, state.episodes), modifier = Modifier.testTag("episode_count"))
        Text(stringResource(R.string.sync_rev, state.revision), modifier = Modifier.testTag("sync_rev"))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::syncNow, modifier = Modifier.testTag("sync_now")) {
                Text(stringResource(R.string.sync_now))
            }
            OutlinedButton(onClick = onEditConnection, modifier = Modifier.testTag("edit_connection")) {
                Text(stringResource(R.string.edit_connection))
            }
        }
    }
}

@Composable
private fun syncStatusText(sync: SyncState): String {
    val failure: FailureReason? = sync.lastFailure
    val lastSuccess = sync.lastSuccessAt
    return when {
        sync.running -> stringResource(R.string.sync_running)
        failure != null -> stringResource(R.string.sync_failed, failure.message)
        lastSuccess != null -> stringResource(
            R.string.sync_last,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(lastSuccess)),
        )
        else -> stringResource(R.string.sync_never)
    }
}
