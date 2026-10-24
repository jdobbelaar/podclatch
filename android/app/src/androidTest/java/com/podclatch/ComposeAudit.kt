package com.podclatch

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice

/**
 * The accessibility audit of US-20 part B, run on the semantics tree that a screen reader sees (the merged
 * tree): every node a user can act on (click, toggle, select, slide, type) has a text or a content
 * description, and a touch target of at least 48 dp by 48 dp. The touch bounds are the ones Android reports to
 * accessibility services, so Material 3's expanded minimum targets count.
 */
fun ComposeTestRule.auditProblems(screen: String, density: Float): List<String> {
    val minPx = 48f * density - 1f
    val problems = mutableListOf<String>()
    fun visit(node: SemanticsNode) {
        val config = node.config
        val interactive = config.contains(SemanticsActions.OnClick) ||
            config.contains(SemanticsProperties.ToggleableState) ||
            config.contains(SemanticsProperties.Selected) ||
            config.contains(SemanticsActions.SetProgress) ||
            config.contains(SemanticsActions.SetText)
        val disabled = config.contains(SemanticsProperties.Disabled)
        if (interactive && !disabled) {
            val name = config.getOrNull(SemanticsProperties.TestTag) ?: node.id.toString()
            val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
            val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
            val editable = config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            if (text.isBlank() && description.isBlank() && editable.isBlank() && !config.contains(SemanticsProperties.EditableText)) {
                problems += "$screen: '$name' is actionable but has no text and no content description"
            }
            val bounds = node.touchBoundsInRoot
            // A node scrolled completely out of its container reports empty bounds; tests scroll it into view.
            val clippedAway = bounds.width <= 0f || bounds.height <= 0f
            if (!clippedAway && (bounds.width < minPx || bounds.height < minPx)) {
                problems += "$screen: '$name' is ${"%.0f".format(bounds.width / density)} x ${"%.0f".format(bounds.height / density)} dp, below 48 dp"
            }
        }
        node.children.forEach(::visit)
    }
    onAllNodes(isRoot()).fetchSemanticsNodes().forEach(::visit)
    return problems
}

/**
 * The same audit as `uiautomator dump` sees it: every clickable node of the app, with the bounds the
 * accessibility service reports (clipped by whatever overlaps it, which the Compose touch bounds miss: the
 * Mini player's progress bar once covered the lower part of its play button). Each needs at least 48 dp in
 * both directions (names are checked on the Compose tree, where the merged text of children counts).
 */
fun uiAutomatorProblems(screen: String, density: Float): List<String> {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    device.findObject(By.text("Don't Show Again"))?.click()
    if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
    val minPx = 48f * density - 1f
    val problems = mutableListOf<String>()
    for (node in device.findObjects(By.pkg("com.podclatch").clickable(true))) {
        val name = node.resourceName ?: node.className
        val bounds = node.visibleBounds
        if (bounds.width() <= 0 || bounds.height() <= 0) continue // scrolled out of its container
        if (bounds.width() < minPx || bounds.height() < minPx) {
            problems += "$screen (dump): '$name' is ${"%.1f".format(bounds.width() / density)} x ${"%.1f".format(bounds.height() / density)} dp, below 48 dp"
        }
    }
    return problems
}
