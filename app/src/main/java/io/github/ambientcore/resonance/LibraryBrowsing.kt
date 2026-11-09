package io.github.ambientcore.resonance

import java.util.Locale

private val whitespace = Regex("\\s+")
fun searchTerms(query: String): List<String> = query.trim().split(whitespace).filter(String::isNotEmpty)

fun filterSongs(songs: List<Song>, query: String): List<Song> {
    val terms = searchTerms(query)
    return if (terms.isEmpty()) songs else songs.filter { it.matchesTerms(terms) }
}

fun matchesText(terms: List<String>, vararg values: String): Boolean =
    terms.all { term -> values.any { it.contains(term, true) } }

enum class LibraryCategory(val label: String) {
    SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), FOLDERS("Folders"), GENRES("Genres")
}

data class LibraryGroup(val id: String, val name: String, val subtitle: String, val songs: List<Song>)

enum class SearchSort(val label: String, val songSort: SongSort) {
    ALPHABETICAL("Alphabetical", SongSort.TITLE),
    YEAR_NEWEST("Year (newest first)", SongSort.YEAR_NEWEST),
    YEAR_OLDEST("Year (oldest first)", SongSort.YEAR_OLDEST),
}

fun LibraryGroup.year(): Int? = songs.mapNotNull { taggedYear(it.year).toIntOrNull() }.minOrNull()

fun searchGroups(songs: List<Song>, query: String, category: LibraryCategory, sort: SearchSort): List<LibraryGroup> {
    require(category != LibraryCategory.SONGS) { "Song search does not use collections" }
    val groups = if (category == LibraryCategory.FOLDERS) {
        val roots = folderTree(songs)
        folderRows(roots, folderIds(roots)).map { it.folder }.filter { it.children.isEmpty() }
            .map { LibraryGroup(it.id, it.name, it.path, it.songs) }
    } else libraryGroups(songs, category)
    val terms = searchTerms(query)
    val matches = groups.filter { group ->
        matchesText(terms, group.name, group.subtitle) || group.songs.any { it.matchesTerms(terms) }
    }
    val alphabetical = compareBy<LibraryGroup> { it.name.lowercase(Locale.ROOT) }
        .thenBy { it.subtitle.lowercase(Locale.ROOT) }.thenBy { it.id }
    return when (sort) {
        SearchSort.ALPHABETICAL -> matches.sortedWith(alphabetical)
        SearchSort.YEAR_NEWEST -> matches.sortedWith(compareBy<LibraryGroup> { it.year() == null }
            .thenByDescending { it.year() }.then(alphabetical))
        SearchSort.YEAR_OLDEST -> matches.sortedWith(compareBy<LibraryGroup> { it.year() == null }
            .thenBy { it.year() }.then(alphabetical))
    }
}

data class SongDestination(val category: LibraryCategory, val value: String, val label: String)

fun songDestinations(song: Song): List<SongDestination> = buildList {
    if (song.artist.isNotBlank()) add(SongDestination(LibraryCategory.ARTISTS, song.artist, "Go to artist"))
    if (song.album.isNotBlank()) add(SongDestination(LibraryCategory.ALBUMS, song.albumKey, "Go to album"))
    if (song.albumArtist.isNotBlank() && !song.albumArtist.equals(song.artist, true)) {
        add(SongDestination(LibraryCategory.ARTISTS, song.albumArtist, "Go to album artist"))
    }
    song.genres.forEach { add(SongDestination(LibraryCategory.GENRES, it, "Go to genre")) }
    if (song.folder.isNotBlank() || song.path.isNotBlank()) {
        add(SongDestination(LibraryCategory.FOLDERS, "", "Go to folder"))
    }
}

fun songDestinationGroup(song: Song, destination: SongDestination, songs: List<Song>): LibraryGroup? {
    return when (destination.category) {
        LibraryCategory.ARTISTS -> {
            val tracks = songs.filter {
                it.artist.equals(destination.value, true) || it.albumArtist.equals(destination.value, true)
            }
            tracks.takeIf { it.isNotEmpty() }?.let {
                LibraryGroup(destination.value, destination.value, "Artists", sortedSongs(it, SongSort.ALBUM))
            }
        }
        LibraryCategory.ALBUMS -> libraryGroups(songs, LibraryCategory.ALBUMS).firstOrNull { it.id == destination.value }
            ?.let { it.copy(songs = sortedSongs(it.songs, SongSort.ALBUM)) }
        LibraryCategory.GENRES -> libraryGroups(songs, LibraryCategory.GENRES)
            .firstOrNull { it.id == destination.value.lowercase(Locale.ROOT) }
            ?.let { it.copy(songs = sortedSongs(it.songs, SongSort.ALBUM)) }
        LibraryCategory.FOLDERS -> {
            var leaf = folderTree(listOf(song)).single()
            while (leaf.children.isNotEmpty()) leaf = leaf.children.single()
            fun find(nodes: List<FolderNode>): FolderNode? = nodes.firstNotNullOfOrNull {
                if (it.id == leaf.id) it else find(it.children)
            }
            find(folderTree(songs))?.let { LibraryGroup(it.id, it.name, it.path, sortedSongs(it.songs, SongSort.ALBUM)) }
        }
        LibraryCategory.SONGS -> throw IllegalArgumentException("Songs is not a track metadata destination")
    }
}

