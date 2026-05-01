package com.example.shuffleplayer.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin SharedPreferences wrapper for the only state we persist:
 *  - last opened source URI (m3u or tree)
 *  - current track index + position
 *  - shuffle on/off + seed for stable ordering
 *  - repeat mode
 *  - last known display title and playing state (for widget rendering)
 */
class Prefs private constructor(private val sp: SharedPreferences) {

    var sourceUri: String?
        get() = sp.getString(KEY_SOURCE_URI, null)
        set(value) = sp.edit().putString(KEY_SOURCE_URI, value).apply()

    var currentIndex: Int
        get() = sp.getInt(KEY_INDEX, 0)
        set(value) = sp.edit().putInt(KEY_INDEX, value).apply()

    var positionMs: Long
        get() = sp.getLong(KEY_POSITION_MS, 0L)
        set(value) = sp.edit().putLong(KEY_POSITION_MS, value).apply()

    var shuffleEnabled: Boolean
        get() = sp.getBoolean(KEY_SHUFFLE, false)
        set(value) = sp.edit().putBoolean(KEY_SHUFFLE, value).apply()

    var repeatMode: Int
        get() = sp.getInt(KEY_REPEAT, REPEAT_OFF)
        set(value) = sp.edit().putInt(KEY_REPEAT, value).apply()

    var shuffleSeed: Long
        get() = sp.getLong(KEY_SHUFFLE_SEED, 0L)
        set(value) = sp.edit().putLong(KEY_SHUFFLE_SEED, value).apply()

    var lastTitle: String?
        get() = sp.getString(KEY_LAST_TITLE, null)
        set(value) = sp.edit().putString(KEY_LAST_TITLE, value).apply()

    var lastArtist: String?
        get() = sp.getString(KEY_LAST_ARTIST, null)
        set(value) = sp.edit().putString(KEY_LAST_ARTIST, value).apply()

    var isPlaying: Boolean
        get() = sp.getBoolean(KEY_IS_PLAYING, false)
        set(value) = sp.edit().putBoolean(KEY_IS_PLAYING, value).apply()

    fun cycleRepeat(): Int {
        val next = when (repeatMode) {
            REPEAT_OFF -> REPEAT_ALL
            REPEAT_ALL -> REPEAT_ONE
            else -> REPEAT_OFF
        }
        repeatMode = next
        return next
    }

    fun toggleShuffle(): Boolean {
        val next = !shuffleEnabled
        shuffleEnabled = next
        return next
    }

    companion object {
        const val REPEAT_OFF = 0
        const val REPEAT_ONE = 1
        const val REPEAT_ALL = 2

        private const val FILE = "shuffle_player_prefs"
        private const val KEY_SOURCE_URI = "source_uri"
        private const val KEY_INDEX = "index"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeat"
        private const val KEY_SHUFFLE_SEED = "shuffle_seed"
        private const val KEY_LAST_TITLE = "last_title"
        private const val KEY_LAST_ARTIST = "last_artist"
        private const val KEY_IS_PLAYING = "is_playing"

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(
                context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
