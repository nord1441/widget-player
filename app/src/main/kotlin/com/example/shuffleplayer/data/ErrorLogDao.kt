package com.example.shuffleplayer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ErrorLogDao {

    @Insert
    suspend fun insert(entity: PlaybackErrorEntity)

    @Query("SELECT * FROM playback_errors ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<PlaybackErrorEntity>>

    @Query("SELECT * FROM playback_errors ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PlaybackErrorEntity>

    @Query(
        "DELETE FROM playback_errors WHERE id NOT IN " +
            "(SELECT id FROM playback_errors ORDER BY timestamp DESC LIMIT :keep)",
    )
    suspend fun trimToMostRecent(keep: Int)

    @Query("DELETE FROM playback_errors")
    suspend fun clear()
}
