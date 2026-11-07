package io.github.ambientcore.resonance

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class HistoryRepository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "listening-history.json"))
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    fun load(): ListeningHistory = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized ListeningHistory()
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val counts = json.getJSONObject("counts")
        val lastPlayed = json.getJSONObject("lastPlayed")
        ListeningHistory(
            counts.keys().asSequence().associateWith { counts.getLong(it) },
            lastPlayed.keys().asSequence().associateWith { lastPlayed.getLong(it) },
            json.getJSONArray("recent").objects().map {
                ListenEvent(it.getString("uri"), it.getString("title"), it.getString("artist"),
                    it.getString("album"), it.getLong("playedAt"))
            },
        )
    }

    fun record(event: ListenEvent) = synchronized(lock) { save(load().record(event)) }
    fun clearSource(source: LibrarySource, key: String) = synchronized(lock) { save(load().withoutSource(source, key)) }

    fun save(history: ListeningHistory) = synchronized(lock) {
        val json = JSONObject().put("counts", JSONObject(history.counts)).put("lastPlayed", JSONObject(history.lastPlayed))
            .put("recent", JSONArray(history.recent.take(1000).map {
                JSONObject().put("uri", it.uri).put("title", it.title).put("artist", it.artist)
                    .put("album", it.album).put("playedAt", it.playedAt)
            }))
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        prefs.edit().putString("history_revision", UUID.randomUUID().toString()).apply()
    }

    companion object { private val lock = Any() }
}
