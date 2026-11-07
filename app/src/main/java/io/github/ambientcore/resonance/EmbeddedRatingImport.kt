package io.github.ambientcore.resonance

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

data class RatingStamp(val modified: Long, val size: Long)
fun Song.ratingStamp(): RatingStamp = RatingStamp(modified, size)

data class RatingImportResult(
    val values: Map<String, Int?>, val stamps: Map<String, RatingStamp>,
    val read: Int, val cached: Int, val manual: Int, val unsupported: Int, val failed: Int,
    val cancelled: Boolean, val warnings: List<String>,
)
data class RatingImportUi(val running: Boolean = false, val progress: String = "", val result: String = "")

class EmbeddedRatingImport(private val context: Context) {
    fun run(data: LibraryData, force: Boolean, stop: AtomicBoolean, progress: (String) -> Unit): RatingImportResult {
        val values = linkedMapOf<String, Int?>()
        val stamps = linkedMapOf<String, RatingStamp>()
        val warnings = mutableListOf<String>()
        var read = 0; var cached = 0; var manual = 0; var unsupported = 0; var failed = 0
        var lastProgress = 0L
        for ((index, song) in data.songs.withIndex()) {
            if (stop.get()) break
            val now = SystemClock.elapsedRealtime()
            if (now - lastProgress >= 250) {
                lastProgress = now
                progress("${index + 1} / ${data.songs.size} tracks; $read files read, $cached unchanged")
            }
            if (song.uri in data.ratingOverrides) { manual++; continue }
            if (song.extension !in EmbeddedRatingTags.extensions) { unsupported++; continue }
            val stamp = song.ratingStamp()
            if (!force && (stamp.modified > 0 || stamp.size > 0) && data.ratingTagCache[song.uri] == stamp) {
                cached++
                continue
            }
            read++
            try {
                val uri = Uri.parse(song.uri)
                if (uri.scheme !in setOf("content", "file")) throw IOException("Only local file/content URIs are supported")
                val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
                val value = stream.use { EmbeddedRatingTags.read(it, song.extension) }
                values[song.uri] = value
                stamps[song.uri] = stamp
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failed++
                Log.w("ResonanceRatings", "Cannot read rating: ${song.fileName}", error)
                if (warnings.size < 5) warnings += "${song.fileName.take(60)}: ${error.message?.take(160)}"
            }
        }
        return RatingImportResult(values, stamps, read, cached, manual, unsupported, failed, stop.get(), warnings)
    }
}

fun mergeImportedRatings(current: LibraryData, result: RatingImportResult): LibraryData {
    val songs = current.songs.associateBy { it.uri }
    val ratings = current.ratings.toMutableMap()
    val cache = current.ratingTagCache.toMutableMap()
    result.stamps.forEach { (uri, stamp) ->
        if (songs[uri]?.ratingStamp() != stamp) return@forEach
        cache[uri] = stamp
        if (uri !in current.ratingOverrides) {
            val value = result.values[uri]
            if (value != null && value > 0) ratings[uri] = value else ratings.remove(uri)
        }
    }
    return current.copy(ratings = ratings, ratingTagCache = cache)
}
