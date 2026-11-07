package io.github.ambientcore.resonance

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

data class TrackMatch(val uri: String? = null, val ambiguous: Boolean = false)

class PlaylistPathIndex(songs: List<Song>) {
    private val uris = songs.mapTo(hashSetOf()) { it.uri }
    private val paths = mutableMapOf<String, MutableSet<String>>()
    private val suffixes = mutableMapOf<String, MutableSet<String>>()
    private val names = mutableMapOf<String, MutableSet<String>>()

    init {
        songs.forEach { song ->
            val file = song.fileName.lowercase(Locale.ROOT)
            if (file.isNotBlank()) names.getOrPut(file) { linkedSetOf() } += song.uri
            val path = normalizePlaylistPath(song.path.ifBlank { "${song.folder}/${song.fileName}" })
            if (path.isNotBlank()) {
                paths.getOrPut(path) { linkedSetOf() } += song.uri
                val parts = path.split('/').filter(String::isNotEmpty)
                for (start in parts.indices) {
                    if (parts.size - start < 2) continue
                    suffixes.getOrPut(parts.drop(start).joinToString("/")) { linkedSetOf() } += song.uri
                }
            }
        }
    }

    fun match(entry: String): TrackMatch {
        if (entry in uris) return TrackMatch(entry)
        if (entry.startsWith("content:", true)) return TrackMatch()
        if (entry.contains("://") && !entry.startsWith("file:", true)) return TrackMatch()
        val path = normalizePlaylistPath(entry)
        paths[path]?.let { return resolve(it) }
        val parts = path.split('/').filter(String::isNotEmpty)
        for (start in parts.indices) {
            if (parts.size - start < 2) continue
            suffixes[parts.drop(start).joinToString("/")]?.let { return resolve(it) }
        }
        return names[parts.lastOrNull().orEmpty()]?.let(::resolve) ?: TrackMatch()
    }

    private fun resolve(candidates: Set<String>): TrackMatch =
        if (candidates.size == 1) TrackMatch(candidates.single()) else TrackMatch(ambiguous = true)
}

fun normalizePlaylistPath(value: String): String {
    var path = value.trim()
    if (path.startsWith("file:", true)) {
        path = try {
            val uri = URI(path)
            val raw = uri.rawPath ?: return ""
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (error: IllegalArgumentException) { return "" }
        catch (error: java.net.URISyntaxException) { return "" }
    }
    path = path.replace('\\', '/').replace(Regex("^[A-Za-z]:"), "").trim('/')
    return path.lowercase(Locale.ROOT)
}

data class PlaylistInput(val sourceKey: String, val name: String, val entries: List<String>)
data class PreparedPlaylist(
    val sourceKey: String, val name: String, val songs: List<String>,
    val total: Int, val missing: Int, val ambiguous: Int, val unavailable: Int = 0,
)
data class PlaylistBatchPreview(val playlists: List<PreparedPlaylist>, val notices: List<String> = emptyList()) {
    val matched: Int get() = playlists.sumOf { it.songs.size }
    val missing: Int get() = playlists.sumOf { it.missing }
    val ambiguous: Int get() = playlists.sumOf { it.ambiguous }
    val unavailable: Int get() = playlists.sumOf { it.unavailable }
}

fun preparePlaylists(inputs: List<PlaylistInput>, songs: List<Song>): List<PreparedPlaylist> {
    val index = PlaylistPathIndex(songs)
    return inputs.map { input ->
        val matches = input.entries.map(index::match)
        PreparedPlaylist(input.sourceKey, input.name, matches.mapNotNull { it.uri }, input.entries.size,
            matches.count { it.uri == null && !it.ambiguous }, matches.count { it.ambiguous })
    }
}
