package com.example.shuffleplayer.playback

import kotlin.random.Random

/**
 * Stable shuffle: given a seed, produces the same permutation across app restarts.
 * Used so that toggling shuffle mid-session and reopening the app preserves the
 * order without us needing to persist a full index array.
 */
object Shuffler {

    fun shuffleStable(tracks: List<ResolvedTrack>, seed: Long): List<ResolvedTrack> {
        if (tracks.size <= 1) return tracks
        val rng = Random(seed)
        // Fisher–Yates on a copy.
        val out = tracks.toMutableList()
        for (i in out.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            if (i != j) {
                val tmp = out[i]
                out[i] = out[j]
                out[j] = tmp
            }
        }
        return out
    }

    fun newSeed(): Long = Random.nextLong().let { if (it == 0L) 1L else it }
}
