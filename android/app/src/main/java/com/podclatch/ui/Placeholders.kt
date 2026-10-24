package com.podclatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podclatch.R

/**
 * The top bar of a tab: its title (with an optional [subtitle] line under it), the tab's own [actions], and a
 * "More options" menu, whose "Settings" item calls [onOpenSettings].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabTopBar(
    title: String,
    onOpenSettings: () -> Unit,
    subtitle: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            if (subtitle == null) {
                Text(title)
            } else {
                Column {
                    Text(title)
                    subtitle()
                }
            }
        },
        windowInsets = WindowInsets(0),
        actions = {
            actions()
            IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("overflow_menu")) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_settings)) },
                    onClick = {
                        expanded = false
                        onOpenSettings()
                    },
                    modifier = Modifier.testTag("menu_settings"),
                )
            }
        },
    )
}

/** The top bar of a full-screen destination, with a Back control that does what system Back does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        windowInsets = WindowInsets(0),
        actions = actions,
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("nav_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
    )
}
