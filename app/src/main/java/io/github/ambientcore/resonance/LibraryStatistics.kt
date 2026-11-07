package io.github.ambientcore.resonance

import java.util.Locale

data class StatCount(val label: String, val count: Int)

data class LibraryStatistics(
    val tracks: Int,
    val albums: Int,
    val artists: Int,
    val duration: Long,
    val bytes: Long,
    val favorites: Int,
    val ratedTracks: Int,
    val averageRating: Double?,
    val formats: List<StatCount>,
    val genres: List<StatCount>,
    val ratings: List<StatCount>,
)

fun libraryStatistics(
    songs: List<Song>, favorites: Set<String> = emptySet(), ratings: Map<String, Int> = emptyMap(),
): LibraryStatistics {
    val genres = linkedMapOf<String, StatCount>()
    songs.forEach { song ->
        song.genres.ifEmpty { listOf("Unknown genre") }.forEach { label ->
            val key = label.lowercase(Locale.ROOT)
            val previous = genres[key]
            genres[key] = StatCount(previous?.label ?: label, (previous?.count ?: 0) + 1)
        }
    }
    val ranked = compareByDescending<StatCount> { it.count }.thenBy { it.label.lowercase(Locale.ROOT) }
    val rated = songs.mapNotNull { song -> ratings[song.uri]?.takeIf { it > 0 } }
    return LibraryStatistics(
        tracks = songs.size,
        albums = songs.map { it.albumKey }.toSet().size,
        artists = songs.map { it.albumArtist }.toSet().size,
        duration = songs.sumOf { it.duration.coerceAtLeast(0) },
        bytes = songs.sumOf { it.size.coerceAtLeast(0) },
        favorites = songs.count { it.uri in favorites },
        ratedTracks = rated.size,
        averageRating = if (rated.isEmpty()) null else rated.average(),
        formats = songs.groupingBy { it.extension.ifBlank { "Unknown" } }.eachCount()
            .map { (label, count) -> StatCount(label, count) }.sortedWith(ranked),
        genres = genres.values.sortedWith(ranked),
        ratings = (5 downTo 0).map { stars ->
            StatCount(when (stars) { 0 -> "Unrated"; 1 -> "1 star"; else -> "$stars stars" },
                songs.count { (ratings[it.uri] ?: 0) == stars })
        },
    )
}

fun audioSizeLabel(bytes: Long): String {
    val value = bytes.coerceAtLeast(0)
    return when {
        value >= (1L shl 30) -> "%.2f GiB".format(Locale.ROOT, value.toDouble() / (1L shl 30))
        value >= (1L shl 20) -> "%.1f MiB".format(Locale.ROOT, value.toDouble() / (1L shl 20))
        value >= (1L shl 10) -> "%.1f KiB".format(Locale.ROOT, value.toDouble() / (1L shl 10))
        else -> "$value B"
    }
}
