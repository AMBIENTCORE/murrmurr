@file:Suppress("UnsafeOptInUsageError")

package io.github.ambientcore.resonance

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.datasource.DataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale

class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var prefs: SharedPreferences
    @Volatile private var equalizerParameters = EqualizerSettings().parameters()
    private val equalizerListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == EQUALIZER_PREF) readToneSettings()
    }
    private var session: MediaSession? = null
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var queueWrite: Job? = null
    private var sourceRead: Job? = null
    private lateinit var history: HistoryRepository
    private var historyWrite: Job? = null
    private val listeningClock = ListeningClock()
    private var listeningSong: Song? = null
    private var listeningDuration = 0L
    private var ended = false
    private var saveTick = 0
    private val handler = Handler(Looper.getMainLooper())
    private val savePosition = object : Runnable {
        override fun run() {
            sampleListening()
            if (++saveTick % 5 == 0) {
                prefs.edit().putInt("queue_index", player.currentMediaItemIndex.coerceAtLeast(0))
                    .putLong("queue_position", player.currentPosition.coerceAtLeast(0)).apply()
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("playback", MODE_PRIVATE)
        history = HistoryRepository(this)
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.app_name).build().apply { setSmallIcon(R.drawable.ic_murrmurr_notification) })
        val obsolete = setOf("sleep_end", "output_mode", "replay_gain", "preamp", "eq_enabled", "eq_centers",
            "eq_min", "eq_max", "eq_error", "gain_info", "tone_vshape_enabled")
        prefs.edit().apply {
            prefs.all.keys.filter { it in obsolete || it.startsWith("eq_band_") }.forEach(::remove)
            putString("output_info", "").putInt("output_bits", 0).putBoolean("output_float", false)
            putInt("output_sample_rate", 0)
        }.apply()
        readToneSettings()
        prefs.registerOnSharedPreferenceChangeListener(equalizerListener)
        val factory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                EqualizerAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(true).build()) {
                    equalizerParameters
                }
        }
        player = ExoPlayer.Builder(this, factory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { ServerAudioSource(this) }))
            .build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
            shuffleModeEnabled = prefs.getBoolean("shuffle", false)
        }
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                val description = "${audioEncodingLabel(audioTrackConfig.encoding)} / ${audioTrackConfig.sampleRate / 1000f} kHz"
                Log.i("ResonanceOutput", "AudioTrack: $description; floating-point tone processing")
                prefs.edit().putString("output_info", description).putInt("output_bits", pcmBitDepth(audioTrackConfig.encoding))
                    .putBoolean("output_float", audioTrackConfig.encoding == C.ENCODING_PCM_FLOAT)
                    .putInt("output_sample_rate", audioTrackConfig.sampleRate).apply()
            }
        })
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                sampleListening()
                // Transition callbacks clear the old source before this coherent track snapshot is read.
                if (events.contains(Player.EVENT_TRACKS_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    readAudioMetadata(player.currentTracks)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && ended) {
                    listeningClock.start(SystemClock.elapsedRealtime())
                    ended = false
                }
                sampleListening()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                sampleListening()
                if (playbackState == Player.STATE_ENDED) ended = true
            }
            override fun onPlayerError(error: PlaybackException) {
                if (listeningSong?.isServer == true) {
                    val reason = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<ServerFailure>().firstOrNull()?.message
                        ?: "Server playback failed (${error.errorCodeName}); check the connection or audio format"
                    reportError(reason)
                    return
                }
                Log.e("ResonancePlayback", "Decoder or audio output failed", error)
                reportError("Playback failed: ${error.message}")
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = saveQueue()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                sampleListening(false, listeningDuration)
                listeningSong = mediaItem?.mediaMetadata?.extras?.getString("song_json")?.let {
                    LibraryRepository.songFromJson(org.json.JSONObject(it))
                }
                listeningDuration = listeningSong?.duration ?: 0L
                listeningClock.start(SystemClock.elapsedRealtime())
                ended = false
                sourceRead?.cancel()
                prefs.edit().putInt("queue_index", player.currentMediaItemIndex.coerceAtLeast(0))
                    .putLong("queue_position", player.currentPosition.coerceAtLeast(0))
                    .putString("audio_info", "").putInt("source_bits", 0).putBoolean("source_lossy", false)
                    .putString("source_uri", mediaItem?.mediaId.orEmpty()).putString("source_codec", "")
                    .putInt("source_bitrate", 0).putString("source_catalog_number", "")
                    .putString("source_year", "")
                    .putString("source_note", "").apply()
            }
            override fun onRepeatModeChanged(repeatMode: Int) { prefs.edit().putInt("repeat", repeatMode).apply() }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { prefs.edit().putBoolean("shuffle", shuffleModeEnabled).apply() }
        })
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(intent).build()
        restoreQueue()
        handler.postDelayed(savePosition, 1000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun sampleListening(playing: Boolean = player.isPlaying, duration: Long = player.duration) {
        val song = listeningSong ?: return
        if (duration > 0 && duration != C.TIME_UNSET) listeningDuration = duration
        if (!listeningClock.sample(SystemClock.elapsedRealtime(), playing, listeningDuration)) return
        val event = ListenEvent(song.uri, song.title, song.artist, song.album, System.currentTimeMillis())
        historyWrite = persistenceScope.launch {
            try { history.record(event) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("ResonanceHistory", "Could not persist a qualified play", error)
                prefs.edit().putString("error", "Could not save play history: ${error.message}").apply()
            }
        }
    }

    private fun saveQueue() {
        val entries = (0 until player.mediaItemCount).mapNotNull { index ->
            player.getMediaItemAt(index).mediaMetadata.extras?.getString("song_json")
        }
        queueWrite?.cancel()
        queueWrite = persistenceScope.launch {
            val queue = JSONArray()
            entries.forEach { entry -> ensureActive(); queue.put(org.json.JSONObject(entry)) }
            ensureActive()
            prefs.edit().putString("queue", queue.toString()).apply()
        }
    }

    private fun restoreQueue() {
        val raw = prefs.getString("queue", null) ?: return
        val index = prefs.getInt("queue_index", 0)
        val position = prefs.getLong("queue_position", 0)
        try {
            val songs = JSONArray(raw).objects().map(LibraryRepository::songFromJson)
            if (songs.isNotEmpty()) {
                player.setMediaItems(songs.map(Song::mediaItem), index.coerceIn(songs.indices), position)
                player.prepare()
            }
        } catch (error: Exception) {
            Log.e("ResonancePlayback", "Cannot restore queue", error)
            reportError("Saved queue could not be restored: ${error.message}")
        }
    }

    private fun readAudioMetadata(tracks: Tracks) {
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        val song = listeningSong
        val group = tracks.groups.firstOrNull { it.isSelected && it.type == C.TRACK_TYPE_AUDIO }
        val format = group?.let { selected ->
            (0 until selected.length).firstOrNull(selected::isTrackSelected)?.let(selected::getTrackFormat)
        }
        val lossy = format?.sampleMimeType in setOf("audio/mpeg", "audio/mp4a-latm", "audio/aac", "audio/opus",
                "audio/vorbis", "audio/3gpp", "audio/amr-wb", "audio/ac3", "audio/eac3", "audio/x-ms-wma")
        val bits = if (format?.sampleMimeType == "audio/raw") pcmBitDepth(format.pcmEncoding) else 0
        publishSource(format, bits, lossy)
        sourceRead?.cancel()
        if (uri.scheme == "resonance-server") {
            publishSource(format, if (lossy) 0 else song?.sourceBits ?: bits, lossy)
            return
        }
        val yearAvailable = taggedYear(recordingYear(format?.metadata), song?.year).isNotBlank()
        val catalogAvailable = catalogNumber(format?.metadata).ifBlank { song?.catalogNumber.orEmpty() }.isNotBlank()
        if ((lossy || bits > 0 || Build.VERSION.SDK_INT < 31) && (format?.bitrate ?: 0) > 0 &&
            yearAvailable && (catalogAvailable || song?.extension !in EmbeddedRatingTags.metadataExtensions)) return
        sourceRead = persistenceScope.launch {
            try {
                val tags = if (song != null && song.extension in EmbeddedRatingTags.metadataExtensions) {
                    val stream = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open source tags")
                    stream.use { EmbeddedRatingTags.readMetadata(it, song.extension) }
                } else EmbeddedTrackMetadata()
                val metadata = MediaMetadataRetriever()
                val (sourceBits, bitrate, year) = try {
                    metadata.setDataSource(this@PlaybackService, uri)
                    val sourceBits = if (format != null && !lossy && bits == 0 && Build.VERSION.SDK_INT >= 31)
                        metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: bits else bits
                    Triple(sourceBits, metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0,
                        taggedYear(tags.year, metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)))
                } finally { metadata.release() }
                withContext(Dispatchers.Main) {
                    if (player.currentMediaItem?.mediaId == item.mediaId) {
                        publishSource(format, sourceBits, lossy, bitrate, year, tags.catalogNumber)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("ResonanceSource", "Cannot read source audio metadata", error)
                withContext(Dispatchers.Main) {
                    if (player.currentMediaItem?.mediaId == item.mediaId) prefs.edit()
                        .putString("source_note", "Source audio metadata unavailable: ${error.message}").apply()
                }
            }
        }
    }

    private fun publishSource(format: Format?, bits: Int, lossy: Boolean, retrievedBitrate: Int = 0,
        retrievedYear: String = "", retrievedCatalog: String = "") {
        val codec = when (format?.sampleMimeType) {
            "audio/mpeg" -> "MP3"
            "audio/mp4a-latm", "audio/aac" -> "AAC"
            "audio/raw" -> "PCM"
            else -> format?.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT)
                ?: listeningSong?.extension?.takeIf { it.isNotBlank() } ?: "Unknown codec"
        }
        val description = buildList {
            add(codec)
            format?.sampleRate?.takeIf { it > 0 }?.let { add("${it / 1000f} kHz") }
            if (bits > 0) add("$bits-bit")
            if (lossy) add("lossy")
            format?.channelCount?.takeIf { it > 0 }?.let { add("$it ch") }
            format?.bitrate?.takeIf { it > 0 }?.let { add("${it / 1000} kbps") }
        }.joinToString(" / ")
        val bitrate = format?.bitrate?.takeIf { it > 0 } ?: retrievedBitrate.takeIf { it > 0 }
            ?: listeningSong?.bitrate ?: 0
        prefs.edit().putString("audio_info", description).putInt("source_bits", bits).putBoolean("source_lossy", lossy)
            .putString("source_uri", player.currentMediaItem?.mediaId.orEmpty()).putString("source_codec", codec)
            .putInt("source_bitrate", bitrate)
            .putString("source_year", taggedYear(recordingYear(format?.metadata), retrievedYear, listeningSong?.year))
            .putString("source_catalog_number", catalogNumber(format?.metadata).ifBlank { retrievedCatalog }
                .ifBlank { listeningSong?.catalogNumber.orEmpty() }).apply()
    }

    private fun reportError(message: String) {
        Log.e("ResonancePlayback", message)
        prefs.edit().putString("error", message).apply()
    }

    private fun readToneSettings() {
        try { equalizerParameters = readEqualizer(prefs).parameters() }
        catch (error: Exception) {
            equalizerParameters = EqualizerSettings().parameters()
            Log.e("ResonanceEQ", "Invalid saved EQ settings", error)
            reportError("Equalizer settings could not be read; EQ disabled. Reset it in Equalizer.")
        }
    }

    override fun onDestroy() {
        sampleListening(false)
        handler.removeCallbacksAndMessages(null)
        prefs.unregisterOnSharedPreferenceChangeListener(equalizerListener)
        sourceRead?.cancel()
        prefs.edit().putLong("queue_position", player.currentPosition.coerceAtLeast(0)).apply()
        session?.release()
        player.release()
        val pendingQueue = queueWrite
        val pendingHistory = historyWrite
        persistenceScope.launch { pendingQueue?.join(); pendingHistory?.join() }
            .invokeOnCompletion { persistenceScope.cancel() }
        super.onDestroy()
    }
}

