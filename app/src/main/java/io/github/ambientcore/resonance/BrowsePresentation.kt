package io.github.ambientcore.resonance

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class BrowseMode(val label: String, val columns: Int = 1) {
    LIST("List"), LIST_ICON("List with icon"), ICONS_SMALL("Icons small", 3),
    ICONS_LARGE("Icons large", 2), COMPACT("Compact list");

    val grid: Boolean get() = columns > 1
    val artwork: Boolean get() = this == LIST_ICON || grid
}

private val LocalBrowseActionSize = staticCompositionLocalOf { 48.dp }
val LocalBrowseSource = staticCompositionLocalOf { "" }

@Composable
fun BrowseActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    val size = LocalBrowseActionSize.current
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides size) {
        IconButton(onClick = onClick, modifier = modifier.size(size), enabled = enabled, content = content)
    }
}

@Composable
fun rememberBrowseMode(scope: String, default: BrowseMode = BrowseMode.LIST_ICON): Pair<BrowseMode, (BrowseMode) -> Unit> {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { context.getSharedPreferences("browse", Context.MODE_PRIVATE) }
    val key = LocalBrowseSource.current + scope
    var mode by rememberSaveable(key) {
        val saved = prefs.getString("mode:$key", default.name)
        val restored = BrowseMode.entries.firstOrNull { it.name == saved }
        if (restored == null) Log.w("ResonanceBrowse", "Unknown view mode for $scope: $saved")
        mutableStateOf(restored ?: default)
    }
    return mode to { mode = it; prefs.edit().putString("mode:$key", it.name).apply() }
}

@Composable
fun rememberBrowseExpanded(scope: String): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { context.getSharedPreferences("browse", Context.MODE_PRIVATE) }
    val key = LocalBrowseSource.current + scope
    var expanded by rememberSaveable(key) { mutableStateOf(prefs.getBoolean("expanded:$key", true)) }
    return expanded to { expanded = it; prefs.edit().putBoolean("expanded:$key", it).apply() }
}

@Composable
fun ViewModeMenu(scope: String, mode: BrowseMode, change: (BrowseMode) -> Unit, compact: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val size = if (compact) 40.dp else 48.dp
    Box {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides size) {
        IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); open = true }, modifier = Modifier.size(size).testTag("view-mode-$scope")
            .semantics { stateDescription = mode.label }) {
            Icon(Icons.Rounded.GridView, "View mode: ${mode.label}", tint = Muted)
        }
        }
        DropdownMenu(open, { open = false }) {
            BrowseMode.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) },
                    trailingIcon = { if (mode == option) Icon(Icons.Rounded.Check, null, tint = Lime) },
                    onClick = { change(option); open = false })
            }
        }
    }
}

@Composable
fun BrowseGrid(mode: BrowseMode, modifier: Modifier = Modifier, state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp), columns: Int = mode.columns,
    content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = modifier,
        state = state, contentPadding = contentPadding, content = content)
}

fun albumGridColumns(mode: BrowseMode): Int = when (mode) {
    BrowseMode.ICONS_SMALL -> 5
    BrowseMode.ICONS_LARGE -> 3
    else -> 1
}

fun LazyGridScope.fullItem(key: String? = null, content: @Composable LazyGridItemScope.() -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)
}

@Composable
fun BrowseItem(title: String, subtitle: String, mode: BrowseMode, modifier: Modifier = Modifier,
    song: Song? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null,
    leading: @Composable () -> Unit = {}, badges: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {}, denseArtwork: Boolean = false) {
    val compact = mode == BrowseMode.COMPACT
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    @Composable fun cover(modifier: Modifier) {
        if (icon == null) Artwork(song, modifier.testTag("browse-artwork"))
        else Box(modifier.clip(RoundedCornerShape(12.dp)).background(InputSurface)
            .testTag("browse-artwork"), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Lime, modifier = Modifier.fillMaxSize(.45f))
        }
    }
    if (mode.grid) {
        Column(modifier.padding(horizontal = if (denseArtwork) 2.dp else 8.dp, vertical = if (denseArtwork) 1.dp else 6.dp)
            .then(clickable).padding(if (denseArtwork) 0.dp else 4.dp)) {
            Box {
                cover(Modifier.fillMaxWidth().aspectRatio(1f))
                Box(Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(12.dp))
                    .background(Ink.copy(alpha = .85f))) { actions() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading()
                Text(title, fontSize = if (denseArtwork) 11.sp else 12.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = if (denseArtwork) 13.sp else LocalTextStyle.current.lineHeight,
                    maxLines = if (denseArtwork) 1 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = if (denseArtwork) 2.dp else 6.dp))
            }
            if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 10.sp,
                lineHeight = if (denseArtwork) 11.sp else LocalTextStyle.current.lineHeight,
                maxLines = if (denseArtwork) 1 else 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) { badges() }
        }
    } else CompositionLocalProvider(
        LocalBrowseActionSize provides if (compact) 28.dp else 36.dp,
        LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = 14.sp),
    ) {
        Row(modifier.fillMaxWidth().then(clickable)
            .padding(start = 22.dp, end = 8.dp, top = if (compact) 0.dp else 2.dp, bottom = if (compact) 0.dp else 2.dp)
            .heightIn(min = if (compact) 32.dp else 36.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            if (mode.artwork) { cover(Modifier.size(40.dp)); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                if (!compact && subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 12.sp,
                    lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            badges()
            actions()
        }
    }
}
