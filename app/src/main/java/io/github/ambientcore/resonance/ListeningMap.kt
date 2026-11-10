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
internal const val MAP_SESSION_GAP_MS = 30 * 60_000L
internal enum class MapMode { CONNECTIONS, ALBUMS }

internal data class MapRequest(
    val artist: String? = null,
    val query: String = "",
    val page: Int = 0,
    val pageSize: Int = 8,
    val mode: MapMode = MapMode.ALBUMS,
    val genreLinks: Boolean = false,
) {
    init {
        require(page >= 0)
        require(pageSize in 1..(MAP_MAX_NODES - if (artist != null && mode == MapMode.CONNECTIONS) 1 else 0))
    }
}

internal data class MapNode(val id: String, val name: String, val subtitle: String, val tracks: Int,
    val played: Boolean = false, val lastPlayed: Long = 0)
internal data class MapLink(val artist: String, val albums: Int, val adjacentPlays: Int, val genres: List<String>,
    val genreCount: Int) {
    val label: String get() = when {
        albums > 0 -> "Shared albums: $albums"
        adjacentPlays >= 2 -> "Played together"
        else -> "Shared tags: $genreCount"
    }
    val explanation: String get() = buildList {
        if (albums > 0) add("Both artists are credited on $albums album(s).")
        if (adjacentPlays >= 2) add("Adjacent plays in this source's history $adjacentPlays times, no more than 30 minutes apart.")
        if (genres.isNotEmpty()) add("Shared genre tags: ${genres.joinToString()}" + if (genreCount > genres.size) " (+${genreCount - genres.size} more)." else ".")
        add("These are library/listening connections, not a claim of musical similarity.")
    }.joinToString(" ")
}
internal data class MapPage(val nodes: List<MapNode>, val matches: Int, val page: Int, val pageCount: Int,
    val center: MapNode? = null, val links: List<MapLink> = emptyList())

internal fun mapDiscoveryLabel(node: MapNode, now: Long = System.currentTimeMillis()): String = when {
    !node.played -> "Unexplored here"
    node.lastPlayed > 0 && now - node.lastPlayed >= 90L * 24 * 60 * 60_000 -> "Unheard 90+ days"
    else -> "Previously played"
}

private val mapWhitespace = Regex("\\s+")
private fun artistName(value: String): String = mapWhitespace.replace(value.trim(), " ")
private fun artistKey(value: String): String = artistName(value).lowercase(Locale.ROOT)

private data class ArtistCredit(val id: String, val name: String, val matches: Boolean)

private class ArtistCredits(private val terms: List<String> = emptyList()) {
    private val known = HashMap<String, ArtistCredit>()
    private val unknown = ArtistCredit("unknown artist", "Unknown artist", matchesText(terms, "Unknown artist"))
    private val missing = ArtistCredit("", "", false)

    private fun credit(raw: String): ArtistCredit? {
        known[raw]?.let { return it.takeUnless { value -> value.id.isEmpty() } }
        val name = artistName(raw)
        val value = if (name.isBlank() || name.equals("Unknown artist", true) || name == "<unknown>") null
            else ArtistCredit(artistKey(name), name, terms.isEmpty() || matchesText(terms, name))
        known[raw] = value ?: missing
        return value
    }

    inline fun visit(song: Song, action: (ArtistCredit) -> Unit) {
        val performer = credit(song.artist)
        val albumArtist = if (song.albumArtist == song.artist) performer else credit(song.albumArtist)
        if (performer != null) action(performer)
        if (albumArtist != null && albumArtist.id != performer?.id) action(albumArtist)
        if (performer == null && albumArtist == null) action(unknown)
    }

    fun matches(song: Song, id: String): Boolean {
        val performer = credit(song.artist)
        if (performer?.id == id) return true
        val owner = if (song.albumArtist == song.artist) performer else credit(song.albumArtist)
        return owner?.id == id || (performer == null && owner == null && id == unknown.id)
    }
}

private class MapCount(val id: String, val name: String, val subtitle: String) {
    val sortName = name.lowercase(Locale.ROOT)
    var tracks = 0
    var played = false
    var lastPlayed = 0L
    fun record(song: Song, history: ListeningHistory) {
        tracks++
        val last = history.lastPlayed[song.uri] ?: 0
        lastPlayed = maxOf(lastPlayed, last)
        played = played || last > 0 || (history.counts[song.uri] ?: 0) > 0
    }
    fun node() = MapNode(id, name, subtitle, tracks, played, lastPlayed)
}

