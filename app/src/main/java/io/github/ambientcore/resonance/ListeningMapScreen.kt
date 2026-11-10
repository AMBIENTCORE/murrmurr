package io.github.ambientcore.resonance

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class MapPreview(val group: LibraryGroup, val explanation: String = "", val neighbor: MapNode? = null)

@Stable
private class MapViewState(zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f) {
    var zoom by mutableFloatStateOf(zoom)
    var panX by mutableFloatStateOf(panX)
    var panY by mutableFloatStateOf(panY)

    fun reset() { zoom = 1f; panX = 0f; panY = 0f }

    companion object {
        val Saver = listSaver<MapViewState, Float>(
            save = { listOf(it.zoom, it.panX, it.panY) },
            restore = { MapViewState(it[0], it[1], it[2]) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListeningMapScreen(
    songs: List<Song>, cache: ListeningMapCache,
    play: (List<Song>, Int, Boolean) -> Unit, queue: (List<Song>) -> Unit,
    openAlbum: (LibraryGroup) -> Unit, history: ListeningHistory = ListeningHistory(),
    initialArtist: String? = null,
) {
    var artist by rememberSaveable { mutableStateOf(initialArtist) }
    var artistLabel by rememberSaveable { mutableStateOf(initialArtist.orEmpty()) }
    var mode by rememberSaveable(artist) { mutableStateOf(MapMode.CONNECTIONS) }
    var genreLinks by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(artist, mode) { mutableStateOf("") }
    var pageNumber by rememberSaveable(artist, mode, query, genreLinks) { mutableIntStateOf(0) }
    var selectedAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedConnection by rememberSaveable { mutableStateOf<String?>(null) }
    val previewOpen = selectedAlbum != null || selectedConnection != null
    val viewKey = "$artist:$mode:$genreLinks:$pageNumber:$query"
    val view = rememberSaveable(viewKey, saver = MapViewState.Saver) { MapViewState() }
    var page by remember { mutableStateOf<MapPage?>(null) }
    var preview by remember { mutableStateOf<MapPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() { focus.clearFocus(); keyboard?.hide() }
    fun closePreview() { selectedAlbum = null; selectedConnection = null }
    fun chooseArtist(node: MapNode?) {
        closePreview(); artist = node?.id; artistLabel = node?.name.orEmpty(); dismissKeyboard()
    }
    BackHandler(previewOpen || artist != null) {
        when { previewOpen -> closePreview()
            mode == MapMode.ALBUMS -> mode = MapMode.CONNECTIONS
            else -> chooseArtist(null) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("listening-map")) {
        val wide = maxWidth >= 840.dp
        val connections = artist != null && mode == MapMode.CONNECTIONS
        val pageSize = if (connections && !wide && maxHeight < 540.dp) 2
            else (if (wide) 12 else 8) - if (connections) 1 else 0
        val request = MapRequest(artist, query, pageNumber,
            pageSize, mode, genreLinks)
        LaunchedEffect(songs, request, history) {
            page = null
            error = null
            try {
                page = cache.page(songs, request, history)
                if (connections) page?.center?.let { artistLabel = it.name }
            }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.e("ResonanceMap", "Cannot build listening map", failure)
                error = "Could not build listening map: ${failure.message}"
            }
        }
        LaunchedEffect(songs, history, selectedAlbum, selectedConnection, page) {
            preview = null
            if (!previewOpen || page == null) return@LaunchedEffect
            val id = selectedAlbum
            if (id != null) {
                val tracks = withContext(Dispatchers.Default) { mapSongs(songs, artist, id) }
                preview = tracks.firstOrNull()?.let { MapPreview(LibraryGroup(id, it.album, it.albumArtist, tracks)) }
            } else {
                val center = artist ?: return@LaunchedEffect
                val neighbor = page?.nodes?.firstOrNull { it.id == selectedConnection }
                val link = page?.links?.firstOrNull { it.artist == selectedConnection }
                if (neighbor != null && link != null) {
                    val tracks = withContext(Dispatchers.Default) {
                        val context = kotlinx.coroutines.currentCoroutineContext()
                        mapConnectionSongs(songs, center, neighbor.id, history, genreLinks) { context.ensureActive() }
                    }
                    if (tracks.isNotEmpty()) preview = MapPreview(
                        LibraryGroup("connection:$center:${neighbor.id}", "$artistLabel / ${neighbor.name}", "Supporting music", tracks),
                        link.explanation, neighbor)
                }
            }
            if (preview == null) {
                error = "This album or connection is no longer supported by the current library/history."
                closePreview()
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (artist != null) IconButton(onClick = { chooseArtist(null) }, modifier = Modifier.testTag("map-all-artists")) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Choose another artist")
                }
                Column(Modifier.weight(1f)) {
                    Text(if (artist == null) "Choose a starting artist" else page?.center?.name ?: artistLabel,
                        fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("map-heading"))
                    Text(if (artist == null) "Real connections, not a Library hub" else "Tap a link to see the supporting music",
                        color = Muted, fontSize = 11.sp)
                }
            }
            if (artist != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(mode == MapMode.CONNECTIONS, { closePreview(); mode = MapMode.CONNECTIONS },
                    label = { Text("Connections") }, modifier = Modifier.testTag("map-mode-connections"))
                FilterChip(mode == MapMode.ALBUMS, { closePreview(); mode = MapMode.ALBUMS },
                    label = { Text("Albums") }, modifier = Modifier.testTag("map-mode-albums"))
                if (connections) FilterChip(genreLinks, { closePreview(); genreLinks = !genreLinks },
                    label = { Text("Genre links", fontSize = 11.sp) }, modifier = Modifier.testTag("map-genre-links"))
            }
            CompactSearchField(query, if (artist == null) "Find an artist" else if (connections) "Filter connections" else "Find an album",
                { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                "map-search", "Clear map search")
            error?.let { Text(it, Modifier.padding(16.dp).testTag("map-error"), color = MaterialTheme.colorScheme.error) }
            val current = page
            when {
                current == null && error == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp).testTag("map-loading"), color = Lime)
                }
                current != null -> {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (current.nodes.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(when {
                                    songs.isEmpty() -> "Scan or refresh your library to explore it."
                                    connections && query.isBlank() -> "No evidenced connections yet. Try Albums, choose another artist, or enable Genre links. Listening links need two adjacent-play pairs, each within 30 minutes."
                                    else -> "No matches"
                                },
                                    color = Muted, modifier = Modifier.padding(24.dp).testTag("map-empty"))
                            } else if (connections && current.center != null) {
                                MapViewport(current, view, Modifier.weight(1f).fillMaxWidth(),
                                    { mode = MapMode.ALBUMS }) { node ->
                                    dismissKeyboard(); selectedConnection = node.id
                                }
                            } else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("map-picker")) {
                                itemsIndexed(current.nodes, key = { _, node -> node.id }) { _, node ->
                                    ListItem(headlineContent = { Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text("${node.tracks} tracks / ${mapDiscoveryLabel(node)}") },
                                        leadingContent = { Icon(if (artist == null) Icons.Rounded.Person else Icons.Rounded.Album, null, tint = Lime) },
                                        modifier = Modifier.clickable(role = Role.Button) {
                                            dismissKeyboard()
                                            if (artist == null) chooseArtist(node) else selectedAlbum = node.id
                                        }.testTag("map-node-${node.id}"))
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${current.matches} ${if (artist == null) "artists" else if (connections) "connections" else "albums"}",
                                    Modifier.weight(1f).testTag("map-count"), color = Muted, fontSize = 12.sp)
                                IconButton(onClick = { closePreview(); pageNumber = current.page - 1; dismissKeyboard() },
                                    enabled = current.page > 0, modifier = Modifier.testTag("map-page-previous")) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous map page")
                                }
                                Text("${current.page + 1} / ${current.pageCount}", fontSize = 12.sp,
                                    modifier = Modifier.testTag("map-page"))
                                IconButton(onClick = { closePreview(); pageNumber = current.page + 1; dismissKeyboard() },
                                    enabled = current.page + 1 < current.pageCount, modifier = Modifier.testTag("map-page-next")) {
                                    Icon(Icons.AutoMirrored.Rounded.NavigateNext, "Next map page")
                                }
                            }
                            Text(if (connections) "Shared albums / repeated adjacent plays. Genre tags are optional, not similarity claims."
                                else "Choose an artist to explore connections, or an album to play it.",
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Muted, fontSize = 10.sp, lineHeight = 13.sp)
                        }
                        if (wide && previewOpen) {
                            Box(Modifier.width(310.dp).fillMaxHeight().background(InputSurface)) {
                                preview?.let { MapAlbumDetails(it, play, queue, openAlbum, ::closePreview, ::chooseArtist) }
                            }
                        }
                    }
                }
            }
        }
        if (!wide && previewOpen) {
            ModalBottomSheet(onDismissRequest = ::closePreview, containerColor = Surface) {
                Box(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    preview?.let { MapAlbumDetails(it, play, queue, openAlbum, ::closePreview, ::chooseArtist) }
                        ?: CircularProgressIndicator(Modifier.padding(24.dp))
                }
            }
        }
    }
}

