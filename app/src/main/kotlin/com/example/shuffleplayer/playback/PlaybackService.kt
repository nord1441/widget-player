package com.example.shuffleplayer.playback

import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.shuffleplayer.data.Prefs
import com.example.shuffleplayer.widget.WidgetUpdater

class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var errorHandler: ErrorHandler

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Disable video tracks so mp4/mkv/webm/HLS-video play audio-only.
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
                Prefs.get(applicationContext).currentIndex = player.currentMediaItemIndex
                WidgetUpdater.refreshAll(applicationContext)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                WidgetUpdater.refreshAll(applicationContext)
            }
        })

        session = MediaSession.Builder(this, player).build()

        applyPersistedPlaybackOptions()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Match the design: keep playing when the task is swiped away. Service
        // is foreground while playing; if paused the system can reclaim it.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
            session = null
        }
        super.onDestroy()
    }

    private fun applyPersistedPlaybackOptions() {
        val prefs = Prefs.get(applicationContext)
        player.shuffleModeEnabled = prefs.shuffleEnabled
        player.repeatMode = when (prefs.repeatMode) {
            Prefs.REPEAT_ONE -> Player.REPEAT_MODE_ONE
            Prefs.REPEAT_ALL -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * Replace the current queue with [tracks] and start playback at [startIndex].
     * Called from MainActivity / widget command handlers via MediaController or
     * a direct binder hook (TBD when wiring the widget commands in step 3).
     */
    fun setQueue(tracks: List<ResolvedTrack>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        val items = tracks.map { it.toMediaItem() }
        player.setMediaItems(items, startIndex, startPositionMs)
        player.prepare()
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
