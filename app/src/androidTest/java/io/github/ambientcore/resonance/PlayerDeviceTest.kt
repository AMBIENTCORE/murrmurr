package io.github.ambientcore.resonance

import android.Manifest
import android.content.Context
import android.net.Uri
import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class PlayerDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE)
    private val keepTestWindowAwake = ActivityLifecycleCallback { activity, stage ->
        if (stage == Stage.RESUMED && activity.packageName.endsWith(".qa")) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Before fun requireIsolatedTestApp() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".qa")) {
            "Device tests must never run against the user's installed library"
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(keepTestWindowAwake)
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard").forEach { command ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    @After fun releaseTestWindowObserver() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(keepTestWindowAwake)
        }
    }

    @Test fun murrmurrBrandAndIconsPreservePackageIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.ambientcore.resonance.qa", context.packageName)
        assertEquals("murrmurr (Test)", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals(R.mipmap.ic_murrmurr, context.applicationInfo.icon)
        assertTrue(context.applicationInfo.loadIcon(context.packageManager) is android.graphics.drawable.AdaptiveIconDrawable)
        val icon = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ic_murrmurr_notification)
            ?: error("Notification logo is not decodable")
        try {
            assertEquals(96, icon.width)
            assertEquals(96, icon.height)
            assertEquals(0, android.graphics.Color.alpha(icon.getPixel(0, 0)))
            var visible = 0
            for (y in 0 until icon.height) for (x in 0 until icon.width) {
                val pixel = icon.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) > 0) {
                    visible++
                    assertEquals(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel))
                    assertEquals(android.graphics.Color.red(pixel), android.graphics.Color.blue(pixel))
                    if (android.graphics.Color.alpha(pixel) == 255) assertEquals(255, android.graphics.Color.red(pixel))
                }
            }
            assertTrue("Notification logo must be visible, not an opaque square", visible in 800..7000)
        } finally { icon.recycle() }
        val repository = LibraryRepository(context)
        val original = repository.load()
        val fixture = File(context.filesDir, "murrmurr-brand-qa.wav")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            writeSilentWave(fixture, seconds = 1)
            repository.save(original.copy(songs = listOf(Song(Uri.fromFile(fixture).toString(), "Brand test", fileName = fixture.name))))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.onNodeWithText("murrmurr (Test)").assertIsDisplayed()
            compose.onNodeWithTag("app-logo").assertIsDisplayed()
        } finally {
            try { scenario?.close() } finally {
                context.stopService(android.content.Intent(context, PlaybackService::class.java))
                repository.save(original)
                check(!fixture.exists() || fixture.delete())
            }
        }
    }

    @Test fun transparentLogoReplacesUnloadedArtworkAndPreservesEmbeddedCovers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val logo = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.murrmurr_logo)
            ?: error("Transparent logo cannot be decoded")
        try {
            assertEquals(256, logo.width)
            assertEquals(256, logo.height)
            assertEquals(0, android.graphics.Color.alpha(logo.getPixel(0, 0)))
            var transparent = 0
            var opaque = 0
            for (y in 0 until logo.height) for (x in 0 until logo.width) {
                val alpha = android.graphics.Color.alpha(logo.getPixel(x, y))
                if (alpha == 0) transparent++
                if (alpha == 255) opaque++
            }
            assertTrue("Background and internal dark areas must be transparent", transparent > logo.width * logo.height / 2)
            assertTrue("The murrmurr mark must remain visible", opaque > logo.width * logo.height / 5)
        } finally { logo.recycle() }
        val repository = LibraryRepository(context)
        val original = repository.load()
        val plain = File(context.filesDir, "murrmurr-artwork-plain.wav")
        val embedded = File(context.filesDir, "murrmurr-artwork-embedded.mp3")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            writeSilentWave(plain, seconds = 1)
            writeSilentMp3(embedded)
            val picture = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            val png = java.io.ByteArrayOutputStream()
            try {
                picture.eraseColor(android.graphics.Color.CYAN)
                assertTrue(picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png))
            } finally { picture.recycle() }
            val payload = byteArrayOf(0) + "image/png\u0000".toByteArray() + byteArrayOf(3, 0) + png.toByteArray()
            val frame = "APIC".toByteArray() + ByteBuffer.allocate(4).putInt(payload.size).array() + ByteArray(2) + payload
            val length = byteArrayOf((frame.size shr 21 and 127).toByte(), (frame.size shr 14 and 127).toByte(),
                (frame.size shr 7 and 127).toByte(), (frame.size and 127).toByte())
            embedded.writeBytes("ID3".toByteArray() + byteArrayOf(3, 0, 0) + length + frame + embedded.readBytes())
            val plainSong = Song(Uri.fromFile(plain).toString(), "No image", fileName = plain.name)
            val embeddedSong = Song(Uri.fromFile(embedded).toString(), "Embedded image", fileName = embedded.name)
            repository.save(original.copy(songs = listOf(plainSong, embeddedSong)))
            val selection = mutableStateOf<Song?>(null)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                activity.setContent {
                    Column {
                        Artwork(plainSong, Modifier.size(80.dp).testTag("missing-cover"))
                        Artwork(selection.value, Modifier.size(120.dp).testTag("selected-cover"))
                    }
                }
            }
            fun imageInCover(cover: String, tag: String): SemanticsMatcher =
                hasTestTag(tag) and hasAnyAncestor(hasTestTag(cover))
            compose.onNode(imageInCover("missing-cover", "artwork-placeholder"), useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(imageInCover("selected-cover", "artwork-placeholder"), useUnmergedTree = true).assertIsDisplayed()
            scenario.onActivity { selection.value = embeddedSong }
            compose.waitUntil(10_000) {
                compose.onAllNodes(imageInCover("selected-cover", "artwork-image"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(imageInCover("selected-cover", "artwork-placeholder"), useUnmergedTree = true).assertDoesNotExist()
            val screenshot = compose.onNodeWithTag("selected-cover").captureToImage().toPixelMap()
            assertEquals(androidx.compose.ui.graphics.Color.Cyan, screenshot[screenshot.width / 2, screenshot.height / 2])
            scenario.onActivity { selection.value = plainSong }
            compose.waitUntil(5000) {
                compose.onAllNodes(imageInCover("selected-cover", "artwork-placeholder"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(imageInCover("selected-cover", "artwork-image"), useUnmergedTree = true).assertDoesNotExist()
        } finally {
            try { scenario?.close() } finally {
                context.stopService(android.content.Intent(context, PlaybackService::class.java))
                repository.save(original)
                check(!plain.exists() || plain.delete())
                check(!embedded.exists() || embedded.delete())
            }
        }
    }

    @Test fun offlineLibraryPlaybackQueueAndPersistence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        val files = listOf(File(context.filesDir, "resonance-qa-one.wav"), File(context.filesDir, "resonance-qa-two.wav"))
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            files.forEach { writeSilentWave(it) }
            val songs = files.mapIndexed { index, file ->
                Song(Uri.fromFile(file).toString(), "QA Track ${index + 1}", "Resonance Test", "Device Test",
                    genre = "Test", track = index + 1, duration = 30_000, fileName = file.name, size = file.length(), folder = "Test fixtures")
            }
            repository.save(original.copy(songs = original.songs + songs))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(20_000) { model.library.value.data.songs.any { it.uri == songs[0].uri } && model.playing.value.connected }

            compose.onNodeWithContentDescription("Library", useUnmergedTree = true).performClick()
            val titleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("QA Track 1", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                    assertTrue(it(titleLayouts))
                }
            assertEquals("Track text must inherit the readable theme foreground",
                Paper, titleLayouts.single().layoutInput.style.color)
            assertNotEquals("Restored player typography must not be monospace",
                androidx.compose.ui.text.font.FontFamily.Monospace, titleLayouts.single().layoutInput.style.fontFamily)
            compose.onNodeWithText("QA Track 1").performClick()
            try {
                compose.waitUntil(15_000) { model.playing.value.playing && model.playing.value.position > 300 }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Playback did not start: ${model.playing.value}; message=${model.library.value.message}; audio=${model.audio.value}", error)
            }
            assertEquals(songs[0].uri, model.playing.value.song?.uri)
            compose.onNodeWithContentDescription("Pause").performClick()
            compose.waitUntil(5000) { !model.playing.value.playing }
            compose.onNodeWithContentDescription("Play").performClick()
            compose.waitUntil(5000) { model.playing.value.playing }
            compose.onAllNodesWithText("QA Track 1").onLast().performClick()
            compose.onNodeWithText("NOW PLAYING").assertExists()
            compose.onNodeWithText("Sleep timer").assertDoesNotExist()
            compose.onNodeWithText("Queue (2)").assertIsDisplayed()
            compose.onNodeWithContentDescription("Rate 5 stars").performClick()
            compose.waitUntil(5000) { model.library.value.data.ratings[songs[0].uri] == 5 }
            compose.onNodeWithContentDescription("Audio information").performClick()
            compose.onNodeWithText("Audio output").assertExists()
            compose.onNodeWithText("DSP").assertDoesNotExist()
            compose.onNodeWithText("Hi-res").assertDoesNotExist()
            compose.onNodeWithText("ReplayGain").assertDoesNotExist()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent KEYCODE_BACK").close()
            compose.waitForIdle()

            scenario.onActivity { model.seek(29_300) }
            compose.waitUntil(5000) { model.playing.value.index == 1 && model.playing.value.playing }
            assertEquals(songs[1].uri, model.playing.value.song?.uri)
            scenario.onActivity { model.previous() }
            compose.waitUntil(5000) { model.playing.value.index == 0 }
            scenario.onActivity { model.seek(5000) }
            compose.waitUntil(5000) { model.playing.value.position >= 5000 }
            scenario.onActivity { model.queue(songs[0], next = true) }
            compose.waitUntil(5000) { model.playing.value.queue.size >= 3 }
            scenario.onActivity { model.next() }
            compose.waitUntil(5000) { model.playing.value.index == 1 }
            scenario.onActivity { model.queueMove(1, 0) }
            compose.waitUntil(5000) { model.playing.value.index == 0 }
            scenario.onActivity { model.queueRemove(1); model.favorite(songs[0]); model.createPlaylist("QA playlist", songs[0]) }
            compose.waitUntil(5000) { songs[0].uri in model.library.value.data.favorites && model.library.value.data.playlists.any { it.name == "QA playlist" } }

            assertTrue("An audio format must be reported", model.audio.value.audioInfo.isNotBlank())
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            val backgroundPosition = model.playing.value.position
            compose.waitUntil(5000) { model.playing.value.position > backgroundPosition + 500 }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { model.togglePlay() }
            compose.waitUntil(5000) { !model.playing.value.playing }
            scenario.recreate()
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(5000) { model.playing.value.connected }
            assertTrue(songs[0].uri in repository.load().favorites)
            assertTrue(repository.load().playlists.any { it.name == "QA playlist" })
            assertEquals(songs[0].uri, model.playing.value.song?.uri)
            compose.onNodeWithContentDescription("Close now playing").performClick()
            compose.onNodeWithContentDescription("Playlists", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("Create playlist").performClick()
            compose.onNodeWithText("Normal playlist").assertExists()
            compose.onNodeWithText("Smart playlist").performClick()
            compose.onNodeWithText("Playlist name").performTextInput("QA smart")
            compose.onNodeWithText("Create").performClick()
            compose.waitUntil(5000) { model.library.value.data.playlists.any { it.name == "QA smart" } }
            val smart = model.library.value.data.playlists.single { it.name == "QA smart" }
            assertEquals(listOf(songs[0].uri), playlistSongs(smart, songs, model.library.value.data.ratings).map { it.uri })
            scenario.onActivity { model.rate(songs[0], 2) }
            compose.waitUntil(5000) { model.library.value.data.ratings[songs[0].uri] == 2 }
            assertTrue(playlistSongs(smart, songs, model.library.value.data.ratings).isEmpty())
        } finally {
            scenario?.onActivity {
                val model = ViewModelProvider(it)[MusicViewModel::class.java]
                model.clearQueue()
            }

            scenario?.close()
            context.stopService(android.content.Intent(context, PlaybackService::class.java))
            repository.save(original)
            files.forEach { file -> check(!file.exists() || file.delete()) { "Could not remove test file ${file.name}" } }
        }
    }

    @Test fun androidMusicIndexImport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "resonance-index-test.wav")
        writeSilentWave(fixture)
        var uri: Uri? = null
        try {
            uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "Resonance Index QA.wav")
                put(MediaStore.Audio.Media.TITLE, "Resonance Index QA")
                put(MediaStore.Audio.Media.ARTIST, "Resonance QA")
                put(MediaStore.Audio.Media.ALBUM, "Integration checks")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Resonance QA")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }) ?: error("Could not create temporary MediaStore fixture")
            context.contentResolver.openOutputStream(uri)!!.use { output -> fixture.inputStream().use { it.copyTo(output) } }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val scan = LibraryRepository(context).scan(LibraryData()) {}
            assertTrue(scan.warnings.joinToString(), scan.warnings.isEmpty())
            assertTrue("New MediaStore file must be indexed", scan.songs.any { it.fileName == "Resonance Index QA.wav" })

        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            check(fixture.delete()) { "Could not delete scan fixture" }
        }
    }

    @Test fun incrementalIndexUsesCheckpointsAndKeepsLegacyIdentities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val fixture = File(context.cacheDir, "resonance-incremental.wav")
        writeSilentWave(fixture)
        var uri: Uri? = null
        try {
            val initial = repository.scan(LibraryData()) {}
            val baseline = LibraryData(songs = initial.songs, checkpoints = initial.checkpoints)
            uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "Resonance Incremental QA.wav")
                put(MediaStore.Audio.Media.TITLE, "Resonance Incremental QA")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Resonance QA")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }) ?: error("Cannot create incremental fixture")
            context.contentResolver.openOutputStream(uri)!!.use { output -> fixture.inputStream().use { it.copyTo(output) } }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val delta = repository.scan(baseline) {}
            val inserted = delta.songs.single { it.fileName == "Resonance Incremental QA.wav" }
            assertEquals(0, delta.stats.audioFilesOpened)
            assertEquals(0, delta.stats.directoriesListed)
            assertTrue("Delta must not reread the existing index", delta.stats.indexRows < initial.stats.indexRows.coerceAtLeast(2))
            val cached = baseline.copy(songs = delta.songs, checkpoints = delta.checkpoints)
            val unchanged = repository.scan(cached) {}
            assertEquals(0, unchanged.stats.audioFilesOpened)
            assertEquals(0, unchanged.stats.directoriesListed)
            assertTrue("An unchanged scan should read no audio rows", unchanged.stats.indexRows <= 1)

            val legacyUri = "content://media/external/audio/media/${android.content.ContentUris.parseId(uri)}"
            val legacy = inserted.copy(uri = legacyUri, mediaVolume = "")
            val legacyData = LibraryData(songs = listOf(legacy), favorites = setOf(legacyUri), ratings = mapOf(legacyUri to 5))
            val migrated = repository.scan(legacyData) {}
            assertEquals(legacyUri, migrated.songs.single { it.fileName == inserted.fileName }.uri)
            println("INDEX_BASELINE: ${initial.stats.summary}")
            println("INDEX_DELTA: ${delta.stats.summary}")
            println("INDEX_UNCHANGED: ${unchanged.stats.summary}")
            context.contentResolver.delete(uri, null, null)
            uri = null
            val refreshed = repository.scan(cached, ScanMode.REFRESH_INDEX) {}
            assertFalse(refreshed.songs.any { it.fileName == inserted.fileName })
            assertEquals(0, refreshed.stats.audioFilesOpened)
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            check(fixture.delete())
        }
    }

    @Test fun libraryMigrationRetainsPlaylistsFavoritesAndFolders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        try {
            val song = Song("content://legacy/1", "Legacy", path = "/storage/F85C-5692/Music/legacy.flac")
            val songJson = LibraryRepository.songToJson(song).apply { remove("mediaVolume") }
            val legacyJson = org.json.JSONObject()
                .put("songs", org.json.JSONArray().put(songJson))
                .put("playlists", org.json.JSONArray().put(org.json.JSONObject().put("id", "old").put("name", "Existing").put("songs", org.json.JSONArray().put(song.uri))))
                .put("favorites", org.json.JSONArray().put(song.uri))
                .put("folders", org.json.JSONArray().put("content://com.android.externalstorage.documents/tree/F85C-5692%3AMusic"))
            File(context.filesDir, "library.json").writeText(legacyJson.toString())
            val migrated = repository.load()
            assertEquals(listOf(song), migrated.songs)
            assertEquals(listOf(song.uri), migrated.playlists.single().songs)
            assertEquals(setOf(song.uri), migrated.favorites)
            assertEquals(1, migrated.folders.size)
            assertTrue(migrated.ratings.isEmpty())
            legacyJson.put("ratings", org.json.JSONObject().put(song.uri, 4))
            File(context.filesDir, "library.json").writeText(legacyJson.toString())
            val oldRatings = repository.load()
            assertEquals(setOf(song.uri), oldRatings.ratingOverrides)
            assertEquals(4, oldRatings.ratings[song.uri])
            val upgraded = oldRatings.copy(ratings = mapOf(song.uri to 5),
                playlists = migrated.playlists + MusicPlaylist("smart", "Five stars", emptyList(), RatingRule(RatingComparison.EXACTLY, 5)),
                checkpoints = mapOf("f85c-5692" to IndexCheckpoint("version", 10)))
            repository.save(upgraded)
            assertEquals(upgraded, repository.load())
        } finally { repository.save(original) }
    }

    @Test fun automaticPrecisionAndObsoleteSettingsMigration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        val originalTone = prefs.getString(EQUALIZER_PREF, null)
        val repository = LibraryRepository(context)
        val original = repository.load()
        val fixture = File(context.filesDir, "resonance-hires-qa.wav")
        val standard = File(context.filesDir, "resonance-standard-qa.wav")
        val compressed = File(context.filesDir, "resonance-lossy-qa.mp3")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            writeSilentWave(fixture, rate = 96_000, bits = 24, channels = 2)
            writeSilentWave(standard)
            writeSilentMp3(compressed)
            val song = Song(Uri.fromFile(fixture).toString(), "QA high-resolution audio with a long title across two lines", duration = 30_000, fileName = fixture.name)
            val standardSong = Song(Uri.fromFile(standard).toString(), "QA 16-bit 44.1 kHz", duration = 30_000, fileName = standard.name)
            val mp3Song = Song(Uri.fromFile(compressed).toString(), "QA MP3", duration = 30_000, fileName = compressed.name)
            repository.save(original.copy(songs = listOf(song, standardSong, mp3Song)))
            prefs.edit().putString("output_mode", "DSP").putBoolean("eq_enabled", true)
                .putFloat("preamp", 6f).putLong("sleep_end", 1L).putBoolean("tone_vshape_enabled", true)
                .remove(EQUALIZER_PREF).apply()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { model.playing.value.connected }
            scenario.onActivity { model.play(listOf(song, standardSong, mp3Song)) }
            compose.waitUntil(10_000) { model.playing.value.playing && model.audio.value.outputInfo.contains("96.0") }
            assertTrue("24-bit WAV must avoid 16-bit conversion: ${model.audio.value.outputInfo}",
                model.audio.value.outputInfo.startsWith("32-bit float PCM"))
            assertEquals(24, model.audio.value.sourceBits)
            assertFalse(model.audio.value.equalizer.enabled)
            compose.onNodeWithTag("mini-player").performClick()
            compose.onNodeWithText("Queue (3)").assertIsDisplayed()
            compose.onNodeWithContentDescription("Pause playback").assertIsDisplayed()
            assertFalse(prefs.contains("output_mode"))
            assertFalse(prefs.contains("eq_enabled"))
            assertFalse(prefs.contains("sleep_end"))
            assertFalse(prefs.contains("preamp"))
            compose.onNodeWithTag("now-playing-equalizer").performClick()
            compose.onNodeWithTag("equalizer-presets").performClick()
            compose.onNodeWithTag("eq-preset-VShaped").performClick()
            assertFalse(model.audio.value.equalizer.enabled)
            compose.onNodeWithTag("equalizer-enabled").performClick()
            compose.waitUntil(5000) { model.audio.value.equalizer.enabled }
            compose.onNodeWithTag("equalizer-enabled").performClick()
            compose.waitUntil(5000) { !model.audio.value.equalizer.enabled }
            assertTrue(model.playing.value.playing)
            assertEquals(song.uri, model.playing.value.song?.uri)
            assertEquals(32, model.audio.value.outputBits)
            compose.onNodeWithTag("equalizer-enabled").performClick()
            compose.waitUntil(5000) { model.audio.value.equalizer.enabled }
            assertEquals("VShaped", readEqualizer(prefs).preset)
            assertFalse(prefs.contains("tone_vshape_enabled"))
            compose.onNodeWithContentDescription("Close equalizer").performClick()
            scenario.onActivity { model.seek(5000) }
            compose.waitUntil(5000) { model.playing.value.position >= 5000 }
            scenario.onActivity { model.next() }
            compose.waitUntil(10_000) { model.playing.value.playing && model.audio.value.outputBits == 32 && model.audio.value.sourceBits == 16 }
            assertTrue(model.audio.value.outputFloating)
            assertTrue(model.audio.value.explanation.contains("preserve the source"))
            assertEquals(listOf(song.uri, standardSong.uri, mp3Song.uri), model.playing.value.queue.map { it.uri })
            scenario.onActivity { model.next() }
            compose.waitUntil(10_000) {
                model.playing.value.playing && model.playing.value.position > 300 &&
                    model.audio.value.sourceLossy && model.audio.value.outputBits > 0
            }
            assertTrue(model.audio.value.audioInfo.startsWith("MP3"))
            assertEquals(0, model.audio.value.sourceBits)
            assertTrue(model.audio.value.explanation.startsWith("Lossy source decoded to PCM"))
            println("MP3_OUTPUT: ${model.audio.value.outputInfo}")
            scenario.onActivity { model.queueJump(0) }
            compose.waitUntil(10_000) { model.playing.value.playing && model.audio.value.outputInfo.startsWith("32-bit float PCM") }
            println("AUTOMATIC_OUTPUT: ${model.audio.value.outputInfo}; source=${model.audio.value.sourceBits}-bit")
        } finally {
            scenario?.onActivity { ViewModelProvider(it)[MusicViewModel::class.java].clearQueue() }
            scenario?.close()
            context.stopService(android.content.Intent(context, PlaybackService::class.java))
            if (originalTone == null) prefs.edit().remove(EQUALIZER_PREF).apply()
            else prefs.edit().putString(EQUALIZER_PREF, originalTone).apply()
            repository.save(original)
            check(!fixture.exists() || fixture.delete())
            check(!standard.exists() || standard.delete())
            check(!compressed.exists() || compressed.delete())
        }
    }

    @Test fun graphicEqualizerControlsPersistProfilesAndBypass() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        val originalTone = prefs.getString(EQUALIZER_PREF, null)
        val fixture = File(context.filesDir, "resonance-graphic-eq.wav")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            prefs.edit().remove(EQUALIZER_PREF).apply()
            writeSilentWave(fixture, seconds = 90)
            val song = Song(Uri.fromFile(fixture).toString(), "EQ track", duration = 90_000, fileName = fixture.name)
            repository.save(original.copy(songs = listOf(song)))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { model.playing.value.connected && !model.library.value.loading }
            scenario.onActivity { model.play(listOf(song)) }
            compose.waitUntil(10_000) { model.playing.value.playing && model.playing.value.position > 200 }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithTag("settings-equalizer").performScrollTo().performClick()
            compose.onNodeWithTag("equalizer-panel").assertExists()
            assertFalse(model.audio.value.equalizer.enabled)
            compose.onNodeWithTag("equalizer-presets").performClick()
            compose.onNodeWithTag("eq-preset-VShaped").performClick()
            assertEquals("VShaped", model.audio.value.equalizer.preset)
            assertFalse(model.audio.value.equalizer.enabled)
            compose.onNodeWithTag("equalizer-enabled").performClick()
            compose.waitUntil(5000) { model.audio.value.equalizer.enabled }
            assertEquals(song.uri, model.playing.value.song?.uri)
            assertTrue(model.playing.value.playing)
            assertTrue(model.audio.value.outputFloating)
            fun reveal(tag: String) {
                compose.onNodeWithTag("equalizer-controls").performScrollToNode(hasTestTag(tag))
            }
            reveal("eq-band-2")
            compose.onNodeWithTag("eq-band-2").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(6f) }
            compose.waitUntil(5000) { model.audio.value.equalizer.gains[2] == 6f }
            assertEquals("Custom", model.audio.value.equalizer.preset)
            reveal("eq-preamp")
            compose.onNodeWithTag("eq-preamp").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(-4f) }
            compose.waitUntil(5000) { model.audio.value.equalizer.preamp == -4f }
            compose.onNodeWithTag("eq-peak-protection").performScrollTo().performClick()
            compose.waitUntil(5000) { !model.audio.value.equalizer.peakProtection }
            compose.onNodeWithTag("eq-peak-protection").performClick()
            reveal("eq-save-preset")
            compose.onNodeWithTag("eq-save-preset").performClick()
            compose.onNodeWithTag("eq-preset-name").performTextInput("Studio EQ")
            compose.onNodeWithText("Save preset").performClick()
            compose.waitUntil(5000) { model.audio.value.equalizer.preset == "Studio EQ" }
            val custom = model.audio.value.equalizer
            assertEquals(custom, readEqualizer(prefs))
            assertEquals(EQ_PRESETS.single { it.name == "VShaped" }.gains[2], 2.5f)
            scenario.recreate()
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { model.playing.value.connected }
            assertEquals(custom, model.audio.value.equalizer)
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithTag("settings-equalizer").performScrollTo().performClick()
            compose.onNodeWithTag("equalizer-presets").performClick()
            compose.onNodeWithContentDescription("Delete EQ preset: Studio EQ").performClick()
            compose.onNodeWithText("Delete").performClick()
            compose.waitUntil(5000) { model.audio.value.equalizer.customPresets.isEmpty() }
            assertEquals("Custom", model.audio.value.equalizer.preset)
            compose.onNodeWithTag("equalizer-enabled").performClick()
            compose.waitUntil(5000) { !model.audio.value.equalizer.enabled }
            reveal("eq-reset")
            compose.onNodeWithTag("eq-reset").performClick()
            compose.waitUntil(5000) { model.audio.value.equalizer.preset == "Flat" }
            assertEquals(EqualizerSettings(), readEqualizer(prefs))
            prefs.edit().putString(EQUALIZER_PREF, "{invalid").apply()
            compose.waitUntil(5000) { !model.audio.value.equalizer.enabled }
            compose.onNodeWithTag("eq-reset").performClick()
            assertEquals(EqualizerSettings(), readEqualizer(prefs))
            compose.onNodeWithContentDescription("Close equalizer").performClick()
            assertEquals(song.uri, model.playing.value.song?.uri)
            assertTrue(model.playing.value.playing)
            assertEquals(listOf(song.uri), model.playing.value.queue.map { it.uri })
        } finally {
            try { scenario?.close() } finally {
                context.stopService(android.content.Intent(context, PlaybackService::class.java))
                repository.save(original)
                if (originalTone == null) prefs.edit().remove(EQUALIZER_PREF).apply()
                else prefs.edit().putString(EQUALIZER_PREF, originalTone).apply()
                check(!fixture.exists() || fixture.delete())
            }
        }
    }

    @Test fun equalizerMigrationPreservesControlsAndEnablesPeakProtection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        val original = prefs.getString(EQUALIZER_PREF, null)
        try {
            val saved = EqualizerSettings().select("VShaped").copy(enabled = true, preset = "Custom", preamp = -2.5f)
                .savePreset("My tuning")
            writeEqualizer(prefs, saved)
            val legacy = org.json.JSONObject(prefs.getString(EQUALIZER_PREF, null) ?: error("Missing EQ settings"))
                .put("version", 1).apply { remove("peakProtection") }
            for (oldHeadroom in listOf(false, true)) {
                legacy.put("autoHeadroom", oldHeadroom)
                prefs.edit().putString(EQUALIZER_PREF, legacy.toString()).apply()
                assertEquals(saved, readEqualizer(prefs))
                writeEqualizer(prefs, saved.copy(peakProtection = false))
                assertFalse(readEqualizer(prefs).peakProtection)
                val updated = org.json.JSONObject(prefs.getString(EQUALIZER_PREF, null) ?: error("Missing migrated settings"))
                assertEquals(2, updated.getInt("version"))
                assertFalse(updated.has("autoHeadroom"))
            }
        } finally {
            if (original == null) prefs.edit().remove(EQUALIZER_PREF).apply()
            else prefs.edit().putString(EQUALIZER_PREF, original).apply()
        }
    }

    @Test fun graphicEqualizerProcessesHighResolutionFasterThanRealtime() {
        val rate = 96_000
        val input = ByteBuffer.allocateDirect(rate * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(rate) { frame ->
            val sample = (.5 * kotlin.math.sin(2 * Math.PI * 50 * frame / rate)).toFloat()
            input.putFloat(sample); input.putFloat(sample)
        }
        input.flip()
        val output = ByteBuffer.allocateDirect(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        val parameters = EqualizerSettings(enabled = true, preset = "Custom", gains = List(10) { 12f }).parameters()
        val eq = GraphicEqualizer(rate, 2, parameters)
        val times = (0 until 4).map {
            input.rewind(); output.clear(); eq.reset(parameters)
            val start = android.os.SystemClock.elapsedRealtime()
            eq.process(input, output, androidx.media3.common.C.ENCODING_PCM_FLOAT, parameters)
            assertEquals(rate * 2 * 4, output.position())
            assertEquals(0L, eq.clippedSamples)
            android.os.SystemClock.elapsedRealtime() - start
        }
        val median = times.drop(1).sorted()[1]
        println("GRAPHIC_EQ: one second of 96kHz stereo processed in ${median}ms")
        assertTrue("EQ processing must have ample real-time headroom", median < 500)
    }

    @Test fun compactAlbumGridsAndArtistGenreAlbumSections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        val fixture = File(context.filesDir, "resonance-album-density.wav")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            writeSilentWave(fixture, seconds = 4)
            val songs = (0 until 24).flatMap { album ->
                listOf(2, 1).map { track ->
                    Song(Uri.fromFile(fixture).buildUpon().fragment("$album-$track").build().toString(),
                        "Track %02d %d".format(java.util.Locale.ROOT, album, track), "QA Performer",
                        "Album %02d".format(java.util.Locale.ROOT, album), genre = "QA Genre", track = track,
                        duration = 4000, fileName = fixture.name)
                }
            }
            repository.save(original.copy(songs = songs))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { !model.library.value.loading && model.playing.value.connected }
            scenario.onActivity { model.play(listOf(songs.first())) }
            compose.waitUntil(10_000) { model.playing.value.playing }
            scenario.onActivity { model.togglePlay() }
            compose.waitUntil(5000) { !model.playing.value.playing }
            compose.onNodeWithContentDescription("Library", useUnmergedTree = true).performClick()
            selectLibraryCategory("Albums")
            val density = context.resources.displayMetrics.density
            val controls = compose.onNodeWithTag("library-controls").fetchSemanticsNode().boundsInRoot
            val search = compose.onNodeWithTag("category-search").fetchSemanticsNode().boundsInRoot
            assertEquals(44f * density, controls.height, density)
            assertEquals(40f * density, search.height, density)
            var smallWidth = 0f
            for ((mode, columns, minimumCovers) in listOf(Triple(BrowseMode.ICONS_SMALL, 5, 20), Triple(BrowseMode.ICONS_LARGE, 3, 6))) {
                compose.onNodeWithTag("view-mode-library-ALBUMS").performClick()
                compose.onNodeWithText(mode.label).performClick()
                val viewport = compose.onNodeWithTag("album-grid").fetchSemanticsNode().boundsInRoot
                assertTrue("No redundant toolbar row below the search", viewport.top - controls.bottom <= 18f * density)
                val covers = compose.onAllNodesWithTag("browse-artwork", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
                val complete = covers.filter { it.top >= viewport.top && it.bottom <= viewport.bottom &&
                    it.left >= viewport.left && it.right <= viewport.right && kotlin.math.abs(it.height - it.width) < 1f }
                println("ALBUM_DENSITY: ${mode.label}, viewport=${viewport.height}px, cover=${covers.first().width}px, rows=${covers.map { it.top }.distinct().take(4)}, complete=${complete.size}")
                assertTrue("${mode.label} must fit at least $minimumCovers complete covers; got ${complete.size}", complete.size >= minimumCovers)
                val firstTop = complete.minOf { it.top }
                assertEquals(columns, complete.count { kotlin.math.abs(it.top - firstTop) < 1f })
                if (mode == BrowseMode.ICONS_SMALL) smallWidth = complete.first().width else assertTrue(complete.first().width > smallWidth)
            }
            selectLibraryCategory("Artists")
            compose.onNodeWithTag("library-group-QA Performer").performClick()
            compose.onNodeWithTag("view-mode-collection").performClick()
            compose.onNodeWithText("Compact list").performClick()
            val groups = albumTrackGroups(songs)
            val first = groups[0]
            val header = "album-section-${first.id}"
            compose.onNodeWithTag("song-list").performScrollToNode(hasTestTag(header))
            val headingTop = compose.onNodeWithTag(header).fetchSemanticsNode().boundsInRoot.top
            val firstTop = compose.onNodeWithTag("track-${first.songs[0].uri}").fetchSemanticsNode().boundsInRoot.top
            val secondTop = compose.onNodeWithTag("track-${first.songs[1].uri}").fetchSemanticsNode().boundsInRoot.top
            assertTrue(headingTop < firstTop && firstTop < secondTop)
            compose.onNodeWithTag("track-${first.songs[1].uri}").performClick()
            compose.waitUntil(10_000) { model.playing.value.playing && model.playing.value.song?.uri == first.songs[1].uri }
            assertEquals(groups.flatMap { it.songs }.map { it.uri }, model.playing.value.queue.map { it.uri })
            scenario.onActivity { model.togglePlay() }
            compose.onNodeWithContentDescription("Back to library").performClick()
            selectLibraryCategory("Genres")
            compose.onNodeWithTag("library-group-qa genre").performClick()
            compose.onNodeWithTag("song-list").performScrollToNode(hasTestTag(header))
            compose.onNodeWithTag(header).assertIsDisplayed()
            compose.onNodeWithTag("category-search").performTextReplacement("Album 01")
            compose.onNodeWithTag("category-search").performImeAction()
            compose.onNodeWithTag(header).assertDoesNotExist()
            compose.onNodeWithTag("album-section-${groups[1].id}").assertIsDisplayed()
            compose.onNodeWithTag("track-${groups[1].songs[0].uri}").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back to library").performClick()
            compose.onNodeWithText("Genres").assertIsDisplayed()
        } finally {
            try { scenario?.close() } finally {
                context.stopService(android.content.Intent(context, PlaybackService::class.java))
                repository.save(original)
                check(!fixture.exists() || fixture.delete())
            }
        }
    }

    @Test fun liveCategorySearchSplitGenresAndFolderTree() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        val files = (1..3).map { File(context.filesDir, "resonance-browse-$it.wav") }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            files.forEach { writeSilentWave(it) }
            val songs = listOf(
                Song(Uri.fromFile(files[0]).toString(), "QA Alpha", "QA Aurora", "QA Amber Album",
                    genre = "Rock; Hard Rock", fileName = files[0].name, path = "/storage/F85C-5692/Music/QA Aurora/QA Amber Album/a.wav"),
                Song(Uri.fromFile(files[1]).toString(), "QA Beta", "QA Borealis", "QA Cobalt Album",
                    genre = "Jazz", fileName = files[1].name, path = "/storage/F85C-5692/Music/QA Borealis/QA Cobalt Album/b.wav"),
                Song(Uri.fromFile(files[2]).toString(), "QA Gamma", "QA Aurora", "QA Amber Album",
                    genre = "Rock", fileName = files[2].name, path = "/storage/emulated/0/Music/QA Aurora/QA Amber Album/c.wav"),
            )
            repository.save(original.copy(songs = songs, playlists = listOf(
                MusicPlaylist("road", "Roadtrip", listOf(songs[0].uri)),
                MusicPlaylist("work", "Work", listOf(songs[1].uri)),
            )))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { !model.library.value.loading }
            compose.onNodeWithContentDescription("Library", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("category-search").performTextReplacement("Alpha")
            compose.onNodeWithText("QA Alpha").assertIsDisplayed()
            compose.onNodeWithText("QA Beta").assertDoesNotExist()
            compose.onNodeWithText("QA Gamma").assertDoesNotExist()

            selectLibraryCategory("Albums")
            compose.onNodeWithTag("category-search").performTextReplacement("Amber")
            compose.onNodeWithText("QA Amber Album").assertIsDisplayed()
            compose.onNodeWithText("QA Cobalt Album").assertDoesNotExist()
            selectLibraryCategory("Artists")
            compose.onNodeWithTag("category-search").performTextReplacement("Borealis")
            compose.onNodeWithText("QA Borealis").assertIsDisplayed()
            compose.onNodeWithText("QA Aurora").assertDoesNotExist()
            selectLibraryCategory("Songs")
            compose.onNodeWithTag("category-search").assertTextContains("Alpha")
            compose.onNodeWithText("QA Alpha").assertIsDisplayed()
            selectLibraryCategory("Albums")
            compose.onNodeWithTag("category-search").assertTextContains("Amber")
            compose.onNodeWithText("QA Amber Album").assertIsDisplayed()
            selectLibraryCategory("Artists")
            compose.onNodeWithTag("category-search").assertTextContains("Borealis")

            selectLibraryCategory("Genres")
            compose.onNodeWithText("Rock").assertIsDisplayed()
            compose.onNodeWithText("Hard Rock").assertIsDisplayed()
            compose.onNodeWithText("Rock; Hard Rock").assertDoesNotExist()
            compose.onNodeWithTag("category-search").performTextReplacement("hard")
            compose.onNodeWithText("Jazz").assertDoesNotExist()
            compose.onNodeWithText("Hard Rock").performClick()
            compose.onNodeWithText("QA Alpha").assertIsDisplayed()
            compose.onNodeWithText("QA Gamma").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back to library").performClick()

            selectLibraryCategory("Folders")
            compose.onNodeWithTag("category-search").performImeAction()
            compose.onNodeWithText("Expand all").performClick()
            val leaf = "folder-storage:f85c-5692/Music/QA Borealis/QA Cobalt Album"
            compose.onNodeWithTag("folder-list").performScrollToNode(hasTestTag(leaf))
            compose.onNodeWithTag(leaf).assertIsDisplayed()
            compose.onNodeWithText("Collapse all").performClick()
            compose.onNodeWithTag(leaf).assertDoesNotExist()
            compose.onNodeWithText("SD card (F85C-5692)").assertIsDisplayed()
            compose.onNodeWithTag("category-search").performTextReplacement("Borealis")
            compose.waitUntil(5000) { compose.onAllNodesWithTag(leaf).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("category-search").performImeAction()
            compose.onNodeWithTag(leaf).assertIsDisplayed()
            compose.onNodeWithText("Internal storage").assertDoesNotExist()
            compose.onNodeWithText("Collapse all").performClick()
            compose.onNodeWithTag(leaf).assertDoesNotExist()
            compose.onNodeWithText("Expand all").performClick()
            compose.onNodeWithTag(leaf).performClick()
            compose.onNodeWithTag("category-search").performTextReplacement("Beta")
            compose.onNodeWithText("QA Beta").assertIsDisplayed()
            compose.onNodeWithTag("category-search").performTextReplacement("Alpha")
            compose.onNodeWithText("No matching tracks").assertIsDisplayed()
            compose.onNodeWithTag("category-search").performImeAction()
            compose.onNodeWithContentDescription("Back to library").performClick()

            compose.onNodeWithContentDescription("Playlists", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("category-search").performTextReplacement("Road")
            compose.onNodeWithText("Roadtrip").assertIsDisplayed()
            compose.onNodeWithText("Work").assertDoesNotExist()
            compose.onNodeWithText("Favorites").assertDoesNotExist()
            compose.onNodeWithContentDescription("Create playlist").assertExists()
            compose.onNodeWithTag("category-search").performTextReplacement("no match")
            compose.onNodeWithContentDescription("Create playlist").assertExists()
        } finally {
            scenario?.close()
            context.stopService(android.content.Intent(context, PlaybackService::class.java))
            repository.save(original)
            files.forEach { check(!it.exists() || it.delete()) }
        }
    }

    @Test fun folderBackRestoresExpansionQueryAndExactScrollPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        val fixture = File(context.filesDir, "resonance-folder-back.wav")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            writeSilentWave(fixture)
            val songs = (0 until 40).map { index ->
                val number = "%02d".format(java.util.Locale.ROOT, index)
                Song(Uri.fromFile(fixture).buildUpon().fragment(number).build().toString(),
                    "Track $number", "Artist $number", "Album $number",
                    fileName = fixture.name, path = "/storage/F85C-5692/Music/Artist $number/Album $number/track.wav")
            }
            repository.save(original.copy(songs = songs))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { !model.library.value.loading }
            compose.onNodeWithContentDescription("Library", useUnmergedTree = true).performClick()
            selectLibraryCategory("Folders")
            val branch = "storage:f85c-5692/Music/Artist 00"
            val leaf = "folder-storage:f85c-5692/Music/Artist 25/Album 25"
            for ((query, systemBack) in listOf("" to false, "Artist" to false, "Artist" to true)) {
                compose.onNodeWithTag("category-search").performTextReplacement(query)
                compose.onNodeWithTag("category-search").performImeAction()
                compose.onNodeWithText("Expand all").performClick()
                compose.onNodeWithTag("folder-list").performScrollToNode(hasTestTag("folder-$branch"))
                compose.onNodeWithTag("toggle-$branch").performClick()
                compose.onNodeWithTag("folder-list").performScrollToNode(hasTestTag(leaf))
                compose.onNodeWithTag("folder-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 37f) }
                val previousTop = compose.onNodeWithTag(leaf).fetchSemanticsNode().boundsInRoot.top
                compose.onNodeWithTag(leaf).performClick()
                compose.onNodeWithText("Track 25").assertIsDisplayed()
                if (systemBack) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
                    ).use { it.readBytes() }
                } else compose.onNodeWithContentDescription("Back to library").performClick()
                compose.onNodeWithContentDescription("Search folders").assertExists()
                if (query.isNotEmpty()) compose.onNodeWithTag("category-search").assertTextContains(query)
                compose.onNodeWithTag(leaf).assertIsDisplayed()
                assertEquals("Back must restore the exact folder scroll position", previousTop,
                    compose.onNodeWithTag(leaf).fetchSemanticsNode().boundsInRoot.top, 1f)
                compose.onNodeWithTag("folder-list").performScrollToNode(hasTestTag("folder-$branch"))
                compose.onNodeWithTag("toggle-$branch").assertContentDescriptionEquals("Expand Artist 00")
            }
        } finally {
            scenario?.close()
            context.stopService(android.content.Intent(context, PlaybackService::class.java))
            repository.save(original)
            check(!fixture.exists() || fixture.delete())
        }
    }

    @Test fun toolbarShuffleAndFloatingStatisticsNavigation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        val files = (1..3).map { File(context.filesDir, "resonance-stats-$it.wav") }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            files.forEach { writeSilentWave(it) }
            val songs = files.mapIndexed { index, file ->
                Song(Uri.fromFile(file).toString(), "Stats track ${index + 1}",
                    artist = if (index < 2) "Artist A" else "Artist B",
                    album = if (index < 2) "Album A" else "Album B",
                    genre = when (index) { 0 -> "Rock; Hard Rock"; 1 -> "rock"; else -> "" },
                    duration = 30_000, size = file.length(), fileName = file.name)
            }
            repository.save(original.copy(songs = songs, favorites = setOf(songs[0].uri),
                ratings = mapOf(songs[0].uri to 5, songs[1].uri to 3),
                playlists = listOf(MusicPlaylist("stats-test", "Stats playlist", listOf(songs[0].uri)))))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { !model.library.value.loading && model.playing.value.connected }

            compose.onNodeWithTag("library-summary").assertDoesNotExist()
            compose.onNodeWithText("Recently added albums").assertIsDisplayed()
            compose.onNodeWithText("Shuffle all").assertDoesNotExist()
            val shuffle = compose.onNodeWithTag("toolbar-shuffle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val scan = compose.onNodeWithContentDescription("Scan for new tracks").fetchSemanticsNode().boundsInRoot
            val settings = compose.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
            assertTrue(shuffle.center.x < scan.center.x && scan.center.x < settings.center.x)
            assertEquals(scan.center.y, shuffle.center.y, 1f)

            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val bar = compose.onNodeWithTag("floating-navigation").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Floating bar must have horizontal space around it", bar.left > root.left && bar.right < root.right)
            assertTrue("Floating bar must sit above the window edge", bar.bottom < root.bottom)
            val playlists = compose.onNodeWithContentDescription("Playlists", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val stats = compose.onNodeWithContentDescription("Stats", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Stats must follow Playlists", stats.center.x > playlists.center.x)
            compose.onNodeWithContentDescription("Stats", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("stats-screen").assertIsDisplayed()
            compose.onNodeWithTag("stat-tracks").assertTextContains("3")
            compose.onNodeWithTag("stat-albums").assertTextContains("2")
            compose.onNodeWithTag("stat-album-artists").assertTextContains("2")
            compose.onNodeWithTag("stat-genres").assertTextContains("3")
            compose.onNodeWithTag("stats-screen").performScrollToNode(hasTestTag("stat-average-rating"))
            compose.onNodeWithTag("stat-favorites").assertTextContains("1")
            compose.onNodeWithTag("stat-rated-tracks").assertTextContains("2")
            compose.onNodeWithTag("stat-average-rating").assertTextContains("4.0 / 5")
            compose.onNodeWithTag("stat-total-duration").assertTextContains("1:30")
            compose.onNodeWithTag("stats-screen").performScrollToNode(hasText("File formats"))
            compose.onNodeWithText("File formats").assertIsDisplayed()
            compose.onNodeWithText("WAV").assertExists()

            compose.onNodeWithTag("toolbar-shuffle").performClick()
            compose.waitUntil(10_000) { model.playing.value.playing && model.playing.value.queue.size == 3 && model.playing.value.shuffle }
            compose.onNodeWithContentDescription("Home", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("library-summary").assertDoesNotExist()
            compose.onNodeWithText("Recently added albums").assertIsDisplayed()
        } finally {
            scenario?.onActivity { ViewModelProvider(it)[MusicViewModel::class.java].clearQueue() }
            scenario?.close()
            context.stopService(android.content.Intent(context, PlaybackService::class.java))
            repository.save(original)
            files.forEach { check(!it.exists() || it.delete()) }
        }
    }

    @Test fun recentHomeSectionsCollapseAndRestoreAcrossNavigation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        val files = listOf(File(context.filesDir, "resonance-home-old.wav"), File(context.filesDir, "resonance-home-new.wav"))
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            files.forEach { writeSilentWave(it) }
            val songs = listOf(
                Song(Uri.fromFile(files[0]).toString(), "Older track", "QA Artist", "Legacy album",
                    duration = 30_000, fileName = files[0].name, modified = 10),
                Song(Uri.fromFile(files[1]).toString(), "Latest track", "QA Artist", "Newest album",
                    duration = 30_000, fileName = files[1].name, modified = 100),
            )
            repository.save(original.copy(songs = songs))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { !model.library.value.loading }
            compose.onNodeWithTag("library-summary").assertDoesNotExist()
            compose.onNodeWithText("Recently added albums").assertIsDisplayed()
            val newest = compose.onNodeWithText("Newest album").fetchSemanticsNode().boundsInRoot
            val older = compose.onNodeWithText("Legacy album").fetchSemanticsNode().boundsInRoot
            assertTrue("Recent albums must be newest first", newest.center.x < older.center.x)
            val expanded = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Expanded")
            val collapsed = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Collapsed")
            compose.onNodeWithTag("recent-tracks-toggle").assert(expanded)
            compose.onNodeWithText("Latest track").assertIsDisplayed()
            compose.onNodeWithTag("recent-tracks-toggle").performClick()
            compose.onNodeWithTag("recent-tracks-toggle").assert(collapsed)
            compose.onNodeWithText("Latest track").assertDoesNotExist()
            compose.onNodeWithText("Older track").assertDoesNotExist()

            compose.onNodeWithContentDescription("Stats", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("stats-screen").assertIsDisplayed()
            compose.onNodeWithContentDescription("Home", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("recent-tracks-toggle").assert(collapsed)
            compose.onNodeWithText("Newest album").performClick()
            compose.onNodeWithText("Latest track").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back to library").performClick()
            compose.onNodeWithTag("recent-tracks-toggle").assert(collapsed)
            compose.onNodeWithText("Latest track").assertDoesNotExist()
            scenario.recreate()
            compose.onNodeWithTag("recent-tracks-toggle").assert(collapsed)
            compose.onNodeWithText("Latest track").assertDoesNotExist()
            compose.onNodeWithTag("recent-tracks-toggle").performClick()
            compose.onNodeWithTag("recent-tracks-toggle").assert(expanded)
            compose.onNodeWithText("Latest track").assertIsDisplayed()
        } finally {
            scenario?.close()
            context.stopService(android.content.Intent(context, PlaybackService::class.java))
            repository.save(original)
            files.forEach { check(!it.exists() || it.delete()) }
        }
    }

    @Test fun qualifiedHistoryIgnoresSeekingAndPausesThenCountsRepeatsAndPersists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val historyRepository = HistoryRepository(context)
        val original = repository.load()
        val originalHistory = historyRepository.load()
        val fixture = File(context.filesDir, "resonance-history-qa.wav")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            historyRepository.save(ListeningHistory())
            writeSilentWave(fixture, seconds = 8)
            val song = Song(Uri.fromFile(fixture).toString(), "History QA", "History artist", "History album",
                duration = 8000, fileName = fixture.name)
            repository.save(original.copy(songs = listOf(song)))
            context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().putInt("repeat", androidx.media3.common.Player.REPEAT_MODE_OFF).apply()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { model.playing.value.connected && !model.history.value.loading }
            scenario.onActivity { model.play(listOf(song)) }
            compose.waitUntil(5000) { model.playing.value.playing }
            scenario.onActivity { model.seek(7900) }
            compose.waitUntil(5000) { !model.playing.value.playing && model.playing.value.position >= 7900 }
            assertTrue("Seeking to the end must not add a play", historyRepository.load().counts.isEmpty())

            scenario.onActivity { model.play(listOf(song)) }
            compose.waitUntil(10_000) { model.history.value.data.counts[song.uri] == 1L }
            scenario.onActivity { model.togglePlay() }
            compose.waitUntil(5000) { !model.playing.value.playing }
            Thread.sleep(1100)
            assertEquals(1L, historyRepository.load().counts[song.uri])
            scenario.onActivity { model.repeat(); model.repeat(); model.togglePlay() }
            compose.waitUntil(15_000) { model.history.value.data.counts[song.uri] == 2L }
            scenario.onActivity { model.togglePlay() }
            compose.waitUntil(5000) { !model.playing.value.playing }
            val saved = historyRepository.load()
            assertEquals(2L, saved.counts[song.uri])
            assertEquals(2, saved.recent.size)
            assertTrue((saved.lastPlayed[song.uri] ?: 0L) > 0)
            scenario.recreate()
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(5000) { !model.history.value.loading }
            assertEquals(2L, model.history.value.data.counts[song.uri])
            compose.onNodeWithContentDescription("Stats", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("stats-screen").performScrollToNode(hasTestTag("stat-qualified-plays"))
            compose.onNodeWithTag("stat-qualified-plays").assertTextContains("2")
            compose.onNodeWithTag("stats-screen").performScrollToNode(hasText("Recently played"))
            compose.onNodeWithText("Recently played").assertIsDisplayed()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Clear play history and counts").performScrollTo().performClick()
            compose.onNodeWithText("Clear listening history?").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(2L, historyRepository.load().counts[song.uri])
            compose.onNodeWithText("Clear play history and counts").performClick()
            compose.onNodeWithText("Clear").performClick()
            compose.waitUntil(5000) { model.history.value.data.counts.isEmpty() }
        } finally {
            scenario?.onActivity {
                val model = ViewModelProvider(it)[MusicViewModel::class.java]
                model.clearQueue()
            }
            scenario?.close()
            context.stopService(android.content.Intent(context, PlaybackService::class.java))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            historyRepository.save(originalHistory)
            context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().putInt("repeat", 0).apply()
            repository.save(original)
            check(!fixture.exists() || fixture.delete())
        }
    }

    @Test fun embeddedRatingImportIsReadOnlyIncrementalAndPreservesManualOverrides() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        val rated = File(context.filesDir, "resonance-rating-qa.mp3")
        val untagged = File(context.filesDir, "resonance-unrated-qa.mp3")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            writeSilentMp3(rated)
            rated.writeBytes(popmTag(255) + rated.readBytes())
            writeSilentMp3(untagged)
            val before = rated.readBytes()
            val songs = listOf(rated, untagged).mapIndexed { index, file ->
                Song(Uri.fromFile(file).toString(), "Rating track $index", "Rating artist", "Rating album",
                    duration = 30_000, modified = file.lastModified(), size = file.length(), fileName = file.name)
            }
            val smart = MusicPlaylist("ratings-smart", "Four plus", emptyList(), RatingRule(stars = 4))
            repository.save(original.copy(songs = songs, ratings = emptyMap(), ratingOverrides = emptySet(),
                ratingTagCache = emptyMap(), playlists = listOf(smart)))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { !model.library.value.loading }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Import embedded ratings").performScrollTo().performClick()
            compose.waitUntil(10_000) { !model.ratingImport.value.running && model.library.value.data.ratings[songs[0].uri] == 5 }
            assertEquals(listOf(songs[0]), playlistSongs(smart, songs, model.library.value.data.ratings))
            assertArrayEquals("Import must not modify music or artwork", before, rated.readBytes())
            val stored = repository.load()
            assertEquals(2, stored.ratingTagCache.size)
            assertTrue(stored.ratingOverrides.isEmpty())
            val cached = EmbeddedRatingImport(context).run(stored, false, java.util.concurrent.atomic.AtomicBoolean(false)) {}
            assertEquals(0, cached.read)
            assertEquals(2, cached.cached)
            assertEquals(0, cached.failed)
            val force = EmbeddedRatingImport(context).run(stored, true, java.util.concurrent.atomic.AtomicBoolean(false)) {}
            assertEquals(2, force.read)
            assertEquals(0, force.failed)
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val partial = EmbeddedRatingImport(context).run(stored, true, stop) { stop.set(true) }
            assertTrue(partial.cancelled)
            assertTrue(partial.read <= 1)

            scenario.onActivity { model.rate(songs[0], 2) }
            compose.waitUntil(5000) { model.library.value.data.ratings[songs[0].uri] == 2 }
            scenario.onActivity { model.importRatings(force = true) }
            compose.waitUntil(10_000) { !model.ratingImport.value.running }
            assertEquals(2, model.library.value.data.ratings[songs[0].uri])
            assertTrue(playlistSongs(smart, songs, model.library.value.data.ratings).isEmpty())
            scenario.onActivity { model.rate(songs[0], 0) }
            compose.waitUntil(5000) { !model.library.value.data.ratings.containsKey(songs[0].uri) }
            scenario.onActivity { model.importRatings(force = true) }
            compose.waitUntil(10_000) { !model.ratingImport.value.running }
            assertFalse(model.library.value.data.ratings.containsKey(songs[0].uri))
            assertTrue(songs[0].uri in repository.load().ratingOverrides)
            assertArrayEquals(before, rated.readBytes())
        } finally {
            scenario?.close()
            context.stopService(android.content.Intent(context, PlaybackService::class.java))
            repository.save(original)
            check(!rated.exists() || rated.delete())
            check(!untagged.exists() || untagged.delete())
        }
    }

    @Test fun playlistTransferRequiresConfirmationAndPreservesNamesPathsAndExistingLists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        val first = File(context.filesDir, "resonance-playlist-one.wav")
        val second = File(context.filesDir, "resonance-playlist-two.wav")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            writeSilentWave(first); writeSilentWave(second)
            val songs = listOf(
                Song(Uri.fromFile(first).toString(), "Ambient", fileName = "track.wav",
                    path = "/storage/F85C-5692/Collection/Ambient/Album/track.wav"),
                Song(Uri.fromFile(second).toString(), "Rock", fileName = "track.wav",
                    path = "/storage/F85C-5692/Collection/Rock/Album/track.wav"),
                Song(Uri.fromFile(File(context.filesDir, "resonance-playlist-unavailable.wav")).toString(),
                    "Unavailable", fileName = "lost.wav", path = "/storage/F85C-5692/Collection/Unavailable/lost.wav"),
            )
            val existing = MusicPlaylist("manual", "Existing playlist", listOf(songs[1].uri))
            repository.save(original.copy(songs = songs, playlists = listOf(existing)))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { !model.library.value.loading }
            fun playlist(key: String, name: String, entries: List<String>) = org.json.JSONObject()
                .put("sourceKey", key).put("name", name).put("entries", org.json.JSONArray(entries))
            val path = "D:\\Music\\Collection\\Ambient\\Album\\track.wav"
            val bundle = org.json.JSONObject().put("version", 1).put("playlists", org.json.JSONArray()
                .put(playlist("musicbee:ambient", "Ambient list", listOf(path, path, "missing.wav", songs[2].path)))
                .put(playlist("musicbee:ambiguous", "Ambiguous list", listOf("track.wav")))
                .put(playlist("musicbee:empty", "Empty list", emptyList())))
            fun sendBundle() {
                val encoded = android.util.Base64.encodeToString(bundle.toString().toByteArray(), android.util.Base64.NO_WRAP)
                context.startActivity(android.content.Intent(context, MainActivity::class.java)
                    .setAction(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("resonance_playlist_bundle", encoded))
            }
            sendBundle()
            compose.waitUntil(10_000) { model.playlistBatch.value != null }
            val preview = model.playlistBatch.value ?: error("Expected playlist preview")
            assertEquals(2, preview.matched)
            assertEquals(1, preview.missing)
            assertEquals(1, preview.ambiguous)
            assertEquals(1, preview.unavailable)
            assertEquals(listOf(existing), repository.load().playlists)
            compose.onNodeWithText("Import playlists").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil(10_000) { model.playlistBatch.value == null }
            assertEquals(listOf(existing), repository.load().playlists)
            sendBundle()
            compose.waitUntil(10_000) { model.playlistBatch.value != null }
            compose.onNodeWithText("Import").performClick()
            compose.waitUntil(10_000) { model.playlistBatch.value == null && model.library.value.data.playlists.size == 4 }
            val imported = repository.load().playlists
            assertEquals(existing, imported.single { it.id == existing.id })
            val ambient = imported.single { it.sourceKey == "musicbee:ambient" }
            assertEquals(listOf(songs[0].uri, songs[0].uri), ambient.songs)
            assertTrue(imported.single { it.sourceKey == "musicbee:empty" }.songs.isEmpty())
            sendBundle()
            compose.waitUntil(10_000) { model.playlistBatch.value != null }
            compose.onNodeWithText("Import").performClick()
            compose.waitUntil(10_000) { model.playlistBatch.value == null }
            assertEquals(4, repository.load().playlists.size)
            assertEquals(ambient.id, repository.load().playlists.single { it.sourceKey == "musicbee:ambient" }.id)
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { !model.library.value.loading }
            assertEquals(imported, model.library.value.data.playlists)
        } finally {
            try { scenario?.close() } finally {
                context.stopService(android.content.Intent(context, PlaybackService::class.java))
                repository.save(original)
                check(!first.exists() || first.delete())
                check(!second.exists() || second.delete())
            }
        }
    }

    @Test fun playlistGroupsAndFiveViewModesPersistAndKeepLiveRules() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        val prefs = context.getSharedPreferences("browse", Context.MODE_PRIVATE)
        val originalPrefs = prefs.all
        val fixture = File(context.filesDir, "resonance-view-modes.wav")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            check(prefs.edit().clear().commit())
            writeSilentWave(fixture)
            val songs = listOf("Alpha", "Beta", "Gamma").mapIndexed { index, name ->
                Song(Uri.fromFile(fixture).buildUpon().fragment("$index").build().toString(),
                    "Mode $name", "Mode Artist", "Mode Album", genre = "Mode Genre", duration = 30_000,
                    fileName = "$name.wav", path = "/storage/F85C-5692/Music/Mode Artist/Mode Album/$name.wav")
            }
            val normal = MusicPlaylist("normal", "Normal test", emptyList())
            val snapshot = MusicPlaylist("snapshot", "PC smart snapshot", listOf(songs[0].uri), sourceKey = "musicbee:snapshot")
            val smart = MusicPlaylist("smart", "Rated Four", emptyList(), RatingRule(RatingComparison.EXACTLY, 4))
            repository.save(original.copy(songs = songs, playlists = listOf(normal, snapshot, smart),
                ratings = mapOf(songs[0].uri to 4), favorites = emptySet()))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { !model.library.value.loading && model.playing.value.connected }
            fun choose(scope: String, mode: BrowseMode) {
                compose.onNodeWithTag("view-mode-$scope").performClick()
                compose.onNodeWithText(mode.label).performClick()
                compose.onNodeWithTag("view-mode-$scope").assert(
                    SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, mode.label))
            }
            compose.onNodeWithContentDescription("Library", useUnmergedTree = true).performClick()
            val density = context.resources.displayMetrics.density
            var listHeight = 0f
            var smallWidth = 0f
            for (mode in BrowseMode.entries) {
                choose("songs", mode)
                val bounds = compose.onNodeWithTag("track-${songs[0].uri}").fetchSemanticsNode().boundsInRoot
                if (!mode.grid) {
                    val expectedDp = when (mode) {
                        BrowseMode.COMPACT -> 32f
                        BrowseMode.LIST_ICON -> 44f
                        else -> 40f
                    }
                    val next = compose.onNodeWithTag("track-${songs[1].uri}").fetchSemanticsNode().boundsInRoot
                    assertEquals("${mode.label} row height", expectedDp * density, bounds.height, density)
                    assertEquals("${mode.label} row-to-row spacing", expectedDp * density, next.top - bounds.top, density)
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    compose.onNodeWithText("Mode Alpha", useUnmergedTree = true)
                        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                            assertTrue(it(layouts))
                        }
                    assertEquals("Compact must not shrink title text", 14f, layouts.single().layoutInput.style.fontSize.value, .01f)
                    assertEquals(18f, layouts.single().layoutInput.style.lineHeight.value, .01f)
                    val badgeLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    compose.onAllNodesWithText("0:30", useUnmergedTree = true).onFirst()
                        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                            assertTrue(it(badgeLayouts))
                        }
                    assertEquals(14f, badgeLayouts.single().layoutInput.style.lineHeight.value, .01f)
                }
                if (mode == BrowseMode.LIST) listHeight = bounds.height
                if (mode == BrowseMode.COMPACT) assertTrue(bounds.height < listHeight)
                if (mode == BrowseMode.ICONS_SMALL) smallWidth = bounds.width
                if (mode == BrowseMode.ICONS_LARGE) assertTrue(bounds.width > smallWidth)
                if (mode.artwork) compose.onAllNodesWithTag("browse-artwork", useUnmergedTree = true).onFirst().assertExists()
                else compose.onAllNodesWithTag("browse-artwork", useUnmergedTree = true).assertCountEquals(0)
                compose.onNodeWithContentDescription("Track options: Mode Alpha").assertExists()
                if (mode == BrowseMode.COMPACT) {
                    compose.onNodeWithContentDescription("Track options: Mode Alpha").performClick()
                    compose.onNodeWithText("Track information").performClick()
                    compose.onNodeWithText("Track information").assertExists()
                    compose.onNodeWithText("Done").performClick()
                }
            }
            for (category in listOf("Albums", "Artists", "Genres")) {
                selectLibraryCategory(category)
                choose("library-${category.uppercase(java.util.Locale.ROOT)}", BrowseMode.ICONS_SMALL)
            }
            selectLibraryCategory("Folders")
            choose("folders", BrowseMode.ICONS_LARGE)
            compose.onNodeWithText("Expand all").performClick()
            val leaf = "folder-storage:f85c-5692/Music/Mode Artist/Mode Album"
            compose.onNodeWithTag("folder-list").performScrollToNode(hasTestTag(leaf))
            compose.onNodeWithTag(leaf).performClick()
            choose("collection", BrowseMode.COMPACT)
            compose.onNodeWithText("Mode Alpha").assertExists()
            compose.onNodeWithContentDescription("Back to library").performClick()
            compose.onNodeWithTag("view-mode-folders").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Icons large"))
            choose("folders", BrowseMode.COMPACT)
            val branch = "storage:f85c-5692/Music/Mode Artist"
            compose.onNodeWithTag("folder-list").performScrollToNode(hasTestTag("folder-$branch"))
            compose.onNodeWithTag("toggle-$branch").performClick()
            compose.onNodeWithTag(leaf).assertDoesNotExist()
            compose.onNodeWithTag("toggle-$branch").performClick()
            compose.onNodeWithTag("folder-list").performScrollToNode(hasTestTag(leaf))
            compose.onNodeWithTag(leaf).assertIsDisplayed()

            compose.onNodeWithContentDescription("Playlists", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("playlist-section-normal").performClick()
            compose.onNodeWithTag("playlist-snapshot").assertDoesNotExist()
            compose.onNodeWithTag("playlist-smart").assertExists()
            compose.onNodeWithTag("playlist-section-smart").performClick()
            compose.onNodeWithTag("playlist-smart").assertDoesNotExist()
            compose.onNodeWithTag("playlist-section-smart").performClick()
            scenario.onActivity { model.rate(songs[1], 4) }
            compose.waitUntil(5000) { model.library.value.data.ratings[songs[1].uri] == 4 }
            compose.onNodeWithText("2 tracks / Exactly 4 stars").assertExists()
            compose.onNodeWithTag("playlist-smart").performClick()
            compose.onNodeWithText("Mode Alpha").assertExists()
            compose.onNodeWithText("Mode Beta").assertExists()
            compose.onNodeWithContentDescription("Back to library").performClick()
            choose("playlists", BrowseMode.ICONS_SMALL)
            scenario.recreate()
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { !model.library.value.loading }
            compose.onNodeWithTag("playlist-section-normal").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Collapsed"))
            compose.onNodeWithTag("playlist-snapshot").assertDoesNotExist()
            compose.onNodeWithTag("playlist-smart").assertExists()
            assertEquals("ICONS_SMALL", prefs.getString("mode:playlists", null))
            assertFalse(prefs.getBoolean("expanded:playlists-normal", true))

            compose.onNodeWithContentDescription("Home", useUnmergedTree = true).performClick()
            choose("home-albums", BrowseMode.LIST)
            choose("home-tracks", BrowseMode.ICONS_SMALL)
            compose.onNodeWithContentDescription("Search", useUnmergedTree = true).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Mode")
            compose.onNode(hasSetTextAction()).performImeAction()
            choose("search", BrowseMode.ICONS_LARGE)
            compose.onNodeWithContentDescription("Track options: Mode Alpha").performClick()
            compose.onNodeWithText("Add to playlist").performClick()
            choose("playlist-picker", BrowseMode.ICONS_SMALL)
            compose.onNodeWithText("Normal test").performClick()
            compose.waitUntil(5000) { songs[0].uri in model.library.value.data.playlists.single { it.id == normal.id }.songs }

            compose.onNodeWithContentDescription("Stats", useUnmergedTree = true).performClick()
            choose("stats", BrowseMode.ICONS_SMALL)
            scenario.onActivity { model.play(songs) }
            compose.waitUntil(5000) { model.playing.value.queue.size == 3 && model.playing.value.playing }
            scenario.onActivity { model.togglePlay() }
            compose.waitUntil(5000) { !model.playing.value.playing }
            compose.onNodeWithTag("mini-player").performClick()
            compose.onNodeWithText("Queue (3)").performClick()
            choose("queue", BrowseMode.ICONS_SMALL)
            compose.onNodeWithContentDescription("Queue options: Mode Alpha").performClick()
            compose.onNodeWithText("Move down in queue").performClick()
            compose.waitUntil(5000) { model.playing.value.queue.first().uri == songs[1].uri }
        } finally {
            try { scenario?.close() } finally {
                context.stopService(android.content.Intent(context, PlaybackService::class.java))
                repository.save(original)
                val editor = prefs.edit().clear()
                originalPrefs.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        else -> error("Unexpected browse preference type")
                    }
                }
                check(editor.commit())
                check(!fixture.exists() || fixture.delete())
            }
        }
    }

    @Test fun navidromePasswordVisibilityAndKeyboardDismissKeepDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        val fixture = File(context.filesDir, "resonance-login-input.wav")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            writeSilentWave(fixture, seconds = 1)
            repository.save(original.copy(songs = listOf(Song(Uri.fromFile(fixture).toString(), "Login fixture", fileName = fixture.name))))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { !model.library.value.loading && model.server.value.initialized }
            scenario.onActivity { model.openServerSetup() }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            fun keyboardShown(): Boolean {
                val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys input_method"))
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                val value = Regex("\\bmInputShown=(true|false)").find(dump)?.groupValues?.get(1)
                    ?: error("Cannot measure keyboard visibility")
                return value == "true"
            }
            fun displayedPassword(): String {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithTag("server-password")
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                return layouts.single().layoutInput.text.text
            }
            compose.onNodeWithTag("server-url").performTextReplacement("https://nas.example.ts.net")
            compose.onNodeWithTag("server-username").performTextReplacement("draft-user")
            compose.onNodeWithTag("server-password").performTextReplacement("draft-password")
            compose.waitUntil(5000) { keyboardShown() }
            assertNotEquals("draft-password", displayedPassword())
            compose.onNodeWithContentDescription("Show password").performClick()
            assertEquals("draft-password", displayedPassword())
            compose.onNodeWithContentDescription("Hide password").performClick()
            assertNotEquals("draft-password", displayedPassword())
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap 5 150"))
                .use { it.readBytes() }
            assertTrue(model.serverSetup.value.open)
            compose.onNodeWithTag("server-password").assertTextContains("draft-password")
            compose.onNodeWithContentDescription("Hide keyboard").performClick()
            compose.waitUntil(5000) { !keyboardShown() }
            compose.onNodeWithTag("server-password").assertIsNotFocused()
            compose.onNodeWithTag("server-url").assertTextContains("https://nas.example.ts.net")
            compose.onNodeWithTag("server-username").assertTextContains("draft-user")
            compose.onNodeWithTag("server-password").assertTextContains("draft-password")
            compose.onNodeWithTag("server-username").performClick()
            compose.waitUntil(5000) { keyboardShown() }
            compose.onNodeWithText("Navidrome server").performTouchInput { click() }
            compose.waitUntil(5000) { !keyboardShown() }
            compose.onNodeWithTag("server-url").performClick()
            compose.waitUntil(5000) { keyboardShown() }
            compose.onNodeWithTag("server-url").performImeAction()
            compose.waitUntil(5000) { !keyboardShown() }
            compose.onNodeWithTag("server-password").performClick()
            compose.waitUntil(5000) { keyboardShown() }
            compose.onNodeWithTag("server-password").performImeAction()
            compose.waitUntil(5000) { !keyboardShown() }
            assertTrue(model.serverSetup.value.open)
            compose.onNodeWithContentDescription("Show password").performClick()
            compose.onNodeWithText("Cancel").performClick()
            assertFalse(model.serverSetup.value.open)
            compose.onNodeWithText("Navidrome server").assertDoesNotExist()
            scenario.onActivity { model.openServerSetup() }
            compose.onNodeWithContentDescription("Show password").assertExists()
            assertEquals("", displayedPassword())
        } finally {
            try { scenario?.close() } finally {
                context.stopService(android.content.Intent(context, PlaybackService::class.java))
                repository.save(original)
                check(!fixture.exists() || fixture.delete())
            }
        }
    }

    @Test fun navidromeSetupSwitchStreamingCacheAndLocalIsolation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(context)
        val original = repository.load()
        val accountFile = File(context.filesDir, "server-account.enc")
        val previousAccount = accountFile.takeIf { it.exists() }?.readBytes()
        val playback = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        val previousSource = playback.getString("library_source", null)
        val fixture = File(context.filesDir, "resonance-navidrome.wav")
        var scenario: ActivityScenario<MainActivity>? = null
        var server: NavidromeFixture? = null
        var key = ""
        try {
            check(!accountFile.exists() || accountFile.delete())
            playback.edit().putString("library_source", "LOCAL").apply()
            writeSilentWave(fixture, seconds = 90)
            val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
            val art = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, art)
            bitmap.recycle()
            val remote = NavidromeFixture(fixture.readBytes(), art.toByteArray())
            server = remote
            val localSong = Song(Uri.fromFile(fixture).toString(), "Local fixture", "Local Artist", "Local Album",
                duration = 90_000, fileName = fixture.name)
            val localData = original.copy(songs = listOf(localSong),
                playlists = listOf(MusicPlaylist("local-only", "Local only", listOf(localSong.uri))),
                ratings = mapOf(localSong.uri to 3))
            repository.save(localData)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { !model.library.value.loading && model.playing.value.connected && model.server.value.initialized }
            scenario.onActivity { model.play(listOf(localSong)) }
            compose.waitUntil(10_000) { model.playing.value.playing && model.playing.value.position > 300 }
            compose.onNodeWithTag("source-toggle").performClick()
            compose.onNodeWithText("Navidrome server").assertExists()
            compose.onNodeWithTag("server-url").performTextReplacement(remote.url)
            compose.onNodeWithTag("server-username").performTextReplacement("fixture-user")
            compose.onNodeWithTag("server-password").performTextReplacement("wrong-password")
            compose.onNodeWithTag("server-password").performImeAction()
            compose.onNodeWithTag("server-http").performScrollTo().performClick()
            compose.onNodeWithText("Test connection").performScrollTo().performClick()
            compose.waitUntil(10_000) { !model.serverSetup.value.running }
            assertFalse(model.serverSetup.value.tested)
            assertFalse(accountFile.exists())
            compose.onNodeWithTag("server-password").performScrollTo().performTextReplacement("fixture-password")
            compose.onNodeWithTag("server-password").performImeAction()
            compose.onNodeWithText("Test connection").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.serverSetup.value.tested }
            compose.onNodeWithText("Save and connect").performClick()
            compose.waitUntil(15_000) { model.source.value == LibrarySource.SERVER && model.library.value.data.songs.size == 3 && !model.server.value.refreshing }
            key = model.server.value.key
            assertEquals(localSong.uri, model.playing.value.song?.uri)
            assertTrue(model.playing.value.playing)
            assertEquals(localData, repository.load())
            assertFalse(accountFile.readText(Charsets.ISO_8859_1).contains("fixture-password"))
            assertFalse(accountFile.readText(Charsets.ISO_8859_1).contains("fixture-user"))
            val remoteSongs = model.server.value.data.songs
            compose.onNodeWithContentDescription("Playlists", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Server Mix").assertExists()
            compose.onNodeWithContentDescription("Create playlist").assertDoesNotExist()
            compose.onNodeWithContentDescription("Import playlists").assertDoesNotExist()
            assertTrue(remoteSongs.all { it.isServer && !it.uri.contains("fixture-password") })
            val playlist = model.server.value.data.playlists.single()
            assertEquals(listOf(remoteSongs[1].uri, remoteSongs[0].uri, remoteSongs[1].uri), playlist.songs)
            val serialized = LibraryRepository.songToJson(remoteSongs[0])
            assertEquals(remoteSongs[0], LibraryRepository.songFromJson(serialized))
            compose.onNodeWithContentDescription("Library", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("category-search").performTextReplacement("Track 1")
            compose.onNodeWithTag("category-search").performImeAction()
            compose.onNodeWithText("Server Track 1").assertIsDisplayed()
            val streamSong = remoteSongs[1]
            scenario.onActivity { model.play(listOf(streamSong)) }
            compose.waitUntil(15_000) { model.playing.value.playing && model.playing.value.song?.uri == streamSong.uri && model.playing.value.position > 300 }
            scenario.onActivity { model.seek(10_000) }
            compose.waitUntil(10_000) { model.playing.value.position >= 10_000 && model.playing.value.playing }
            assertTrue(remote.requests.any { it.first == "stream" && it.second["format"] == "raw" })
            scenario.onActivity { model.rate(streamSong, 5); model.favorite(streamSong) }
            compose.waitUntil(10_000) { model.server.value.data.ratings[streamSong.uri] == 5 && streamSong.uri in model.server.value.data.favorites }
            assertEquals(localData, repository.load())
            compose.onNodeWithTag("source-toggle").performClick()
            compose.waitUntil(5000) { model.source.value == LibrarySource.LOCAL }
            assertEquals(streamSong.uri, model.playing.value.song?.uri)
            assertTrue(model.playing.value.playing)
            compose.onNodeWithContentDescription("Library", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("category-search").performTextReplacement("Local")
            compose.onNodeWithTag("category-search").performImeAction()
            remote.failPlaylist = true
            compose.onNodeWithTag("source-toggle").performClick()
            compose.waitUntil(10_000) { !model.server.value.refreshing && model.server.value.error != null }
            compose.onNodeWithTag("track-${streamSong.uri}").assertIsDisplayed()
            assertEquals(remoteSongs, model.server.value.data.songs)
            assertEquals(playlist, model.server.value.data.playlists.single())
            assertTrue(model.playing.value.playing)
            scenario.onActivity { model.togglePlay() }
            compose.waitUntil(5000) { !model.playing.value.playing }
            remote.failPlaylist = false
            remote.unavailable = true
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            compose.waitUntil(10_000) { model.source.value == LibrarySource.SERVER && model.library.value.data.songs.size == 3 && !model.server.value.refreshing }
            assertNotNull(model.server.value.error)
            assertEquals(5, model.server.value.data.ratings[streamSong.uri])
            compose.onNodeWithTag("source-toggle").performClick()
            compose.waitUntil(5000) { model.source.value == LibrarySource.LOCAL }
            assertEquals(localData, model.library.value.data)
            assertEquals(localData, repository.load())
            assertNotNull(ServerAccountStore(context).load())
        } finally {
            try { scenario?.close() } finally {
                context.stopService(android.content.Intent(context, PlaybackService::class.java))
                repository.save(original)
                if (key.isNotEmpty()) ServerCatalogStore(context, key).clear()
                if (previousAccount == null) check(!accountFile.exists() || accountFile.delete()) else accountFile.writeBytes(previousAccount)
                if (previousSource == null) playback.edit().remove("library_source").apply()
                else playback.edit().putString("library_source", previousSource).apply()
                check(!fixture.exists() || fixture.delete())
                server?.close()
            }
        }
    }

    @Test fun navidromePaginatesAndRejectsStalledPages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "resonance-navidrome-paging.wav")
        val accountFile = File(context.filesDir, "server-account.enc")
        val originalAccount = accountFile.takeIf { it.exists() }?.readBytes()
        try {
            writeSilentWave(fixture, seconds = 1)
            NavidromeFixture(fixture.readBytes(), byteArrayOf(), 501).use { remote ->
                val client = NavidromeClient(serverAccount(remote.url, "fixture-user", "fixture-password", true))
                val catalog = client.catalog({}, {})
                assertEquals(501, catalog.songs.size)
                assertEquals(listOf("0", "500"), remote.requests.filter { it.first == "search3" }.map { it.second["songOffset"] })
                remote.invalidPagination = true
                assertThrows(ServerFailure::class.java) { client.catalog({}, {}) }
                val invalid = NavidromeClient(serverAccount(remote.url, "fixture-user", "wrong-password", true))
                val error = assertThrows(ServerFailure::class.java) { invalid.request("ping") }
                assertFalse(error.message.orEmpty().contains("wrong-password"))
                val account = client.account
                ServerAccountStore(context).save(account)
                val source = ServerAudioSource(context)
                val spec = androidx.media3.datasource.DataSpec.Builder()
                    .setUri(Uri.parse(serverTrackUri(account.key, "id-0"))).setPosition(16).setLength(24).build()
                try {
                    assertEquals(24L, source.open(spec))
                    val buffer = ByteArray(24)
                    var offset = 0
                    while (offset < buffer.size) {
                        val read = source.read(buffer, offset, buffer.size - offset)
                        assertTrue(read > 0)
                        offset += read
                    }
                    assertArrayEquals(fixture.readBytes().copyOfRange(16, 40), buffer)
                } finally { source.close() }
                assertThrows(ServerFailure::class.java) {
                    source.open(androidx.media3.datasource.DataSpec.Builder().setUri(Uri.parse(remote.url)).build())
                }
                assertThrows(ServerFailure::class.java) {
                    source.open(androidx.media3.datasource.DataSpec.Builder()
                        .setUri(Uri.parse(serverTrackUri(sha256("different-account"), "id-0"))).build())
                }
            }
        } finally {
            if (originalAccount == null) check(!accountFile.exists() || accountFile.delete()) else accountFile.writeBytes(originalAccount)
            check(!fixture.exists() || fixture.delete())
        }
    }

    private fun selectLibraryCategory(category: String) {
        compose.onNodeWithTag("library-category-menu").performClick()
        compose.onNodeWithTag("library-category-${category.uppercase(java.util.Locale.ROOT)}").performClick()
    }

    private fun popmTag(rating: Int): ByteArray {
        val payload = "Windows Media Player 9 Series\u0000".toByteArray() + byteArrayOf(rating.toByte(), 0, 0, 0, 99)
        val frame = "POPM".toByteArray() + ByteBuffer.allocate(4).putInt(payload.size).array() + ByteArray(2) + payload
        val length = byteArrayOf((frame.size shr 21 and 127).toByte(), (frame.size shr 14 and 127).toByte(),
            (frame.size shr 7 and 127).toByte(), (frame.size and 127).toByte())
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + length + frame
    }

    private fun writeSilentMp3(file: File) {
        // MPEG-1 Layer III frames with zero-length granules encode silence.
        val frame = ByteArray(417)
        frame[0] = 0xff.toByte()
        frame[1] = 0xfb.toByte()
        frame[2] = 0x90.toByte()
        file.outputStream().use { output -> repeat(1200) { output.write(frame) } }
    }

    private fun writeSilentWave(file: File, rate: Int = 44_100, bits: Int = 16, channels: Int = 1, seconds: Int = 30) {
        val frameSize = channels * bits / 8
        val bytes = rate * seconds * frameSize
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * frameSize).putShort(frameSize.toShort()).putShort(bits.toShort())
            .put("data".toByteArray()).putInt(bytes)
        file.outputStream().use { output ->
            output.write(header.array())
            val silence = ByteArray(8192)
            var remaining = bytes
            while (remaining > 0) { val count = minOf(remaining, silence.size); output.write(silence, 0, count); remaining -= count }
        }
    }
}
