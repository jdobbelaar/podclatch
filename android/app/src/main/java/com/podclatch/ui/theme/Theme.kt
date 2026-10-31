package com.podclatch.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** The status-bar clock and icons are dark on the light theme and light on the dark theme. */
fun useDarkSystemBarIcons(darkTheme: Boolean): Boolean = !darkTheme

/**
 * Material 3 theme that follows the system dark/light setting. The same decision picks the colour scheme and
 * the status-bar icon style, so the two cannot disagree; it is re-applied whenever the setting changes.
 */
@Composable
fun PodClatchTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = useDarkSystemBarIcons(darkTheme)
        }
    }
    MaterialTheme(colorScheme = colorScheme) {
        Surface(content = content)
    }
}
