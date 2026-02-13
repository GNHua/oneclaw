package com.tomandy.palmclaw.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tomandy.palmclaw.data.dao.ConversationDao
import com.tomandy.palmclaw.data.dao.MessageDao
import com.tomandy.palmclaw.data.entity.ConversationEntity
import com.tomandy.palmclaw.data.entity.MessageEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN messageCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN lastMessagePreview TEXT NOT NULL DEFAULT ''")
                // Backfill from existing data
                db.execSQL("""
                    UPDATE conversations SET messageCount = (
                        SELECT COUNT(*) FROM messages WHERE messages.conversationId = conversations.id
                    )
                """)
                db.execSQL("""
                    UPDATE conversations SET lastMessagePreview = COALESCE((
                        SELECT content FROM messages
                        WHERE messages.conversationId = conversations.id
                          AND (messages.role = 'user' OR messages.role = 'assistant')
                        ORDER BY messages.timestamp DESC
                        LIMIT 1
                    ), '')
                """)
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "palmclaw_database"
                )
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
