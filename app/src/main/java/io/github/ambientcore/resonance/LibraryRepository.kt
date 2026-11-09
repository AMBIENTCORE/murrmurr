package io.github.ambientcore.resonance

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

data class LibraryData(
    val songs: List<Song> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val folders: Set<String> = emptySet(),
    val ratings: Map<String, Int> = emptyMap(),
    val checkpoints: Map<String, IndexCheckpoint> = emptyMap(),
    val ratingOverrides: Set<String> = ratings.keys,
    val ratingTagCache: Map<String, RatingStamp> = emptyMap(),
)

data class ScanStats(
    val added: Int = 0, val updated: Int = 0, val indexQueries: Int = 0, val indexRows: Int = 0,
    val audioFilesOpened: Int = 0, val directoriesListed: Int = 0, val elapsedMs: Long = 0,
) {
    val summary: String get() = "$added new, $updated updated / $indexRows index rows / " +
        "$audioFilesOpened audio reads / $directoriesListed folder reads / ${"%.2f".format(Locale.ROOT, elapsedMs / 1000.0)} s"
}

data class ScanResult(
    val songs: List<Song>, val warnings: List<String>,
    val checkpoints: Map<String, IndexCheckpoint> = emptyMap(), val stats: ScanStats = ScanStats(),
)

class LibraryRepository(private val context: Context, fileName: String = "library.json") {
    private val file = AtomicFile(File(context.filesDir, fileName))
    private val audioExtensions = setOf("mp3", "mp2", "flac", "m4a", "m4b", "mp4", "aac", "ogg", "oga", "opus",
        "wav", "aiff", "aif", "amr", "mka", "webm", "ac3", "eac3", "ec3")

    fun load(): LibraryData {
        if (!file.baseFile.exists()) return LibraryData()
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val ratings = json.optJSONObject("ratings") ?: JSONObject()
        val checkpoints = json.optJSONObject("checkpoints") ?: JSONObject()
        val ratingCache = json.optJSONObject("ratingTagCache") ?: JSONObject()
        return LibraryData(
            songs = json.getJSONArray("songs").objects().map(::songFromJson),
            playlists = json.getJSONArray("playlists").objects().map {
                val smart = it.optJSONObject("rule")
                MusicPlaylist(it.getString("id"), it.getString("name"), it.getJSONArray("songs").strings(),
                    smart?.let { rule -> RatingRule(RatingComparison.valueOf(rule.getString("comparison")), rule.getInt("stars")) },
                    it.optString("sourceKey"))
            },
            favorites = json.getJSONArray("favorites").strings().toSet(),
            folders = json.getJSONArray("folders").strings().toSet(),
            ratings = ratings.keys().asSequence().associateWith { ratings.getInt(it).coerceIn(0, 5) },
            checkpoints = checkpoints.keys().asSequence().associateWith { key ->
                checkpoints.getJSONObject(key).let { IndexCheckpoint(it.getString("version"), it.getLong("generation")) }
            },
            ratingOverrides = json.optJSONArray("ratingOverrides")?.strings()?.toSet()
                ?: ratings.keys().asSequence().toSet(),
            ratingTagCache = ratingCache.keys().asSequence().associateWith { key ->
                ratingCache.getJSONObject(key).let { RatingStamp(it.getLong("modified"), it.getLong("size")) }
            },
        )
    }

