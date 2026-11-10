package io.github.ambientcore.resonance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.lang.ref.WeakReference

internal const val MAP_MAX_NODES = 12

internal data class MapRequest(
    val artist: String? = null,
    val query: String = "",
    val page: Int = 0,
    val pageSize: Int = 8,
) {
    init {
        require(page >= 0)
        require(pageSize in 1..MAP_MAX_NODES)
    }
}

internal data class MapNode(val id: String, val name: String, val subtitle: String, val tracks: Int)
internal data class MapPage(val nodes: List<MapNode>, val matches: Int, val page: Int, val pageCount: Int)

private val mapWhitespace = Regex("\\s+")
private fun artistName(value: String): String = mapWhitespace.replace(value.trim(), " ")
private fun artistKey(value: String): String = artistName(value).lowercase(Locale.ROOT)

private data class ArtistCredit(val id: String, val name: String, val matches: Boolean)

private class ArtistCredits(private val terms: List<String> = emptyList()) {
    private val known = HashMap<String, ArtistCredit?>()
    private val unknown = ArtistCredit("unknown artist", "Unknown artist", matchesText(terms, "Unknown artist"))

    private fun credit(raw: String): ArtistCredit? {
        if (known.containsKey(raw)) return known[raw]
        val name = artistName(raw)
        val value = if (name.isBlank() || name.equals("Unknown artist", true) || name == "<unknown>") null
            else ArtistCredit(artistKey(name), name, terms.isEmpty() || matchesText(terms, name))
        known[raw] = value
        return value
    }

    fun visit(song: Song, action: (ArtistCredit) -> Unit) {
        val performer = credit(song.artist)
        val albumArtist = if (song.albumArtist == song.artist) performer else credit(song.albumArtist)
        if (performer != null) action(performer)
        if (albumArtist != null && albumArtist.id != performer?.id) action(albumArtist)
        if (performer == null && albumArtist == null) action(unknown)
    }

    fun matches(song: Song, id: String): Boolean {
        var matches = false
        visit(song) { if (it.id == id) matches = true }
        return matches
    }
}

private class MapCount(val id: String, val name: String, val subtitle: String) {
    val sortName = name.lowercase(Locale.ROOT)
    var tracks = 0
    fun node() = MapNode(id, name, subtitle, tracks)
}

internal fun buildMapPage(songs: List<Song>, request: MapRequest, checkActive: () -> Unit = {}): MapPage {
    val terms = searchTerms(request.query)
    val artists = ArtistCredits(terms)
    val counts = HashMap<String, MapCount>()
    songs.forEachIndexed { index, song ->
        if (index % 256 == 0) checkActive()
        if (request.artist == null) {
            artists.visit(song) { credit ->
                if (credit.matches) counts.getOrPut(credit.id) { MapCount(credit.id, credit.name, "Artist") }.tracks++
            }
        } else if (artists.matches(song, request.artist) &&
            (terms.isEmpty() || matchesText(terms, song.album, song.albumArtist))) {
            val id = song.albumKey
            counts.getOrPut(id) { MapCount(id, song.album.ifBlank { "Unknown album" }, song.albumArtist) }.tracks++
        }
    }
    checkActive()
    val ordered = counts.values.sortedWith(compareByDescending<MapCount> { it.tracks }
        .thenBy { it.sortName }.thenBy { it.id })
    checkActive()
    val pageCount = ((ordered.size + request.pageSize - 1) / request.pageSize).coerceAtLeast(1)
    val page = request.page.coerceAtMost(pageCount - 1)
    // Copy only the visible page, so the cache cannot retain every temporary summary.
    val start = page * request.pageSize
    return MapPage(ordered.subList(start, minOf(start + request.pageSize, ordered.size)).map { it.node() },
        ordered.size, page, pageCount)
}

internal fun mapSongs(songs: List<Song>, artist: String?, album: String? = null): List<Song> {
    val artists = ArtistCredits()
    return sortedSongs(songs.filter { song ->
        if (album != null) song.albumKey == album
        else artist != null && artists.matches(song, artist)
    }, SongSort.ALBUM)
}

internal class ListeningMapCache {
    private val mutex = Mutex()
    private var source = WeakReference<List<Song>>(null)
    private var request: MapRequest? = null
    private var result: MapPage? = null
    @Volatile var builds = 0
        private set

    suspend fun page(songs: List<Song>, next: MapRequest): MapPage = withContext(Dispatchers.Default) {
        mutex.withLock {
            val cached = result
            if (source.get() === songs && request == next && cached != null) return@withLock cached
            val context = currentCoroutineContext()
            val page = buildMapPage(songs, next) { context.ensureActive() }
            context.ensureActive()
            source = WeakReference(songs)
            request = next
            result = page
            builds++
            page
        }
    }
}
