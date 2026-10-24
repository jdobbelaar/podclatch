package com.podclatch

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * US-20 part B, the audit: on every screen and state the criteria list (see [ScreenWalker]), no actionable
 * node lacks a name, and none has a touch target below 48 dp by 48 dp. Each test gathers every problem it
 * finds and fails once with the full list, so one run reports all of them.
 */
class AccessibilityAuditTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = AppHarness()
    private val density = app.context.resources.displayMetrics.density
    private val problems = mutableListOf<String>()
    private val walker = ScreenWalker(compose, app) { screen -> problems += compose.auditProblems(screen, density) }

    @After
    fun tearDown() {
        app.stop()
    }

    private fun assertClean() {
        assertTrue("Accessibility problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun theEmptyQueueAndTheEmptyFeedsTab() {
        walker.walkEmpty()
        assertClean()
    }

    @Test
    fun theConnectionScreen() {
        walker.walkConnection()
        assertClean()
    }

    @Test
    fun everyScreenWithData() {
        walker.walkData()
        assertClean()
    }
}
