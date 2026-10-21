package com.podclatch.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.podclatch.R
import com.podclatch.ui.FeedDetailPlaceholderScreen
import com.podclatch.ui.QueuePlaceholderScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podclatch.ui.feeds.FeedsScreen
import com.podclatch.ui.subscribe.SUBSCRIBE_OUTCOME_KEY
import com.podclatch.ui.subscribe.SubscribeOutcome
import com.podclatch.ui.subscribe.SubscribeScreen

/**
 * The single NavHost: Queue (the start) and Feeds are the top-level destinations with the bottom bar;
 * Subscribe and Feed Detail are full screens above Feeds. Back, tab state, and rotation are handled by
 * NavController's standard saved state.
 */
@Composable
fun MainNav(navController: NavHostController, onOpenConnection: () -> Unit) {
    val entry by navController.currentBackStackEntryAsState()
    val selected = tabForRoute(entry?.destination?.route)
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (selected != null) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selected == Tab.QUEUE,
                        onClick = { navController.switchTab(Queue) },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_queue)) },
                        modifier = Modifier.testTag("tab_queue"),
                    )
                    NavigationBarItem(
                        selected = selected == Tab.FEEDS,
                        onClick = { navController.switchTab(Feeds) },
                        icon = { Icon(painterResource(R.drawable.ic_feeds), contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_feeds)) },
                        modifier = Modifier.testTag("tab_feeds"),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Queue, modifier = Modifier.padding(padding)) {
            composable<Queue> { QueuePlaceholderScreen(onOpenConnection) }
            composable<Feeds> { feedsEntry ->
                // The Subscribe view leaves its outcome here before it pops; Feeds reads it once.
                val handle = feedsEntry.savedStateHandle
                val outcome by handle.getStateFlow<SubscribeOutcome?>(SUBSCRIBE_OUTCOME_KEY, null)
                    .collectAsStateWithLifecycle()
                FeedsScreen(
                    onOpenFeed = { id -> navController.navigate(FeedDetail(id)) { launchSingleTop = true } },
                    onSubscribe = { navController.navigate(Subscribe) { launchSingleTop = true } },
                    onOpenConnection = onOpenConnection,
                    subscribeOutcome = outcome,
                    onSubscribeOutcomeHandled = { handle[SUBSCRIBE_OUTCOME_KEY] = null },
                )
            }
            composable<Subscribe> {
                SubscribeScreen(
                    onBack = { navController.popBackStack() },
                    onSubscribed = { outcome ->
                        navController.previousBackStackEntry?.savedStateHandle?.set(SUBSCRIBE_OUTCOME_KEY, outcome)
                        navController.popBackStack()
                    },
                )
            }
            composable<FeedDetail> { FeedDetailPlaceholderScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

/** The standard bottom-bar pattern: each tab's back stack and state is saved and restored. */
private fun <T : Any> NavController.switchTab(route: T) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
