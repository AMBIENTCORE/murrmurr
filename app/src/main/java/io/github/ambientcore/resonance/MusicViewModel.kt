@file:Suppress("UnsafeOptInUsageError")

package io.github.ambientcore.resonance

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class LibraryUi(
    val data: LibraryData = LibraryData(),
    val loading: Boolean = true,
    val scanning: Boolean = false,
    val progress: String = "",
    val message: String? = null,
    val sort: SongSort = SongSort.TITLE,
    val lastScan: ScanStats? = null,
)

data class PlayingUi(
    val connected: Boolean = false,
    val song: Song? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val queue: List<Song> = emptyList(),
    val index: Int = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val positionSampleTimeMs: Long = 0,
    val playbackSpeed: Float = 1f,
    val nextIndex: Int = -1,
)

data class AudioUi(
    val audioInfo: String = "",
    val outputInfo: String = "",
    val sourceBits: Int = 0,
    val sourceLossy: Boolean = false,
    val outputBits: Int = 0,
    val outputFloating: Boolean = false,
    val sourceNote: String = "",
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val outputSampleRate: Int = 0,
    val sourceUri: String = "",
    val sourceCodec: String = "",
    val sourceBitrate: Int = 0,
    val catalogNumber: String = "",
    val sourceYear: String = "",
    val playbackError: String = "",
) {
    val explanation: String get() = sourceNote.ifBlank {
        explainAudioPrecision(sourceBits, sourceLossy, outputBits, outputFloating)
    }
}

