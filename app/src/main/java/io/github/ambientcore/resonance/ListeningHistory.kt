package io.github.ambientcore.resonance

import java.text.DateFormat
import java.util.Date

data class ListenEvent(val uri: String, val title: String, val artist: String, val album: String, val playedAt: Long)
data class ListeningHistory(
    val counts: Map<String, Long> = emptyMap(),
    val lastPlayed: Map<String, Long> = emptyMap(),
    val recent: List<ListenEvent> = emptyList(),
)
data class HistoryUi(val data: ListeningHistory = ListeningHistory(), val loading: Boolean = true, val error: String? = null)

fun ListeningHistory.record(event: ListenEvent): ListeningHistory {
    require(event.uri.isNotBlank() && event.playedAt > 0) { "A listening event needs a track and timestamp" }
    return copy(
        counts = counts + (event.uri to ((counts[event.uri] ?: 0L) + 1L)),
        lastPlayed = lastPlayed + (event.uri to event.playedAt),
        recent = (listOf(event) + recent).take(1000),
    )
}

class ListeningClock {
    private var lastSample = 0L
    private var wasPlaying = false
    private var listened = 0L
    private var counted = false

    fun start(now: Long) {
        lastSample = now
        wasPlaying = false
        listened = 0
        counted = false
    }

    fun sample(now: Long, playing: Boolean, duration: Long): Boolean {
        val timestamp = now.coerceAtLeast(lastSample)
        if (wasPlaying) listened += timestamp - lastSample
        lastSample = timestamp
        wasPlaying = playing
        val threshold = if (duration > 0) (duration / 2).coerceIn(1, 30_000) else 30_000L
        if (!counted && listened >= threshold) {
            counted = true
            return true
        }
        return false
    }
}

fun playedAtLabel(timestamp: Long?): String = timestamp?.takeIf { it > 0 }?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
} ?: "--"
