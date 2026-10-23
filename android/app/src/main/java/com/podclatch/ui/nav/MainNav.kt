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
import androidx.compose.runtime.LaunchedEffect
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
import com.podclatch.ui.queue.QueueScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Column
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.podclatch.ui.feeddetail.FeedDetailScreen
import com.podclatch.ui.player.MiniPlayer
import com.podclatch.ui.player.PlayerScreen
import com.podclatch.ui.player.PlayerViewModel
import com.podclatch.ui.player.miniPlayerVisible
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
fun MainNav(
    navController: NavHostController,
    onOpenConnection: () -> Unit,
    openQueueRequested: Boolean = false,
    onOpenQueueHandled: () -> Unit = {},
) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // A tap on the new-episodes notification: show the Queue tab from wherever the user is.
    LaunchedEffect(openQueueRequested) {
        if (openQueueRequested) {
            // No destination yet means a cold start, which already begins on the Queue.
            val current = navController.currentDestination?.route
            if (current != null && needsQueueSwitch(current)) navController.switchTab(Queue)
            onOpenQueueHandled()
        }
    }
    val selected = tabForRoute(route)
    val player: PlayerViewModel = hiltViewModel()
    val playback by player.playbackState.collectAsStateWithLifecycle()
    val showMini = miniPlayerVisible(playback) && !isPlayerRoute(route)
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            Column {
                if (showMini) {
                    MiniPlayer(player, onOpenPlayer = { navController.navigate(Player) { launchSingleTop = true } })
                }
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
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Queue, modifier = Modifier.padding(padding)) {
            composable<Queue> { QueueScreen(onOpenConnection) }
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
            composable<FeedDetail> { FeedDetailScreen(onBack = { navController.popBackStack() }) }
            composable<Player> { PlayerScreen(onClose = { navController.popBackStack() }) }
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
