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
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    openAlbum: (LibraryGroup) -> Unit,
) {
    var artist by rememberSaveable { mutableStateOf<String?>(null) }
    var artistLabel by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable(artist) { mutableStateOf("") }
    var pageNumber by rememberSaveable(artist, query) { mutableIntStateOf(0) }
    var selectedAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    val viewKey = "$artist:$pageNumber:$query"
    val view = rememberSaveable(viewKey, saver = MapViewState.Saver) { MapViewState() }
    var page by remember { mutableStateOf<MapPage?>(null) }
    var album by remember { mutableStateOf<LibraryGroup?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() { focus.clearFocus(); keyboard?.hide() }
    fun allArtists() { artist = null; artistLabel = ""; selectedAlbum = null; dismissKeyboard() }
    BackHandler(selectedAlbum != null || artist != null) {
        if (selectedAlbum != null) selectedAlbum = null else allArtists()
    }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("listening-map")) {
        val wide = maxWidth >= 840.dp
        val request = MapRequest(artist, query, pageNumber, if (wide) 12 else 8)
        LaunchedEffect(songs, request) {
            page = null
            error = null
            try { page = cache.page(songs, request) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.e("ResonanceMap", "Cannot build listening map", failure)
                error = "Could not build listening map: ${failure.message}"
            }
        }
        LaunchedEffect(songs, selectedAlbum) {
            album = null
            val id = selectedAlbum ?: return@LaunchedEffect
            val tracks = withContext(Dispatchers.Default) { mapSongs(songs, artist, id) }
            album = tracks.firstOrNull()?.let { LibraryGroup(id, it.album, it.albumArtist, tracks) }
            if (tracks.isEmpty()) {
                error = "This album is no longer in the library. Refresh the map and try again."
                selectedAlbum = null
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (artist != null) IconButton(onClick = ::allArtists, modifier = Modifier.testTag("map-all-artists")) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "All artists")
                }
                Column(Modifier.weight(1f)) {
                    Text(if (artist == null) "Your collection, connected" else artistLabel,
                        fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("map-heading"))
                    Text(if (artist == null) "Artist credits, not genre guesses" else "Explore this artist's albums",
                        color = Muted, fontSize = 11.sp)
                }
            }
            CompactSearchField(query, if (artist == null) "Find an artist" else "Find an album",
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
                                Text(if (songs.isEmpty()) "Scan or refresh your library to explore it." else "No matches",
                                    color = Muted, modifier = Modifier.padding(24.dp).testTag("map-empty"))
                            } else MapViewport(current.nodes, artistLabel.ifBlank { "Your library" },
                                artist != null, view, Modifier.weight(1f).fillMaxWidth()) { node ->
                                dismissKeyboard()
                                if (artist == null) {
                                    artist = node.id
                                    artistLabel = node.name
                                } else selectedAlbum = node.id
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${current.matches} ${if (artist == null) "artists" else "albums"}",
                                    Modifier.weight(1f).testTag("map-count"), color = Muted, fontSize = 12.sp)
                                IconButton(onClick = { pageNumber = current.page - 1; dismissKeyboard() },
                                    enabled = current.page > 0, modifier = Modifier.testTag("map-page-previous")) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous map page")
                                }
                                Text("${current.page + 1} / ${current.pageCount}", fontSize = 12.sp,
                                    modifier = Modifier.testTag("map-page"))
                                IconButton(onClick = { pageNumber = current.page + 1; dismissKeyboard() },
                                    enabled = current.page + 1 < current.pageCount, modifier = Modifier.testTag("map-page-next")) {
                                    Icon(Icons.AutoMirrored.Rounded.NavigateNext, "Next map page")
                                }
                            }
                            Text("Pinch to zoom, drag to pan. Lines show library / album membership.",
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Muted, fontSize = 10.sp, lineHeight = 13.sp)
                        }
                        if (wide && selectedAlbum != null) {
                            Box(Modifier.width(310.dp).fillMaxHeight().background(InputSurface)) {
                                album?.let { MapAlbumDetails(it, play, queue, openAlbum, { selectedAlbum = null }) }
                            }
                        }
                    }
                }
            }
        }
        if (!wide && selectedAlbum != null) {
            ModalBottomSheet(onDismissRequest = { selectedAlbum = null }, containerColor = Surface) {
                Box(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    album?.let { MapAlbumDetails(it, play, queue, openAlbum, { selectedAlbum = null }) }
                        ?: CircularProgressIndicator(Modifier.padding(24.dp))
                }
            }
        }
    }
}

