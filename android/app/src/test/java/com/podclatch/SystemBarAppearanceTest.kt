package com.podclatch

import com.podclatch.ui.theme.useDarkSystemBarIcons
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins where edge-to-edge and the status-bar icon style are set (#99). */
class SystemBarAppearanceTest {
    @Test fun lightThemeGetsDarkIcons() = assertTrue(useDarkSystemBarIcons(darkTheme = false))

    @Test fun darkThemeGetsLightIcons() = assertFalse(useDarkSystemBarIcons(darkTheme = true))

    @Test fun themeAppliesIconStyleFromTheSameDecisionAsTheColourScheme() {
        val src = File("src/main/java/com/podclatch/ui/theme/Theme.kt").readText()
        assertTrue(src.contains("isAppearanceLightStatusBars = useDarkSystemBarIcons(darkTheme)"))
        assertTrue(src.contains("if (darkTheme) darkColorScheme()"))
    }

    @Test fun activityEnablesEdgeToEdge() {
        val src = File("src/main/java/com/podclatch/MainActivity.kt").readText()
        assertTrue(src.contains("enableEdgeToEdge()"))
    }
}
