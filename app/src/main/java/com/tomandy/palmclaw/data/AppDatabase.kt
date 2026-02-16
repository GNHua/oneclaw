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
    version = 11,
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE messages SET toolName = 'stopped' WHERE role = 'meta' AND content = 'stopped'")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imagePaths TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN audioPaths TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN videoPaths TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN documentPaths TEXT")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS agent_profiles (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        systemPrompt TEXT NOT NULL,
                        model TEXT,
                        allowedTools TEXT,
                        enabledSkills TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("ALTER TABLE conversations ADD COLUMN agentProfileId TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS agent_profiles")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove agentProfileId column by recreating the table
                db.execSQL(
                    """
                    CREATE TABLE conversations_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        messageCount INTEGER NOT NULL DEFAULT 0,
                        lastMessagePreview TEXT NOT NULL DEFAULT ''
                    )
                    """
                )
                db.execSQL(
                    """
                    INSERT INTO conversations_new (id, title, createdAt, updatedAt, messageCount, lastMessagePreview)
                    SELECT id, title, createdAt, updatedAt, messageCount, lastMessagePreview FROM conversations
                    """
                )
                db.execSQL("DROP TABLE conversations")
                db.execSQL("ALTER TABLE conversations_new RENAME TO conversations")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "palmclaw_database"
                )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
