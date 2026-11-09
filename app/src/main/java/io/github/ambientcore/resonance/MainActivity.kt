package io.github.ambientcore.resonance

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.util.Base64
import android.util.Log
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val model: MusicViewModel by viewModels()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.rescan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowTheme(model.theme.value)
        setContent {
            val theme = model.theme.collectAsStateWithLifecycle().value
            SideEffect { applyWindowTheme(theme) }
            MurrmurrApp(model, ::requestMusicAccess)
        }
        consumePlaylistTransfer(intent)
        val audio = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (savedInstanceState == null && checkSelfPermission(audio) != PackageManager.PERMISSION_GRANTED) requestMusicAccess()
    }

    private fun applyWindowTheme(theme: AppTheme) {
        val background = theme.colorScheme.background.toArgb()
        enableEdgeToEdge(SystemBarStyle.dark(background), SystemBarStyle.dark(background))
        window.setBackgroundDrawable(ColorDrawable(background))
    }

    override fun onStart() {
        super.onStart()
        if (!BuildConfig.DEBUG) model.checkForUpdates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePlaylistTransfer(intent)
    }

    private fun consumePlaylistTransfer(intent: Intent) {
        val encoded = intent.getStringExtra("resonance_playlist_bundle") ?: return
        intent.removeExtra("resonance_playlist_bundle")
        try {
            require(encoded.length <= 350_000) { "Playlist handoff is too large; use the file importer" }
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            model.preparePlaylistBundle(text)
        } catch (error: Exception) {
            Log.e("ResonancePlaylists", "Invalid playlist handoff", error)
            model.message("Cannot read playlist transfer: ${error.message}")
        }
    }

    private fun requestMusicAccess() {
        val requested = mutableListOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(requested.toTypedArray())
    }
}
