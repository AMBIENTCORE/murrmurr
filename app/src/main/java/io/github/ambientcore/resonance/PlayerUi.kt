package io.github.ambientcore.resonance

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class Collection(val name: String, val subtitle: String, val songs: List<Song>, val playlistId: String? = null,
    val groupByAlbum: Boolean = false)

private data class SongBrowseRequest(val song: Song, val destination: SongDestination)
private val LocalSongNavigation = staticCompositionLocalOf<((Song, SongDestination) -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MurrmurrApp(model: MusicViewModel, requestPermission: () -> Unit) {
    val theme by model.theme.collectAsStateWithLifecycle()
    MurrmurrTheme(theme) {
        val appName = stringResource(R.string.app_name)
        val library by model.library.collectAsStateWithLifecycle()
        val source by model.source.collectAsStateWithLifecycle()
        val server by model.server.collectAsStateWithLifecycle()
        val serverSetup by model.serverSetup.collectAsStateWithLifecycle()
        val localLibrary by model.localLibrary.collectAsStateWithLifecycle()
        val history by model.history.collectAsStateWithLifecycle()
        val ratingImport by model.ratingImport.collectAsStateWithLifecycle()
        val playlistBatch by model.playlistBatch.collectAsStateWithLifecycle()
        val playlistCommit by model.playlistCommit.collectAsStateWithLifecycle()
        val updates by model.updates.collectAsStateWithLifecycle()
        val uriHandler = LocalUriHandler.current
        var tabs by rememberSaveable { mutableStateOf(listOf(0, 0)) }
        val tab = tabs[source.ordinal]
        var expanded by rememberSaveable { mutableStateOf(false) }
        var controlsTop by remember { mutableFloatStateOf(Float.NaN) }
        val density = LocalDensity.current
        val windowHeight = LocalWindowInfo.current.containerSize.height
        val noticeInset = if (!expanded) 0.dp else if (controlsTop.isFinite())
            with(density) { (windowHeight - controlsTop).coerceAtLeast(0f).toDp() } else 108.dp
        val browseKey = if (source == LibrarySource.SERVER) "SERVER:${server.key}" else "LOCAL"
        var collections by remember(browseKey) { mutableStateOf<List<Collection>>(emptyList()) }
        val collection = collections.lastOrNull()
        fun openCollection(item: Collection) { collections = collections + item }
        fun closeCollection() { collections = collections.dropLast(1) }
        var settings by remember { mutableStateOf(false) }
        var equalizer by remember { mutableStateOf(false) }
        var addSong by remember { mutableStateOf<Song?>(null) }
        var details by remember { mutableStateOf<Song?>(null) }
        var songBrowseRequest by remember { mutableStateOf<SongBrowseRequest?>(null) }
        var createPlaylist by remember { mutableStateOf(false) }
        var createSmart by remember { mutableStateOf(false) }
        var editSmart by remember { mutableStateOf<MusicPlaylist?>(null) }
        var exportSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
        val snackbar = remember { SnackbarHostState() }
        val browseState = rememberSaveableStateHolder()
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(model::addFolder) }
        val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importPlaylist) }
        val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { it?.let { uri -> model.exportPlaylist(uri, exportSongs) } }
        fun pickFolder() = folderPicker.launch(null)
        fun showDetails(song: Song) {
            val audio = model.audio.value.takeIf { it.sourceUri == song.uri }
            details = song.copy(catalogNumber = song.catalogNumber.ifBlank { audio?.catalogNumber.orEmpty() },
                bitrate = audio?.sourceBitrate?.takeIf { it > 0 } ?: song.bitrate,
                year = taggedYear(audio?.sourceYear, song.year))
        }
        val data = library.data
        val sorted = remember(data.songs, library.sort) { sortedSongs(data.songs, library.sort) }
        LaunchedEffect(library.message) {
            library.message?.let { snackbar.showSnackbar(it); model.message(null) }
        }
        LaunchedEffect(songBrowseRequest, source, localLibrary.loading, localLibrary.scanning, localLibrary.data.songs,
            server.initialized, server.key, server.refreshing, server.data.songs) {
            val request = songBrowseRequest ?: return@LaunchedEffect
            val song = request.song
            if (song.isServer) {
                if (!server.initialized) return@LaunchedEffect
                if (!server.configured || server.key != song.serverKey) {
                    model.message("Connect this track's Navidrome account to browse its library.")
                    songBrowseRequest = null
                    return@LaunchedEffect
                }
            }
            val targetSource = if (song.isServer) LibrarySource.SERVER else LibrarySource.LOCAL
            if (source != targetSource) {
                model.switchSource()
                return@LaunchedEffect
            }
            if (!song.isServer && localLibrary.loading) return@LaunchedEffect
            val group = songDestinationGroup(song, request.destination,
                if (song.isServer) server.data.songs else localLibrary.data.songs)
            if (group == null) {
                if (song.isServer && server.refreshing || !song.isServer && localLibrary.scanning) return@LaunchedEffect
                model.message("No matching tracks in this library. Refresh it and try again.")
            } else {
                tabs = tabs.mapIndexed { index, old -> if (index == targetSource.ordinal) 1 else old }
                collections = listOf(Collection(group.name, group.subtitle, group.songs,
                    groupByAlbum = request.destination.category in setOf(LibraryCategory.ARTISTS, LibraryCategory.GENRES)))
                expanded = false
            }
            songBrowseRequest = null
        }
        BackHandler(expanded || collection != null || tab != 0) {
            when { expanded -> expanded = false; collection != null -> closeCollection()
                else -> tabs = tabs.mapIndexed { index, old -> if (index == source.ordinal) 0 else old } }
        }
        CompositionLocalProvider(
            LocalBrowseSource provides if (source == LibrarySource.SERVER) "server:${server.key}:" else "",
            LocalSongNavigation provides { song, destination -> songBrowseRequest = SongBrowseRequest(song, destination) },
        ) {
        Scaffold(
            modifier = Modifier.testTag("player-root"),
            containerColor = Ink,
            contentColor = Paper,
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = noticeInset)) },
            bottomBar = {
                if (!expanded) Column(Modifier.navigationBarsPadding()) {
                    MiniPlayer(model) { expanded = true }
                    NavigationBar(containerColor = Surface, tonalElevation = 0.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            .shadow(4.dp, RoundedCornerShape(24.dp), ambientColor = Ink, spotColor = Ink)
                            .clip(RoundedCornerShape(24.dp)).height(64.dp).testTag("floating-navigation")) {
                        listOf("Home" to Icons.Rounded.Home, "Library" to Icons.Rounded.LibraryMusic,
                            "Search" to Icons.Rounded.Search, "Playlists" to Icons.AutoMirrored.Rounded.QueueMusic,
                            "Stats" to Icons.Rounded.BarChart).forEachIndexed { index, (label, icon) ->
                            NavigationBarItem(selected = tab == index, onClick = {
                                tabs = tabs.mapIndexed { position, old -> if (position == source.ordinal) index else old }; collections = emptyList()
                            },
                                icon = { Icon(icon, label) }, label = { Text(label, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = InputSurface,
                                    selectedIconColor = Lime, selectedTextColor = Lime,
                                    unselectedIconColor = Muted, unselectedTextColor = Muted))
                        }
                    }
                }
            },
        ) { padding ->
            if (expanded) {
                NowPlaying(model, Modifier.padding(padding), { expanded = false }, { settings = true }, { equalizer = true },
                    { addSong = it }, ::showDetails, controlsPositioned = { controlsTop = it })
            } else Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (collection != null) IconButton(onClick = ::closeCollection) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library") }
                    else Image(painterResource(R.drawable.murrmurr_logo), null,
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).testTag("app-logo"))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(if (collection == null) appName else "Collection", fontWeight = FontWeight.Bold,
                            fontSize = if (collection == null) 24.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { model.play(data.songs, shuffle = true) }, enabled = data.songs.isNotEmpty(),
                        modifier = Modifier.testTag("toolbar-shuffle"),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Lime, disabledContentColor = Muted.copy(alpha = .38f))) {
                        Icon(Icons.Rounded.Shuffle, "Shuffle all")
                    }
                    TextButton(onClick = {
                        addSong = null; details = null; createPlaylist = false; editSmart = null
                        model.switchSource()
                    }, modifier = Modifier.testTag("source-toggle"), contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(if (source == LibrarySource.LOCAL) "Local" else "Server", fontSize = 12.sp, color = Lime)
                    }
                    IconButton(onClick = { model.rescan() }, enabled = !library.scanning && !ratingImport.running) {
                        Icon(Icons.Rounded.Refresh, if (source == LibrarySource.SERVER) "Refresh server library" else "Scan for new tracks", tint = Muted)
                    }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Settings, "Settings", tint = Muted) }
                }
                if (source == LibrarySource.SERVER && server.error != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(server.error.orEmpty(), Modifier.weight(1f), fontSize = 11.sp, color = Pink,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = model::refreshServer, enabled = !server.refreshing) { Text("Retry") }
                    }
                }
                if (library.scanning || library.loading || ratingImport.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lime)
                    Text(if (ratingImport.running) ratingImport.progress else library.progress.ifBlank { "Opening library" },
                        Modifier.padding(horizontal = 24.dp, vertical = 6.dp), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                }
                val selected = collection
                val onAdd: (Song) -> Unit = { addSong = it }
                val onDetails: (Song) -> Unit = ::showDetails
                when {
                    selected != null -> {
                        val playlist = data.playlists.firstOrNull { it.id == selected.playlistId }
                        val songs = if (selected.playlistId == "favorites") data.songs.filter { it.uri in data.favorites }
                        else if (selected.playlistId != null) playlist?.let { playlistSongs(it, data.songs, data.ratings) }.orEmpty()
                        else selected.songs
                        SongList(songs, model, data.favorites, onAdd, onDetails, title = selected.name,
                            subtitle = "${songs.size} tracks / ${durationLabel(songs.sumOf { it.duration })}",
                            onExport = if (source == LibrarySource.LOCAL) ({ exportSongs = songs; exportPicker.launch("${selected.name.replace(Regex("[^\\w -]"), "_")}.m3u8") }) else null,
                            onRemove = playlist?.takeIf { it.rule == null && source == LibrarySource.LOCAL }?.let { item -> { song -> model.removeFromPlaylist(item.id, song.uri) } },
                            groupByAlbum = selected.groupByAlbum,
                            openAlbum = { group -> openCollection(Collection(group.name, group.subtitle, group.songs)) })
                    }
                    tab == 0 -> browseState.SaveableStateProvider("$browseKey:home") {
                        Home(data, model, ::pickFolder, ::openCollection, onAdd, onDetails)
                    }
                    tab == 1 -> browseState.SaveableStateProvider("$browseKey:library") {
                        Library(sorted, model, data.favorites, ::pickFolder, ::openCollection, onAdd, onDetails)
                    }
                    tab == 2 -> browseState.SaveableStateProvider("$browseKey:search") {
                        Search(sorted, model, data.favorites, onAdd, onDetails, ::openCollection)
                    }
                    tab == 3 -> browseState.SaveableStateProvider("$browseKey:playlists") { Playlists(data, { smart -> createSmart = smart; createPlaylist = true }, { importPicker.launch(arrayOf("audio/*", "application/octet-stream", "text/*", "application/json")) },
                        ::openCollection, model::deletePlaylist, { editSmart = it }, source == LibrarySource.SERVER)
                    }
                    else -> browseState.SaveableStateProvider("$browseKey:stats") { StatsScreen(data, history) { model.play(listOf(it)) } }
                }
                }
                if (serverSetup.open) ServerSetupDialog(model)
            }
        }
        updates.available?.let { update ->
            AppUpdateDialog(update, model::dismissUpdate) {
                try {
                    uriHandler.openUri(update.apkUrl)
                    model.dismissUpdate()
                } catch (error: android.content.ActivityNotFoundException) {
                    android.util.Log.w("MurrmurrUpdates", "No browser can open the update download", error)
                    model.message("No browser available to download the update.")
                } catch (error: IllegalArgumentException) {
                    android.util.Log.w("MurrmurrUpdates", "Cannot open the update download", error)
                    model.message("Could not open the update download.")
                }
            }
        }
        playlistBatch?.let { preview ->
            AlertDialog(onDismissRequest = model::dismissPlaylistBatch,
                title = { Text("Import playlists") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${preview.playlists.size} playlists / ${preview.matched} matched / " +
                        "${preview.missing} missing / ${preview.ambiguous} ambiguous / ${preview.unavailable} unavailable",
                        color = Muted, fontSize = 12.sp)
                    preview.notices.forEach { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
                    preview.playlists.forEach {
                        Text(it.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                        Text("${it.songs.size} / ${it.total} tracks", color = Muted, fontSize = 11.sp)
                    }
                    Text("Matching source playlists are updated; unrelated playlists are unchanged. Empty source playlists are retained.",
                        color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                } },
                confirmButton = { TextButton(onClick = model::commitPlaylistBatch, enabled = !playlistCommit) { Text("Import") } },
                dismissButton = { TextButton(onClick = model::dismissPlaylistBatch, enabled = !playlistCommit) { Text("Cancel") } })
        }
        if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = Surface) {
            Settings(model, data, requestPermission, ::pickFolder) { settings = false; equalizer = true }
        }
        if (equalizer) EqualizerPanel(model) { equalizer = false }
        addSong?.let { song ->
            val (mode, changeMode) = rememberBrowseMode("playlist-picker")
            val normal = remember(localLibrary.data.playlists) { localLibrary.data.playlists.filter { it.rule == null } }
            val songIndex = remember(localLibrary.data.songs) { localLibrary.data.songs.associateBy { it.uri } }
            AlertDialog(onDismissRequest = { addSong = null }, title = { Text("Add to playlist") },
                text = { Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ViewModeMenu("playlist-picker", mode, changeMode)
                    }
                    BrowseGrid(mode, Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        items(normal, key = { it.id }) { playlist ->
                            BrowseItem(playlist.name, "${playlist.songs.size} tracks", mode,
                                song = playlist.songs.firstOrNull()?.let(songIndex::get),
                                onClick = { model.addToPlaylist(playlist.id, song); addSong = null })
                        }
                    }
                    var name by remember { mutableStateOf("") }
                    OutlinedTextField(name, { name = it }, label = { Text("New playlist name") }, singleLine = true)
                    TextButton(onClick = { if (name.isNotBlank()) { model.createPlaylist(name, song); addSong = null } }, enabled = name.isNotBlank()) { Text("Create and add") }
                } }, confirmButton = { TextButton(onClick = { addSong = null }) { Text("Close") } })
        }
        if (createPlaylist && !createSmart) {
            var name by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { createPlaylist = false }, title = { Text("New playlist") },
                text = { OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true) },
                confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { model.createPlaylist(name); createPlaylist = false }) { Text("Create") } },
                dismissButton = { TextButton(onClick = { createPlaylist = false }) { Text("Cancel") } })
        }
        if ((createPlaylist && createSmart) || editSmart != null) {
            SmartPlaylistDialog(editSmart, { createPlaylist = false; editSmart = null }) { name, rule ->
                val existing = editSmart
                if (existing == null) model.createSmartPlaylist(name, rule) else model.updateSmartPlaylist(existing.id, name, rule)
                createPlaylist = false
                editSmart = null
            }
        }
        details?.let { song ->
            AlertDialog(onDismissRequest = { details = null }, title = { Text("Track information") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Title" to song.title, "Artist" to song.artist, "Album artist" to song.albumArtist, "Album" to song.album,
                        "Genres" to song.genres.joinToString(", ").ifBlank { "Not indexed" }, "Year" to song.year, "Disc / track" to "${song.disc} / ${song.track}",
                        "Catalog number" to song.catalogNumber, "Duration" to durationLabel(song.duration),
                        "Format" to trackFormatLabel(song), "File size" to "${song.size / 1024} KiB",
                        "Folder" to song.folder, "File" to song.fileName,
                        "Play count" to (if (history.error != null) "Unavailable" else (history.data.counts[song.uri] ?: 0L).toString()),
                        "Last played" to playedAtLabel(history.data.lastPlayed[song.uri]),
                        "Rating source" to when {
                            song.isServer -> "Navidrome"
                            song.uri in data.ratingOverrides -> appName
                            (data.ratings[song.uri] ?: 0) > 0 -> "Embedded file tag"
                            else -> "Unrated"
                        }).forEach { (key, value) ->
                        Column { Text(key.uppercase(), color = Lime, fontSize = 10.sp, letterSpacing = 1.sp); Text(value.ifBlank { "-" }, fontSize = 14.sp) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { details = null }) { Text("Done") } })
        }
    }
}

