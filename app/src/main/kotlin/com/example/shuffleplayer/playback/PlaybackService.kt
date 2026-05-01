package com.example.shuffleplayer.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Source URI currently loaded into [player], so we can avoid reloading on every command. */
    private var loadedSource: String? = null
    private var loadJob: Job? = null

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
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tweakReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(tweakReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) handleAction(intent)
        return super.onStartCommand(intent, flags, startId)
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
        }
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
            val tracks = withContext(Dispatchers.IO) { SourceLoader.load(applicationContext, uri) }
            val ordered = applyShuffleIfEnabled(tracks)
            if (ordered.isEmpty()) return@launch
            val prefs = Prefs.get(applicationContext)
            val startIndex = prefs.currentIndex.coerceIn(0, ordered.size - 1)
            val items = ordered.map { it.toMediaItem() }
            player.setMediaItems(items, startIndex, prefs.positionMs)
            player.prepare()
            player.play()
            loadedSource = uri.toString()
        }
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
}
