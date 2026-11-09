package io.github.ambientcore.resonance

import java.util.Locale

data class Song(
    val uri: String,
    val title: String,
    val artist: String = "Unknown artist",
    val album: String = "Unknown album",
    val albumArtist: String = artist,
    val genre: String = "",
    val year: String = "",
    val track: Int = 0,
    val disc: Int = 0,
    val duration: Long = 0,
    val folder: String = "",
    val fileName: String = "",
    val path: String = "",
    val modified: Long = 0,
    val size: Long = 0,
    val mediaVolume: String = "",
    val serverKey: String = "",
    val serverTrackId: String = "",
    val coverArtId: String = "",
    val serverAlbumId: String = "",
    val sourceBits: Int = 0,
    val catalogNumber: String = "",
    val bitrate: Int = 0,
) {
    val isServer: Boolean get() = serverTrackId.isNotEmpty()
    val albumKey: String get() = if (isServer && serverAlbumId.isNotEmpty()) "$serverKey\u0000$serverAlbumId" else "$albumArtist\u0000$album"
    val extension: String get() = fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val genres: List<String> get() = genre.split(';').map(String::trim).filter(String::isNotEmpty)
        .distinctBy { it.lowercase(Locale.ROOT) }
    fun matches(query: String): Boolean = matchesTerms(searchTerms(query))
    fun matchesTerms(terms: List<String>, scope: SearchScope = SearchScope.EVERYTHING): Boolean = when (scope) {
        SearchScope.EVERYTHING -> matchesText(terms, title, artist, album, albumArtist, genre, fileName, year, folder)
        SearchScope.SONGS -> matchesText(terms, title)
        SearchScope.ARTISTS -> matchesText(terms, artist, albumArtist)
        SearchScope.ALBUMS -> matchesText(terms, album)
        SearchScope.GENRES -> matchesText(terms, *genres.toTypedArray())
        SearchScope.FOLDERS -> matchesText(terms, folder)
    }
}

fun trackFormatLabel(song: Song, codec: String = "", bitrate: Int = song.bitrate): String =
    listOfNotNull(song.extension.ifBlank { codec }.takeIf { it.isNotBlank() },
        bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" }).joinToString(" / ")

private val tagYearPattern = Regex("""^(\d{4})(?:$|[-/.T\s])""")

fun taggedYear(vararg values: String?): String {
    for (value in values) {
        val year = value?.trim()?.let { tagYearPattern.find(it)?.groupValues?.get(1) }
        if (year != null && year.toInt() > 0) return year
    }
    return ""
}

enum class RatingComparison(val label: String) {
    AT_LEAST("At least"), EXACTLY("Exactly"), AT_MOST("At most")
}

data class RatingRule(val comparison: RatingComparison = RatingComparison.AT_LEAST, val stars: Int = 4) {
    init { require(stars in 0..5) }
    fun matches(rating: Int): Boolean = when (comparison) {
        RatingComparison.AT_LEAST -> rating >= stars
        RatingComparison.EXACTLY -> rating == stars
        RatingComparison.AT_MOST -> rating <= stars
    }
    val label: String get() = "${comparison.label} $stars stars"
}

data class MusicPlaylist(val id: String, val name: String, val songs: List<String>, val rule: RatingRule? = null,
    val sourceKey: String = "")

fun playlistSongs(playlist: MusicPlaylist, songs: List<Song>, ratings: Map<String, Int>): List<Song> {
    val rule = playlist.rule
    if (rule != null) return songs.filter { rule.matches(ratings[it.uri] ?: 0) }
        .sortedWith(compareByDescending<Song> { ratings[it.uri] ?: 0 }.thenBy { it.title.lowercase(Locale.ROOT) })
    val index = songs.associateBy { it.uri }
    return playlist.songs.mapNotNull(index::get)
}

enum class ScanMode(val label: String) {
    NEW_TRACKS("Scan for new tracks"), REFRESH_INDEX("Refresh tags / remove missing"), DEEP("Deep folder scan")
}

data class IndexCheckpoint(val version: String, val generation: Long)

fun validCheckpoint(checkpoint: IndexCheckpoint?, version: String, generation: Long): Boolean =
    checkpoint != null && checkpoint.version == version && checkpoint.generation <= generation

fun volumeForSong(song: Song): String = song.mediaVolume.ifBlank {
    when {
        song.path.startsWith("/storage/emulated/0/") -> "external_primary"
        song.path.startsWith("/storage/") -> song.path.removePrefix("/storage/").substringBefore('/').lowercase(Locale.ROOT)
        else -> ""
    }
}

enum class SongSort(val label: String) {
    TITLE("Title"), ARTIST("Artist"), ALBUM("Album / track"), NEWEST("Recently added"), DURATION("Duration"),
    YEAR_NEWEST("Year (newest first)"), YEAR_OLDEST("Year (oldest first)")
}

fun sortedSongs(songs: List<Song>, sort: SongSort): List<Song> = when (sort) {
    SongSort.TITLE -> songs.sortedBy { it.title.lowercase(Locale.ROOT) }
    SongSort.ARTIST -> songs.sortedWith(compareBy({ it.artist.lowercase(Locale.ROOT) }, { it.album }, { it.disc }, { it.track }, { it.title }))
    SongSort.ALBUM -> songs.sortedWith(compareBy({ it.albumArtist.lowercase(Locale.ROOT) }, { it.album.lowercase(Locale.ROOT) }, { it.disc }, { it.track }, { it.title }))
    SongSort.NEWEST -> songs.sortedByDescending { it.modified }
    SongSort.DURATION -> songs.sortedByDescending { it.duration }
    SongSort.YEAR_NEWEST -> songs.sortedWith(compareBy<Song> { taggedYear(it.year).isBlank() }
        .thenByDescending { taggedYear(it.year).toIntOrNull() ?: 0 }.thenBy { it.title.lowercase(Locale.ROOT) })
    SongSort.YEAR_OLDEST -> songs.sortedWith(compareBy<Song> { taggedYear(it.year).isBlank() }
        .thenBy { taggedYear(it.year).toIntOrNull() ?: 0 }.thenBy { it.title.lowercase(Locale.ROOT) })
}

fun durationLabel(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}

data class PlaylistImport(val songs: List<String>, val unmatched: Int, val ambiguous: Int = 0)

fun matchM3u(text: String, library: List<Song>): PlaylistImport {
    val entries = text.removePrefix("\uFEFF").lineSequence().map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith("#") }.toList()
    val prepared = preparePlaylists(listOf(PlaylistInput("m3u", "Imported playlist", entries)), library).single()
    return PlaylistImport(prepared.songs, prepared.missing + prepared.ambiguous, prepared.ambiguous)
}

fun exportM3u(songs: List<Song>): String = buildString {
    appendLine("#EXTM3U")
    songs.forEach {
        appendLine("#EXTINF:${it.duration / 1000},${it.artist.replace('\n', ' ')} - ${it.title.replace('\n', ' ')}")
        appendLine(it.path.ifBlank { it.uri })
    }
}
