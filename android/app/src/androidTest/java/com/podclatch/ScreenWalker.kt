package com.podclatch

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput

/**
 * Walks the app through every screen and state the accessibility criteria list (US-20 part B), calling
 * [visit] with the screen's name once each one is on the screen. The audit test checks descriptions and touch
 * targets there; the text-scale test checks the layout and takes screenshots.
 */
class ScreenWalker(private val compose: ComposeTestRule, private val app: AppHarness, private val visit: (String) -> Unit) {
    private fun click(tag: String) {
        compose.awaitTag(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    fun walkEmpty() {
        app.start()
        app.launch()
        compose.awaitTag("queue_empty")
        visit("Queue (empty)")
        click("tab_feeds")
        compose.awaitTag("feeds_empty")
        visit("Feeds (empty)")
    }

    fun walkConnection() {
        app.start(configured = false)
        app.launch()
        compose.awaitTag("server_url")
        visit("Connection screen")
    }

    fun walkData() {
        app.start()
        app.api.seedFeed("Feed A")
        app.api.seedFeed("Feed B")
        app.launch()

        // Queue with items, then its overflow menu and Settings.
        compose.awaitTag("queue_item_13")
        compose.awaitTag("queue_item_23")
        visit("Queue (items)")
        click("overflow_menu")
        compose.awaitTag("menu_refresh")
        visit("Queue overflow menu")
        click("menu_settings")
        compose.awaitTag("settings_screen")
        visit("Settings (top)")
        for (tag in listOf("setting_notify_new", "setting_sync_interval", "setting_auto_delete")) {
            compose.onNodeWithTag(tag).performScrollTo()
            visit("Settings (scrolled to $tag)")
        }
        click("nav_back")
        compose.awaitTag("queue_item_13")

        // The mini player and the full player, with the Speed sheet.
        click("queue_item_13")
        compose.awaitTag("mini_player")
        compose.awaitTag("mini_play_pause")
        visit("Queue with the mini player")
        click("mini_player")
        compose.awaitTag("player_close")
        visit("Full player")
        compose.onNodeWithTag("player_speed").performTouchInput { longClick() }
        compose.awaitTag("speed_sheet")
        visit("Speed sheet")
        click("speed_cancel")
        compose.awaitGone("speed_sheet")
        click("player_close")

        // Feeds, Feed Detail, its menus, and its confirmation dialogs.
        click("tab_feeds")
        compose.awaitTag("feed_row_1")
        visit("Feeds (items)")
        click("feed_row_1")
        compose.awaitTag("episode_row_13")
        visit("Feed Detail")
        click("episode_menu_13")
        compose.awaitTag("menu_toggle_played_13")
        visit("Feed Detail row menu")
        click("menu_toggle_played_13")
        compose.awaitGone("menu_toggle_played_13")
        click("feed_detail_overflow")
        compose.awaitTag("menu_unsubscribe")
        visit("Feed Detail overflow menu")
        click("menu_unsubscribe")
        compose.awaitTag("confirm_unsubscribe")
        visit("Unsubscribe dialog")
        click("cancel_unsubscribe")
        compose.awaitGone("confirm_unsubscribe")
        click("mark_all_new")
        compose.awaitTag("confirm_mark_all")
        visit("Mark all dialog")
        click("cancel_mark_all")
        compose.awaitGone("confirm_mark_all")
        click("nav_back")

        // Subscribe with search results and a preview.
        click("subscribe_fab")
        compose.awaitTag("subscribe_screen")
        visit("Subscribe")
        compose.awaitTag("search_field")
        compose.onNodeWithTag("search_field").performTextInput("fake term")
        compose.awaitTag("search_result_0")
        visit("Subscribe with search results")
        click("search_result_0")
        compose.awaitTag("search_preview")
        visit("Search preview")
    }
}
