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
 * Drives the real app (no Compose test library) against a MockWebServer: opening "Connection settings"
 * from the Feeds tab and then pressing Cancel or system Back must return to the Feeds tab, not Queue.
 * It points the app at the mock server through the connection screen itself, so it works from a fresh
 * install and from an already configured one.
 */
class ConnectionSettingsNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var server: MockWebServer

    private val feedJson = """{"id":41,"feedUrl":"https://feeds.example.com/a.xml","title":"Nav Test Feed","author":"Nobody",
        "description":"","artworkUrl":"","priority":0,"listenOrder":"NEWEST_FIRST","lastRefreshedAt":null,"subscribedAt":1,"rev":3}"""
    private val page = """{"feeds":[$feedJson],"episodes":[],"tombstones":[],"nextRev":3,"hasMore":false}"""

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

    private fun wait(selector: BySelector, timeoutMs: Long = 15_000): UiObject2? =
        device.wait(Until.findObject(selector), timeoutMs)

    private fun fill(id: String, value: String) {
        val field = wait(res(id))!!
        field.click()
        field.text = value
    }

    private fun openConnectionSettings() {
        wait(res("overflow_menu"))!!.click()
        // The menu is a popup window, which does not expose test tags; find the item by its text.
        wait(By.text("Connection settings"))!!.click()
        assertNotNull("connection screen", wait(res("server_url")))
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
        if (onConnectionScreen == false) openConnectionSettings()
        fill("server_url", server.url("/").toString().trimEnd('/'))
        fill("auth_token", "test-token")
        wait(res("test_connection"))!!.click()
        val save = wait(By.res("save_continue").enabled(true))!!
        save.click()
        // A different server asks for confirmation before the cache is wiped.
        device.wait(Until.findObject(By.text("Switch server")), 2_000)?.click()
        assertNotNull("tabs after saving", wait(res("tab_queue")))
    }

    private fun openFeedsTab() {
        wait(res("tab_feeds"))!!.click()
        assertNotNull("the Feed row", wait(res("feed_row_41")))
        assertTrue(wait(res("tab_feeds"))!!.isSelected)
    }

    private fun assertBackOnFeedsTab() {
        assertNotNull("the Feed row after returning", wait(res("feed_row_41")))
        assertTrue("Feeds tab selected", wait(res("tab_feeds"))!!.isSelected)
        assertNull("not on the connection screen", device.findObject(res("server_url")))
    }

    @Test
    fun cancelFromTheFeedsTabReturnsToTheFeedsTab() {
        openFeedsTab()
        openConnectionSettings()
        wait(res("connection_cancel"))!!.click()
        assertBackOnFeedsTab()
    }

    @Test
    fun systemBackFromTheFeedsTabReturnsToTheFeedsTab() {
        openFeedsTab()
        openConnectionSettings()
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
        val tab = wait(By.res("tab_queue").selected(true))
        assertNotNull("Queue tab selected", tab)
        assertNotNull(wait(res("queue_list")) ?: wait(res("queue_empty")))
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
        wait(res("feed_row_41"))!!.click()
        assertNull("the tab bar is hidden in Feed Detail", device.wait(Until.findObject(res("tab_feeds")), 1_000))
        tapTheNotification()
        assertOnQueueTab()
    }

    @Test
    fun theQueueTabAlsoReturnsToTheQueueTab() {
        wait(res("tab_queue"))!!.click()
        openConnectionSettings()
        wait(res("connection_cancel"))!!.click()
        assertNotNull(wait(res("queue_list")) ?: wait(res("queue_empty")))
        assertTrue(wait(res("tab_queue"))!!.isSelected)
    }
}