    fun save(data: LibraryData) {
        val checkpoints = JSONObject()
        val ratingCache = JSONObject()
        data.ratingTagCache.forEach { (uri, stamp) ->
            ratingCache.put(uri, JSONObject().put("modified", stamp.modified).put("size", stamp.size))
        }
        data.checkpoints.forEach { (volume, checkpoint) ->
            checkpoints.put(volume, JSONObject().put("version", checkpoint.version).put("generation", checkpoint.generation))
        }
        val json = JSONObject().put("songs", JSONArray(data.songs.map(::songToJson)))
            .put("playlists", JSONArray(data.playlists.map {
                JSONObject().put("id", it.id).put("name", it.name).put("songs", JSONArray(it.songs)).put("sourceKey", it.sourceKey).apply {
                    it.rule?.let { rule -> put("rule", JSONObject().put("comparison", rule.comparison.name).put("stars", rule.stars)) }
                }
            })).put("favorites", JSONArray(data.favorites.toList())).put("folders", JSONArray(data.folders.toList()))
            .put("ratings", JSONObject(data.ratings)).put("checkpoints", checkpoints)
            .put("ratingOverrides", JSONArray(data.ratingOverrides.toList())).put("ratingTagCache", ratingCache)
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun scan(previous: LibraryData, mode: ScanMode = ScanMode.NEW_TRACKS, progress: (String) -> Unit): ScanResult {
        val start = SystemClock.elapsedRealtime()
        val found = previous.songs.associateByTo(linkedMapOf()) { it.uri }
        val byPath = previous.songs.filter { it.path.isNotBlank() }.associateByTo(hashMapOf()) { it.path }
        val warnings = mutableListOf<String>()
        val checkpoints = previous.checkpoints.toMutableMap()
        var indexQueries = 0
        var indexRows = 0
        val counters = DeepCounters()
        fun upsert(song: Song) {
            val existing = byPath[song.path] ?: found[song.uri]
            var canonical = if (existing != null) song.copy(uri = existing.uri,
                year = taggedYear(song.year).ifBlank {
                    if (song.modified == existing.modified && song.size == existing.size) taggedYear(existing.year) else ""
                }) else song
            if (mode != ScanMode.NEW_TRACKS && canonical.year.isBlank()) {
                try {
                    counters.opened++
                    progress("Reading year tags: ${canonical.fileName}")
                    canonical = canonical.copy(year = readYear(canonical))
                } catch (error: Exception) {
                    Log.w("ResonanceScan", "Cannot read year for ${canonical.fileName}", error)
                    warnings += "Could not read year for ${canonical.fileName}: ${error.message}"
                }
            }
            found[canonical.uri] = canonical
            if (canonical.path.isNotBlank()) byPath[canonical.path] = canonical
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            val volumes = MediaStore.getExternalVolumeNames(context)
            for (volume in volumes) {
                progress("Checking Android music index: $volume")
                try {
                    val version = MediaStore.getVersion(context, volume) ?: throw IOException("Index version unavailable")
                    val generation = MediaStore.getGeneration(context, volume)
                    val checkpoint = previous.checkpoints[volume]
                    val incremental = mode == ScanMode.NEW_TRACKS && validCheckpoint(checkpoint, version, generation)
                    if (incremental && checkpoint!!.generation == generation) continue
                    val lowerBound = if (incremental) checkpoint!!.generation else null
                    indexQueries++
                    val songs = scanMediaStore(volume, lowerBound, generation)
                    indexRows += songs.size
                    if (MediaStore.getVersion(context, volume) != version) throw IOException("Android rebuilt its index during scanning; scan again")
                    if (mode != ScanMode.NEW_TRACKS) {
                        found.entries.removeAll { volumeForSong(it.value) == volume }
                    }
                    songs.forEach(::upsert)
                    checkpoints[volume] = IndexCheckpoint(version, generation)
                } catch (error: Exception) {
                    Log.e("ResonanceScan", "Index scan failed for $volume", error)
                    warnings += "Index $volume: ${error.message}. Cached tracks preserved."
                }
            }
            val offline = previous.checkpoints.keys - volumes
            if (offline.isNotEmpty()) warnings += "Offline storage retained in cache: ${offline.joinToString()}"
        } else warnings += "Music permission not granted. Grant access for fast scanning, or use Deep folder scan."

        if (mode == ScanMode.DEEP) {
            val old = previous.songs.associateBy { it.uri }
            previous.folders.forEach { tree ->
                val folderSongs = mutableListOf<Song>()
                try {
                    val uri = Uri.parse(tree)
                    scanTree(uri, DocumentsContract.getTreeDocumentId(uri), old, byPath, folderSongs, warnings, counters, progress)
                    val seen = folderSongs.mapTo(hashSetOf()) { byPath[it.path]?.uri ?: it.uri }
                    found.entries.removeAll { it.key.startsWith("$tree/") && it.key !in seen }
                    folderSongs.forEach(::upsert)
                } catch (error: Exception) {
                    Log.e("ResonanceScan", "Folder scan failed: $tree", error)
                    warnings += "Folder unavailable: ${error.message}. Cached entries preserved."
                }
            }
        } else {
            val nonIndexed = previous.folders.count {
                Uri.parse(it).authority != "com.android.externalstorage.documents"
            }
            if (nonIndexed > 0) warnings += "$nonIndexed folder(s) are outside Android's local music index; use Deep folder scan for those."
        }

        val old = previous.songs.associateBy { it.uri }
        val result = if (found.size == old.size && found.all { old[it.key] == it.value }) previous.songs
            else sortedSongs(found.values.toList(), SongSort.TITLE)
        return ScanResult(result, warnings, checkpoints, ScanStats(
            added = result.count { it.uri !in old }, updated = result.count { it.uri in old && it != old[it.uri] },
            indexQueries = indexQueries, indexRows = indexRows, audioFilesOpened = counters.opened,
            directoriesListed = counters.directories, elapsedMs = SystemClock.elapsedRealtime() - start,
        ))
    }

    internal fun readYear(song: Song): String {
        if (song.extension in EmbeddedRatingTags.metadataExtensions) {
            val input = context.contentResolver.openInputStream(Uri.parse(song.uri)) ?: throw IOException("Cannot open track tags")
            return input.use { EmbeddedRatingTags.readMetadata(it, song.extension).year }
        }
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(context, Uri.parse(song.uri))
            taggedYear(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
        } finally { metadata.release() }
    }

    private fun scanMediaStore(volume: String, after: Long?, through: Long): List<Song> {
        val collection = MediaStore.Audio.Media.getContentUri(volume)
        val projection = arrayOf("_id", "title", "artist", "album", "album_artist", "genre", "year", "track", "duration",
            "relative_path", "_display_name", "_data", "date_modified", "_size", "bitrate")
        val selection = "is_music != 0 AND is_pending = 0 AND generation_modified <= ?" +
            if (after != null) " AND generation_modified > ?" else ""
        val args = if (after != null) arrayOf(through.toString(), after.toString()) else arrayOf(through.toString())
        return context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val number = cursor.long("track").toInt()
                    add(Song(
                        uri = ContentUris.withAppendedId(collection, cursor.long("_id")).toString(),
                        title = cursor.text("title").ifBlank { cursor.text("_display_name").substringBeforeLast('.') },
                        artist = cursor.text("artist").known("Unknown artist"),
                        album = cursor.text("album").known("Unknown album"),
                        albumArtist = cursor.text("album_artist").ifBlank { cursor.text("artist").known("Unknown artist") },
                        genre = cursor.text("genre"), year = taggedYear(cursor.text("year")), track = number % 1000, disc = number / 1000,
                        duration = cursor.long("duration"), folder = cursor.text("relative_path").trimEnd('/').ifBlank { "Internal storage" },
                        fileName = cursor.text("_display_name"), path = cursor.text("_data"),
                        modified = cursor.long("date_modified") * 1000, size = cursor.long("_size"), mediaVolume = volume,
                        bitrate = cursor.long("bitrate").toInt(),
                    ))
                }
            }
        } ?: throw IOException("The Android music index could not be opened")
    }

    private class DeepCounters(var opened: Int = 0, var directories: Int = 0, var lastProgress: Long = 0)

    private fun scanTree(
        tree: Uri, parent: String, old: Map<String, Song>, indexed: Map<String, Song>, result: MutableList<Song>,
        warnings: MutableList<String>, counters: DeepCounters, progress: (String) -> Unit,
    ) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf("document_id", "_display_name", "mime_type", "last_modified", "_size")
        counters.directories++
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val now = SystemClock.elapsedRealtime()
                if (now - counters.lastProgress >= 250) {
                    counters.lastProgress = now
                    progress("${counters.directories} folders checked / ${counters.opened} new or changed files read")
                }
                val id = cursor.text("document_id")
                if (cursor.text("mime_type") == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scanTree(tree, id, old, indexed, result, warnings, counters, progress)
                    continue
                }
                val name = cursor.text("_display_name")
                if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in audioExtensions) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                val path = documentPath(id)
                val modified = cursor.long("last_modified")
                val size = cursor.long("_size")
                val cached = old[uri.toString()] ?: indexed[path]
                if (cached != null && cached.size == size && kotlin.math.abs(cached.modified - modified) < 1000) {
                    result += cached
                    continue
                }
                counters.opened++
                try {
                    val metadata = MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(context, uri)
                        fun tag(key: Int) = metadata.extractMetadata(key).orEmpty()
                        val artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifBlank { "Unknown artist" }
                        result += Song(
                            uri = cached?.uri ?: uri.toString(),
                            title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { name.substringBeforeLast('.') },
                            artist = artist, album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM).ifBlank { "Unknown album" },
                            albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).ifBlank { artist },
                            genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE),
                            year = taggedYear(tag(MediaMetadataRetriever.METADATA_KEY_YEAR), tag(MediaMetadataRetriever.METADATA_KEY_DATE)),
                            track = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
                            disc = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
                            duration = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0,
                            folder = id.substringAfter(':').substringBeforeLast('/', "Selected folder"),
                            fileName = name, path = path, modified = modified, size = size,
                            bitrate = tag(MediaMetadataRetriever.METADATA_KEY_BITRATE).toIntOrNull() ?: 0,
                        )
                    } finally { metadata.release() }
                } catch (error: Exception) {
                    Log.w("ResonanceScan", "Cannot read $name", error)
                    warnings += "Could not read $name: ${error.message}"
                    if (cached != null) result += cached
                }
            }
        } ?: throw IOException("Cannot read selected folder")
    }

    companion object {
        fun songToJson(song: Song): JSONObject = JSONObject()
            .put("uri", song.uri).put("title", song.title).put("artist", song.artist).put("album", song.album)
            .put("albumArtist", song.albumArtist).put("genre", song.genre).put("year", song.year)
            .put("track", song.track).put("disc", song.disc).put("duration", song.duration)
            .put("folder", song.folder).put("fileName", song.fileName).put("path", song.path)
            .put("modified", song.modified).put("size", song.size).put("mediaVolume", song.mediaVolume)
            .put("serverKey", song.serverKey).put("serverTrackId", song.serverTrackId).put("coverArtId", song.coverArtId)
            .put("serverAlbumId", song.serverAlbumId).put("sourceBits", song.sourceBits)
            .put("catalogNumber", song.catalogNumber).put("bitrate", song.bitrate)

        fun songFromJson(json: JSONObject): Song = Song(
            json.getString("uri"), json.getString("title"), json.optString("artist", "Unknown artist"),
            json.optString("album", "Unknown album"), json.optString("albumArtist", "Unknown artist"),
            json.optString("genre"), json.optString("year"), json.optInt("track"), json.optInt("disc"),
            json.optLong("duration"), json.optString("folder"), json.optString("fileName"), json.optString("path"),
            json.optLong("modified"), json.optLong("size"), json.optString("mediaVolume"),
            json.optString("serverKey"), json.optString("serverTrackId"), json.optString("coverArtId"),
            json.optString("serverAlbumId"), json.optInt("sourceBits"),
            json.optString("catalogNumber"), json.optInt("bitrate"),
        )

        private fun documentPath(id: String): String {
            if (!id.contains(':')) return ""
            val volume = id.substringBefore(':')
            return if (volume == "primary") "/storage/emulated/0/${id.substringAfter(':')}"
            else if (volume.matches(Regex("[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}"))) "/storage/$volume/${id.substringAfter(':')}"
            else ""
        }
    }
}

fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
private fun Cursor.text(column: String): String = getColumnIndex(column).let { if (it >= 0 && !isNull(it)) getString(it) else "" }
private fun Cursor.long(column: String): Long = getColumnIndex(column).let { if (it >= 0 && !isNull(it)) getLong(it) else 0 }
private fun String.known(fallback: String): String = if (isBlank() || this == "<unknown>") fallback else this
