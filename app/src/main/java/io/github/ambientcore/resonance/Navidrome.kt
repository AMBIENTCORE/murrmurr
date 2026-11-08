package io.github.ambientcore.resonance

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class LibrarySource { LOCAL, SERVER }

class ServerAccount(val url: String, val username: String, val password: String, val allowHttp: Boolean) {
    val key: String get() = sha256("$url\u0000$username")
    override fun toString(): String = "ServerAccount(redacted)"
}

fun serverAccount(url: String, username: String, password: String, allowHttp: Boolean): ServerAccount {
    val parsed = try { URI(url.trim()) } catch (_: java.net.URISyntaxException) {
        throw IllegalArgumentException("Enter a valid server URL")
    }
    require(parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank()) { "Use an http:// or https:// server URL" }
    require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null) { "URL must not contain credentials, a query or a fragment" }
    require(parsed.scheme != "http" || allowHttp) { "HTTP requires the trusted LAN / Tailscale option" }
    require(parsed.port == -1 || parsed.port in 1..65535) { "Invalid server port" }
    require(username.isNotBlank() && password.isNotEmpty()) { "Enter a username and password" }
    return ServerAccount(parsed.toASCIIString().trimEnd('/'), username.trim(), password, allowHttp)
}

fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 255) }

fun serverTrackUri(key: String, id: String): String {
    require(key.matches(Regex("[a-f0-9]{64}")) && id.isNotBlank()) { "Invalid server track identity" }
    return "resonance-server://$key/" + URLEncoder.encode(id, "UTF-8").replace("+", "%20")
}

class ServerAccountStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "server-account.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey("resonance-navidrome", null)
        if (existing != null) return existing as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("resonance-navidrome",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): ServerAccount? = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized null
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > 29 && bytes[0].toInt() == 1) { "Invalid saved server account" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val json = JSONObject(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8))
        serverAccount(json.getString("url"), json.getString("username"), json.getString("password"), json.getBoolean("allowHttp"))
    }
    fun save(account: ServerAccount) = synchronized(lock) {
        val json = JSONObject().put("url", account.url).put("username", account.username)
            .put("password", account.password).put("allowHttp", account.allowHttp)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(1) + cipher.iv + cipher.doFinal(json.toString().toByteArray()))
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    companion object { private val lock = Any() }
}

class ServerFailure(message: String) : IOException(message)

