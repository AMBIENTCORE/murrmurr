package io.github.ambientcore.resonance

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class VisualThemeTest {
    @Test fun restoredInterfaceRetainsTheApprovedColorPalette() {
        assertEquals(Color(0xFF160C24), PlayerColors.background)
        assertEquals(Color(0xFF2C1B3C), PlayerColors.surface)
        assertEquals(Color(0xFFC3FF38), PlayerColors.primary)
        assertEquals(Color(0xFFFF63CA), PlayerColors.secondary)
        assertEquals(Color(0xFF60F5D6), PlayerColors.tertiary)
        assertEquals(Color(0xFFF0E8CF), PlayerColors.onSurface)
        assertEquals(Color(0xFFC1B6CD), PlayerColors.onSurfaceVariant)
    }

    @Test fun enabledTextMaintainsReadableContrastAcrossPlayerPalette() {
        AppTheme.entries.forEach { theme ->
            val colors = theme.colorScheme
            val pairs = listOf(colors.onPrimary to colors.primary, colors.onSecondary to colors.secondary,
                colors.onTertiary to colors.tertiary, colors.onBackground to colors.background,
                colors.onSurface to colors.surface, colors.onSurfaceVariant to colors.surface,
                colors.onSurface to theme.inputSurface, colors.onSurfaceVariant to theme.inputSurface,
                colors.primary to colors.background, colors.tertiary to colors.surface,
                colors.onPrimaryContainer to colors.primaryContainer,
                colors.onSecondaryContainer to colors.secondaryContainer, colors.onError to colors.error)
            pairs.forEach { (text, background) ->
                val ratio = contrast(text, background)
                assertTrue("${theme.label} text contrast must be at least 4.5:1; got $ratio", ratio >= 4.5f)
            }
        }
    }

    @Test fun cinderUsesGreySurfacesAndTheLogoCreamAccent() {
        val colors = AppTheme.CINDER.colorScheme
        assertEquals(Color(0xFFFFECD1), colors.primary)
        listOf(colors.background, colors.surface, colors.surfaceContainerLowest, colors.surfaceContainerLow,
            colors.surfaceContainer, colors.surfaceContainerHigh, colors.surfaceContainerHighest,
            colors.onSurface, colors.onSurfaceVariant, AppTheme.CINDER.inputSurface).forEach { color ->
            assertEquals(color.red, color.green, 0f)
            assertEquals(color.red, color.blue, 0f)
        }
    }

    @Test fun defaultThemeRemainsTheFirstChoiceWithTheExistingPalette() {
        assertEquals(AppTheme.DEFAULT, AppTheme.entries.first())
        assertEquals(PlayerColors, AppTheme.DEFAULT.colorScheme)
        assertEquals(Color(0xFF1E2340), AppTheme.DEFAULT.inputSurface)
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (max(a, b) + .05f) / (min(a, b) + .05f)
    }
}
