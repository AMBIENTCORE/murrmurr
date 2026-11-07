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
        val pairs = listOf(Ink to Lime, Ink to Pink, Ink to Cyan, Ink to Paper,
            Paper to Surface, Muted to Surface, Paper to InputSurface, Muted to InputSurface,
            Lime to Ink, Cyan to Surface)
        pairs.forEach { (text, background) ->
            val ratio = contrast(text, background)
            assertTrue("Text contrast must be at least 4.5:1; got $ratio", ratio >= 4.5f)
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (max(a, b) + .05f) / (min(a, b) + .05f)
    }
}
