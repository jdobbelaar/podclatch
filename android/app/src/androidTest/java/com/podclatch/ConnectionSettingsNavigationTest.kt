package com.podclatch

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the real app (no Compose test library) against a MockWebServer: opening Settings from the Feeds
 * tab and then pressing the Back button or system Back must return to the Feeds tab, not Queue, and the
 * Settings screen saves a changed setting at once. It points the app at the mock server through the
 * connection form in Settings itself, so it works from a fresh install and from an already configured one.
 */
class ConnectionSettingsNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var server: MockWebServer

    private val feedJson = """{"id":41,"feedUrl":"https://feeds.example.com/a.xml","title":"Nav Test Feed","author":"Nobody",
        "description":"","artworkUrl":"","priority":0,"listenOrder":"NEWEST_FIRST","lastRefreshedAt":null,"subscribedAt":1,"rev":3}"""
    private val episodeJson = """{"id":51,"feedId":41,"guid":"g-51","title":"Nav Test Episode","description":"",
        "audioUrl":"https://audio.example.com/51.mp3","publishedAt":2000,"durationSec":60,"artworkUrl":null,"status":"NEW",
        "playedAt":null,"positionSec":0,"stateUpdatedAt":1000,"rev":3}"""
    private val page = """{"feeds":[$feedJson],"episodes":[$episodeJson],"tombstones":[],"nextRev":3,"hasMore":false}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/healthz" -> json("""{"status":"ok"}""")
                "/v1/sync" -> json(page)
                else -> MockResponse.Builder().code(404).body("""{"detail":"no"}""").build()
            }
        }
        server.start()
        // The first Episode loaded in a process would ask for this at a moment the test cannot predict.
        device.executeShellCommand("pm grant com.podclatch android.permission.POST_NOTIFICATIONS")
        val context = instrumentation.targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        pointAppAtTheMockServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(body: String) =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun res(id: String): BySelector = By.res(id)

    /**
     * Waits like [UiDevice.wait] but, in short slices, drops the accessibility node cache of this test process
     * between them. The cache is invalidated by accessibility events from the app, and Compose throttles those
     * events, so a change (for example the Feeds tab becoming selected) can go unreported and the cache keeps
     * the old node state for good: the screen shows the new state while every query still sees the old one.
     * Every query after [clearCache] is fresh from the app, so this waits for the same end state, no weaker.
     */
    private fun <T> waitFresh(timeoutMs: Long, condition: androidx.test.uiautomator.SearchCondition<T>): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var result: T? = null
        while (System.currentTimeMillis() < deadline) {
            val slice = minOf(2_000L, maxOf(1L, deadline - System.currentTimeMillis()))
            result = device.wait(condition, slice)
            if (result != null && (result as Any) != false) return result
            clearCache()
        }
        return result
    }

    private fun clearCache() {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
    }

    private fun wait(selector: BySelector, timeoutMs: Long = 15_000): UiObject2? =
        waitFresh(timeoutMs, Until.findObject(selector))

    private fun fill(id: String, value: String) {
        click(res(id))
        // A fresh lookup for the write: the click may have recomposed the field.
        wait(res(id))!!.text = value
    }

    private fun openSettings() {
        click(res("overflow_menu"))
        // The menu is a popup window, which does not expose test tags; find the item by its text.
        click(By.text("Settings"))
        assertNotNull("Settings screen", wait(res("server_url")))
    }

    /** Connects the app to the mock server, from the connection screen if it is shown or from the menu. */
    private fun pointAppAtTheMockServer() {
        // The emulator image may show system dialogs on launch (a 16 KB page-size notice, "isn't
        // responding"); dismiss them while waiting for the app's first screen.
        val deadline = System.currentTimeMillis() + 60_000
        var onConnectionScreen: Boolean? = null
        while (onConnectionScreen == null && System.currentTimeMillis() < deadline) {
            device.findObject(By.text("Don't Show Again"))?.click()
            device.findObject(By.res("aerr_wait"))?.click()
            onConnectionScreen = when {
                device.hasObject(res("server_url")) -> true
                device.hasObject(res("overflow_menu")) -> false
                else -> null
            }
            if (onConnectionScreen == null) Thread.sleep(500)
        }
        assertNotNull("the app's first screen", onConnectionScreen)
        if (onConnectionScreen == false) openSettings()
        fill("server_url", server.url("/").toString().trimEnd('/'))
        fill("auth_token", "test-token")
        click(res("test_connection"))
        click(By.res("save_continue").enabled(true))
        // A different server asks for confirmation before the cache is wiped.
        device.wait(Until.findObject(By.text("Switch server")), 2_000)?.click()
        if (onConnectionScreen == false) {
            // Settings stays open after Save; wait for the saved message, then go back to the tabs.
            awaitPresent("the saved message", By.res("connection_message").text("Saved."))
            click(res("nav_back"))
        }
        assertNotNull("tabs after saving", wait(res("tab_queue")))
    }

    /** Waits for the exact end state with a fresh query each time, so no UiObject2 outlives a recomposition. */
    private fun awaitPresent(what: String, selector: BySelector, timeoutMs: Long = 15_000) {
        assertTrue("Not reached within $timeoutMs ms: $what", waitFresh(timeoutMs, Until.hasObject(selector)) == true)
    }

    private fun awaitGone(what: String, selector: BySelector, timeoutMs: Long = 15_000) {
        assertTrue("Not reached within $timeoutMs ms: $what", waitFresh(timeoutMs, Until.gone(selector)) == true)
    }

    /** Clicks a freshly found object, retrying when a recomposition invalidates it between the find and the click. */
    private fun click(selector: BySelector) {
        var last: Exception? = null
        repeat(5) {
            try {
                val target = wait(selector) ?: throw AssertionError("Not found: $selector")
                target.click()
                return
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                last = e
            }
        }
        throw AssertionError("Stale after 5 tries: $selector", last)
    }

    private fun openFeedsTab() {
        click(res("tab_feeds"))
        awaitPresent("the Feed row", res("feed_row_41"))
        awaitPresent("Feeds tab selected", By.res("tab_feeds").selected(true))
    }

    private fun assertBackOnFeedsTab() {
        awaitPresent("the Feed row after returning", res("feed_row_41"))
        awaitPresent("Feeds tab selected", By.res("tab_feeds").selected(true))
        awaitGone("the connection screen", res("server_url"))
    }

    @Test
    fun theBackButtonFromSettingsOnTheFeedsTabReturnsToTheFeedsTab() {
        openFeedsTab()
        openSettings()
        click(res("nav_back"))
        assertBackOnFeedsTab()
    }

    @Test
    fun systemBackFromSettingsOnTheFeedsTabReturnsToTheFeedsTab() {
        openFeedsTab()
        openSettings()
        device.pressBack()
        assertBackOnFeedsTab()
    }

    /** What a tap on the new-episodes notification sends. */
    private fun tapTheNotification() {
        val context = instrumentation.targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setAction(com.podclatch.ui.nav.ACTION_OPEN_QUEUE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun assertOnQueueTab() {
        awaitPresent("Queue tab selected", By.res("tab_queue").selected(true))
        awaitGone("the full player", res("player_close"))
        awaitPresent("the Queue content", res("queue_list"))
    }

    @Test
    fun theNotificationTapSelectsTheQueueFromTheFeedsTab() {
        openFeedsTab()
        tapTheNotification()
        assertOnQueueTab()
    }

    @Test
    fun theNotificationTapSelectsTheQueueFromFeedDetail() {
        openFeedsTab()
        click(res("feed_row_41"))
        awaitGone("the tab bar in Feed Detail", res("tab_feeds"))
        tapTheNotification()
        assertOnQueueTab()
    }

    /**
     * Presses Back until the Feeds tab is selected. The Episode may already be loaded from an earlier test
     * (the process outlives a test), in which case tapping its row can open the player first, so one Back
     * is not always enough to get from Feed Detail to the tab.
     */
    private fun backToTheFeedsTab() {
        repeat(3) {
            device.pressBack()
            if (waitFresh(4_000, Until.hasObject(By.res("tab_feeds").selected(true))) == true) return
        }
        awaitPresent("Feeds tab selected", By.res("tab_feeds").selected(true), timeoutMs = 1_000)
    }

    /** Opens the full player from the Queue tab (the case where a naive switchTab brings the Player back). */
    private fun openFullPlayerFromTheQueueTab() {
        openFeedsTab()
        click(res("feed_row_41"))
        click(res("episode_row_51"))
        awaitPresent("the mini player", res("mini_player"))
        backToTheFeedsTab() // Feed Detail to Feeds
        click(res("tab_queue"))
        awaitPresent("Queue tab selected", By.res("tab_queue").selected(true))
        click(res("mini_player"))
        awaitPresent("the full player", res("player_close"))
        awaitGone("the tab bar under the player", res("tab_queue"))
    }

    @Test
    fun theNotificationTapSelectsTheQueueFromTheFullPlayerOpenedOnTheQueueTab() {
        openFullPlayerFromTheQueueTab()
        device.pressHome()
        tapTheNotification()
        assertOnQueueTab()
    }

    @Test
    fun theNotificationTapSelectsTheQueueFromTheFullPlayerOpenedOnTheFeedsTab() {
        openFeedsTab()
        click(res("feed_row_41"))
        click(res("episode_row_51"))
        awaitPresent("the mini player", res("mini_player"))
        backToTheFeedsTab()
        click(res("mini_player"))
        awaitPresent("the full player", res("player_close"))
        device.pressHome()
        tapTheNotification()
        assertOnQueueTab()
    }

    @Test
    fun theBackButtonFromSettingsOnTheQueueTabReturnsToTheQueueTab() {
        click(res("tab_queue"))
        openSettings()
        click(res("nav_back"))
        awaitPresent("the Queue content", res("queue_list"))
        awaitPresent("Queue tab selected", By.res("tab_queue").selected(true))
    }

    @Test
    fun settingsShowsEverySectionInOrderWithoutTheBottomBar() {
        openSettings()
        awaitGone("the tab bar under Settings", res("tab_queue"))
        for (tag in listOf("auth_token", "setting_skip_back", "setting_skip_forward", "setting_assigned_speed", "setting_wifi_only")) {
            awaitPresent("the control $tag", res(tag))
        }
        device.pressBack()
        awaitPresent("Queue tab selected", By.res("tab_queue").selected(true))
    }

    @Test
    fun aChoiceIsSavedAtOnceAndShownInTheRow() {
        openSettings()
        val original = 15 // the default; the test starts from a fresh install or one it has restored
        try {
            click(res("setting_skip_back"))
            click(res("setting_skip_back_option_10"))
            awaitPresent("the row shows 10 seconds", By.res("setting_skip_back").desc("Skip back, 10 seconds"))
        } finally {
            click(res("setting_skip_back"))
            click(res("setting_skip_back_option_$original"))
            awaitPresent("the row shows $original seconds", By.res("setting_skip_back").desc("Skip back, $original seconds"))
        }
    }

    @Test
    fun aSwitchTogglesFromItsLabelAndStoresTheChoice() {
        openSettings()
        val before = waitFresh(10_000, Until.findObject(res("setting_wifi_only")))!!.isChecked
        click(res("setting_wifi_only"))
        awaitPresent("the switch flipped", By.res("setting_wifi_only").checked(!before))
        click(res("setting_wifi_only"))
        awaitPresent("the switch restored", By.res("setting_wifi_only").checked(before))
    }
}
