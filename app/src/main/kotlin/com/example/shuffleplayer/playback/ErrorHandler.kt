package com.example.shuffleplayer.playback

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.example.shuffleplayer.data.ErrorLogDb
import com.example.shuffleplayer.data.PlaybackErrorEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles Player errors per the design:
 *  1. log
 *  2. seek next + prepare + play
 *  3. count consecutive failures; halt at 5.
 *
 * Reset to 0 after any successful track transition.
 */
class ErrorHandler(
    private val context: Context,
    private val player: Player,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var consecutiveFailures = 0

    fun onError(error: PlaybackException) {
        val current = player.currentMediaItem
        logError(
            uri = current?.localConfiguration?.uri?.toString().orEmpty(),
            label = current?.mediaMetadata?.title?.toString(),
            error = error,
        )

        consecutiveFailures += 1
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            player.pause()
            return
        }

        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        } else {
            player.pause()
        }
    }

    fun onPlaybackProgressedSuccessfully() {
        consecutiveFailures = 0
    }

    private fun logError(uri: String, label: String?, error: PlaybackException) {
        val entity = PlaybackErrorEntity(
            timestamp = System.currentTimeMillis(),
            uri = uri,
            trackLabel = label,
            errorCode = error.errorCode,
            errorCategory = categoryOf(error.errorCode),
            errorMessage = error.message ?: error.errorCodeName,
        )
        scope.launch {
            val dao = ErrorLogDb.get(context).errorLogDao()
            dao.insert(entity)
            dao.trimToMostRecent(MAX_LOG_ENTRIES)
        }
    }

    private fun categoryOf(code: Int): String = when (code) {
        in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "IO"
        in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "PARSING"
        in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> "DECODER"
        else -> "OTHER"
    }

    companion object {
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val MAX_LOG_ENTRIES = 100
    }
}
