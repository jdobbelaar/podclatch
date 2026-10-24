package com.podclatch

import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.podclatch.data.network.ApiError
import com.podclatch.data.network.ApiException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * US-20 parts A and B on the real app with the fake API: the accessibility custom actions that replace the
 * swipes and the drag, the refresh menu items, the live regions of the banners, the download failure label and
 * its Retry download item, and the playback error with its Retry button.
 */
class ResilienceUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = AppHarness()

    @After
    fun tearDown() {
        app.stop()
    }

    private fun click(tag: String) {
        compose.awaitTag(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    private fun actions(tag: String): List<CustomAccessibilityAction> =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun labels(tag: String) = actions(tag).map { it.label }

    private fun invoke(tag: String, label: String) {
        val action = actions(tag).first { it.label == label }
        compose.runOnUiThread { action.action() }
    }

    private fun hasPoliteLiveRegion(tag: String): Boolean {
        fun walk(node: SemanticsNode): Boolean =
            node.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite || node.children.any(::walk)
        return walk(compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())
    }

    private fun unreachable() = ApiException(ApiError.Unreachable(false))

    // ---- gesture alternatives ----

    @Test
    fun aQueueRowOffersMarkAsPlayedAndMarkAsNewAndMarkAsPlayedDoesWhatTheSwipeDoes() {
        app.start()
        app.api.seedFeed()
        app.launch()
        compose.awaitTag("queue_item_13")
        assertEquals(listOf("Mark as Played", "Mark as New"), labels("queue_item_13"))
        invoke("queue_item_13", "Mark as Played")
        compose.awaitGone("queue_item_13")
        compose.await("the Played state reached the server") { app.api.calls.contains("push") }
    }

    @Test
    fun aFeedsRowOffersUnsubscribeAndOnlyTheMovesThatExist() {
        app.start()
        repeat(3) { app.api.seedFeed("Feed $it") }
        app.launch()
        click("tab_feeds")
        compose.awaitTag("feed_row_3")
        assertEquals(listOf("Unsubscribe", "Move down"), labels("feed_row_1"))
        assertEquals(listOf("Unsubscribe", "Move up", "Move down"), labels("feed_row_2"))
        assertEquals(listOf("Unsubscribe", "Move up"), labels("feed_row_3"))

        invoke("feed_row_1", "Move down")
        compose.await("the server order is 2, 1, 3") { app.api.feedOrder() == listOf(2L, 1L, 3L) }
        assertEquals(1, app.api.calls.count { it == "reorder" })

        compose.await("the row actions follow the new order") { labels("feed_row_2") == listOf("Unsubscribe", "Move down") }
        invoke("feed_row_2", "Unsubscribe")
        compose.awaitTag("confirm_unsubscribe")
    }

    @Test
    fun theRefreshMenuItemsRunTheSameRefreshAsTheBigSwipe() {
        app.start()
        app.api.seedFeed()
        app.launch()
        compose.awaitTag("queue_item_13")

        val pullsBeforeQueue = app.api.calls.count { it == "pull" }
        click("overflow_menu")
        click("menu_refresh")
        compose.await("one refresh of all Feeds, then a pull") {
            app.api.calls.count { it == "refresh" } == 1 && app.api.calls.count { it == "pull" } > pullsBeforeQueue
        }

        click("tab_feeds")
        compose.awaitTag("feed_row_1")
        click("overflow_menu")
        click("menu_refresh")
        compose.await("a second refresh of all Feeds") { app.api.calls.count { it == "refresh" } == 2 }

        val pullsBeforeFeed = app.api.calls.count { it == "pull" }
        click("feed_row_1")
        click("feed_detail_overflow")
        click("menu_refresh")
        compose.await("one refresh of this Feed, then a pull") {
            app.api.calls.count { it == "refreshFeed" } == 1 && app.api.calls.count { it == "pull" } > pullsBeforeFeed
        }
    }

    // ---- live regions ----

    @Test
    fun theBannersAfterAFailedActionAreLiveRegions() {
        app.start()
        app.api.seedFeed()
        app.launch()
        compose.awaitTag("queue_item_13")

        app.api.failure = unreachable()
        click("overflow_menu")
        click("menu_refresh")
        compose.awaitTag("queue_message")
        assertTrue("queue_message is a polite live region", hasPoliteLiveRegion("queue_message"))
        click("message_dismiss")
        compose.awaitGone("queue_message")

        click("tab_feeds")
        compose.awaitTag("feed_row_1")
        click("overflow_menu")
        click("menu_refresh")
        compose.awaitTag("feeds_message")
        assertTrue("feeds_message is a polite live region", hasPoliteLiveRegion("feeds_message"))
        click("message_dismiss")

        click("feed_row_1")
        click("feed_detail_overflow")
        click("menu_refresh")
        compose.awaitTag("feed_detail_message")
        assertTrue("feed_detail_message is a polite live region", hasPoliteLiveRegion("feed_detail_message"))
    }

    @Test
    fun theConnectionMessageIsALiveRegion() {
        app.start(configured = false)
        app.launch()
        compose.awaitTag("server_url")
        app.api.failure = unreachable()
        compose.onNodeWithTag("server_url").performTextReplacement("http://fake-server.invalid")
        compose.onNodeWithTag("auth_token").performTextReplacement("test-token")
        click("test_connection")
        compose.awaitTag("connection_message")
        assertTrue("connection_message is a polite live region", hasPoliteLiveRegion("connection_message"))
    }

    // ---- download failure and playback error UI ----

    @Test
    fun aFailedDownloadShowsItsLabelAndRetryDownloadRequestsItOnce() {
        app.start()
        app.api.brokenAudio = true
        app.api.seedFeed()
        app.launch()
        click("tab_feeds")
        click("feed_row_1")
        compose.awaitTag("episode_download_13")
        compose.onNodeWithTag("episode_download_13").assertTextEquals("Download unavailable")
        assertTrue("an Episode that was never downloaded shows no label", !compose.exists("episode_download_11"))

        click("episode_menu_11")
        compose.awaitTag("menu_toggle_played_11")
        assertTrue("no Retry download for an Episode that did not fail", !compose.exists("menu_retry_download_11"))
        click("menu_toggle_played_11")

        val before = app.audioRequests.count { it == "/missing/e13.wav" }
        click("episode_menu_13")
        click("menu_retry_download_13")
        compose.await("exactly one more request for Episode Three's audio") {
            app.audioRequests.count { it == "/missing/e13.wav" } == before + 1
        }
    }

    @Test
    fun aPlaybackErrorShowsItsMessageAndRetryAsksForThePlaybackAgain() {
        app.start()
        app.api.brokenAudio = true
        app.api.seedFeed()
        app.launch()
        click("tab_feeds")
        click("feed_row_1")
        click("episode_row_12")
        compose.await("the mini player says Playback failed") {
            compose.onAllNodes(hasTestTag("mini_player") and hasText("Playback failed")).fetchSemanticsNodes().isNotEmpty()
        }
        click("mini_player")
        compose.awaitTag("player_error")
        compose.awaitTag("player_retry")
        assertTrue("player_error is a polite live region", hasPoliteLiveRegion("player_error"))

        val before = app.audioRequests.count { it == "/missing/e12.wav" }
        compose.onNodeWithTag("player_retry").performClick()
        compose.await("the retry asked for the audio again") { app.audioRequests.count { it == "/missing/e12.wav" } > before }
        compose.awaitTag("player_error")
    }
}