class NavidromeClient(val account: ServerAccount) {
    fun url(endpoint: String, parameters: Map<String, String> = emptyMap()): String {
        require(endpoint in setOf("ping", "search3", "getPlaylists", "getPlaylist", "stream", "getCoverArt", "star", "unstar", "setRating")) {
            "Unsupported server endpoint"
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        val token = MessageDigest.getInstance("MD5").digest((account.password + salt).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val auth = mapOf("u" to account.username, "t" to token, "s" to salt, "v" to "1.16.1", "c" to "murrmurr", "f" to "json")
        require(parameters.keys.none { it in auth || it == "p" }) { "Authentication parameters cannot be replaced" }
        return account.url + "/rest/$endpoint.view?" + (auth + parameters).entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
    }

    private fun connection(endpoint: String, parameters: Map<String, String>): HttpURLConnection =
        (URI(url(endpoint, parameters)).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", if (endpoint == "getCoverArt") "image/*" else "application/json")
        }

    fun bytes(endpoint: String, parameters: Map<String, String> = emptyMap(), limit: Int = 8 * 1024 * 1024): ByteArray {
        var connection: HttpURLConnection? = null
        try {
            connection = connection(endpoint, parameters)
            val status = connection.responseCode
            if (status in 300..399) throw ServerFailure("Server redirected the request; enter its final URL")
            if (status == 401 || status == 403) throw ServerFailure("Server rejected the login")
            if (status !in 200..299) throw ServerFailure("Server returned HTTP $status")
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) throw ServerFailure("Server response exceeds the size limit")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (error: ServerFailure) { throw error }
        catch (_: javax.net.ssl.SSLException) { throw ServerFailure("TLS connection failed; check the server certificate and URL") }
        catch (_: IOException) { throw ServerFailure("Server unavailable; check Wi-Fi, Tailscale and the server address") }
        finally { connection?.disconnect() }
    }

    fun request(endpoint: String, parameters: Map<String, String> = emptyMap()): JSONObject {
        val root = try { JSONObject(bytes(endpoint, parameters).toString(Charsets.UTF_8)).getJSONObject("subsonic-response") }
        catch (error: ServerFailure) { throw error }
        catch (_: org.json.JSONException) { throw ServerFailure("Server returned an invalid OpenSubsonic response") }
        if (root.optString("status") != "ok") {
            val code = root.optJSONObject("error")?.optInt("code") ?: 0
            throw ServerFailure(when (code) {
                40, 41 -> "Server rejected the login"
                50 -> "Server account lacks permission"
                70 -> "Server track or playlist no longer exists"
                else -> "Server API failed (code $code)"
            })
        }
        return root
    }

    fun catalog(progress: (String) -> Unit, checkCancelled: () -> Unit): LibraryData {
        request("ping")
        val songs = linkedMapOf<String, Song>()
        val ratings = mutableMapOf<String, Int>()
        val favorites = mutableSetOf<String>()
        var offset = 0
        do {
            checkCancelled()
            val result = request("search3", mapOf("query" to "", "artistCount" to "0", "albumCount" to "0",
                "songCount" to "500", "songOffset" to "$offset")).getJSONObject("searchResult3")
            val page = result.optJSONArray("song")?.objects().orEmpty()
            if (page.isEmpty()) break
            val before = songs.size
            page.forEach { item ->
                if (!item.optBoolean("isVideo")) {
                    val song = song(item)
                    songs[song.uri] = song
                    item.optInt("userRating").takeIf { it in 1..5 }?.let { ratings[song.uri] = it }
                    if (item.optString("starred").isNotEmpty()) favorites += song.uri
                }
            }
            if (songs.size == before) throw ServerFailure("Server pagination did not advance")
            offset += page.size
            if (offset > 200_000) throw ServerFailure("Server library exceeds the supported 200,000-track limit")
            progress("${songs.size} server tracks")
        } while (page.size >= 500)
        checkCancelled()
        val playlists = request("getPlaylists").getJSONObject("playlists").optJSONArray("playlist")?.objects().orEmpty().map { summary ->
            checkCancelled()
            val id = summary.getString("id")
            val playlist = request("getPlaylist", mapOf("id" to id)).getJSONObject("playlist")
            val entries = playlist.optJSONArray("entry")?.objects().orEmpty().map { item ->
                val song = song(item)
                songs.putIfAbsent(song.uri, song)
                song.uri
            }
            progress("Loading server playlist: ${summary.getString("name")}")
            MusicPlaylist("server:${account.key}:$id", summary.getString("name"), entries,
                sourceKey = "navidrome:$id")
        }
        return LibraryData(songs = songs.values.toList(), playlists = playlists, ratings = ratings,
            favorites = favorites, ratingOverrides = emptySet())
    }

    private fun song(json: JSONObject): Song {
        val id = json.getString("id")
        val artist = json.optString("artist", "Unknown artist")
        val album = json.optString("album", "Unknown album")
        val genres = json.optJSONArray("genres")?.objects()?.map { it.getString("name") }
        val created = json.optString("created")
        val timestamp = if (created.isBlank()) 0L else try { Instant.parse(created).epochSecond }
            catch (_: java.time.format.DateTimeParseException) { throw ServerFailure("Server returned an invalid track date") }
        return Song(serverTrackUri(account.key, id), json.getString("title"), artist, album,
            json.optString("albumArtist", artist), genres?.joinToString("; ") ?: json.optString("genre"),
            taggedYear(json.optString("year"), json.optJSONObject("releaseDate")?.optString("year"),
                json.optJSONObject("originalReleaseDate")?.optString("year")), json.optInt("track"), json.optInt("discNumber"),
            json.optLong("duration") * 1000, folder = "$artist/$album",
            fileName = json.optString("path").substringAfterLast('/').ifBlank { "$id.${json.optString("suffix")}" },
            modified = timestamp, size = json.optLong("size"), mediaVolume = "server",
            serverKey = account.key, serverTrackId = id, coverArtId = json.optString("coverArt"),
            serverAlbumId = json.optString("albumId"), sourceBits = json.optInt("bitDepth"),
            catalogNumber = json.optString("catalogNumber"), bitrate = json.optInt("bitRate") * 1000)
    }
}

class ServerCatalogStore(private val context: Context, val key: String) {
    init { require(key.matches(Regex("[a-f0-9]{64}"))) { "Invalid server catalog identity" } }
    private val name = "server-library-$key.json"
    private val repository = LibraryRepository(context, name)
    fun load(): LibraryData = repository.load()
    fun save(data: LibraryData) = repository.save(data)
    fun modified(): Long = File(context.filesDir, name).lastModified()
    fun clear() = AtomicFile(File(context.filesDir, name)).delete()
}