private class RelationCount {
    val albums = HashSet<String>()
    val genres = sortedSetOf<String>()
    var adjacent = 0
    fun link(artist: String) = MapLink(artist, albums.size, adjacent, genres.take(3), genres.size)
    val supported: Boolean get() = albums.isNotEmpty() || adjacent >= 2 || genres.isNotEmpty()
}

private fun mapGenres(song: Song): List<String> = song.genres.map { it.lowercase(Locale.ROOT) }
    .filter { it != "unknown genre" && it != "<unknown>" }

private fun relationshipCounts(songs: List<Song>, focus: String, credits: ArtistCredits,
    focusAlbums: Set<String>, focusGenres: Set<String>, history: ListeningHistory,
    historyTracks: Map<String, Song>, checkActive: () -> Unit,
    albumKeys: Array<String?>? = null): Map<String, RelationCount> {
    if (focus == "unknown artist") return emptyMap()
    val relations = HashMap<String, RelationCount>()
    songs.forEachIndexed { index, song ->
        if (index % 256 == 0) checkActive()
        val album = albumKeys?.get(index) ?: song.albumKey
        val sharedAlbum = album in focusAlbums
        val sharedGenres = if (focusGenres.isEmpty()) emptyList() else mapGenres(song).filter { it in focusGenres }
        if (sharedAlbum || sharedGenres.isNotEmpty()) credits.visit(song) { credit ->
            if (credit.id != focus && credit.id != "unknown artist") {
                val relation = relations.getOrPut(credit.id) { RelationCount() }
                if (sharedAlbum) relation.albums += album
                relation.genres += sharedGenres
            }
        }
    }
    history.recent.sortedBy { it.playedAt }.zipWithNext().forEach { (first, second) ->
        checkActive()
        if (second.playedAt - first.playedAt !in 1..MAP_SESSION_GAP_MS || first.uri == second.uri) return@forEach
        val a = historyTracks[first.uri] ?: return@forEach
        val b = historyTracks[second.uri] ?: return@forEach
        val aFocus = credits.matches(a, focus)
        val bFocus = credits.matches(b, focus)
        if (aFocus != bFocus) credits.visit(if (aFocus) b else a) {
            if (it.id != "unknown artist") relations.getOrPut(it.id) { RelationCount() }.adjacent++
        }
    }
    return relations
}

internal fun buildMapPage(songs: List<Song>, request: MapRequest, history: ListeningHistory = ListeningHistory(),
    checkActive: () -> Unit = {}): MapPage {
    val terms = searchTerms(request.query)
    val artists = ArtistCredits(terms)
    val counts = HashMap<String, MapCount>()
    val connections = request.artist != null && request.mode == MapMode.CONNECTIONS
    val focusAlbums = HashSet<String>()
    val focusGenres = HashSet<String>()
    val historyUris = if (connections) history.recent.mapTo(HashSet()) { it.uri } else emptySet()
    val historyTracks = HashMap<String, Song>()
    val albumKeys = if (connections) arrayOfNulls<String>(songs.size) else null
    songs.forEachIndexed { index, song ->
        if (index % 256 == 0) checkActive()
        var matchesFocus = false
        if (request.artist == null || connections) {
            artists.visit(song) { credit ->
                if (credit.matches || connections) counts.getOrPut(credit.id) {
                    MapCount(credit.id, credit.name, "Artist")
                }.record(song, history)
                if (credit.id == request.artist) matchesFocus = true
            }
        } else if (artists.matches(song, request.artist) &&
            (terms.isEmpty() || matchesText(terms, song.album, song.albumArtist))) {
            val id = song.albumKey
            counts.getOrPut(id) { MapCount(id, song.album.ifBlank { "Unknown album" }, song.albumArtist) }.record(song, history)
        }
        if (connections) {
            val albumKey = song.albumKey
            albumKeys?.set(index, albumKey)
            if (matchesFocus) {
                focusAlbums += albumKey
                if (request.genreLinks) focusGenres += mapGenres(song)
            }
            if (song.uri in historyUris) historyTracks[song.uri] = song
        }
    }
    checkActive()
    val relations = if (connections) relationshipCounts(songs, requireNotNull(request.artist), artists,
        focusAlbums, focusGenres, history, historyTracks, checkActive, albumKeys) else emptyMap()
    val candidates = if (connections) counts.values.filter {
        it.id != request.artist && relations[it.id]?.supported == true && matchesText(terms, it.name)
    } else counts.values
    val order = if (connections) compareByDescending<MapCount> { relations[it.id]?.albums?.size ?: 0 }
        .thenByDescending { (relations[it.id]?.adjacent ?: 0).takeIf { it >= 2 } ?: 0 }
        .thenByDescending { relations[it.id]?.genres?.size ?: 0 }
        .thenBy { it.played }.thenBy { it.lastPlayed }.thenBy { it.sortName }.thenBy { it.id }
        else compareByDescending<MapCount> { it.tracks }.thenBy { it.sortName }.thenBy { it.id }
    val ordered = candidates.sortedWith(order)
    checkActive()
    val pageCount = ((ordered.size + request.pageSize - 1) / request.pageSize).coerceAtLeast(1)
    val page = request.page.coerceAtMost(pageCount - 1)
    // Copy only the visible page, so the cache cannot retain every temporary summary.
    val start = page * request.pageSize
    val nodes = ordered.subList(start, minOf(start + request.pageSize, ordered.size)).map { it.node() }
    return MapPage(nodes, ordered.size, page, pageCount,
        if (connections) counts[request.artist]?.node() else null,
        if (connections) nodes.map { relations.getValue(it.id).link(it.id) } else emptyList())
}

