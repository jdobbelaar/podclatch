package com.podclatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podclatch.R
import com.podclatch.ui.feeds.FeedDetailViewModel
import com.podclatch.ui.feeds.shouldPopFeedDetail
import androidx.compose.runtime.LaunchedEffect

/**
 * The top bar of a tab: its title and a "More options" menu. "Connection settings" is a temporary
 * stand-in for the Settings screen (US-17), so the connection screen stays reachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabTopBar(title: String, onOpenConnection: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title) },
        windowInsets = WindowInsets(0),
        actions = {
            IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("overflow_menu")) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_connection_settings)) },
                    onClick = {
                        expanded = false
                        onOpenConnection()
                    },
                    modifier = Modifier.testTag("menu_connection_settings"),
                )
            }
        },
    )
}

/** The top bar of a full-screen destination, with a Back control that does what system Back does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        windowInsets = WindowInsets(0),
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("nav_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
    )
}

/** The Queue tab until US-13: the Sync line and button that QA uses to force a Sync. */
@Composable
fun QueuePlaceholderScreen(onOpenConnection: () -> Unit, vm: QueueViewModel = hiltViewModel()) {
    val sync by vm.sync.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TabTopBar(stringResource(R.string.tab_queue), onOpenConnection) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.queue_placeholder), modifier = Modifier.testTag("queue_placeholder"))
            Text(syncStatusText(sync), modifier = Modifier.testTag("sync_status"))
            Button(onClick = vm::syncNow, modifier = Modifier.testTag("sync_now")) {
                Text(stringResource(R.string.sync_now))
            }
        }
    }
}

/** The Subscribe destination until the Subscribe stories replace it. */
@Composable
fun SubscribePlaceholderScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.subscribe_title), onBack) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.subscribe_placeholder), modifier = Modifier.testTag("subscribe_placeholder"))
        }
    }
}

/** The Feed Detail destination until the Feed Detail story replaces it; pops itself if the Feed is not in the cache. */
@Composable
fun FeedDetailPlaceholderScreen(onBack: () -> Unit, vm: FeedDetailViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val pop = shouldPopFeedDetail(items, vm.feedId)
    LaunchedEffect(pop) { if (pop) onBack() }
    val title = items?.firstOrNull { it.id == vm.feedId }?.title?.ifBlank { null }
        ?: stringResource(R.string.feed_untitled)
    Scaffold(
        topBar = { BackTopBar(title, onBack) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("feed_detail_title"))
            Text(stringResource(R.string.feed_detail_placeholder), modifier = Modifier.testTag("feed_detail_placeholder"))
        }
    }
}
