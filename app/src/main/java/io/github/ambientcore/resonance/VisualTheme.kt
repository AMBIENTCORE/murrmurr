package io.github.ambientcore.resonance

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal const val THEME_PREF = "theme"

enum class AppTheme(val label: String) {
    DEFAULT("Default"), CINDER("Cinder");

    val colorScheme: ColorScheme get() = when (this) {
        DEFAULT -> PlayerColors
        CINDER -> CinderColors
    }
    val inputSurface: Color get() = when (this) {
        DEFAULT -> Color(0xFF1E2340)
        CINDER -> Color(0xFF1A1A1A)
    }
}

internal val PlayerColors = run {
    val ink = Color(0xFF160C24)
    val surface = Color(0xFF2C1B3C)
    val paper = Color(0xFFF0E8CF)
    val lime = Color(0xFFC3FF38)
    val pink = Color(0xFFFF63CA)
    val cyan = Color(0xFF60F5D6)
    darkColorScheme(
        primary = lime, onPrimary = ink, primaryContainer = lime, onPrimaryContainer = ink,
        secondary = pink, onSecondary = ink, secondaryContainer = Color(0xFF56203F), onSecondaryContainer = paper,
        tertiary = cyan, onTertiary = ink, background = ink, onBackground = paper,
        surface = surface, onSurface = paper, onSurfaceVariant = Color(0xFFC1B6CD),
        surfaceContainerLowest = ink, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = Color(0xFF382447), surfaceContainerHighest = Color(0xFF493257),
        outline = pink, outlineVariant = Color(0xFF665174),
        inverseSurface = Color(0xFFFFE969), inverseOnSurface = ink, inversePrimary = pink,
        error = Color(0xFFFF9288), onError = ink,
    )
}

internal val CinderColors = run {
    val ink = Color(0xFF101010)
    val surface = Color(0xFF232323)
    val paper = Color(0xFFE8E8E8)
    val cream = Color(0xFFFFECD1)
    darkColorScheme(
        primary = cream, onPrimary = ink, primaryContainer = cream, onPrimaryContainer = ink,
        secondary = Color(0xFFD6C6AF), onSecondary = ink,
        secondaryContainer = Color(0xFF3D362D), onSecondaryContainer = cream,
        tertiary = Color(0xFFE7D6BE), onTertiary = ink,
        tertiaryContainer = Color(0xFF333333), onTertiaryContainer = paper,
        background = ink, onBackground = paper,
        surface = surface, onSurface = paper,
        surfaceVariant = Color(0xFF303030), onSurfaceVariant = Color(0xFFB9B9B9),
        surfaceContainerLowest = ink, surfaceContainerLow = Color(0xFF1D1D1D),
        surfaceContainer = surface, surfaceContainerHigh = Color(0xFF303030),
        surfaceContainerHighest = Color(0xFF3C3C3C),
        outline = Color(0xFFAA9C89), outlineVariant = Color(0xFF505050),
        inverseSurface = cream, inverseOnSurface = ink, inversePrimary = Color(0xFF574A36),
        error = Color(0xFFE5B9AC), onError = ink,
        errorContainer = Color(0xFF49332D), onErrorContainer = cream,
    )
}

private val LocalInputSurface = staticCompositionLocalOf { AppTheme.DEFAULT.inputSurface }

@Composable
internal fun MurrmurrTheme(theme: AppTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInputSurface provides theme.inputSurface) {
        MaterialTheme(colorScheme = theme.colorScheme, typography = Typography(), content = content)
    }
}

// Existing palette names follow semantic roles so every screen uses the selected theme.
internal val Ink: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
internal val Surface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
internal val InputSurface: Color @Composable @ReadOnlyComposable get() = LocalInputSurface.current
internal val Paper: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
internal val Lime: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
internal val Pink: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.secondary
internal val Cyan: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.tertiary
internal val Yellow: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.inverseSurface
internal val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
internal fun playerInputColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = InputSurface, unfocusedContainerColor = InputSurface,
    focusedBorderColor = Cyan, unfocusedBorderColor = Pink,
    focusedLabelColor = Lime, unfocusedLabelColor = Muted,
    focusedTextColor = Paper, unfocusedTextColor = Paper, cursorColor = Lime,
)
