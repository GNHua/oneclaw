package com.tomandy.oneclaw.scheduler.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database for cronjob scheduling
 */
@Database(
    entities = [CronjobEntity::class, ExecutionLog::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class CronjobDatabase : RoomDatabase() {

    abstract fun cronjobDao(): CronjobDao
    abstract fun executionLogDao(): ExecutionLogDao

    companion object {
        @Volatile
        private var INSTANCE: CronjobDatabase? = null

        fun getDatabase(context: Context): CronjobDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CronjobDatabase::class.java,
                    "cronjob_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
