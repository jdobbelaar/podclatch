package com.podclatch.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podclatch.R
import com.podclatch.domain.SettingsChoices
import com.podclatch.domain.formatSpeed
import com.podclatch.ui.BackTopBar
import com.podclatch.ui.ConnectionForm
import com.podclatch.ui.ConnectionViewModel

/**
 * The Settings screen (US-17): the server connection (the same form as the connection screen) and every device
 * setting. A full-screen destination above the tabs; Back returns to the tab it was opened from. Its own
 * [ConnectionViewModel] belongs to this destination, so an edit that was not saved is dropped on Back.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
    connection: ConnectionViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val connectionState by connection.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("settings_screen"),
        ) {
            SectionHeader(stringResource(R.string.settings_section_server))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectionForm(connection, connectionState, showRejectedNotice = false, saveLabel = stringResource(R.string.settings_save))
            }
            val s = state
            if (s != null) DeviceSettings(s, vm)
        }
    }
}

@Composable
private fun DeviceSettings(s: SettingsUiState, vm: SettingsViewModel) {
    val context = LocalContext.current
    val seconds: @Composable (Int) -> String = { pluralStringResource(R.plurals.settings_seconds, it, it) }

    SectionHeader(stringResource(R.string.settings_section_playback))
    ChoiceRow(
        tag = "setting_skip_back",
        label = stringResource(R.string.settings_skip_back),
        options = SettingsChoices.withCurrent(SettingsChoices.SKIP_SECONDS, s.skipBackSec),
        selected = s.skipBackSec,
        format = seconds,
        onPick = vm::setSkipBack,
    )
    ChoiceRow(
        tag = "setting_skip_forward",
        label = stringResource(R.string.settings_skip_forward),
        options = SettingsChoices.withCurrent(SettingsChoices.SKIP_SECONDS, s.skipForwardSec),
        selected = s.skipForwardSec,
        format = seconds,
        onPick = vm::setSkipForward,
    )
    ChoiceRow(
        tag = "setting_assigned_speed",
        label = stringResource(R.string.settings_assigned_speed),
        options = SettingsChoices.speedsWithCurrent(s.assignedSpeed),
        selected = s.assignedSpeed,
        same = SettingsChoices::sameSpeed,
        format = { formatSpeed(it) },
        onPick = vm::setAssignedSpeed,
    )

    SectionHeader(stringResource(R.string.settings_section_downloads))
    SwitchRow("setting_wifi_only", stringResource(R.string.settings_wifi_only), s.wifiOnly, vm::setWifiOnly)
    ChoiceRow(
        tag = "setting_download_ahead",
        label = stringResource(R.string.settings_download_ahead),
        options = SettingsChoices.DOWNLOAD_AHEAD,
        selected = s.downloadAhead,
        format = { pluralStringResource(R.plurals.settings_episodes, it, it) },
        onPick = vm::setDownloadAhead,
    )
    SwitchRow("setting_auto_delete", stringResource(R.string.settings_auto_delete), s.autoDelete, vm::setAutoDelete)

    SectionHeader(stringResource(R.string.settings_section_sync))
    ChoiceRow(
        tag = "setting_sync_interval",
        label = stringResource(R.string.settings_sync_interval),
        options = SettingsChoices.withCurrent(SettingsChoices.SYNC_HOURS, s.syncIntervalHours),
        selected = s.syncIntervalHours,
        format = { pluralStringResource(R.plurals.settings_hours, it, it) },
        onPick = vm::setSyncInterval,
    )
    NotifyRow(s.notifyNewEpisodes, vm::setNotifyNewEpisodes, context)
}

/** The new-episode switch. Turning it on asks for the OS permission when needed; the setting is stored either way. */
@Composable
private fun NotifyRow(enabled: Boolean, onChange: (Boolean) -> Unit, context: Context) {
    var granted by remember { mutableStateOf(notificationsGranted(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // The user may change the permission in the system settings and come back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = notificationsGranted(context) }
    SwitchRow("setting_notify_new", stringResource(R.string.settings_notify_new), enabled) { on ->
        onChange(on)
        if (SettingsChoices.shouldRequestOnEnable(Build.VERSION.SDK_INT, on, granted)) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (SettingsChoices.notificationsBlocked(Build.VERSION.SDK_INT, enabled, granted)) {
        Text(
            stringResource(R.string.settings_notify_blocked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp).testTag("setting_notify_blocked_note"),
        )
        TextButton(
            onClick = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
            modifier = Modifier.heightIn(min = 48.dp).testTag("setting_notify_open_settings"),
        ) { Text(stringResource(R.string.settings_notify_open_settings)) }
    }
}

private fun notificationsGranted(context: Context): Boolean =
    !SettingsChoices.needsNotificationPermission(Build.VERSION.SDK_INT) ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun SectionHeader(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        HorizontalDivider()
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp).semantics { heading() },
        )
    }
}

/** A labelled switch; the whole row toggles, and its description carries the label and the state. */
@Composable
private fun SwitchRow(tag: String, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val state = stringResource(if (checked) R.string.settings_on else R.string.settings_off)
    val description = stringResource(R.string.settings_value_description, label, state)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(tag)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = description }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A labelled choice: shows the current value and opens a short list of the options on a tap. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun <T : Any> ChoiceRow(
    tag: String,
    label: String,
    options: List<T>,
    selected: T,
    format: @Composable (T) -> String,
    onPick: (T) -> Unit,
    same: (T, T) -> Boolean = { a, b -> a == b },
) {
    var open by remember { mutableStateOf(false) }
    val valueText = format(selected)
    val description = stringResource(R.string.settings_value_description, label, valueText)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(tag)
            .clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = description }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = (options.indexOfFirst { same(it, selected) } - 2).coerceAtLeast(0),
        )
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                // The dialog is its own window, so it needs its own switch for test tags to reach UiAutomator.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("${tag}_options"),
                ) {
                    items(options) { option ->
                        val isSelected = same(option, selected)
                        val text = format(option)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("${tag}_option_$option")
                                .selectable(selected = isSelected, role = Role.RadioButton) {
                                    open = false
                                    onPick(option)
                                },
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Text(text, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("${tag}_cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
