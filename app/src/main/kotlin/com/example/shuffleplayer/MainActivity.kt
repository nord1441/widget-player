package com.example.shuffleplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.example.shuffleplayer.data.Prefs
import com.example.shuffleplayer.settings.SettingsScreen

class MainActivity : ComponentActivity() {

    private val openM3u = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) persistAsSource(uri, takeRead = true)
    }

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) persistAsSource(uri, takeRead = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleViewIntent(intent)

        setContent {
            SettingsScreen(
                onPickM3u = { openM3u.launch(M3U_MIME_TYPES) },
                onPickFolder = { openTree.launch(null) },
                onClearSource = { Prefs.get(this).sourceUri = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        persistAsSource(uri, takeRead = false)
    }

    private fun persistAsSource(uri: Uri, takeRead: Boolean) {
        if (takeRead && uri.scheme == "content") {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        Prefs.get(this).sourceUri = uri.toString()
    }

    private companion object {
        val M3U_MIME_TYPES = arrayOf(
            "audio/x-mpegurl",
            "audio/mpegurl",
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "*/*",
        )
    }
}
