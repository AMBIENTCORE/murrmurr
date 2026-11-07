@file:Suppress("UnsafeOptInUsageError")

package io.github.ambientcore.resonance

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import java.io.InputStream
import java.net.HttpURLConnection

class ServerAudioSource(context: Context) : BaseDataSource(true) {
    private val accounts = ServerAccountStore(context)
    private val local = DefaultDataSource.Factory(context).createDataSource()
    private var stream: InputStream? = null
    private var connection: HttpURLConnection? = null
    private var remote = false
    private var opened = false
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        remote = dataSpec.uri.scheme == "resonance-server"
        if (!remote) {
            if (dataSpec.uri.scheme in setOf("http", "https")) throw ServerFailure("Network playback requires a configured server track")
            return local.open(dataSpec)
        }
        transferInitializing(dataSpec)
        try {
            val account = accounts.load() ?: throw ServerFailure("Configure the server before playing this track")
            if (dataSpec.uri.host != account.key) throw ServerFailure("This track belongs to a different server account")
            val id = dataSpec.uri.pathSegments.singleOrNull() ?: throw ServerFailure("Invalid server track")
            val client = NavidromeClient(account)
            val http = (java.net.URI(client.url("stream", mapOf("id" to id, "format" to "raw", "maxBitRate" to "0")))
                .toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else "${dataSpec.position + dataSpec.length - 1}"
                    setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
                }
            }
            connection = http
            val status = http.responseCode
            if (status == 401 || status == 403) throw ServerFailure("Server rejected playback authorization")
            if (status in 300..399) throw ServerFailure("Server redirected playback; update its URL")
            if (status !in 200..299) throw ServerFailure("Server playback returned HTTP $status")
            if (status == 206) {
                val range = http.getHeaderField("Content-Range").orEmpty()
                if (!range.startsWith("bytes ${dataSpec.position}-")) throw ServerFailure("Server returned an incorrect audio range")
            }
            val type = http.contentType.orEmpty()
            if (type.contains("json") || type.contains("xml") || type.contains("text/html")) throw ServerFailure("Server did not return an audio stream")
            val input = http.inputStream
            stream = input
            if (status == 200 && dataSpec.position > 0) {
                var skip = dataSpec.position
                val buffer = ByteArray(8192)
                while (skip > 0) {
                    val count = input.read(buffer, 0, minOf(skip, buffer.size.toLong()).toInt())
                    if (count < 0) throw ServerFailure("Server audio ended before the requested position")
                    skip -= count
                }
            }
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                else http.contentLengthLong.takeIf { it >= 0 }?.let { if (status == 200) it - dataSpec.position else it }
                    ?: C.LENGTH_UNSET.toLong()
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (error: Exception) {
            close()
            if (error is ServerFailure) throw error
            throw ServerFailure("Server playback unavailable; check the connection and Tailscale")
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!remote) return local.read(buffer, offset, length)
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val input = stream ?: throw ServerFailure("Server audio stream is not open")
        val count = try { input.read(buffer, offset, if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()) }
            catch (_: java.io.IOException) { throw ServerFailure("Server connection interrupted; reconnect and resume playback") }
        if (count < 0) {
            if (remaining > 0) throw ServerFailure("Server audio stream ended unexpectedly")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining >= 0) remaining -= count
        bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = if (remote) uri else local.uri
    override fun close() {
        if (!remote) { local.close(); return }
        try { stream?.close() } catch (_: java.io.IOException) {
            android.util.Log.w("ResonanceServer", "Server stream close failed")
        } finally {
            stream = null
            connection?.disconnect()
            connection = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
