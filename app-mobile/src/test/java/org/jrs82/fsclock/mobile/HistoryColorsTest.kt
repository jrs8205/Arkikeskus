package org.jrs82.fsclock.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryColorsTest {
    private fun assertContrast(ink: Color, surface: Color) {
        val a = ink.luminance()
        val b = surface.luminance()
        val contrast = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
        assertTrue("Contrast $contrast for $ink on $surface", contrast >= 4.5f)
    }

    @Test fun historyColorsMeetContrastInBothThemes() {
        for (scheme in listOf(LightColors, DarkColors)) {
            val colors = historyColors(scheme)
            val dark = scheme === DarkColors
            assertEquals(if (dark) scheme.background else scheme.surfaceContainer, colors.page)
            assertEquals(if (dark) scheme.surfaceContainer else scheme.surfaceContainerLowest, colors.card)
            assertEquals(if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainer, colors.pill)
            assertEquals(scheme.outlineVariant, colors.border)
            assertNotEquals(colors.page, colors.card)
            for (background in listOf(colors.page, colors.card, colors.pill)) {
                assertContrast(scheme.onSurface, background)
                assertContrast(scheme.onSurfaceVariant, background)
            }
            assertEquals(if (dark) scheme.onPrimaryContainer else scheme.primary, scheme.historyCarIcon)
            assertContrast(scheme.historyCarIcon, scheme.primaryContainer)
        }
        val wallpaper = LightColors.copy(surfaceContainer = LightColors.secondaryContainer,
            surfaceContainerLowest = LightColors.tertiaryContainer, outlineVariant = LightColors.secondary)
        assertEquals(HistoryColors(wallpaper.surfaceContainer, wallpaper.surfaceContainerLowest,
            wallpaper.surfaceContainer, wallpaper.outlineVariant), historyColors(wallpaper))
    }
}
