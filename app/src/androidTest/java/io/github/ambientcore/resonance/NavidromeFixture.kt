package io.github.ambientcore.resonance

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class NavidromeFixture(private val audio: ByteArray, private val artwork: ByteArray, private val count: Int = 3) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    val url = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    @Volatile var unavailable = false
    @Volatile var failPlaylist = false
    @Volatile var invalidPagination = false
    @Volatile var error: Throwable? = null
    val ratings = mutableMapOf("id-0" to 4)
    val favorites = mutableSetOf("id-0")
    private val workers = CopyOnWriteArrayList<Thread>()
    private val accept = thread(name = "NavidromeFixture") {
        while (running.get()) {
            try {
                val client = socket.accept()
                workers += thread { client.use { handle(it) } }
            } catch (failure: java.io.IOException) {
                if (running.get()) error = failure
            }
        }
    }
    private fun song(index: Int): JSONObject = JSONObject().put("id", "id-$index")
        .put("title", "Server Track $index").put("artist", "Server Artist").put("albumArtist", "Server Artist")
        .put("album", "Server Album").put("albumId", "album-1").put("duration", 30).put("suffix", "wav")
        .put("path", "Server Artist/Server Album/$index.wav").put("coverArt", "cover-1")
        .put("bitDepth", 16).put("size", audio.size).put("created", "2026-11-01T12:00:00Z")
        .put("bitRate", 705).put("catalogNumber", "SERVER-CAT-1")
        .put("year", 0).put("releaseDate", JSONObject().put("year", 2026))
        .put("genre", "Ambient; Electronic").put("userRating", synchronized(ratings) { ratings["id-$index"] ?: 0 })
        .apply { if (synchronized(favorites) { "id-$index" in favorites }) put("starred", "2026-11-01T12:00:00Z") }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val reader = client.getInputStream().bufferedReader()
            val first = reader.readLine() ?: return
            val target = java.net.URI(first.split(' ')[1])
            val parameters = target.rawQuery.split('&').associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val endpoint = target.path.substringAfterLast('/').removeSuffix(".view")
            requests += endpoint to parameters
            fun respond(status: Int, type: String, bytes: ByteArray, extra: String = "") {
                client.getOutputStream().apply {
                    write("HTTP/1.1 $status Test\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n".toByteArray())
                    write(bytes); flush()
                }
            }
            if (unavailable) { respond(503, "text/plain", byteArrayOf()); return }
            val token = MessageDigest.getInstance("MD5").digest(("fixture-password" + parameters["s"]).toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            if (parameters["u"] != "fixture-user" || parameters["t"] != token) { respond(401, "text/plain", byteArrayOf()); return }
            if (endpoint == "stream") {
                check(parameters["format"] == "raw" && parameters["maxBitRate"] == "0")
                val range = headers["range"]?.removePrefix("bytes=")?.split('-')
                val start = range?.get(0)?.toInt() ?: 0
                val end = range?.getOrNull(1)?.toIntOrNull() ?: (audio.size - 1)
                respond(if (range == null) 200 else 206, "audio/wav", audio.copyOfRange(start, minOf(end + 1, audio.size)),
                    if (range == null) "" else "Content-Range: bytes $start-${minOf(end, audio.lastIndex)}/${audio.size}\r\n")
                return
            }
            if (endpoint == "getCoverArt") { respond(200, "image/png", artwork); return }
            if (endpoint == "getPlaylist" && failPlaylist) { respond(500, "text/plain", byteArrayOf()); return }
            val root = JSONObject().put("status", "ok").put("version", "1.16.1").put("type", "navidrome").put("openSubsonic", true)
            when (endpoint) {
                "ping" -> Unit
                "search3" -> {
                    check(parameters["query"] == "")
                    val offset = if (invalidPagination) 0 else parameters.getValue("songOffset").toInt()
                    val size = parameters.getValue("songCount").toInt()
                    root.put("searchResult3", JSONObject().put("song", JSONArray((offset until minOf(offset + size, count)).map(::song))))
                }
                "getPlaylists" -> root.put("playlists", JSONObject().put("playlist", JSONArray()
                    .put(JSONObject().put("id", "mix").put("name", "Server Mix"))))
                "getPlaylist" -> root.put("playlist", JSONObject().put("entry", JSONArray()
                    .put(song(1)).put(song(0)).put(song(1))))
                "setRating" -> synchronized(ratings) { ratings[parameters.getValue("id")] = parameters.getValue("rating").toInt() }
                "star" -> synchronized(favorites) { favorites += parameters.getValue("id") }
                "unstar" -> synchronized(favorites) { favorites -= parameters.getValue("id") }
                else -> error("Unexpected fixture endpoint")
            }
            respond(200, "application/json", JSONObject().put("subsonic-response", root).toString().toByteArray())
        } catch (failure: java.io.IOException) {
            if (running.get() && failure !is java.net.SocketException) error = failure
        } catch (failure: Throwable) { error = failure }
    }
    override fun close() {
        running.set(false)
        socket.close()
        accept.join(3000)
        workers.forEach { it.join(3000) }
        error?.let { throw AssertionError("Navidrome fixture failed", it) }
    }
}