internal fun catalogNumber(metadata: Metadata?): String {
    if (metadata == null) return ""
    for (index in 0 until metadata.length()) {
        val entry = metadata[index]
        val (key, value) = when (entry) {
            is VorbisComment -> entry.key to entry.value
            is TextInformationFrame -> entry.description.orEmpty() to entry.values.firstOrNull().orEmpty()
            else -> continue
        }
        val normalized = key.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        if (normalized in setOf("CATALOGNUMBER", "CATALOGNO", "CATALOG", "CATALOGUENUMBER", "CATALOGUENO") && value.isNotBlank()) {
            return value.trim()
        }
    }
    return ""
}

internal fun recordingYear(metadata: Metadata?): String {
    if (metadata == null) return ""
    val years = mutableListOf<String>()
    val originalYears = mutableListOf<String>()
    val builder = MediaMetadata.Builder()
    for (index in 0 until metadata.length()) {
        val entry = metadata[index]
        entry.populateMediaMetadata(builder)
        val (key, value) = when (entry) {
            is VorbisComment -> entry.key to entry.value
            is TextInformationFrame -> when (entry.id) {
                "TYER", "TDRC" -> "YEAR" to entry.values.firstOrNull().orEmpty()
                "TORY", "TDOR" -> "ORIGINALYEAR" to entry.values.firstOrNull().orEmpty()
                "TXXX" -> entry.description.orEmpty() to entry.values.firstOrNull().orEmpty()
                else -> continue
            }
            else -> continue
        }
        when (key.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)) {
            "YEAR", "DATE" -> years += value
            "ORIGINALYEAR", "ORIGINALDATE" -> originalYears += value
        }
    }
    val decoded = builder.build()
    return taggedYear(*(years + listOfNotNull(decoded.recordingYear?.toString(), decoded.releaseYear?.toString()) +
        originalYears).toTypedArray())
}

fun pcmBitDepth(encoding: Int): Int = when (encoding) {
    C.ENCODING_PCM_8BIT -> 8
    C.ENCODING_PCM_16BIT -> 16
    C.ENCODING_PCM_24BIT -> 24
    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
    else -> 0
}

fun audioEncodingLabel(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_8BIT -> "8-bit PCM"
    C.ENCODING_PCM_16BIT -> "16-bit PCM"
    C.ENCODING_PCM_24BIT -> "24-bit PCM"
    C.ENCODING_PCM_32BIT -> "32-bit PCM"
    C.ENCODING_PCM_FLOAT -> "32-bit float PCM"
    else -> "Encoding $encoding"
}

fun Song.mediaItem(): MediaItem = MediaItem.Builder().setMediaId(uri).setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setAlbumArtist(albumArtist).setTrackNumber(track).setDiscNumber(disc)
        .setRecordingYear(taggedYear(year).toIntOrNull())
        .setExtras(Bundle().apply { putString("song_json", LibraryRepository.songToJson(this@mediaItem).toString()) }).build())
    .build()