@Composable
private fun Home(data: LibraryData, model: MusicViewModel, pickFolder: () -> Unit, open: (Collection) -> Unit, add: (Song) -> Unit, details: (Song) -> Unit) {
    val source by model.source.collectAsStateWithLifecycle()
    val albums = remember(data.songs) { recentlyAddedAlbums(data.songs) }
    val recent = remember(data.songs) { sortedSongs(data.songs, SongSort.NEWEST).take(12) }
    var recentExpanded by rememberSaveable { mutableStateOf(true) }
    val (albumMode, changeAlbumMode) = rememberBrowseMode("home-albums", BrowseMode.ICONS_LARGE)
    val (trackMode, changeTrackMode) = rememberBrowseMode("home-tracks")
    BrowseGrid(trackMode, Modifier.testTag("home-list")) {
        if (data.songs.isEmpty()) {
            fullItem { EmptyLibrary(pickFolder, source == LibrarySource.SERVER) }
        } else {
            fullItem("recent-albums-title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle("Recently added albums", "") }
                    ViewModeMenu("home-albums", albumMode, changeAlbumMode)
                }
            }
            if (albumMode.grid) fullItem("recent-albums") {
                LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(albums, key = { it.first().albumKey }) { songs ->
                        val size = if (albumMode == BrowseMode.ICONS_SMALL) 80.dp else 120.dp
                        Column(Modifier.width(size).clickable { open(Collection(songs.first().album, songs.first().albumArtist, sortedSongs(songs, SongSort.ALBUM))) }) {
                            Artwork(songs.first(), Modifier.size(size))
                            Text(songs.first().album, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            Text(songs.first().albumArtist, color = Muted, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            else albums.forEach { tracks -> fullItem("album:${tracks.first().albumKey}") {
                BrowseItem(tracks.first().album, tracks.first().albumArtist, albumMode, song = tracks.first(),
                    onClick = { open(Collection(tracks.first().album, tracks.first().albumArtist, sortedSongs(tracks, SongSort.ALBUM))) })
            } }
            fullItem("recent-tracks-header") {
                Row(Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (recentExpanded) "Collapse" else "Expand") {
                        recentExpanded = !recentExpanded
                    }
                    .padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 14.dp)
                    .semantics { stateDescription = if (recentExpanded) "Expanded" else "Collapsed" }
                    .testTag("recent-tracks-toggle"), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recently added", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    ViewModeMenu("home-tracks", trackMode, changeTrackMode)
                    Icon(if (recentExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Muted)
                }
            }
            if (recentExpanded) items(recent, key = { "recent:${it.uri}" }) { song ->
                SongRow(song, song.uri in data.favorites, { model.play(recent, recent.indexOf(song)) }, model, add, details, mode = trackMode)
            }
        }
    }
}

@Composable
private fun EmptyLibrary(pickFolder: () -> Unit, server: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(38.dp), tint = Muted)
        Spacer(Modifier.height(12.dp))
        Text(if (server) "No cached server tracks" else "No tracks", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(if (server) "Refresh the server library when connected." else "Select a folder or scan the device music library.", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        if (!server) TextButton(onClick = pickFolder) { Text("Add music folder") }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 14.dp)) {
        if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 9.sp, letterSpacing = 1.6.sp)
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Library(songs: List<Song>, model: MusicViewModel, favorites: Set<String>, pickFolder: () -> Unit, open: (Collection) -> Unit, add: (Song) -> Unit, details: (Song) -> Unit) {
    val source by model.source.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf(LibraryCategory.SONGS) }
    var queries by rememberSaveable { mutableStateOf(List(LibraryCategory.entries.size) { "" }) }
    val query = queries[category.ordinal]
    val scope = when (category) {
        LibraryCategory.SONGS -> "songs"
        LibraryCategory.FOLDERS -> "folders"
        else -> "library-${category.name}"
    }
    val (mode, changeMode) = rememberBrowseMode(scope)
    var categories by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp).testTag("library-controls"),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); categories = true },
                    modifier = Modifier.width(102.dp).height(40.dp).testTag("library-category-menu")
                        .semantics { contentDescription = "Library category: ${category.label}" },
                    contentPadding = PaddingValues(horizontal = 8.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = Surface, contentColor = Paper)) {
                    Text(category.label, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1)
                    Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp))
                }
                DropdownMenu(categories, { categories = false }) {
                    LibraryCategory.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, modifier = Modifier.testTag("library-category-${option.name}"),
                            trailingIcon = { if (category == option) Icon(Icons.Rounded.Check, null, tint = Lime) },
                            onClick = { category = option; categories = false })
                    }
                }
            }
            CompactSearchField(query, "Search ${category.label.lowercase()}", { value ->
                queries = queries.mapIndexed { index, old -> if (index == category.ordinal) value else old }
            }, Modifier.weight(1f), "category-search", "Clear category search")
            ViewModeMenu(scope, mode, changeMode, compact = true)
        }
        }
        if (songs.isEmpty()) EmptyLibrary(pickFolder, source == LibrarySource.SERVER)
        else if (category == LibraryCategory.SONGS) {
            val matches = remember(songs, query) { filterSongs(songs, query) }
            SongList(matches, model, favorites, add, details, title = "Songs", subtitle = "${matches.size} tracks",
                showSort = true, searchable = false, libraryMode = mode)
        } else if (category == LibraryCategory.FOLDERS) {
            FolderBrowser(songs, query, open, mode)
        }
        else {
            val groups = remember(songs, category) { libraryGroups(songs, category) }
            val matches = remember(groups, query) { filterGroups(groups, query) }
            Text("${matches.size} ${category.label.lowercase()}", Modifier.padding(horizontal = 16.dp).padding(top = 2.dp),
                color = Muted, fontSize = 11.sp, lineHeight = 14.sp)
            BrowseGrid(mode, Modifier.weight(1f).testTag(if (category == LibraryCategory.ALBUMS) "album-grid" else "library-group-grid"),
                columns = if (category == LibraryCategory.ALBUMS) albumGridColumns(mode) else mode.columns) {
                if (matches.isEmpty()) fullItem { Text("No matches", Modifier.padding(24.dp), color = Muted) }
                items(matches, key = { it.id }) { group ->
                    val dense = category == LibraryCategory.ALBUMS && mode.grid
                    BrowseItem(group.name, if (dense) group.subtitle else "${group.songs.size} tracks" + if (group.subtitle.isNotBlank()) " / ${group.subtitle}" else "",
                        mode, modifier = Modifier.testTag("library-group-${group.id}"), song = group.songs.first(),
                        onClick = { open(Collection(group.name, category.label, sortedSongs(group.songs, SongSort.ALBUM),
                            groupByAlbum = category in listOf(LibraryCategory.ARTISTS, LibraryCategory.GENRES))) },
                        denseArtwork = dense)
                }
            }
        }
    }
}

