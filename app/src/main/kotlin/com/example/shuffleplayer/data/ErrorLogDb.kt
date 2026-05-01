package com.example.shuffleplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "playback_errors")
data class PlaybackErrorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val uri: String,
    val trackLabel: String?,
    val errorCode: Int,
    val errorCategory: String,
    val errorMessage: String,
)

@Database(entities = [PlaybackErrorEntity::class], version = 1, exportSchema = false)
abstract class ErrorLogDb : RoomDatabase() {
    abstract fun errorLogDao(): ErrorLogDao

    companion object {
        @Volatile private var instance: ErrorLogDb? = null

        fun get(context: Context): ErrorLogDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ErrorLogDb::class.java,
                "error_log.db",
            ).build().also { instance = it }
        }
    }
}