@Composable
private fun MapViewport(nodes: List<MapNode>, centerLabel: String, albums: Boolean,
    view: MapViewState, modifier: Modifier, select: (MapNode) -> Unit) {
    val density = LocalDensity.current
    val transform = rememberTransformableState { scale, pan, _ ->
        view.zoom = (view.zoom * scale).coerceIn(.75f, 3f)
        view.panX = (view.panX + pan.x / density.density).coerceIn(-1000f, 1000f)
        view.panY = (view.panY + pan.y / density.density).coerceIn(-1000f, 1000f)
    }
    val zoom = view.zoom
    val panX = view.panX
    val panY = view.panY
    val lineColor = Muted.copy(alpha = .3f)
    val accent = if (albums) Cyan else Lime
    BoxWithConstraints(modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(18.dp))
        .background(InputSurface).clipToBounds().transformable(transform)
        .testTag("listening-map-graph").semantics {
            contentDescription = if (albums) "Artist album map" else "Library artist map"
            stateDescription = "Zoom ${(zoom * 100).roundToInt()}%; ${nodes.size} nodes"
        }) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val labelWidth = with(density) { 106.dp.toPx() }
        val labelHeight = with(density) { 64.dp.toPx() }
        val origin = Offset(width / 2 + panX * density.density, height / 2 + panY * density.density)
        val radiusX = (width / 2 - labelWidth / 2 - 12 * density.density).coerceAtLeast(0f)
        val radiusY = (height / 2 - labelHeight / 2 - 30 * density.density).coerceAtLeast(0f)
        val largest = remember(nodes) { nodes.maxOf { it.tracks }.coerceAtLeast(1) }
        val points = remember(nodes, width, height, zoom, panX, panY) {
            nodes.indices.map { index ->
                val angle = -PI / 2 + index * 2 * PI / nodes.size
                origin + Offset((cos(angle) * radiusX * zoom).toFloat(), (sin(angle) * radiusY * zoom).toFloat())
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            points.forEachIndexed { index, point ->
                drawLine(lineColor, origin, point, (1f + nodes[index].tracks.toFloat() / largest) * density.density)
            }
            drawCircle(accent.copy(alpha = .08f), 44 * density.density, origin)
            drawCircle(accent.copy(alpha = .5f), 4 * density.density, origin)
        }
        Text(centerLabel, color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset { IntOffset((origin.x - labelWidth / 2).roundToInt(),
                (origin.y + 8 * density.density).roundToInt()) }.width(106.dp).padding(horizontal = 6.dp))
        nodes.forEachIndexed { index, node ->
            val point = points[index]
            Column(Modifier.offset { IntOffset((point.x - labelWidth / 2).roundToInt(),
                (point.y - labelHeight / 2).roundToInt()) }.size(106.dp, 64.dp)
                .clip(RoundedCornerShape(14.dp)).background(Surface)
                .border(1.dp, accent.copy(alpha = .4f), RoundedCornerShape(14.dp))
                .clickable(role = Role.Button) { select(node) }.testTag("map-node-${node.id}")
                .semantics { contentDescription = "${node.name}, ${node.tracks} credited tracks" }
                .padding(horizontal = 8.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(node.name, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${node.tracks} tracks", fontSize = 10.sp, lineHeight = 12.sp, color = Muted, maxLines = 1)
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
private fun MapAlbumDetails(album: LibraryGroup, play: (List<Song>, Int, Boolean) -> Unit,
    queue: (List<Song>) -> Unit, openAlbum: (LibraryGroup) -> Unit, close: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("map-album-details"), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(album.songs.first(), Modifier.size(64.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(album.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(album.subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Close album preview") }
            }
            Text("${album.songs.size} tracks / ${durationLabel(album.songs.sumOf { it.duration })}",
                Modifier.padding(vertical = 8.dp), color = Muted, fontSize = 12.sp)
            Row {
                FilledTonalButton(onClick = { play(album.songs, 0, false) }, modifier = Modifier.testTag("map-play")) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Text("Play")
                }
                TextButton(onClick = { play(album.songs, 0, true) }, modifier = Modifier.testTag("map-shuffle")) { Text("Shuffle") }
                TextButton(onClick = { queue(album.songs) }, modifier = Modifier.testTag("map-queue")) { Text("Queue") }
            }
            TextButton(onClick = { openAlbum(album) }, modifier = Modifier.testTag("map-open-album")) { Text("Open album") }
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
