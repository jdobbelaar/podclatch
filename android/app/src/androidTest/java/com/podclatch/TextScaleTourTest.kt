package com.podclatch

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * US-20 part B, text scaling: walks every screen (see [ScreenWalker]) at whatever font scale and theme the
 * device is set to, saves a screenshot of each under the app's `files/tour/` folder (name: scale, theme, step,
 * screen) for QA to look at, and checks the one thing a JVM-free test can judge exactly: the Sync status line
 * on the Queue tab shows all of its text (no ellipsis, nothing clipped), at any font scale.
 */
class TextScaleTourTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = AppHarness()
    private val configuration: Configuration = app.context.resources.configuration
    private val scale = "%.1f".format(configuration.fontScale)
    private val theme = if (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    private val dir = File(app.context.getExternalFilesDir(null), "tour").also { it.mkdirs() }
    private var step = 0
    private val syncChecked = mutableListOf<String>()

    private val walker = ScreenWalker(compose, app) { screen ->
        if (screen.startsWith("Queue") && compose.exists("sync_status")) {
            val result = mutableListOf<TextLayoutResult>()
            val node = compose.onNodeWithTag("sync_status").fetchSemanticsNode()
            compose.runOnUiThread { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(result) }
            assertTrue("sync_status has a text layout at $screen", result.isNotEmpty())
            assertFalse("sync_status shows all of its text at font scale $scale at $screen", result.first().hasVisualOverflow)
            syncChecked += screen
        }
        shot(screen)
    }

    @After
    fun tearDown() {
        app.stop()
    }

    private fun shot(screen: String) {
        val bitmap = app.context.let {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        } ?: return
        val slug = screen.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        File(dir, "%s_%s_%02d_%s.png".format(scale, theme, step++, slug)).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, it)
        }
    }

    @Test
    fun theEmptyScreens() {
        walker.walkEmpty()
        assertTrue("the Sync status line was checked", syncChecked.isNotEmpty())
    }

    @Test
    fun theConnectionScreen() {
        walker.walkConnection()
    }

    @Test
    fun everyScreenWithData() {
        walker.walkData()
        assertTrue("the Sync status line was checked", syncChecked.isNotEmpty())
    }
}
