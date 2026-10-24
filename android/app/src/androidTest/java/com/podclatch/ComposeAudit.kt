package com.podclatch

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule

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