class MusicViewModel(application: Application) : AndroidViewModel(application), SharedPreferences.OnSharedPreferenceChangeListener {
    private val repository = LibraryRepository(application)
    private val prefs = application.getSharedPreferences("playback", Application.MODE_PRIVATE)
    private val mutableTheme = MutableStateFlow(readTheme())
    val theme = mutableTheme.asStateFlow()
    private val historyRepository = HistoryRepository(application)
    private val libraryMutex = Mutex()
    private val mutableLibrary = MutableStateFlow(LibraryUi())
    val localLibrary = mutableLibrary.asStateFlow()
    private val mutableSource = MutableStateFlow(LibrarySource.LOCAL)
    val source = mutableSource.asStateFlow()
    private val mutableServer = MutableStateFlow(ServerUi())
    val server = mutableServer.asStateFlow()
    private val mutableServerSetup = MutableStateFlow(ServerSetupUi())
    val serverSetup = mutableServerSetup.asStateFlow()
    private val accountStore = ServerAccountStore(application)
    private var account: ServerAccount? = null
    private var testedAccount: ServerAccount? = null
    private var serverJob: Job? = null
    private var setupJob: Job? = null
    private val serverMutex = Mutex()
    val library = combine(mutableLibrary, mutableSource, mutableServer) { local, source, server ->
        if (source == LibrarySource.LOCAL) local
        else local.copy(data = server.data, loading = !server.initialized, scanning = server.refreshing,
            progress = server.progress, sort = server.sort, lastScan = null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUi())
    private val mutablePlaying = MutableStateFlow(PlayingUi())
    val playing = mutablePlaying.asStateFlow()
    private val mutableAudio = MutableStateFlow(AudioUi())
    val audio = mutableAudio.asStateFlow()
    private val mutableUpdates = MutableStateFlow(UpdateUi())
    val updates = mutableUpdates.asStateFlow()
    private var lastUpdateCheck: Long? = null
    private var dismissedUpdate: String? = null
    private val mutableHistory = MutableStateFlow(HistoryUi())
    val history = combine(mutableHistory, mutableSource, mutableServer) { history, source, server ->
        history.copy(data = history.data.forSource(source, server.key))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUi())
    private val mutableRatingImport = MutableStateFlow(RatingImportUi())
    val ratingImport = mutableRatingImport.asStateFlow()
    private val mutablePlaylistBatch = MutableStateFlow<PlaylistBatchPreview?>(null)
    val playlistBatch = mutablePlaylistBatch.asStateFlow()
    private val mutablePlaylistCommit = MutableStateFlow(false)
    val playlistCommit = mutablePlaylistCommit.asStateFlow()
    private var playlistPreparing = false
    private var historyLoad: Job? = null
    private var stopRatingImport: AtomicBoolean? = null
    private var controller: MediaController? = null
    private val future = MediaController.Builder(application, SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private var cachedTimeline: androidx.media3.common.Timeline? = null
    private var cachedQueue: List<Song> = emptyList()

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
        updateAudio()
        reloadHistory()
        future.addListener({
            try {
                controller = future.get().also { control ->
                    control.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) { updatePlaying() }
                    })
                }
                updatePlaying()
            } catch (error: Exception) { showError("Playback service connection failed", error) }
        }, ContextCompat.getMainExecutor(application))
        viewModelScope.launch {
            try {
                val configured = withContext(Dispatchers.IO) { accountStore.load() }
                account = configured
                mutableServer.value = ServerUi(configured = configured != null,
                    url = configured?.url.orEmpty(), username = configured?.username.orEmpty(),
                    allowHttp = configured?.allowHttp ?: false, key = configured?.key.orEmpty())
                if (configured != null) {
                    try {
                        val cached = withContext(Dispatchers.IO) {
                            val store = ServerCatalogStore(application, configured.key)
                            store.load() to store.modified()
                        }
                        mutableServer.update { it.copy(data = cached.first, updated = cached.second) }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        serverError("Cannot open cached server catalog; refresh to rebuild it", error)
                    }
                }
                mutableServer.update { it.copy(initialized = true) }
                if (configured != null && prefs.getString("library_source", "LOCAL") == "SERVER") {
                    mutableSource.value = LibrarySource.SERVER
                    refreshServer()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                serverError("Cannot open saved server configuration or catalog", error)
                mutableServer.update { it.copy(initialized = true) }
            }
        }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { repository.load() }
                mutableLibrary.value = mutableLibrary.value.copy(data = data, loading = false)
                if (data.songs.isEmpty()) rescan()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableLibrary.value = mutableLibrary.value.copy(loading = false)
                showError("Could not load saved library; rescan to rebuild it", error)
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(500)
                controller?.takeIf { it.isPlaying }?.let {
                    mutablePlaying.value = mutablePlaying.value.copy(position = it.currentPosition.coerceAtLeast(0),
                        positionSampleTimeMs = android.os.SystemClock.elapsedRealtime(),
                        playbackSpeed = it.playbackParameters.speed)
                }
            }
        }
    }

    fun checkForUpdates(manual: Boolean = false) {
        if (mutableUpdates.value.checking) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!manual && lastUpdateCheck?.let { now - it < 15 * 60_000L } == true) return
        lastUpdateCheck = now
        mutableUpdates.update { it.copy(checking = true, error = null, status = null) }
        viewModelScope.launch {
            try {
                val update = withContext(Dispatchers.IO) { latestAppUpdate() }
                mutableUpdates.value = UpdateUi(available = update?.takeIf { manual || it.version != dismissedUpdate },
                    status = if (update == null) "You're using the latest version." else "Version ${update.version} is available.")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("MurrmurrUpdates", "GitHub update check failed", error)
                val text = "Could not check for updates: ${error.message}"
                mutableUpdates.update { it.copy(checking = false, error = text) }
                if (manual) message(text)
            }
        }
    }

    fun dismissUpdate() {
        dismissedUpdate = mutableUpdates.value.available?.version
        mutableUpdates.update { it.copy(available = null) }
    }

    fun rescan(mode: ScanMode = ScanMode.NEW_TRACKS) {
        if (mutableSource.value == LibrarySource.SERVER) { refreshServer(); return }
        if (mutableLibrary.value.scanning || mutableLibrary.value.loading) return
        if (mutableRatingImport.value.running) { message("Stop the rating import before scanning"); return }
        mutableLibrary.value = mutableLibrary.value.copy(scanning = true, progress = mode.label)
        viewModelScope.launch {
            try {
                libraryMutex.withLock {
                    val old = mutableLibrary.value.data
                    val result = withContext(Dispatchers.IO) {
                        repository.scan(old, mode) { progress ->
                            mutableLibrary.update { it.copy(progress = progress) }
                        }
                    }
                    val updated = old.copy(songs = result.songs, checkpoints = result.checkpoints)
                    if (updated != old) withContext(Dispatchers.IO) { repository.save(updated) }
                    mutableLibrary.value = mutableLibrary.value.copy(data = updated,
                        lastScan = result.stats,
                        message = "${result.songs.size} tracks. ${result.stats.summary}" +
                            if (result.warnings.isNotEmpty()) ". ${result.warnings.take(3).joinToString("; ")}" else "")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError("Scan failed; previous library preserved", error)
            } finally { mutableLibrary.value = mutableLibrary.value.copy(scanning = false, progress = "") }
        }
    }

    fun addFolder(uri: Uri) {
        if (mutableSource.value == LibrarySource.SERVER) { message("Music folders are configured on Navidrome"); return }
        viewModelScope.launch {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                libraryMutex.withLock {
                    val updated = mutableLibrary.value.data.copy(folders = mutableLibrary.value.data.folders + uri.toString())
                    withContext(Dispatchers.IO) { repository.save(updated) }
                    mutableLibrary.value = mutableLibrary.value.copy(data = updated)
                }
                rescan()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError("Could not add folder", error)
            }
        }
    }

    fun sort(sort: SongSort) {
        if (mutableSource.value == LibrarySource.SERVER) mutableServer.update { it.copy(sort = sort) }
        else mutableLibrary.value = mutableLibrary.value.copy(sort = sort)
    }
    fun message(text: String?) { mutableLibrary.value = mutableLibrary.value.copy(message = text) }
    fun favorite(song: Song) {
        if (song.isServer) {
            editServerSong(song) { client, current ->
                val favorite = song.uri !in current.favorites
                client.request(if (favorite) "star" else "unstar", mapOf("id" to song.serverTrackId))
                current.copy(favorites = if (favorite) current.favorites + song.uri else current.favorites - song.uri)
            }
        } else editLibrary { it.copy(favorites = if (song.uri in it.favorites) it.favorites - song.uri else it.favorites + song.uri) }
    }
    fun rate(song: Song, stars: Int) {
        if (stars !in 0..5) { message("Ratings must be between 0 and 5 stars"); return }
        if (song.isServer) {
            editServerSong(song) { client, current ->
                client.request("setRating", mapOf("id" to song.serverTrackId, "rating" to "$stars"))
                current.copy(ratings = if (stars == 0) current.ratings - song.uri else current.ratings + (song.uri to stars))
            }
            return
        }
        editLibrary { it.copy(ratings = if (stars == 0) it.ratings - song.uri else it.ratings + (song.uri to stars),
            ratingOverrides = it.ratingOverrides + song.uri) }
    }

    fun importRatings(force: Boolean = false) {
        if (mutableSource.value == LibrarySource.SERVER) { message("Server ratings come from Navidrome, not device tags"); return }
        if (mutableRatingImport.value.running) { message("A rating import is already running"); return }
        if (mutableLibrary.value.loading || mutableLibrary.value.scanning) { message("Wait for the library scan to finish"); return }
        val snapshot = mutableLibrary.value.data
        if (snapshot.songs.isEmpty()) { message("Scan some music before importing ratings"); return }
        val stop = AtomicBoolean(false)
        stopRatingImport = stop
        mutableRatingImport.value = RatingImportUi(true, "Checking embedded rating tags")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    EmbeddedRatingImport(getApplication()).run(snapshot, force, stop) { text ->
                        mutableRatingImport.update { it.copy(progress = text) }
                    }
                }
                var changed = 0
                libraryMutex.withLock {
                    val current = mutableLibrary.value.data
                    val updated = mergeImportedRatings(current, result)
                    changed = (current.ratings.keys + updated.ratings.keys).count { current.ratings[it] != updated.ratings[it] }
                    withContext(Dispatchers.IO) { repository.save(updated) }
                    mutableLibrary.value = mutableLibrary.value.copy(data = updated)
                }
                val summary = "${if (result.cancelled) "Stopped. " else ""}$changed ratings updated; " +
                    "${result.read} files read, ${result.cached} unchanged, ${result.manual} local ratings preserved, " +
                    "${result.unsupported} unsupported, ${result.failed} errors." +
                    if (result.warnings.isNotEmpty()) " ${result.warnings.take(3).joinToString("; ")}" else ""
                mutableRatingImport.value = RatingImportUi(result = summary)
                message(summary)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError("Rating import failed; previous saved data preserved", error)
                mutableRatingImport.value = RatingImportUi(result = "Import failed: ${error.message}")
            } finally {
                stopRatingImport = null
                mutableRatingImport.update { it.copy(running = false, progress = "") }
            }
        }
    }

    fun cancelRatingImport() {
        val stop = stopRatingImport ?: run { message("No rating import is running"); return }
        stop.set(true)
        mutableRatingImport.update { it.copy(progress = "Stopping after the current file") }
    }

    private fun reloadHistory() {
        historyLoad?.cancel()
        historyLoad = viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { historyRepository.load() }
                mutableHistory.value = HistoryUi(data, loading = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("ResonanceHistory", "Cannot load listening history", error)
                mutableHistory.value = mutableHistory.value.copy(loading = false, error = "Cannot load history: ${error.message}")
                message("Cannot load listening history: ${error.message}")
            }
        }
    }

    fun clearHistory() {
        val source = mutableSource.value
        val serverKey = mutableServer.value.key
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    historyRepository.clearSource(source, serverKey)
                }
                message("Listening history and play counts cleared")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError("Could not clear listening history", error)
            }
        }
    }
    fun createPlaylist(name: String, song: Song? = null) {
        if (mutableSource.value == LibrarySource.SERVER || song?.isServer == true) { message("Manage server playlists in Navidrome"); return }
        if (name.isBlank()) { message("Enter a playlist name"); return }
        editLibrary { it.copy(playlists = it.playlists + MusicPlaylist(UUID.randomUUID().toString(), name.trim(), listOfNotNull(song?.uri))) }
    }
    fun createSmartPlaylist(name: String, rule: RatingRule) {
        if (mutableSource.value == LibrarySource.SERVER) { message("Manage server smart playlists in Navidrome"); return }
        if (name.isBlank()) { message("Enter a playlist name"); return }
        editLibrary { it.copy(playlists = it.playlists + MusicPlaylist(UUID.randomUUID().toString(), name.trim(), emptyList(), rule)) }
    }
    fun updateSmartPlaylist(id: String, name: String, rule: RatingRule) {
        if (name.isBlank()) { message("Enter a playlist name"); return }
        editLibrary { data ->
            require(data.playlists.any { it.id == id && it.rule != null }) { "Smart playlist not found" }
            data.copy(playlists = data.playlists.map { if (it.id == id) it.copy(name = name.trim(), rule = rule) else it })
        }
    }
    fun deletePlaylist(id: String) {
        if (id.startsWith("server:")) { message("Manage server playlists in Navidrome"); return }
        editLibrary { it.copy(playlists = it.playlists.filterNot { playlist -> playlist.id == id }) }
    }
    fun addToPlaylist(id: String, song: Song) = editLibrary { data ->
        require(data.playlists.any { it.id == id && it.rule == null }) { "Only normal playlists accept manually added tracks" }
        data.copy(playlists = data.playlists.map { if (it.id == id && song.uri !in it.songs) it.copy(songs = it.songs + song.uri) else it })
    }
    fun removeFromPlaylist(id: String, uri: String) = editLibrary { data ->
        require(data.playlists.any { it.id == id && it.rule == null }) { "Smart playlists update automatically from ratings" }
        data.copy(playlists = data.playlists.map { if (it.id == id) it.copy(songs = it.songs.filterNot { item -> item == uri }) else it })
    }

    private fun editLibrary(edit: (LibraryData) -> LibraryData) {
        viewModelScope.launch {
            try {
                libraryMutex.withLock {
                    val updated = edit(mutableLibrary.value.data)
                    withContext(Dispatchers.IO) { repository.save(updated) }
                    mutableLibrary.value = mutableLibrary.value.copy(data = updated)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError("Could not save library changes", error)
            }
        }
    }

    fun importPlaylist(uri: Uri) {
        if (mutableSource.value == LibrarySource.SERVER) { message("Import device playlists in Local mode"); return }
        viewModelScope.launch {
            try {
                val input = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: uri.lastPathSegment ?: "Imported playlist.m3u"
                    val stream = resolver.openInputStream(uri) ?: error("Cannot open playlist")
                    val text = stream.bufferedReader().use { reader ->
                        val result = StringBuilder()
                        val buffer = CharArray(8192)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            require(result.length + count <= 4_000_000) { "Playlist file is too large" }
                            result.append(buffer, 0, count)
                        }
                        result.toString()
                    }
                    name to text
                }
                if (input.first.endsWith(".json", true)) {
                    val parsed = parsePlaylistBundle(input.second)
                    stagePlaylists(parsed.first, parsed.second)
                } else {
                    val entries = input.second.removePrefix("\uFEFF").lineSequence().map(String::trim)
                        .filter { it.isNotBlank() && !it.startsWith("#") }.toList()
                    stagePlaylists(listOf(PlaylistInput("m3u:$uri", input.first.substringBeforeLast('.'), entries)), emptyList())
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError("Could not prepare playlist import", error)
            }
        }
    }

    fun preparePlaylistBundle(text: String) {
        if (mutableSource.value == LibrarySource.SERVER) { message("Switch to Local before importing a device playlist bundle"); return }
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) { parsePlaylistBundle(text) }
                stagePlaylists(parsed.first, parsed.second)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError("Cannot prepare playlist transfer", error)
            }
        }
    }

    private fun parsePlaylistBundle(text: String): Pair<List<PlaylistInput>, List<String>> {
        require(text.length <= 1_000_000) { "Playlist bundle is too large" }
        val root = org.json.JSONObject(text)
        require(root.get("version") == 1) { "Unsupported playlist bundle version" }
        val inputs = root.getJSONArray("playlists").objects().map {
            val key = it.get("sourceKey") as? String ?: error("Playlist source must be text")
            val name = it.get("name") as? String ?: error("Playlist name must be text")
            val entries = it.getJSONArray("entries")
            PlaylistInput(key, name.trim(), (0 until entries.length()).map { index ->
                entries.get(index) as? String ?: error("Playlist entries must be paths")
            })
        }
        require(inputs.isNotEmpty() && inputs.size <= 500) { "Bundle must contain 1-500 playlists" }
        require(inputs.map { it.sourceKey }.distinct().size == inputs.size) { "Duplicate playlist sources in bundle" }
        require(inputs.sumOf { it.entries.size } <= 20_000) { "Too many playlist entries" }
        inputs.forEach {
            require(it.sourceKey.isNotBlank() && it.sourceKey.length <= 1024 && it.name.isNotBlank() && it.name.length <= 256) { "Invalid playlist name/source" }
            require(it.entries.all { entry -> entry.length <= 4096 && !entry.contains('\u0000') }) { "Invalid playlist entry" }
        }
        val unsupported = root.optJSONArray("unsupported")?.length() ?: 0
        return inputs to if (unsupported > 0) listOf("$unsupported smart definitions were not exported as track lists and are not included.") else emptyList()
    }

    private suspend fun stagePlaylists(inputs: List<PlaylistInput>, notices: List<String>) {
        require(mutablePlaylistBatch.value == null && !playlistPreparing) { "Finish or cancel the current playlist preview first" }
        playlistPreparing = true
        try {
            require(inputs.sumOf { it.entries.size } <= 20_000) { "Too many playlist entries" }
            val snapshot = mutableLibrary.filter { !it.loading && !it.scanning }.first().data
            require(snapshot.songs.isNotEmpty()) { "Scan the music library before importing playlists" }
            val prepared = withContext(Dispatchers.IO) {
                val matched = preparePlaylists(inputs, snapshot.songs)
                val available = matched.flatMap { it.songs }.toSet().associateWith { uri ->
                    try {
                        val descriptor = getApplication<Application>().contentResolver.openFileDescriptor(Uri.parse(uri), "r")
                        if (descriptor == null) throw java.io.IOException("No readable audio descriptor")
                        descriptor.use { true }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        Log.w("ResonancePlaylists", "Indexed audio is unavailable: $uri", error)
                        false
                    }
                }
                matched.map { playlist ->
                    val usable = playlist.songs.filter { available[it] == true }
                    playlist.copy(songs = usable, unavailable = playlist.songs.size - usable.size)
                }
            }
            mutablePlaylistBatch.value = PlaylistBatchPreview(prepared, notices)
        } finally { playlistPreparing = false }
    }

    fun dismissPlaylistBatch() { if (!mutablePlaylistCommit.value) mutablePlaylistBatch.value = null }

    fun commitPlaylistBatch() {
        val preview = mutablePlaylistBatch.value ?: run { message("No playlist import is pending"); return }
        if (mutablePlaylistCommit.value) { message("Playlist import is already being saved"); return }
        mutablePlaylistCommit.value = true
        viewModelScope.launch {
            try {
                libraryMutex.withLock {
                    val current = mutableLibrary.value.data
                    val indexed = current.songs.mapTo(hashSetOf()) { it.uri }
                    val playlists = current.playlists.toMutableList()
                    preview.playlists.forEach { imported ->
                        val index = playlists.indexOfFirst { it.sourceKey == imported.sourceKey }
                        val songs = imported.songs.filter { it in indexed }
                        if (index >= 0) playlists[index] = playlists[index].copy(name = imported.name, songs = songs, rule = null)
                        else playlists += MusicPlaylist(UUID.randomUUID().toString(), imported.name, songs, sourceKey = imported.sourceKey)
                    }
                    val updated = current.copy(playlists = playlists)
                    withContext(Dispatchers.IO) { repository.save(updated) }
                    mutableLibrary.value = mutableLibrary.value.copy(data = updated)
                }
                mutablePlaylistBatch.value = null
                message("${preview.playlists.size} playlists imported; ${preview.matched} entries matched, " +
                    "${preview.missing} missing, ${preview.ambiguous} ambiguous, ${preview.unavailable} unavailable.")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError("Could not save imported playlists", error)
            } finally { mutablePlaylistCommit.value = false }
        }
    }

    fun exportPlaylist(uri: Uri, songs: List<Song>) {
        if (songs.any { it.isServer }) { message("Export server playlists from Navidrome"); return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open destination")
                    stream.bufferedWriter().use { it.write(exportM3u(songs)) }
                }
                message("Playlist exported")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError("Could not export playlist", error)
            }
        }
    }

    fun play(songs: List<Song>, index: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) { message("Add some music first"); return }
        val control = controller ?: run { message("Player is connecting; try again shortly"); return }
        control.shuffleModeEnabled = shuffle
        control.setMediaItems(songs.map(Song::mediaItem), index.coerceIn(songs.indices), 0)
        control.prepare()
        control.play()
    }
    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun editEqualizer(change: (EqualizerSettings) -> EqualizerSettings): Boolean {
        return try {
            val updated = change(mutableAudio.value.equalizer)
            writeEqualizer(prefs, updated)
            true
        } catch (error: Exception) {
            showError("Could not update equalizer", error)
            false
        }
    }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() {
        controller?.let {
            if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0)
        }
    }
    fun seek(position: Long) {
        controller?.let { it.seekTo(position); updatePlaying() }
    }
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() {
        controller?.let {
            val next = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            it.repeatMode = next
        }
    }
    fun queue(song: Song, next: Boolean = false) {
        val control = controller ?: run { message("Player is not connected"); return }
        if (next) control.addMediaItem((control.currentMediaItemIndex + 1).coerceIn(0, control.mediaItemCount), song.mediaItem())
        else control.addMediaItem(song.mediaItem())
        message(if (next) "Will play next" else "Added to queue")
    }
    fun queueJump(index: Int) { controller?.let { it.seekToDefaultPosition(index); it.prepare(); it.play() } }
    fun queueRemove(index: Int) { controller?.removeMediaItem(index) }
    fun clearQueue() { controller?.let { it.stop(); it.clearMediaItems() } }
    fun queueMove(index: Int, to: Int) { controller?.let { if (to in 0 until it.mediaItemCount) it.moveMediaItem(index, to) } }

    private fun updatePlaying() {
        val control = controller ?: return
        if (cachedTimeline !== control.currentTimeline) {
            cachedTimeline = control.currentTimeline
            cachedQueue = (0 until control.mediaItemCount).mapNotNull { index ->
                control.getMediaItemAt(index).mediaMetadata.extras?.getString("song_json")?.let {
                    LibraryRepository.songFromJson(org.json.JSONObject(it))
                }
            }
        }
        val songs = cachedQueue
        mutablePlaying.value = PlayingUi(true, songs.getOrNull(control.currentMediaItemIndex), control.isPlaying,
            control.playbackState == Player.STATE_BUFFERING, control.currentPosition.coerceAtLeast(0),
            control.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: songs.getOrNull(control.currentMediaItemIndex)?.duration ?: 0,
            songs, control.currentMediaItemIndex, control.shuffleModeEnabled, control.repeatMode,
            android.os.SystemClock.elapsedRealtime(), control.playbackParameters.speed, control.nextMediaItemIndex)
    }

    private fun updateAudio() {
        val tone = try { readEqualizer(prefs) } catch (error: Exception) {
            Log.e("ResonanceEQ", "Invalid saved EQ settings", error)
            message("Equalizer settings could not be read; EQ disabled. Reset it in Equalizer.")
            EqualizerSettings()
        }
        mutableAudio.value = AudioUi(
            prefs.getString("audio_info", "").orEmpty(), prefs.getString("output_info", "").orEmpty(),
            prefs.getInt("source_bits", 0), prefs.getBoolean("source_lossy", false),
            prefs.getInt("output_bits", 0), prefs.getBoolean("output_float", false),
            prefs.getString("source_note", "").orEmpty(),
            tone, prefs.getInt("output_sample_rate", 0),
            prefs.getString("source_uri", "").orEmpty(), prefs.getString("source_codec", "").orEmpty(),
            prefs.getInt("source_bitrate", 0), prefs.getString("source_catalog_number", "").orEmpty(),
            prefs.getString("source_year", "").orEmpty(),
            prefs.getString("playback_error", "").orEmpty(),
        )
    }

    private fun readTheme(): AppTheme {
        val saved = prefs.getString(THEME_PREF, AppTheme.DEFAULT.name)
        val restored = AppTheme.entries.firstOrNull { it.name == saved }
        if (restored == null) Log.w("ResonanceTheme", "Unknown saved theme: $saved")
        return restored ?: AppTheme.DEFAULT
    }

    fun setTheme(theme: AppTheme) {
        mutableTheme.value = theme
        prefs.edit().putString(THEME_PREF, theme.name).apply()
    }

    fun switchSource() {
        if (mutableSource.value == LibrarySource.SERVER) {
            mutableSource.value = LibrarySource.LOCAL
            prefs.edit().putString("library_source", "LOCAL").apply()
        } else {
            if (!mutableServer.value.initialized) { message("Server configuration is loading"); return }
            if (account == null) { openServerSetup(); return }
            mutableSource.value = LibrarySource.SERVER
            prefs.edit().putString("library_source", "SERVER").apply()
            refreshServer()
        }
    }

    fun openServerSetup() { testedAccount = null; mutableServerSetup.value = ServerSetupUi(open = true) }
    fun invalidateServerTest() {
        setupJob?.cancel()
        testedAccount = null
        mutableServerSetup.value = ServerSetupUi(open = true)
    }
    fun closeServerSetup() {
        setupJob?.cancel()
        testedAccount = null
        mutableServerSetup.value = ServerSetupUi()
    }
    fun testServer(url: String, username: String, password: String, allowHttp: Boolean) {
        setupJob?.cancel()
        testedAccount = null
        mutableServerSetup.value = ServerSetupUi(open = true, running = true, message = "Testing connection")
        setupJob = viewModelScope.launch {
            try {
                val candidate = serverAccount(url, username, password.ifEmpty {
                    account?.takeIf { it.url == url.trim().trimEnd('/') && it.username == username.trim() }?.password.orEmpty()
                }, allowHttp)
                withContext(Dispatchers.IO) { NavidromeClient(candidate).request("ping") }
                ensureActive()
                testedAccount = candidate
                mutableServerSetup.value = ServerSetupUi(open = true, tested = true, message = "Connection successful")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("ResonanceServer", "Connection test failed (${error.javaClass.simpleName})")
                mutableServerSetup.value = ServerSetupUi(open = true, message = safeServerError(error))
            }
        }
    }
    fun saveServer() {
        val candidate = testedAccount ?: run { message("Test the connection before saving"); return }
        setupJob = viewModelScope.launch {
            mutableServerSetup.update { it.copy(running = true) }
            try {
                serverJob?.cancel()
                serverMutex.withLock {
                    val data = withContext(Dispatchers.IO) {
                        val cached = ServerCatalogStore(getApplication(), candidate.key).load()
                        accountStore.save(candidate)
                        cached
                    }
                    account = candidate
                    mutableServer.value = ServerUi(data = data, initialized = true, configured = true,
                        url = candidate.url, username = candidate.username, allowHttp = candidate.allowHttp, key = candidate.key,
                        updated = ServerCatalogStore(getApplication(), candidate.key).modified())
                }
                testedAccount = null
                mutableServerSetup.value = ServerSetupUi()
                mutableSource.value = LibrarySource.SERVER
                prefs.edit().putString("library_source", "SERVER").apply()
                refreshServer()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("ResonanceServer", "Could not save account (${error.javaClass.simpleName})")
                mutableServerSetup.update { it.copy(running = false, message = "Could not save the server account") }
            }
        }
    }
    fun refreshServer() {
        val currentAccount = account ?: run { openServerSetup(); return }
        if (mutableServer.value.refreshing) return
        mutableServer.update { it.copy(refreshing = true, error = null, progress = "Connecting to server") }
        serverJob = viewModelScope.launch {
            try {
                serverMutex.withLock {
                    require(account?.key == currentAccount.key) { "The server account changed; try again" }
                    val result = withContext(Dispatchers.IO) {
                        NavidromeClient(currentAccount).catalog({ progress ->
                            mutableServer.update { it.copy(progress = progress) }
                        }, { ensureActive() })
                    }
                    ensureActive()
                    withContext(Dispatchers.IO) { ServerCatalogStore(getApplication(), currentAccount.key).save(result) }
                    mutableServer.update { it.copy(data = result, updated = System.currentTimeMillis(), error = null) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                serverError("Server refresh failed; cached catalog preserved", error)
            } finally { mutableServer.update { it.copy(refreshing = false, progress = "") } }
        }
    }
    fun clearServerCache() {
        val account = account ?: run { message("No server is configured"); return }
        if (mutableServer.value.refreshing) { message("Wait for the server refresh before clearing its cache"); return }
        viewModelScope.launch {
            try {
                serverMutex.withLock {
                    withContext(Dispatchers.IO) { ServerCatalogStore(getApplication(), account.key).clear() }
                    mutableServer.update { it.copy(data = LibraryData(), updated = 0) }
                }
                message("Server catalog cleared; refresh to reload it")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                serverError("Could not clear server catalog", error)
            }
        }
    }
    private fun editServerSong(song: Song, edit: (NavidromeClient, LibraryData) -> LibraryData) {
        val account = account?.takeIf { it.key == song.serverKey } ?: run { message("Configure this track's server account first"); return }
        viewModelScope.launch {
            try {
                serverMutex.withLock {
                    require(this@MusicViewModel.account?.key == account.key) { "The server account changed; try again" }
                    val updated = withContext(Dispatchers.IO) {
                        val result = edit(NavidromeClient(account), mutableServer.value.data)
                        ServerCatalogStore(getApplication(), account.key).save(result)
                        result
                    }
                    mutableServer.update { it.copy(data = updated) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                serverError("Could not update server rating or favorite", error)
            }
        }
    }
    private fun safeServerError(error: Exception): String =
        if (error is ServerFailure || error is IllegalArgumentException) error.message.orEmpty()
        else "Invalid server response or unavailable saved data"
    private fun serverError(prefix: String, error: Exception) {
        Log.e("ResonanceServer", "$prefix (${error.javaClass.simpleName})")
        mutableServer.update { it.copy(error = "$prefix: ${safeServerError(error)}") }
        message("$prefix: ${safeServerError(error)}")
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "error") {
            prefs.getString("error", null)?.let { message(it); prefs.edit().remove("error").apply() }
        } else if (key == THEME_PREF) mutableTheme.value = readTheme()
        else if (key == "history_revision") reloadHistory()
        else if (key in setOf("audio_info", "output_info", "source_bits", "source_lossy", "output_bits", "output_float", "source_note", "output_sample_rate",
                "source_uri", "source_codec", "source_bitrate", "source_catalog_number", "source_year", "playback_error", EQUALIZER_PREF)) updateAudio()
    }

    private fun showError(message: String, error: Exception) { Log.e("Resonance", message, error); message("$message: ${error.message}") }

    override fun onCleared() {
        stopRatingImport?.set(true)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        MediaController.releaseFuture(future)
        super.onCleared()
    }
}
