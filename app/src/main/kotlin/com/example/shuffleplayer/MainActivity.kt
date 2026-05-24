package com.example.shuffleplayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.shuffleplayer.data.Prefs
import com.example.shuffleplayer.playback.PlaybackCommands
import com.example.shuffleplayer.settings.ErrorLogScreen
import com.example.shuffleplayer.settings.SettingsScreen
import com.example.shuffleplayer.widget.PlayerWidgetProvider
import com.example.shuffleplayer.widget.WidgetUpdater

class MainActivity : ComponentActivity() {

    private var sourceState by mutableStateOf<String?>(null)

    private val openM3u = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) persistAsSource(uri, takeRead = true)
    }

    private val openTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) persistAsSource(uri, takeRead = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceState = Prefs.get(this).sourceUri
        handleViewIntent(intent)

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(Screen.SETTINGS) }
                when (screen) {
                    Screen.SETTINGS -> SettingsScreen(
                        sourceUri = sourceState,
                        onPickM3u = { openM3u.launch(M3U_MIME_TYPES) },
                        onPickFolder = { openTree.launch(null) },
                        onClearSource = ::clearSource,
                        onPinWidget = ::requestPinWidget,
                        onOpenErrorLog = { screen = Screen.ERRORS },
                    )
                    Screen.ERRORS -> ErrorLogScreen(onBack = { screen = Screen.SETTINGS })
                }
            }
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

    private fun clearSource() {
        val prefs = Prefs.get(this)
        prefs.sourceUri = null
        // Also wipe the queue/widget remnants of the previous source so the widget
        // doesn't keep showing a stale title and the next play doesn't resume from
        // the old index/position.
        prefs.currentIndex = 0
        prefs.positionMs = 0L
        prefs.lastTitle = null
        prefs.lastArtist = null
        prefs.isPlaying = false
        // Tell the service (if running) to stop and drop its queue. Broadcast is
        // intentional: we don't want to start the service just to tell it to stop.
        sendBroadcast(
            Intent(PlaybackCommands.ACTION_CLEAR).setPackage(packageName),
        )
        WidgetUpdater.refreshAll(this)
        sourceState = null
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
        sourceState = uri.toString()
    }

    private fun requestPinWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        if (!mgr.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, "Launcher does not support widget pinning", Toast.LENGTH_SHORT)
                .show()
            return
        }
        val provider = ComponentName(this, PlayerWidgetProvider::class.java)
        val callback = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, PlayerWidgetProvider::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mgr.requestPinAppWidget(provider, null, callback)
    }

    private enum class Screen { SETTINGS, ERRORS }

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