@Composable
private fun MapViewport(page: MapPage, view: MapViewState, modifier: Modifier,
    openAlbums: () -> Unit, select: (MapNode) -> Unit) {
    val nodes = page.nodes
    val center = checkNotNull(page.center)
    val density = LocalDensity.current
    val transform = rememberTransformableState { scale, pan, _ ->
        view.zoom = (view.zoom * scale).coerceIn(.75f, 3f)
        view.panX = (view.panX + pan.x / density.density).coerceIn(-1000f, 1000f)
        view.panY = (view.panY + pan.y / density.density).coerceIn(-1000f, 1000f)
    }
    val zoom = view.zoom
    val colors = page.links.map { when {
        it.albums > 0 -> Cyan
        it.adjacentPlays >= 2 -> Pink
        else -> Lime
    } }
    BoxWithConstraints(modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(18.dp))
        .background(InputSurface).clipToBounds().transformable(transform)
        .testTag("listening-map-graph").semantics {
            contentDescription = "Evidence-based artist connections"
            stateDescription = "Zoom ${(zoom * 100).roundToInt()}%; ${nodes.size + 1} nodes"
        }) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val labelWidth = with(density) { 106.dp.toPx() }
        val labelHeight = with(density) { 72.dp.toPx() }
        val origin = Offset(width / 2, height / 2)
        val radiusX = (width / 2 - labelWidth / 2 - 12 * density.density).coerceAtLeast(0f)
        val radiusY = (height / 2 - labelHeight / 2 - 52 * density.density).coerceAtLeast(0f)
        val points = remember(nodes, width, height, zoom) {
            nodes.indices.map { index ->
                val angle = if (nodes.size <= 2) index * PI else -PI / 2 + index * 2 * PI / nodes.size
                origin + Offset((cos(angle) * radiusX * zoom).toFloat(), (sin(angle) * radiusY * zoom).toFloat())
            }
        }
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = view.panX * density.density
            translationY = view.panY * density.density
        }) {
            Canvas(Modifier.fillMaxSize()) {
                points.forEachIndexed { index, point ->
                    drawLine(colors[index].copy(alpha = .6f), origin, point, 2 * density.density)
                }
            }
            nodes.forEachIndexed { index, node ->
                val point = points[index]
                val link = page.links[index]
                val color = colors[index]
                val middle = (origin + point) / 2f
                Box(Modifier.offset { IntOffset((middle.x - 24 * density.density).roundToInt(),
                    (middle.y - 24 * density.density).roundToInt()) }.size(48.dp)
                    .clickable(role = Role.Button) { select(node) }.testTag("map-link-${node.id}")
                    .semantics { contentDescription = "Why ${center.name} connects to ${node.name}: ${link.label}" },
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(InputSurface)
                        .border(1.dp, color, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Link, null, Modifier.size(12.dp), tint = color)
                    }
                }
                Column(Modifier.offset { IntOffset((point.x - labelWidth / 2).roundToInt(),
                    (point.y - labelHeight / 2).roundToInt()) }.size(106.dp, 72.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Surface)
                    .border(1.dp, color.copy(alpha = .6f), RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { select(node) }.testTag("map-node-${node.id}")
                    .semantics { contentDescription = "${node.name}: ${link.label}. ${mapDiscoveryLabel(node)}" }
                    .padding(horizontal = 8.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(node.name, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(link.label, fontSize = 10.sp, lineHeight = 12.sp, color = color, maxLines = 1)
                    Text(mapDiscoveryLabel(node), fontSize = 9.sp, lineHeight = 11.sp, color = Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.offset { IntOffset((origin.x - labelWidth / 2).roundToInt(),
                (origin.y - 28 * density.density).roundToInt()) }.size(106.dp, 56.dp)
                .clip(RoundedCornerShape(16.dp)).background(Surface)
                .border(1.dp, Paper, RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = openAlbums)
                .testTag("map-focus").padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(center.name, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Browse albums", color = Muted, fontSize = 10.sp)
            }
        }
        Row(Modifier.align(Alignment.BottomStart).background(InputSurface.copy(alpha = .92f)),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { view.zoom = (view.zoom / 1.25f).coerceAtLeast(.75f) },
                modifier = Modifier.testTag("map-zoom-out")) {
                Icon(Icons.Rounded.Remove, "Zoom out")
            }
            IconButton(onClick = view::reset, modifier = Modifier.testTag("map-reset")) {
                Icon(Icons.Rounded.CenterFocusStrong, "Reset map view")
            }
            IconButton(onClick = { view.zoom = (view.zoom * 1.25f).coerceAtMost(3f) },
                modifier = Modifier.testTag("map-zoom-in")) {
                Icon(Icons.Rounded.Add, "Zoom in")
            }
        }
    }
}

@Composable
private fun MapAlbumDetails(preview: MapPreview, play: (List<Song>, Int, Boolean) -> Unit,
    queue: (List<Song>) -> Unit, openAlbum: (LibraryGroup) -> Unit, close: () -> Unit,
    focusArtist: (MapNode?) -> Unit) {
    val album = preview.group
    LazyColumn(Modifier.fillMaxSize().testTag("map-album-details"), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(album.songs.first(), Modifier.size(64.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(album.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(album.subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = close) {
                    Icon(Icons.Rounded.Close, if (preview.neighbor == null) "Close album preview" else "Close connection preview")
                }
            }
            if (preview.explanation.isNotBlank()) Text(preview.explanation,
                Modifier.padding(top = 12.dp).testTag("map-evidence"), fontSize = 12.sp, color = Muted)
            Text("${album.songs.size} tracks / ${durationLabel(album.songs.sumOf { it.duration })}",
                Modifier.padding(vertical = 8.dp), color = Muted, fontSize = 12.sp)
            Row {
                FilledTonalButton(onClick = { play(album.songs, 0, false) }, modifier = Modifier.testTag("map-play")) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Text("Play")
                }
                TextButton(onClick = { play(album.songs, 0, true) }, modifier = Modifier.testTag("map-shuffle")) { Text("Shuffle") }
                TextButton(onClick = { queue(album.songs) }, modifier = Modifier.testTag("map-queue")) { Text("Queue") }
            }
            preview.neighbor?.let { neighbor ->
                TextButton(onClick = { focusArtist(neighbor) }, modifier = Modifier.testTag("map-focus-neighbor")) {
                    Text("Explore ${neighbor.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = { openAlbum(album) }, modifier = Modifier.testTag("map-open-album")) {
                Text(if (preview.neighbor == null) "Open album" else "Open tracks")
            }
        }
        itemsIndexed(album.songs, key = { index, song -> "${song.uri}:$index" }) { index, song ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button) { play(album.songs, index, false) }
                .testTag("map-track-${song.uri}").padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", Modifier.width(28.dp), color = Muted, fontSize = 11.sp)
                Text(song.title, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(durationLabel(song.duration), color = Muted, fontSize = 11.sp)
            }
        }
    }
}
