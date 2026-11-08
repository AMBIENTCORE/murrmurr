package io.github.ambientcore.resonance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.io.File

private object ArtworkCache {
    val images = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val slots = Semaphore(2)

    suspend fun load(context: Context, song: Song): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = if (song.isServer) "${song.uri}:${song.coverArtId}:1024" else song.uri
        images[cacheKey]?.let { return@withContext it }
        if (cacheKey in missing) return@withContext null
        slots.withPermit {
            images[cacheKey]?.let { return@withPermit it }
            if (song.isServer) {
                if (song.coverArtId.isBlank()) return@withPermit null
                try {
                    val account = ServerAccountStore(context).load() ?: throw ServerFailure("No server account")
                    if (account.key != song.serverKey) throw ServerFailure("Artwork belongs to a different account")
                    val folder = File(context.cacheDir, "server-artwork")
                    check(folder.isDirectory || folder.mkdirs()) { "Cannot create artwork cache" }
                    val file = File(folder, sha256(cacheKey) + ".image")
                    val bytes = if (file.exists()) file.readBytes() else NavidromeClient(account)
                        .bytes("getCoverArt", mapOf("id" to song.coverArtId, "size" to "1024"), 4 * 1024 * 1024)
                    val bitmap = decodeArtwork(bytes) ?: throw ServerFailure("Server returned invalid artwork")
                    if (!file.exists()) file.writeBytes(bytes)
                    file.setLastModified(System.currentTimeMillis())
                    val files = folder.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
                        ?: throw java.io.IOException("Cannot list artwork cache")
                    var size = files.sumOf { it.length() }
                    files.forEach { old ->
                        if (size > 32L * 1024 * 1024 && old != file) {
                            val length = old.length()
                            if (!old.delete()) throw java.io.IOException("Cannot trim artwork cache")
                            size -= length
                        }
                    }
                    images.put(cacheKey, bitmap)
                    return@withPermit bitmap
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.w("ResonanceArtwork", "Server artwork unavailable (${error.javaClass.simpleName})")
                    return@withPermit null
                }
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(song.uri))
                val bytes = retriever.embeddedPicture
                if (bytes == null) { missing += song.uri; return@withPermit null }
                decodeArtwork(bytes)?.also { images.put(cacheKey, it) }
            } catch (error: RuntimeException) {
                Log.w("ResonanceArtwork", "Artwork unavailable for ${song.fileName}", error)
                missing += song.uri
                null
            } finally { retriever.release() }
        }
    }
}

private fun decodeArtwork(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (bounds.outWidth / inSampleSize > 1024 || bounds.outHeight / inSampleSize > 1024) inSampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

@Composable
fun Artwork(song: Song?, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val image by produceState<Bitmap?>(null, song?.uri, song?.coverArtId) {
        value = null
        value = song?.let { ArtworkCache.load(context, it) }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().testTag("artwork-image"), contentScale = ContentScale.Crop)
        else Image(painterResource(R.drawable.murrmurr_logo), null,
            Modifier.fillMaxSize(.72f).testTag("artwork-placeholder"), contentScale = ContentScale.Fit)
    }
}
