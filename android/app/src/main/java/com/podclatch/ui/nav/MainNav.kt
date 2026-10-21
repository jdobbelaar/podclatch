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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.podclatch.R
import com.podclatch.ui.FeedDetailPlaceholderScreen
import com.podclatch.ui.QueuePlaceholderScreen
import com.podclatch.ui.SubscribePlaceholderScreen
import com.podclatch.ui.feeds.FeedsScreen

/**
 * The single NavHost: Queue (the start) and Feeds are the top-level destinations with the bottom bar;
 * Subscribe and Feed Detail are full screens above Feeds. Back, tab state, and rotation are handled by
 * NavController's standard saved state.
 */
@Composable
fun MainNav(onOpenConnection: () -> Unit) {
    val navController = rememberNavController()
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
            composable<Feeds> {
                FeedsScreen(
                    onOpenFeed = { id -> navController.navigate(FeedDetail(id)) { launchSingleTop = true } },
                    onSubscribe = { navController.navigate(Subscribe) { launchSingleTop = true } },
                    onOpenConnection = onOpenConnection,
                )
            }
            composable<Subscribe> { SubscribePlaceholderScreen(onBack = { navController.popBackStack() }) }
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