fun libraryGroups(songs: List<Song>, category: LibraryCategory): List<LibraryGroup> {
    val grouped = linkedMapOf<String, MutableList<Song>>()
    val names = mutableMapOf<String, String>()
    songs.forEach { song ->
        val labels = when (category) {
            LibraryCategory.ALBUMS -> listOf(song.albumKey to song.album)
            LibraryCategory.ARTISTS -> listOf(song.albumArtist to song.albumArtist)
            LibraryCategory.GENRES -> song.genres.ifEmpty { listOf("Unknown genre") }
                .map { it.lowercase(Locale.ROOT) to it }
            else -> throw IllegalArgumentException("This category does not use grouped browsing")
        }
        labels.forEach { (id, name) ->
            names.putIfAbsent(id, name)
            grouped.getOrPut(id) { mutableListOf() } += song
        }
    }
    return grouped.map { (id, tracks) ->
        LibraryGroup(id, names.getValue(id),
            if (category == LibraryCategory.ALBUMS) tracks.first().albumArtist else "", tracks)
    }.sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.subtitle.lowercase(Locale.ROOT) }))
}

fun filterGroups(groups: List<LibraryGroup>, query: String): List<LibraryGroup> {
    val terms = searchTerms(query)
    return if (terms.isEmpty()) groups else groups.filter { matchesText(terms, it.name, it.subtitle) }
}

fun albumTrackGroups(songs: List<Song>): List<LibraryGroup> =
    libraryGroups(songs, LibraryCategory.ALBUMS).map { it.copy(songs = sortedSongs(it.songs, SongSort.ALBUM)) }

fun recentlyAddedAlbums(songs: List<Song>): List<List<Song>> =
    libraryGroups(songs, LibraryCategory.ALBUMS)
        .sortedByDescending { group -> group.songs.maxOf { it.modified } }
        .take(12).map { it.songs }

data class FolderNode(
    val id: String, val name: String, val path: String, val songs: List<Song>, val children: List<FolderNode>,
)
data class FolderRow(val folder: FolderNode, val depth: Int)

private class FolderBuilder(val id: String, val name: String, val path: String) {
    val songs = mutableListOf<Song>()
    val children = linkedMapOf<String, FolderBuilder>()
    fun freeze(): FolderNode = FolderNode(id, name, path, songs.toList(),
        children.values.sortedBy { it.name.lowercase(Locale.ROOT) }.map { it.freeze() })
}

fun folderTree(songs: List<Song>): List<FolderNode> {
    val roots = linkedMapOf<String, FolderBuilder>()
    songs.forEach { song ->
        val volume = volumeForSong(song).ifBlank { "other" }
        val label = when (volume) {
            "external_primary" -> "Internal storage"
            "server" -> "Server (artist / album)"
            "other" -> "Other storage"
            else -> "SD card (${volume.uppercase(Locale.ROOT)})"
        }
        val normalized = song.path.replace('\\', '/')
        val directory = when {
            normalized.startsWith("/storage/emulated/0/") -> normalized.removePrefix("/storage/emulated/0/").substringBeforeLast('/', "")
            normalized.startsWith("/storage/") -> normalized.removePrefix("/storage/").substringAfter('/', "").substringBeforeLast('/', "")
            else -> song.folder.trim('/')
        }
        var current = roots.getOrPut(volume) { FolderBuilder("storage:$volume", label, label) }
        current.songs += song
        directory.split('/').filter(String::isNotEmpty).forEach { segment ->
            val parent = current
            current = parent.children.getOrPut(segment) {
                FolderBuilder("${parent.id}/$segment", segment, "${parent.path}/$segment")
            }
            current.songs += song
        }
    }
    return roots.values.sortedBy { it.name.lowercase(Locale.ROOT) }.map { it.freeze() }
}

fun filterFolderTree(roots: List<FolderNode>, query: String): List<FolderNode> {
    val terms = searchTerms(query)
    if (terms.isEmpty()) return roots
    fun filter(node: FolderNode): FolderNode? {
        if (matchesText(terms, node.path)) return node
        val children = node.children.mapNotNull(::filter)
        return if (children.isEmpty()) null else node.copy(children = children)
    }
    return roots.mapNotNull(::filter)
}

fun folderIds(roots: List<FolderNode>): Set<String> = buildSet {
    fun visit(node: FolderNode) { add(node.id); node.children.forEach(::visit) }
    roots.forEach(::visit)
}

fun folderRows(roots: List<FolderNode>, expanded: Set<String>): List<FolderRow> = buildList {
    fun visit(node: FolderNode, depth: Int) {
        add(FolderRow(node, depth))
        if (node.id in expanded) node.children.forEach { visit(it, depth + 1) }
    }
    roots.forEach { visit(it, 0) }
}

fun explainAudioPrecision(sourceBits: Int, lossy: Boolean, outputBits: Int, floatingPoint: Boolean): String = when {
    outputBits == 0 -> "Output is not initialized."
    lossy && outputBits == 16 -> "Lossy source decoded to PCM; 16-bit output is normal."
    lossy -> "Lossy source decoded to PCM; higher output precision cannot restore lost detail."
    sourceBits == 0 -> "Source bit depth is unavailable. This reports Android output, not the DAC."
    sourceBits == 16 && outputBits == 16 -> "16-bit source and output; no bit-depth reduction."
    floatingPoint && sourceBits <= 24 -> "Floating-point output can preserve the source's PCM precision."
    floatingPoint -> "Floating-point output is active; full 32-bit integer precision is not guaranteed."
    outputBits < sourceBits -> "Source is $sourceBits-bit, but decoder/output is using $outputBits-bit PCM."
    else -> "Output precision is at least the reported source precision."
}
