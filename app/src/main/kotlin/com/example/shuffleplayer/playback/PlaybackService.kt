package com.example.shuffleplayer.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.shuffleplayer.R
import com.example.shuffleplayer.data.Prefs
import com.example.shuffleplayer.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var errorHandler: ErrorHandler
    private val coroutineErrorHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Background work failed", t)
    }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + coroutineErrorHandler,
    )

    /** Source URI currently loaded into [player], so we can avoid reloading on every command. */
    private var loadedSource: String? = null
    private var loadJob: Job? = null
    private var placeholderForegroundActive = false

    /**
     * Receives "live tweak" actions (shuffle/repeat changes) without using
     * startForegroundService — those don't lead to playback so we must not
     * promote the service to foreground.
     */
    private val tweakReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            handleAction(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Audio-only: video tracks completely disabled per design.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        errorHandler = ErrorHandler(applicationContext, player)

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorHandler.onError(error)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                errorHandler.onPlaybackProgressedSuccessfully()
                val prefs = Prefs.get(applicationContext)
                prefs.currentIndex = player.currentMediaItemIndex
                prefs.lastTitle = mediaItem?.mediaMetadata?.title?.toString()
                prefs.lastArtist = mediaItem?.mediaMetadata?.artist?.toString()
                WidgetUpdater.refreshAll(applicationContext)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Prefs.get(applicationContext).isPlaying = isPlaying
                WidgetUpdater.refreshAll(applicationContext)
            }
        })

        session = MediaSession.Builder(this, player).build()
        applyPersistedPlaybackOptions()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .build()
                .also { it.setSmallIcon(R.drawable.ic_notification) },
        )

        val filter = IntentFilter().apply {
            addAction(PlaybackCommands.ACTION_SET_SHUFFLE)
            addAction(PlaybackCommands.ACTION_SET_REPEAT)
            addAction(PlaybackCommands.ACTION_CLEAR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tweakReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(tweakReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // We were likely started via startForegroundService(). Android 8+ requires us
        // to call startForeground() within ~5 seconds or it kills us with
        // ForegroundServiceDidNotStartInTimeException. The IO load below can easily
        // exceed that on large trees, so we post a placeholder notification now and
        // let MediaSessionService replace it once the player actually starts.
        if (isPlaybackInitiating(intent?.action)) ensurePlaceholderForeground()
        if (intent != null) handleAction(intent)
        return super.onStartCommand(intent, flags, startId)
    }

    private fun isPlaybackInitiating(action: String?): Boolean = when (action) {
        PlaybackCommands.ACTION_PLAY_PAUSE,
        PlaybackCommands.ACTION_NEXT,
        PlaybackCommands.ACTION_PREV,
        PlaybackCommands.ACTION_PLAY_FROM_PREFS,
        PlaybackCommands.ACTION_LOAD_URI -> true
        else -> false
    }

    private fun ensurePlaceholderForeground() {
        if (placeholderForegroundActive) return
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(this, PLACEHOLDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_loading))
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MEDIA_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(MEDIA_NOTIFICATION_ID, notification)
        }
        placeholderForegroundActive = true
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(PLACEHOLDER_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                PLACEHOLDER_CHANNEL_ID,
                getString(R.string.notification_channel_playback),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    private fun handleAction(intent: Intent) {
        when (intent.action) {
            PlaybackCommands.ACTION_PLAY_PAUSE -> handlePlayPause()
            PlaybackCommands.ACTION_NEXT -> handleNext()
            PlaybackCommands.ACTION_PREV -> handlePrev()
            PlaybackCommands.ACTION_PLAY_FROM_PREFS -> handlePlayFromPrefs()
            PlaybackCommands.ACTION_LOAD_URI -> {
                val uriStr = intent.getStringExtra(PlaybackCommands.EXTRA_URI)
                if (uriStr != null) loadAndPlay(Uri.parse(uriStr))
            }
            PlaybackCommands.ACTION_SET_SHUFFLE -> {
                val on = intent.getBooleanExtra(PlaybackCommands.EXTRA_BOOL, false)
                val prefs = Prefs.get(this)
                prefs.shuffleEnabled = on
                if (on && prefs.shuffleSeed == 0L) prefs.shuffleSeed = Shuffler.newSeed()
                if (player.mediaItemCount > 0) rebuildQueueWithCurrentSettings()
            }
            PlaybackCommands.ACTION_SET_REPEAT -> {
                val mode = intent.getIntExtra(PlaybackCommands.EXTRA_INT, Prefs.REPEAT_OFF)
                Prefs.get(this).repeatMode = mode
                player.repeatMode = repeatModeForPlayer(mode)
            }
            PlaybackCommands.ACTION_CLEAR -> handleClear()
        }
    }

    private fun handleClear() {
        loadJob?.cancel()
        player.stop()
        player.clearMediaItems()
        loadedSource = null
        releasePlaceholderForeground()
        // MediaSessionService may still hold foreground for a moment after stop();
        // explicitly drop it so the notification disappears immediately.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(tweakReceiver) }
        loadJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        session?.run {
            player.release()
            release()
            session = null
        }
        super.onDestroy()
    }

    private fun handlePlayPause() {
        if (player.mediaItemCount == 0) {
            handlePlayFromPrefs()
            return
        }
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun handleNext() {
        if (player.mediaItemCount == 0) {
            handlePlayFromPrefs()
            return
        }
        player.seekToNext()
        if (!player.isPlaying) player.play()
    }

    private fun handlePrev() {
        if (player.mediaItemCount == 0) {
            handlePlayFromPrefs()
            return
        }
        player.seekToPrevious()
        if (!player.isPlaying) player.play()
    }

    private fun handlePlayFromPrefs() {
        val src = Prefs.get(this).sourceUri ?: return
        if (loadedSource == src && player.mediaItemCount > 0) {
            player.play()
            return
        }
        loadAndPlay(Uri.parse(src))
    }

    private fun loadAndPlay(uri: Uri) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val tracks = runCatching {
                withContext(Dispatchers.IO) { SourceLoader.load(applicationContext, uri) }
            }.onFailure { Log.e(TAG, "Failed to load source $uri", it) }.getOrDefault(emptyList())

            val ordered = applyShuffleIfEnabled(tracks)
            if (ordered.isEmpty()) {
                // Nothing to play — drop the placeholder so the user isn't left with
                // a stuck "Loading…" notification.
                releasePlaceholderForeground()
                return@launch
            }
            val prefs = Prefs.get(applicationContext)
            val startIndex = prefs.currentIndex.coerceIn(0, ordered.size - 1)
            val items = ordered.map { it.toMediaItem() }
            player.setMediaItems(items, startIndex, prefs.positionMs)
            player.prepare()
            player.play()
            loadedSource = uri.toString()
        }
    }

    private fun releasePlaceholderForeground() {
        if (!placeholderForegroundActive) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        placeholderForegroundActive = false
    }

    private fun rebuildQueueWithCurrentSettings() {
        val src = loadedSource ?: Prefs.get(this).sourceUri ?: return
        val wasPlaying = player.isPlaying
        loadJob?.cancel()
        loadJob = scope.launch {
            val tracks = withContext(Dispatchers.IO) {
                SourceLoader.load(applicationContext, Uri.parse(src))
            }
            val ordered = applyShuffleIfEnabled(tracks)
            if (ordered.isEmpty()) return@launch
            val items = ordered.map { it.toMediaItem() }
            // Try to keep playing the same item across the reorder.
            val currentMediaId = player.currentMediaItem?.mediaId
            val newIndex = items.indexOfFirst { it.mediaId == currentMediaId }
                .takeIf { it >= 0 } ?: 0
            val pos = player.currentPosition
            player.setMediaItems(items, newIndex, pos)
            player.prepare()
            if (wasPlaying) player.play()
        }
    }

    private fun applyShuffleIfEnabled(tracks: List<ResolvedTrack>): List<ResolvedTrack> {
        val prefs = Prefs.get(this)
        if (!prefs.shuffleEnabled) return tracks
        var seed = prefs.shuffleSeed
        if (seed == 0L) {
            seed = Shuffler.newSeed()
            prefs.shuffleSeed = seed
        }
        return Shuffler.shuffleStable(tracks, seed)
    }

    private fun applyPersistedPlaybackOptions() {
        val prefs = Prefs.get(applicationContext)
        // Use our custom shuffler — keep ExoPlayer's own shuffle off.
        player.shuffleModeEnabled = false
        player.repeatMode = repeatModeForPlayer(prefs.repeatMode)
    }

    private fun repeatModeForPlayer(mode: Int): Int = when (mode) {
        Prefs.REPEAT_ONE -> Player.REPEAT_MODE_ONE
        Prefs.REPEAT_ALL -> Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_OFF
    }

    private fun ResolvedTrack.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(displayTitle)
            .setArtist(artist)
            .build()
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        private const val TAG = "PlaybackService"

        // Matches DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID so that
        // MediaSessionService's eventual startForeground() replaces our placeholder
        // rather than stacking a second foreground notification.
        private const val MEDIA_NOTIFICATION_ID = 1001
        private const val PLACEHOLDER_CHANNEL_ID = "playback_placeholder"
    }
}