@Composable
private fun CategorySearch(query: String, label: String, change: (String) -> Unit) {
    CompactSearchField(query, label, change, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        "category-search", "Clear category search")
}

@Composable
private fun CompactSearchField(query: String, label: String, change: (String) -> Unit, modifier: Modifier,
    tag: String, clearLabel: String) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(query, change, singleLine = true,
        modifier = modifier.height(40.dp).clip(shape).background(InputSurface)
            .border(1.dp, if (focused) Pink else Surface, shape).onFocusChanged { focused = it.isFocused }
            .testTag(tag).semantics { contentDescription = label },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Paper, fontSize = 14.sp, lineHeight = 18.sp),
        cursorBrush = SolidColor(Pink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
        decorationBox = { input ->
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = Muted)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(label, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    input()
                }
                if (query.isNotEmpty()) CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 28.dp) {
                    IconButton(onClick = { change("") }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, clearLabel, Modifier.size(18.dp), tint = Muted)
                    }
                }
            }
        })
}

@Composable
private fun FolderBrowser(songs: List<Song>, query: String, open: (Collection) -> Unit, mode: BrowseMode) {
    val roots = remember(songs) { folderTree(songs) }
    val filtered = remember(roots, query) { filterFolderTree(roots, query) }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var expandedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyGridState()
    LaunchedEffect(query, filtered) {
        if (query != expandedQuery) {
            if (query.isNotBlank()) expanded = folderIds(filtered).toList()
            expandedQuery = query
        }
    }
    val rows = remember(filtered, expanded) { folderRows(filtered, expanded.toSet()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = folderIds(filtered).toList() }, enabled = filtered.isNotEmpty()) { Text("Expand all") }
            TextButton(onClick = { expanded = emptyList() }, enabled = expanded.isNotEmpty()) { Text("Collapse all") }
        }
        BrowseGrid(mode, Modifier.weight(1f).testTag("folder-list"), state = listState) {
            if (rows.isEmpty()) fullItem { Text("No matching folders", Modifier.padding(24.dp), color = Muted) }
            items(rows, key = { it.folder.id }, span = { if (it.folder.children.isNotEmpty()) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { row ->
                val node = row.folder
                val rowMode = if (mode.grid && node.children.isNotEmpty()) BrowseMode.LIST_ICON else mode
                BrowseItem(node.name, "${node.songs.size} tracks" + if (mode.grid) " / ${node.path}" else "", rowMode,
                    modifier = Modifier.padding(start = if (rowMode.grid) 0.dp else (row.depth.coerceAtMost(10) * 10).dp)
                        .testTag("folder-${node.id}"),
                    icon = Icons.Rounded.Folder, onClick = { open(Collection(node.name, node.path, sortedSongs(node.songs, SongSort.ALBUM))) },
                    leading = {
                    if (node.children.isNotEmpty()) BrowseActionButton(
                        onClick = { expanded = if (node.id in expanded) expanded - node.id else expanded + node.id },
                        modifier = Modifier.testTag("toggle-${node.id}"),
                    ) {
                        Icon(if (node.id in expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                            if (node.id in expanded) "Collapse ${node.name}" else "Expand ${node.name}", tint = Muted)
                    } else if (!rowMode.grid) Spacer(Modifier.width(48.dp))
                })
            }
        }
    }
}

@Composable
private fun SongList(songs: List<Song>, model: MusicViewModel, favorites: Set<String>, add: (Song) -> Unit, details: (Song) -> Unit,
    title: String, subtitle: String, showSort: Boolean = false, onExport: (() -> Unit)? = null, onRemove: ((Song) -> Unit)? = null,
    searchable: Boolean = true, groupByAlbum: Boolean = false, libraryMode: BrowseMode? = null,
    openAlbum: ((LibraryGroup) -> Unit)? = null) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    val scope = if (showSort) "songs" else if (title == "Search results") "search" else "collection"
    val presentation = if (libraryMode == null) rememberBrowseMode(scope) else null
    val mode = libraryMode ?: requireNotNull(presentation).first
    val collectionDisplay = if (groupByAlbum) rememberCollectionDisplay() else null
    val showAlbums = collectionDisplay?.first == CollectionDisplay.ALBUMS
    var displayMenu by remember { mutableStateOf(false) }
    val matches = remember(songs, query, searchable) { filterSongs(songs, if (searchable) query else "") }
    val groups = remember(songs, query, searchable, groupByAlbum) {
        if (groupByAlbum) searchGroups(songs, if (searchable) query else "", LibraryCategory.ALBUMS, SearchSort.ALPHABETICAL)
            .map { it.copy(songs = sortedSongs(it.songs, SongSort.ALBUM)) } else emptyList()
    }
    val displayed = remember(matches, groups, showAlbums) { if (showAlbums) groups.flatMap { it.songs } else matches }
    Column(Modifier.fillMaxSize()) {
    if (searchable) CategorySearch(query, if (showAlbums) "Search albums" else "Search tracks") { query = it }
    collectionDisplay?.let { (display, changeDisplay) ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Display as", color = Muted, fontSize = 12.sp)
            Box {
                TextButton(onClick = { displayMenu = true }, modifier = Modifier.testTag("collection-display-menu")
                    .semantics { stateDescription = display.label }) {
                    Text(display.label)
                    Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp))
                }
                DropdownMenu(displayMenu, { displayMenu = false }) {
                    CollectionDisplay.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) },
                            modifier = Modifier.testTag("collection-display-${option.name}"),
                            trailingIcon = { if (display == option) Icon(Icons.Rounded.Check, null, tint = Lime) },
                            onClick = { changeDisplay(option); displayMenu = false })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            presentation?.let { ViewModeMenu(scope, mode, it.second) }
        }
    }
    BrowseGrid(mode, Modifier.weight(1f).testTag("song-list"),
        columns = if (showAlbums) albumGridColumns(mode) else mode.columns) {
        fullItem {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (libraryMode == null) 18.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (libraryMode != null) Text(subtitle, Modifier.weight(1f), color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                else Column(Modifier.weight(1f)) {
                    Text(title, modifier = Modifier.testTag("collection-title"), fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text((if (groupByAlbum) "${groups.size} albums / " else "") + if (query.isBlank()) subtitle else "${matches.size} matches",
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (!groupByAlbum) presentation?.let { ViewModeMenu(scope, mode, it.second) }
                if (showSort) SortMenu(model)
                if (onExport != null) IconButton(onClick = onExport, enabled = songs.isNotEmpty()) { Icon(Icons.Rounded.FileUpload, "Export playlist") }
                IconButton(onClick = { model.play(displayed, shuffle = true) }, enabled = displayed.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, "Shuffle collection", tint = Lime) }
                FilledIconButton(onClick = { model.play(displayed) }, enabled = displayed.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, "Play collection") }
            }
        }
        if (matches.isEmpty()) fullItem { Text("No matching tracks", Modifier.padding(24.dp), color = Muted) }
        if (showAlbums) {
            val navigate = checkNotNull(openAlbum) { "Album collections require navigation" }
            items(groups, key = { it.id }) { group ->
                BrowseItem(group.name, "${group.subtitle} / ${group.songs.size} tracks", mode,
                    modifier = Modifier.testTag("collection-album-${group.id}"), song = group.songs.first(),
                    onClick = { navigate(group) }, denseArtwork = mode.grid)
            }
        } else itemsIndexed(displayed, key = { index, song -> "${song.uri}:$index" }) { index, song ->
            SongRow(song, song.uri in favorites, { model.play(displayed, index) }, model, add, details, onRemove, mode)
        }
    }
    }
}

