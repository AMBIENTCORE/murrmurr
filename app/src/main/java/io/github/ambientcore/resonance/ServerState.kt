package io.github.ambientcore.resonance

data class ServerUi(
    val data: LibraryData = LibraryData(),
    val initialized: Boolean = false,
    val configured: Boolean = false,
    val url: String = "",
    val username: String = "",
    val allowHttp: Boolean = false,
    val key: String = "",
    val refreshing: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val updated: Long = 0,
    val sort: SongSort = SongSort.TITLE,
)

data class ServerSetupUi(val open: Boolean = false, val running: Boolean = false,
    val tested: Boolean = false, val message: String = "")

fun ListeningHistory.forSource(source: LibrarySource, key: String): ListeningHistory {
    fun includes(uri: String): Boolean = if (source == LibrarySource.LOCAL) !uri.startsWith("resonance-server:")
        else uri.startsWith("resonance-server://$key/")
    return copy(counts = counts.filterKeys(::includes), lastPlayed = lastPlayed.filterKeys(::includes),
        recent = recent.filter { includes(it.uri) })
}

fun ListeningHistory.withoutSource(source: LibrarySource, key: String): ListeningHistory {
    val selected = forSource(source, key)
    return copy(counts = counts - selected.counts.keys, lastPlayed = lastPlayed - selected.lastPlayed.keys,
        recent = recent.filterNot { it in selected.recent })
}
