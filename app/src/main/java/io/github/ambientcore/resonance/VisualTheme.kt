package io.github.ambientcore.resonance

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val Ink = Color(0xFF160C24)
internal val Surface = Color(0xFF2C1B3C)
internal val InputSurface = Color(0xFF1E2340)
internal val Paper = Color(0xFFF0E8CF)
internal val Lime = Color(0xFFC3FF38)
internal val Pink = Color(0xFFFF63CA)
internal val Cyan = Color(0xFF60F5D6)
internal val Yellow = Color(0xFFFFE969)
internal val Muted = Color(0xFFC1B6CD)

internal val PlayerColors = darkColorScheme(
    primary = Lime, onPrimary = Ink, primaryContainer = Lime, onPrimaryContainer = Ink,
    secondary = Pink, onSecondary = Ink, secondaryContainer = Color(0xFF56203F), onSecondaryContainer = Paper,
    tertiary = Cyan, onTertiary = Ink, background = Ink, onBackground = Paper,
    surface = Surface, onSurface = Paper, onSurfaceVariant = Muted,
    surfaceContainerLowest = Ink, surfaceContainerLow = Surface, surfaceContainer = Surface,
    surfaceContainerHigh = Color(0xFF382447), surfaceContainerHighest = Color(0xFF493257),
    outline = Pink, outlineVariant = Color(0xFF665174),
    inverseSurface = Yellow, inverseOnSurface = Ink, inversePrimary = Pink,
    error = Color(0xFFFF9288), onError = Ink,
)

@Composable
internal fun playerInputColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = InputSurface, unfocusedContainerColor = InputSurface,
    focusedBorderColor = Cyan, unfocusedBorderColor = Pink,
    focusedLabelColor = Lime, unfocusedLabelColor = Muted,
    focusedTextColor = Paper, unfocusedTextColor = Paper, cursorColor = Lime,
)