@Composable
private fun SortMenu(model: MusicViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.Sort, "Sort library") }
        DropdownMenu(open, { open = false }) {
            SongSort.entries.forEach { sort -> DropdownMenuItem(text = { Text(sort.label) }, onClick = { model.sort(sort); open = false }) }
        }
    }
}

@Composable
private fun SongRow(song: Song, favorite: Boolean, play: () -> Unit, model: MusicViewModel, add: (Song) -> Unit, details: (Song) -> Unit, remove: ((Song) -> Unit)? = null, mode: BrowseMode = BrowseMode.LIST_ICON) {
    val rating by remember(model, song.uri) { model.library.map { it.data.ratings[song.uri] ?: 0 }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = 0)
    BrowseItem(song.title, song.artist, mode, modifier = Modifier.testTag("track-${song.uri}"), song = song, onClick = play, badges = {
        if (favorite) Icon(Icons.Rounded.Favorite, null, tint = Lime, modifier = Modifier.size(12.dp))
        if (rating > 0) {
            Icon(Icons.Rounded.Star, "Rated $rating stars", tint = Lime, modifier = Modifier.padding(start = 4.dp).size(12.dp))
            Text("$rating", color = Muted, fontSize = 10.sp)
        }
        Text(durationLabel(song.duration), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
    }, actions = {
        SongActions(song, model, add, details, remove) { open ->
            BrowseActionButton(onClick = open) { Icon(Icons.Rounded.MoreVert, "Track options: ${song.title}", tint = Muted, modifier = Modifier.size(20.dp)) }
        }
    })
}

@Composable
private fun SongActions(song: Song, model: MusicViewModel, add: (Song) -> Unit, details: (Song) -> Unit,
    remove: ((Song) -> Unit)? = null, button: @Composable (() -> Unit) -> Unit) {
    var menu by remember(song.uri) { mutableStateOf(false) }
    var rate by remember(song.uri) { mutableStateOf(false) }
    var genres by remember(song.uri) { mutableStateOf(false) }
    val navigate = LocalSongNavigation.current
    val destinations = remember(song) { songDestinations(song) }
    val genreDestinations = destinations.filter { it.category == LibraryCategory.GENRES }
    val feedback by remember(model, song.uri) {
        val data = if (song.isServer) model.server.map { it.data } else model.localLibrary.map { it.data }
        data.map { (song.uri in it.favorites) to (it.ratings[song.uri] ?: 0) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false to 0)
    val (favorite, rating) = feedback
    Box {
            button { menu = true }
            DropdownMenu(menu, { menu = false }) {
                if (navigate != null) {
                    destinations.filter { it.category != LibraryCategory.GENRES }.forEach { destination ->
                        DropdownMenuItem(text = { Text(destination.label) }, onClick = {
                            menu = false
                            navigate(song, destination)
                        })
                    }
                    if (genreDestinations.isNotEmpty()) DropdownMenuItem(text = { Text("Go to genre") }, onClick = {
                        menu = false
                        if (genreDestinations.size == 1) navigate(song, genreDestinations.single()) else genres = true
                    })
                }
                DropdownMenuItem(text = { Text("Rate track") }, onClick = { rate = true; menu = false })
                listOf("Play next" to { model.queue(song, true) }, "Add to queue" to { model.queue(song) },
                    (if (favorite) "Remove favorite" else "Favorite") to { model.favorite(song) },
                    "Track information" to { details(song) }).forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { action(); menu = false })
                }
                if (!song.isServer) DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { add(song); menu = false })
                if (remove != null) DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = { remove(song); menu = false })
            }
    }
    if (genres && navigate != null) AlertDialog(onDismissRequest = { genres = false }, title = { Text("Go to genre") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            genreDestinations.forEach { destination ->
                TextButton(onClick = { genres = false; navigate(song, destination) }, modifier = Modifier.fillMaxWidth()) {
                    Text(destination.value)
                }
            }
        } },
        confirmButton = { TextButton(onClick = { genres = false }) { Text("Cancel") } })
    if (rate) AlertDialog(onDismissRequest = { rate = false }, title = { Text("Rate track") },
            text = { Column {
                Text(song.title, maxLines = 2)
                RatingPicker(rating) { model.rate(song, it) }
                Text(if (song.isServer) "Saved to your Navidrome account when connected." else "Stored in ${stringResource(R.string.app_name)}. Audio file tags are unchanged.", color = Muted, fontSize = 12.sp)
            } }, confirmButton = { TextButton(onClick = { rate = false }) { Text("Done") } })
}

