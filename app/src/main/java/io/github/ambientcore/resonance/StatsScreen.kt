package io.github.ambientcore.resonance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun StatsScreen(data: LibraryData, history: HistoryUi, play: (Song) -> Unit) {
    val (mode, changeMode) = rememberBrowseMode("stats", BrowseMode.LIST)
    val stats = remember(data.songs, data.favorites, data.ratings) {
        libraryStatistics(data.songs, data.favorites, data.ratings)
    }
    val songIndex = remember(data.songs) { data.songs.associateBy { it.uri } }
    val metrics = listOf(
        "Tracks" to stats.tracks.toString(), "Albums" to stats.albums.toString(),
        "Album artists" to stats.artists.toString(), "Genres" to stats.genres.size.toString(),
        "Total duration" to durationLabel(stats.duration), "Indexed audio size" to audioSizeLabel(stats.bytes),
        "Playlists" to data.playlists.size.toString(), "Favorites" to stats.favorites.toString(),
        "Rated tracks" to stats.ratedTracks.toString(),
        "Average rating" to (stats.averageRating?.let { "%.1f / 5".format(Locale.ROOT, it) } ?: "Unrated"),
        "Qualified plays" to (if (history.loading || history.error != null) "--" else history.data.counts.values.sum().toString()),
        "Played tracks" to (if (history.loading || history.error != null) "--" else history.data.counts.size.toString()),
    )
    BrowseGrid(mode, Modifier.fillMaxSize().testTag("stats-screen"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        fullItem {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Stats", Modifier.weight(1f).padding(start = 10.dp), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                ViewModeMenu("stats", mode, changeMode)
            }
        }
        metrics.chunked(2).forEach { pair -> fullItem {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Surface)
                        .padding(14.dp).testTag("stat-${label.lowercase(Locale.ROOT).replace(' ', '-')}")
                        .semantics(mergeDescendants = true) {}) {
                        Text(label, color = Muted, fontSize = 11.sp)
                        Text(value, color = Paper, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        } }
        fullItem {
            StatsHeading("Listening history")
            Text("Counts after 30 seconds of playback, or half of tracks shorter than a minute. Seeking and paused time are excluded.",
                color = Muted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
            if (history.loading) Text("Loading history", color = Muted, fontSize = 12.sp)
            history.error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
        if (!history.loading && history.error == null) {
            fullItem { StatsHeading("Recently played") }
            if (history.data.recent.isEmpty()) fullItem { Text("No qualified plays yet", color = Muted, fontSize = 13.sp) }
            items(history.data.recent.take(20).withIndex().toList(), key = { "recent:${it.index}:${it.value.uri}" }) { (_, event) ->
                HistoryRow(event.title, event.artist, playedAtLabel(event.playedAt), songIndex[event.uri],
                    mode, songIndex[event.uri]?.let { song -> { play(song) } })
            }
            fullItem { StatsHeading("Most played") }
            items(history.data.counts.entries.sortedByDescending { it.value }.take(10), key = { "played:${it.key}" }) { entry ->
                val song = songIndex[entry.key]
                val snapshot = history.data.recent.firstOrNull { it.uri == entry.key }
                HistoryRow(song?.title ?: snapshot?.title ?: "Unavailable track", song?.artist ?: snapshot?.artist.orEmpty(),
                    "${entry.value} plays", song, mode, song?.let { { play(it) } })
            }
        }
        fullItem { StatsHeading("File formats") }
        if (stats.formats.isEmpty()) fullItem { Text("No indexed tracks", color = Muted, fontSize = 13.sp) }
        items(stats.formats, key = { "format:${it.label}" }) { StatsBreakdown(it, stats.tracks, mode) }
        fullItem { StatsHeading("Ratings") }
        items(stats.ratings, key = { "rating:${it.label}" }) { StatsBreakdown(it, stats.tracks, mode) }
        fullItem {
            StatsHeading("Top genres")
            Text("Tracks with multiple genres count in each genre.", color = Muted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
        items(stats.genres.take(10), key = { "genre:${it.label}" }) { StatsBreakdown(it, stats.tracks, mode) }
        fullItem {
            Text("Library duration and audio size use indexed metadata, not listening time. Unknown durations and file sizes contribute zero.",
                color = Muted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun HistoryRow(title: String, artist: String, detail: String, song: Song?, mode: BrowseMode, play: (() -> Unit)?) {
    BrowseItem(title, artist + if (play == null) " / Not in current library" else "", mode, song = song,
        onClick = play, badges = { Text(detail, color = Lime, fontSize = 10.sp, maxLines = 2,
            modifier = Modifier.widthIn(max = 100.dp)) })
}

@Composable
private fun StatsHeading(label: String) {
    Text(label, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun StatsBreakdown(stat: StatCount, total: Int, mode: BrowseMode) {
    val fraction = if (total > 0) stat.count.toFloat() / total else 0f
    Column(Modifier.fillMaxWidth().padding(vertical = if (mode.grid) 3.dp else 1.dp)) {
        BrowseItem(stat.label, "", mode, icon = Icons.Rounded.BarChart,
            badges = { Text("${stat.count} / ${"%.1f".format(Locale.ROOT, fraction * 100)}%", color = Muted, fontSize = 11.sp) })
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = if (mode.grid) 7.dp else 3.dp).height(4.dp),
            color = Cyan, trackColor = Surface, drawStopIndicator = {})
    }
}
