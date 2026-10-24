package com.podclatch

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.podclatch.ui.queue.PlayedBarDark
import com.podclatch.ui.queue.PlayedBarLight
import com.podclatch.ui.queue.contrastRatio
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-20 part B: the colour pairs the app draws, in light and dark, against WCAG AA. The theme is the stock
 * Material 3 scheme (`ui/theme/Theme.kt`), and the pairs below are the ones the screens use: body text and
 * labels need 4.5 : 1, icons, large text, and the Queue playback bar's two colours need 3 : 1.
 *
 * Excused (cannot be checked in the JVM): artwork images, the Coil placeholders (decorative), and the
 * disabled states Material 3 draws at reduced alpha (WCAG exempts inactive components).
 */
class ContrastTest {
    private class ColorPair(val name: String, val foreground: Color, val background: Color, val minimum: Double)

    private fun ratio(a: Color, b: Color) = contrastRatio(a.toArgb().toLong() and 0xFFFFFFFFL, b.toArgb().toLong() and 0xFFFFFFFFL)

    private fun pairs(c: ColorScheme): List<ColorPair> = listOf(
        // Body text and labels, 4.5 : 1.
        ColorPair("onSurface on surface (titles, body)", c.onSurface, c.surface, 4.5),
        ColorPair("onSurface on background", c.onSurface, c.background, 4.5),
        ColorPair("onSurfaceVariant on surface (dates, feed names, hints)", c.onSurfaceVariant, c.surface, 4.5),
        ColorPair("onSurfaceVariant on surfaceContainerHigh (Played row)", c.onSurfaceVariant, c.surfaceContainerHigh, 4.5),
        ColorPair("onSurfaceVariant on secondaryContainer (now playing row)", c.onSurfaceVariant, c.secondaryContainer, 4.5),
        ColorPair("onSurface on secondaryContainer (now playing title)", c.onSurface, c.secondaryContainer, 4.5),
        ColorPair("primary on surface (New label, buttons)", c.primary, c.surface, 4.5),
        ColorPair("primary on secondaryContainer (Now playing label)", c.primary, c.secondaryContainer, 4.5),
        ColorPair("error on surface (playback and download errors)", c.error, c.surface, 4.5),
        ColorPair("onErrorContainer on errorContainer (banners)", c.onErrorContainer, c.errorContainer, 4.5),
        ColorPair("onPrimary on primary (unplayed badge, buttons)", c.onPrimary, c.primary, 4.5),
        ColorPair("onSecondaryContainer on secondaryContainer (swipe labels)", c.onSecondaryContainer, c.secondaryContainer, 4.5),
        ColorPair("onSurfaceVariant on surfaceVariant (zero badge)", c.onSurfaceVariant, c.surfaceVariant, 4.5),
        // Icons and non-text parts, 3 : 1.
        ColorPair("primary on surface (icons, New dot)", c.primary, c.surface, 3.0),
        ColorPair("onSurfaceVariant on surface (icons)", c.onSurfaceVariant, c.surface, 3.0),
        ColorPair("primary on secondaryContainer (playing icon)", c.primary, c.secondaryContainer, 3.0),
    )

    private fun check(c: ColorScheme, theme: String) {
        val failures = pairs(c).filter { ratio(it.foreground, it.background) < it.minimum }
            .map { "$theme: ${it.name} is ${"%.2f".format(ratio(it.foreground, it.background))}, needs ${it.minimum}" }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun everyLightPairMeetsAA() = check(lightColorScheme(), "light")

    @Test
    fun everyDarkPairMeetsAA() = check(darkColorScheme(), "dark")

    @Test
    fun thePlaybackBarsTwoColoursDifferByAtLeastThreeToOneInBothThemes() {
        assertTrue(contrastRatio(PlayedBarLight.unplayed, PlayedBarLight.played) >= 3.0)
        assertTrue(contrastRatio(PlayedBarDark.unplayed, PlayedBarDark.played) >= 3.0)
    }

    @Test
    fun theRatioFormulaMatchesTheKnownExtremes() {
        assertTrue(Math.abs(contrastRatio(0xFF000000, 0xFFFFFFFF) - 21.0) < 0.01)
        assertTrue(Math.abs(contrastRatio(0xFF777777, 0xFF777777) - 1.0) < 0.01)
    }
}
