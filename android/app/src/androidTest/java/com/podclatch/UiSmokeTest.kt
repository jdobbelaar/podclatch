package com.podclatch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The UI smoke test (US-20 part C): subscribe, play from Feed Detail, and see the Episode in the Queue, in
 * the real app against a fake `PodClatchApi` (the debug build's API seam) and a local audio server inside the
 * test. It does not replace the full-stack test with the Compose stack (US-32, issue 29). Every wait is for an
 * exact end state with a timeout, and a failure prints the last state on the screen.
 */
class UiSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = AppHarness()

    @Before
    fun setUp() {
        app.start()
        app.launch()
    }

    @After
    fun tearDown() {
        app.stop()
    }

    @Test
    fun subscribePlayFromFeedDetailAndSeeTheEpisodeInTheQueue() {
        // (1) Configured and empty on the Queue tab.
        compose.awaitTag("queue_empty")

        // (2) Subscribe from the Feeds tab; the Feed appears there.
        compose.onNodeWithTag("tab_feeds").performClick()
        compose.awaitTag("subscribe_fab")
        compose.onNodeWithTag("subscribe_fab").performClick()
        compose.awaitTag("subscribe_url_field")
        compose.onNodeWithTag("subscribe_url_field").performTextInput("https://feeds.example.com/smoke.xml")
        compose.onNodeWithTag("subscribe_url_button").performClick()
        compose.awaitTag("feed_row_1")
        assertTrue("the fake server was asked to subscribe once", app.api.calls.count { it == "subscribe" } == 1)

        // (3) Open the Feed and tap the oldest Episode (the top-up downloads only the newest).
        compose.onNodeWithTag("feed_row_1").performClick()
        compose.awaitTag("episode_row_11")
        compose.onNodeWithTag("episode_row_11").performClick()

        // (4) Playback starts (the mini player shows the title) and the Episode's download starts.
        compose.await("the mini player shows Episode One") {
            compose.onAllNodes(hasTestTag("mini_player") and hasText("Episode One")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("mini_player").assertIsDisplayed()
        compose.await("the audio server was asked for Episode One") { "/e11.wav" in app.audioRequests }

        // (5) The Queue tab lists that Episode.
        compose.onNodeWithTag("nav_back").performClick()
        compose.awaitTag("tab_queue")
        compose.onNodeWithTag("tab_queue").performClick()
        compose.awaitTag("queue_item_11")
    }
}