internal fun mapConnectionSongs(songs: List<Song>, focus: String, neighbor: String, history: ListeningHistory,
    genreLinks: Boolean, checkActive: () -> Unit = {}): List<Song> {
    val credits = ArtistCredits()
    val focusAlbums = HashSet<String>()
    val focusGenres = HashSet<String>()
    val historyUris = history.recent.mapTo(HashSet()) { it.uri }
    val historyTracks = HashMap<String, Song>()
    songs.forEachIndexed { index, song ->
        if (index % 256 == 0) checkActive()
        if (credits.matches(song, focus)) {
            focusAlbums += song.albumKey
            if (genreLinks) focusGenres += mapGenres(song)
        }
        if (song.uri in historyUris) historyTracks[song.uri] = song
    }
    val relation = relationshipCounts(songs, focus, credits, focusAlbums, focusGenres, history, historyTracks, checkActive)[neighbor]
        ?.takeIf { it.supported } ?: return emptyList()
    val supportedUris = HashSet<String>()
    if (relation.adjacent >= 2) history.recent.sortedBy { it.playedAt }.zipWithNext().forEach { (first, second) ->
        if (second.playedAt - first.playedAt in 1..MAP_SESSION_GAP_MS && first.uri != second.uri) {
            val a = historyTracks[first.uri]
            val b = historyTracks[second.uri]
            if (a != null && b != null) {
                val aFocus = credits.matches(a, focus)
                val bFocus = credits.matches(b, focus)
                if (aFocus != bFocus && credits.matches(if (aFocus) b else a, neighbor)) {
                    supportedUris += a.uri; supportedUris += b.uri
                }
            }
        }
    }
    val result = songs.filterIndexed { index, song ->
        if (index % 256 == 0) checkActive()
        song.uri in supportedUris || ((credits.matches(song, focus) || credits.matches(song, neighbor)) &&
            (song.albumKey in relation.albums ||
                (relation.genres.isNotEmpty() && mapGenres(song).any { it in relation.genres })))
    }.distinctBy { it.uri }
    return sortedSongs(result, SongSort.ALBUM)
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
    private var historyCounts = WeakReference<Map<String, Long>>(null)
    private var historyLastPlayed = WeakReference<Map<String, Long>>(null)
    private var historyRecent = WeakReference<List<ListenEvent>>(null)
    private var result: MapPage? = null
    @Volatile var builds = 0
        private set

    suspend fun page(songs: List<Song>, next: MapRequest,
        listening: ListeningHistory = ListeningHistory()): MapPage = withContext(Dispatchers.Default) {
        mutex.withLock {
            val cached = result
            if (source.get() === songs && request == next && historyCounts.get() === listening.counts &&
                historyLastPlayed.get() === listening.lastPlayed && historyRecent.get() === listening.recent &&
                cached != null) return@withLock cached
            val context = currentCoroutineContext()
            val page = buildMapPage(songs, next, listening) { context.ensureActive() }
            context.ensureActive()
            source = WeakReference(songs)
            request = next
            historyCounts = WeakReference(listening.counts)
            historyLastPlayed = WeakReference(listening.lastPlayed)
            historyRecent = WeakReference(listening.recent)
            result = page
            builds++
            page
        }
    }
}