@Composable
private fun Search(songs: List<Song>, model: MusicViewModel, favorites: Set<String>, add: (Song) -> Unit,
    details: (Song) -> Unit, open: (Collection) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(LibraryCategory.SONGS) }
    var scope by rememberSaveable { mutableStateOf(SearchScope.EVERYTHING) }
    var sort by rememberSaveable { mutableStateOf(SearchSort.ALPHABETICAL) }
    var categoryMenu by remember { mutableStateOf(false) }
    var scopeMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val (mode, changeMode) = rememberBrowseMode("search")
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val results = remember(query, songs, sort, scope) {
        if (query.isBlank()) emptyList() else sortedSongs(filterSongs(songs, query, scope), sort.songSort)
    }
    val groups = remember(query, songs, category, sort, scope) {
        if (query.isBlank() || category == LibraryCategory.SONGS) emptyList() else searchGroups(songs, query, category, sort, scope)
    }
    fun dismissKeyboard() { focus.clearFocus(); keyboard?.hide() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Search", Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Box {
                TextButton(onClick = { dismissKeyboard(); categoryMenu = true }, modifier = Modifier.testTag("search-category-menu")
                    .semantics { stateDescription = category.label; contentDescription = "Display search results as: ${category.label}" }) {
                    Icon(when (category) {
                        LibraryCategory.SONGS -> Icons.Rounded.MusicNote
                        LibraryCategory.ALBUMS -> Icons.Rounded.Album
                        LibraryCategory.ARTISTS -> Icons.Rounded.Person
                        LibraryCategory.GENRES -> Icons.Rounded.Category
                        LibraryCategory.FOLDERS -> Icons.Rounded.Folder
                    }, null, Modifier.size(18.dp), tint = Lime)
                    Text(category.label, Modifier.padding(horizontal = 4.dp), fontSize = 12.sp)
                    Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(categoryMenu, { categoryMenu = false }) {
                    LibraryCategory.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, modifier = Modifier.testTag("search-category-${option.name}"),
                            trailingIcon = { if (category == option) Icon(Icons.Rounded.Check, null, tint = Lime) },
                            onClick = { category = option; categoryMenu = false })
                    }
                }
            }
            ViewModeMenu("search", mode, changeMode)
            Box {
                IconButton(onClick = { dismissKeyboard(); sortMenu = true }, modifier = Modifier.testTag("search-sort-menu")
                    .semantics { stateDescription = sort.label }) { Icon(Icons.Rounded.Sort, "Sort search results", tint = Muted) }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    SearchSort.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, modifier = Modifier.testTag("search-sort-${option.name}"),
                            trailingIcon = { if (sort == option) Icon(Icons.Rounded.Check, null, tint = Lime) },
                            onClick = { sort = option; sortMenu = false })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { dismissKeyboard(); scopeMenu = true }, modifier = Modifier.testTag("search-scope-menu")
                    .semantics { stateDescription = scope.label; contentDescription = "Search in: ${scope.label}" }) {
                    Text("In: ${scope.label}", fontSize = 12.sp)
                    Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(scopeMenu, { scopeMenu = false }) {
                    SearchScope.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, modifier = Modifier.testTag("search-scope-${option.name}"),
                            trailingIcon = { if (scope == option) Icon(Icons.Rounded.Check, null, tint = Lime) },
                            onClick = { scope = option; scopeMenu = false })
                    }
                }
            }
            CompactSearchField(query, "Search ${scope.label.lowercase()}", { query = it },
                Modifier.weight(1f), "global-search", "Clear search")
        }
        if (query.isNotBlank()) {
            Text("${if (category == LibraryCategory.SONGS) results.size else groups.size} ${category.label.lowercase()}",
                Modifier.padding(horizontal = 22.dp, vertical = 4.dp), fontSize = 12.sp, color = Muted)
            BrowseGrid(mode, Modifier.weight(1f).testTag("search-results")) {
                if (category == LibraryCategory.SONGS) {
                    if (results.isEmpty()) fullItem { Text("No matches", Modifier.padding(24.dp), color = Muted) }
                    itemsIndexed(results, key = { index, song -> "${song.uri}:$index" }) { index, song ->
                        SongRow(song, song.uri in favorites, { model.play(results, index) }, model, add, details, mode = mode)
                    }
                } else {
                    if (groups.isEmpty()) fullItem { Text("No matches", Modifier.padding(24.dp), color = Muted) }
                    items(groups, key = { it.id }) { group ->
                        BrowseItem(group.name, listOf(group.subtitle, group.year()?.toString().orEmpty(), "${group.songs.size} tracks")
                            .filter { it.isNotBlank() }.joinToString(" / "), mode,
                            modifier = Modifier.testTag("search-group-${group.id}"), song = group.songs.first(),
                            icon = if (category == LibraryCategory.FOLDERS) Icons.Rounded.Folder else null,
                            onClick = { open(Collection(group.name, group.subtitle, sortedSongs(group.songs, SongSort.ALBUM),
                                groupByAlbum = category in setOf(LibraryCategory.ARTISTS, LibraryCategory.GENRES))) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Playlists(data: LibraryData, create: (Boolean) -> Unit, import: () -> Unit, open: (Collection) -> Unit, delete: (String) -> Unit, edit: (MusicPlaylist) -> Unit, server: Boolean = false) {
    var query by rememberSaveable { mutableStateOf("") }
    val (mode, changeMode) = rememberBrowseMode("playlists")
    val (normalExpanded, expandNormal) = rememberBrowseExpanded("playlists-normal")
    val (smartExpanded, expandSmart) = rememberBrowseExpanded("playlists-smart")
    val terms = remember(query) { searchTerms(query) }
    val matches = remember(data.playlists, terms) { data.playlists.filter { matchesText(terms, it.name, it.rule?.label.orEmpty()) } }
    val songIndex = remember(data.songs) { data.songs.associateBy { it.uri } }
    Column(Modifier.fillMaxSize()) {
    CategorySearch(query, "Search playlists") { query = it }
    BrowseGrid(mode, Modifier.testTag("playlist-list")) {
        fullItem {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Playlists", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                ViewModeMenu("playlists", mode, changeMode)
                if (!server) IconButton(onClick = import) { Icon(Icons.Rounded.FileDownload, "Import playlists") }
                var createMenu by remember { mutableStateOf(false) }
                if (!server) Box {
                    FilledIconButton(onClick = { createMenu = true }) { Icon(Icons.Rounded.Add, "Create playlist") }
                    DropdownMenu(createMenu, { createMenu = false }) {
                        DropdownMenuItem(text = { Text("Normal playlist") }, onClick = { createMenu = false; create(false) })
                        DropdownMenuItem(text = { Text("Smart playlist") }, onClick = { createMenu = false; create(true) })
                    }
                }
            }
        }
        if (server) fullItem { Text("Server playlists are refreshed from Navidrome. Manage their contents and rules there.",
            Modifier.padding(horizontal = 22.dp), fontSize = 11.sp, color = Muted) }
        for (smart in if (server) listOf(false) else listOf(false, true)) {
            val expanded = if (smart) smartExpanded else normalExpanded
            val section = if (smart) "smart" else "normal"
            val playlists = matches.filter { (it.rule != null) == smart }
            fullItem("section:$section") {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    if (smart) expandSmart(!expanded) else expandNormal(!expanded)
                }.padding(horizontal = 22.dp, vertical = 12.dp).testTag("playlist-section-$section")
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Muted)
                    Text(if (smart) "Smart playlists" else "Playlists", Modifier.weight(1f).padding(start = 8.dp),
                        fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                    Text("${playlists.size}", color = Muted, fontSize = 12.sp)
                }
            }
            if (expanded) {
                if (!smart && matchesText(terms, "Favorites")) item(key = "favorites") {
                    BrowseItem("Favorites", "${data.favorites.size} tracks", mode, icon = Icons.Rounded.Favorite,
                        onClick = { open(Collection("Favorites", "", emptyList(), "favorites")) })
                }
                if (playlists.isEmpty()) fullItem("empty:$section") {
                    Text(if (smart) "No smart playlists" else "No playlists", Modifier.padding(22.dp), color = Muted, fontSize = 12.sp)
                }
                items(playlists, key = { it.id }) { playlist ->
                    val tracks = remember(playlist, songIndex, data.ratings) {
                        if (playlist.rule != null) playlistSongs(playlist, data.songs, data.ratings)
                        else playlist.songs.mapNotNull(songIndex::get)
                    }
                    PlaylistCard(playlist, tracks, mode, open, delete, edit)
                }
            }
        }
    }
    }
}

@Composable
private fun PlaylistCard(playlist: MusicPlaylist, songs: List<Song>, mode: BrowseMode,
    open: (Collection) -> Unit, delete: (String) -> Unit, edit: (MusicPlaylist) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    BrowseItem(playlist.name, "${songs.size} tracks" + (playlist.rule?.let { " / ${it.label}" } ?: ""),
        mode, modifier = Modifier.testTag("playlist-${playlist.id}"), song = songs.firstOrNull(),
        onClick = { open(Collection(playlist.name, "", emptyList(), playlist.id)) }, actions = {
            if (!playlist.id.startsWith("server:")) Box {
                BrowseActionButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Playlist options") }
                DropdownMenu(menu, { menu = false }) {
                    if (playlist.rule != null) DropdownMenuItem(text = { Text("Edit smart rule") }, onClick = { menu = false; edit(playlist) })
                    DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${playlist.name}?") },
        text = { Text("Only the playlist is removed. Audio files are not deleted.") },
        confirmButton = { TextButton(onClick = { delete(playlist.id); confirmDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun MiniPlayer(model: MusicViewModel, expand: () -> Unit) {
    val state by model.playing.collectAsStateWithLifecycle()
    val song = state.song ?: return
    PrefetchArtwork(state.queue.getOrNull(state.nextIndex))
    val position = rememberPlaybackPosition(state)
    Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(InputSurface).clickable(onClick = expand).testTag("mini-player")) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song, Modifier.size(42.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(song.artist + if (song.isServer) " / Server" else "", color = Muted, maxLines = 1, fontSize = 10.sp)
            }
            RepeatModeButton(state, model::repeat, "repeat-mode-mini")
            IconButton(onClick = model::togglePlay) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", tint = Pink) }
            IconButton(onClick = model::next) { Icon(Icons.Rounded.SkipNext, "Next track", tint = Cyan) }
        }
        LinearProgressIndicator(progress = { if (state.duration > 0) (position.value.toFloat() / state.duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp).testTag("mini-player-progress"),
            color = Lime, trackColor = Surface, drawStopIndicator = {})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlaying(model: MusicViewModel, modifier: Modifier, close: () -> Unit, settings: () -> Unit,
    equalizer: () -> Unit, add: (Song) -> Unit, details: (Song) -> Unit,
    controlsPositioned: (Float) -> Unit = {}) {
    val state by model.playing.collectAsStateWithLifecycle()
    val audio by model.audio.collectAsStateWithLifecycle()
    val song = state.song
    PrefetchArtwork(state.queue.getOrNull(state.nextIndex))
    var queue by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize().testTag("now-playing")) {
    val compact = maxHeight < 580.dp
    val dense = maxHeight < 400.dp
    val wide = maxWidth >= 592.dp && maxWidth >= maxHeight
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().testTag("now-playing-toolbar"), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Close now playing") }
            Text("NOW PLAYING", Modifier.weight(1f), color = Lime, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = equalizer, modifier = Modifier.size(48.dp).testTag("now-playing-equalizer")) {
                Icon(Icons.Rounded.Equalizer, "Open equalizer", tint = Muted)
            }
            IconButton(onClick = { queue = true }, modifier = Modifier.size(48.dp).testTag("now-playing-queue")) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue (${state.queue.size})", tint = Muted)
            }
            IconButton(onClick = settings, modifier = Modifier.size(48.dp).testTag("now-playing-settings")) {
                Icon(Icons.Rounded.Settings, "Settings", tint = Muted)
            }
            if (song != null) SongActions(song, model, add, details) { open ->
                IconButton(onClick = open, modifier = Modifier.size(48.dp).testTag("now-playing-options")) {
                    Icon(Icons.Rounded.MoreHoriz, "Track options: ${song.title}", tint = Muted)
                }
            }
        }
            if (audio.playbackError.isNotBlank()) {
                Row(Modifier.fillMaxWidth().testTag("playback-error"), verticalAlignment = Alignment.CenterVertically) {
                    Text(audio.playbackError, Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = model::togglePlay) { Text("Retry") }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp).testTag("now-playing-wide"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FittedArtwork(song, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        NowPlayingMetadata(song, audio, compact, dense)
                        NowPlayingControls(state, model, controlsPositioned)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp)) {
                    FittedArtwork(song, Modifier.fillMaxWidth().weight(1f))
                    Spacer(Modifier.height(8.dp))
                    NowPlayingMetadata(song, audio, compact, dense)
                    NowPlayingControls(state, model, controlsPositioned)
                }
            }
    }
    }
    if (queue) ModalBottomSheet(onDismissRequest = { queue = false }, containerColor = Surface) {
        val (mode, changeMode) = rememberBrowseMode("queue", BrowseMode.LIST)
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            ViewModeMenu("queue", mode, changeMode)
            TextButton(onClick = model::clearQueue) { Text("Clear queue") }
        }
        BrowseGrid(mode, Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("queue-list")) {
            itemsIndexed(state.queue, key = { index, item -> "$index:${item.uri}" }) { index, item ->
                var options by remember { mutableStateOf(false) }
                BrowseItem(item.title, item.artist, mode, song = item, onClick = { model.queueJump(index) }, leading = {
                    Text(if (index == state.index) ">" else "${index + 1}", color = if (index == state.index) Lime else Muted,
                        fontSize = 11.sp, modifier = Modifier.width(26.dp))
                }, actions = {
                  if (mode.grid || mode == BrowseMode.COMPACT) Box {
                    BrowseActionButton(onClick = { options = true }) { Icon(Icons.Rounded.MoreVert, "Queue options: ${item.title}") }
                    DropdownMenu(options, { options = false }) {
                        DropdownMenuItem(text = { Text("Move up in queue") }, enabled = index > 0,
                            onClick = { model.queueMove(index, index - 1); options = false })
                        DropdownMenuItem(text = { Text("Move down in queue") }, enabled = index < state.queue.lastIndex,
                            onClick = { model.queueMove(index, index + 1); options = false })
                        DropdownMenuItem(text = { Text("Remove from queue") }, onClick = { model.queueRemove(index); options = false })
                    }
                  } else Row {
                    BrowseActionButton(onClick = { model.queueMove(index, index - 1) }, enabled = index > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up in queue") }
                    BrowseActionButton(onClick = { model.queueMove(index, index + 1) }, enabled = index < state.queue.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down in queue") }
                    BrowseActionButton(onClick = { model.queueRemove(index) }) { Icon(Icons.Rounded.Close, "Remove from queue", Modifier.size(18.dp)) }
                  }
                })
            }
        }
    }
}

@Composable
private fun FittedArtwork(song: Song?, modifier: Modifier) {
    BoxWithConstraints(modifier.testTag("now-playing-artwork-space"), contentAlignment = Alignment.Center) {
        Artwork(song, Modifier.size(minOf(maxWidth, maxHeight)).testTag("now-playing-artwork"))
    }
}

@Composable
private fun NowPlayingMetadata(song: Song?, audio: AudioUi, compact: Boolean, dense: Boolean) {
    val currentAudio = audio.takeIf { it.sourceUri == song?.uri }
    val catalog = song?.catalogNumber.orEmpty().ifBlank { currentAudio?.catalogNumber.orEmpty() }
    val smallText = if (dense) 10.sp else if (compact) 11.sp else 12.sp
    val smallLine = if (dense) 12.sp else 14.sp
    val navigate = LocalSongNavigation.current
    val destinations = song?.let(::songDestinations).orEmpty()
    fun link(label: String): Modifier {
        val destination = destinations.firstOrNull { it.label == label }
        return if (song != null && destination != null && navigate != null) {
            Modifier.clickable(role = Role.Button, onClickLabel = label) { navigate(song, destination) }
        } else Modifier
    }
    val linkColor = Lime
    val genreText = buildAnnotatedString {
        append("Genres: ")
        val genres = destinations.filter { it.category == LibraryCategory.GENRES }
        if (genres.isEmpty()) append("-")
        genres.forEachIndexed { index, destination ->
            if (index > 0) append(", ")
            if (song != null && navigate != null) {
                withLink(LinkAnnotation.Clickable("genre:${destination.value}", styles = TextLinkStyles(SpanStyle(color = linkColor)),
                    linkInteractionListener = { navigate(song, destination) })) { append(destination.value) }
            } else append(destination.value)
        }
    }
    Column(Modifier.fillMaxWidth().testTag("now-playing-metadata"), verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 2.dp)) {
        Text(song?.artist.orEmpty(), color = Muted, fontSize = if (dense) 11.sp else if (compact) 13.sp else 14.sp,
            lineHeight = if (dense) 14.sp else if (compact) 16.sp else 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("now-playing-artist").then(link("Go to artist")))
        Text(song?.title ?: "No track selected", fontWeight = FontWeight.Bold, fontSize = if (dense) 17.sp else if (compact) 18.sp else 22.sp,
            lineHeight = if (dense) 20.sp else if (compact) 22.sp else 26.sp, maxLines = if (compact) 1 else 2,
            minLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("now-playing-title"))
        Text(song?.album.orEmpty(), fontSize = if (compact) 12.sp else 13.sp, lineHeight = if (dense) 14.sp else 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("now-playing-album").then(link("Go to album")))
        Text(taggedYear(currentAudio?.sourceYear, song?.year).ifBlank { "-" }, color = Muted, fontSize = smallText,
            lineHeight = smallLine, maxLines = 1,
            modifier = Modifier.testTag("now-playing-year"))
        Box(Modifier.fillMaxWidth().heightIn(min = with(LocalDensity.current) { smallLine.toDp() })) {
            if (catalog.isNotBlank()) Text("Catalog No. $catalog", color = Muted, fontSize = smallText,
                lineHeight = smallLine, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("now-playing-catalog"))
        }
        Text(genreText, color = Muted, fontSize = smallText,
            lineHeight = smallLine, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("now-playing-genres"))
        if (song != null) Text(trackFormatLabel(song, currentAudio?.sourceCodec.orEmpty(),
            currentAudio?.sourceBitrate?.takeIf { it > 0 } ?: song.bitrate).ifBlank { "-" },
            color = Lime, fontSize = smallText, lineHeight = smallLine, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("now-playing-format"))
    }
}

@Composable
private fun NowPlayingControls(state: PlayingUi, model: MusicViewModel, controlsPositioned: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().requiredHeight(108.dp).testTag("now-playing-controls")
        .onGloballyPositioned { controlsPositioned(it.boundsInRoot().top) }) {
    PlaybackSeekbar(state, model::seek)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        IconButton(onClick = model::shuffle, modifier = Modifier.size(48.dp).semantics { stateDescription = if (state.shuffle) "On" else "Off" }) {
            Icon(Icons.Rounded.Shuffle, "Toggle shuffle", tint = if (state.shuffle) Lime else Muted)
        }
        IconButton(onClick = model::previous, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous track", Modifier.size(28.dp)) }
        FilledIconButton(onClick = model::togglePlay, modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Pink, contentColor = Ink)) {
            if (state.buffering) CircularProgressIndicator(Modifier.size(24.dp), color = Ink, strokeWidth = 2.dp)
            else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.playing) "Pause playback" else "Resume playback", Modifier.size(30.dp))
        }
        IconButton(onClick = model::next, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.SkipNext, "Next track", Modifier.size(28.dp)) }
        RepeatModeButton(state, model::repeat, "repeat-mode-player")
    }
    }
}

@Composable
private fun RepeatModeButton(state: PlayingUi, change: () -> Unit, tag: String) {
    val active = state.repeat != Player.REPEAT_MODE_OFF
    IconButton(onClick = change, enabled = state.connected,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Lime.copy(alpha = if (active) .12f else 0f)),
        modifier = Modifier.size(48.dp).testTag(tag).semantics {
            stateDescription = when (state.repeat) {
                Player.REPEAT_MODE_ONE -> "One"; Player.REPEAT_MODE_ALL -> "All"; else -> "Off"
            }
        }) {
        Icon(if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            "Cycle repeat mode", tint = if (active) Lime else Muted)
    }
}

@Composable
private fun PlaybackSeekbar(state: PlayingUi, seek: (Long) -> Unit) {
    val position by rememberPlaybackPosition(state)
    var seeking by remember(state.song?.uri) { mutableStateOf<Float?>(null) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(durationLabel(seeking?.toLong() ?: if (state.duration > 0) position else state.position),
            color = Muted, fontSize = 11.sp, maxLines = 1)
        Slider(value = seeking ?: position.toFloat().coerceIn(0f, state.duration.coerceAtLeast(1).toFloat()),
            onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { seek(it.toLong()) }; seeking = null },
            valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(), enabled = state.duration > 0,
            modifier = Modifier.weight(1f).testTag("now-playing-seek"),
            colors = SliderDefaults.colors(thumbColor = Pink, activeTrackColor = Cyan, inactiveTrackColor = Surface))
        Text(durationLabel(state.duration), color = Muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Settings(model: MusicViewModel, data: LibraryData, permission: () -> Unit, folder: () -> Unit, equalizer: () -> Unit) {
    val theme by model.theme.collectAsStateWithLifecycle()
    val source by model.source.collectAsStateWithLifecycle()
    val server by model.server.collectAsStateWithLifecycle()
    val ratingImport by model.ratingImport.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val updates by model.updates.collectAsStateWithLifecycle()
    var confirmClearHistory by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 36.dp)) {
        Text("Settings", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Themes", Modifier.padding(top = 16.dp), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            AppTheme.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().selectable(selected = theme == option,
                    onClick = { model.setTheme(option) }, role = Role.RadioButton)
                    .testTag("theme-${option.name.lowercase()}").padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = theme == option, onClick = null)
                    Text(option.label, Modifier.weight(1f).padding(start = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(option.colorScheme.primary, option.colorScheme.secondary, option.colorScheme.background).forEach { color ->
                            Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(color)
                                .border(1.dp, option.colorScheme.outline, RoundedCornerShape(4.dp)))
                        }
                    }
                }
            }
        }
        Text("Server", Modifier.padding(top = 16.dp), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(if (server.configured) "${server.url}\n${server.data.songs.size} cached tracks / updated ${playedAtLabel(server.updated.takeIf { it > 0 })}"
            else "No Navidrome server configured", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        TextButton(onClick = model::openServerSetup, enabled = server.initialized) { Text("Configure Navidrome server") }
        if (server.configured) {
            TextButton(onClick = model::refreshServer, enabled = !server.refreshing) { Text("Refresh server library") }
            TextButton(onClick = model::clearServerCache, enabled = !server.refreshing) { Text("Clear server catalog cache") }
        }
        Text("Connect Tailscale on your Android device and NAS when using the server's Tailscale address. Streaming requires connectivity; cached metadata does not make audio available offline.",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        server.error?.let { Text(it, color = Pink, fontSize = 11.sp) }
        Column(Modifier.testTag("settings-audio-output")) {
            Text("Audio output", Modifier.padding(top = 16.dp), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = equalizer, modifier = Modifier.testTag("settings-equalizer")) { Icon(Icons.Rounded.Equalizer, "Open equalizer", Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Equalizer") }
        }
        if (source == LibrarySource.LOCAL) {
        Text("Library", Modifier.padding(top = 16.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text("${data.songs.size} indexed tracks / ${data.folders.size} selected folders", color = Muted, fontSize = 12.sp)
        TextButton(onClick = folder) { Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add music folder") }
        TextButton(onClick = permission) { Text("Grant device music access") }
        ScanMode.entries.forEach { mode ->
            TextButton(onClick = { model.rescan(mode) }, enabled = !library.scanning && !ratingImport.running) { Text(mode.label) }
        }
        library.lastScan?.let { Text(it.summary, color = Lime, fontSize = 11.sp) }
        Text("New-track scans query Android's index changes without walking folders or opening audio files. Android must index newly copied files first. Deep scan checks selected folders for unindexed files and is slower. Refresh reconciles tags and deleted files; offline cards remain cached.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Surface)
        Text("Listening history", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text("Plays count after 30 seconds, or half the duration for tracks under one minute.",
            color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
        TextButton(onClick = { confirmClearHistory = true }) { Text("Clear play history and counts") }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { model.checkForUpdates(manual = true) }, enabled = !updates.checking,
            modifier = Modifier.testTag("check-for-updates")) {
            Text(if (updates.checking) "Checking for updates..." else "Check for updates")
        }
        updates.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
        updates.status?.let { Text(it, color = Muted, fontSize = 11.sp, modifier = Modifier.testTag("update-check-status")) }
        Text("${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 11.sp)
    }
    if (confirmClearHistory) AlertDialog(onDismissRequest = { confirmClearHistory = false },
        title = { Text("Clear listening history?") },
        text = { Text("This removes device history and play counts for the selected source. Music files, ratings and playlists are unchanged.") },
        confirmButton = { TextButton(onClick = { model.clearHistory(); confirmClearHistory = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") } })
}

@Composable
internal fun AppUpdateDialog(update: AppUpdate, dismiss: () -> Unit, download: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Update available") },
        text = { Text("murrmurr ${update.version} is available. Download the APK from GitHub and install it over this app to keep your data.") },
        confirmButton = { TextButton(onClick = download) { Text("Download") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Later") } })
}

@Composable
private fun RatingPicker(rating: Int, compact: Boolean = false, change: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (stars in 1..5) {
            IconButton(onClick = { change(stars) }, modifier = Modifier.size(if (compact) 36.dp else 42.dp)) {
                Icon(if (stars <= rating) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Rate $stars stars", tint = if (stars <= rating) Lime else Muted, modifier = Modifier.size(20.dp))
            }
        }
        TextButton(onClick = { change(0) }) { Text("Clear", fontSize = 11.sp) }
    }
}

@Composable
private fun SmartPlaylistDialog(existing: MusicPlaylist?, dismiss: () -> Unit, save: (String, RatingRule) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var comparison by remember(existing?.id) { mutableStateOf(existing?.rule?.comparison ?: RatingComparison.AT_LEAST) }
    var stars by remember(existing?.id) { mutableIntStateOf(existing?.rule?.stars ?: 4) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (existing == null) "New smart playlist" else "Edit smart playlist") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Playlist name") })
            Text("Rating", Modifier.padding(top = 16.dp), fontWeight = FontWeight.SemiBold)
            Column {
                RatingComparison.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable { comparison = option }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(comparison == option, { comparison = option })
                        Text(option.label)
                    }
                }
            }
            RatingPicker(stars, change = { stars = it })
            Text(if (stars == 0) "0 = unrated" else "$stars stars", color = Lime, fontSize = 12.sp)
            Text("Tracks enter or leave this playlist automatically when their ${stringResource(R.string.app_name)} rating changes. PC ratings are not imported.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(name, RatingRule(comparison, stars)) }) { Text(if (existing == null) "Create" else "Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
