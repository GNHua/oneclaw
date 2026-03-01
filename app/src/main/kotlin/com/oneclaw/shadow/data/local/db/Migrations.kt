package com.oneclaw.shadow.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // RFC-013: Add memory_index table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_index (
                id TEXT NOT NULL PRIMARY KEY,
                source_type TEXT NOT NULL,
                source_date TEXT,
                chunk_text TEXT NOT NULL,
                embedding BLOB,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // RFC-013: Add last_logged_message_id to sessions
        db.execSQL("ALTER TABLE sessions ADD COLUMN last_logged_message_id TEXT DEFAULT NULL")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // RFC-014: Add load_skill to General Assistant's tool_ids
        db.execSQL(
            """UPDATE agents
               SET tool_ids = '["get_current_time","read_file","write_file","http_request","load_skill"]'
               WHERE id = 'agent-general-assistant'
               AND tool_ids NOT LIKE '%load_skill%'"""
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // RFC-019: Add scheduled_tasks table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scheduled_tasks (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                agent_id TEXT NOT NULL,
                prompt TEXT NOT NULL,
                schedule_type TEXT NOT NULL,
                hour INTEGER NOT NULL,
                minute INTEGER NOT NULL,
                day_of_week INTEGER,
                date_millis INTEGER,
                is_enabled INTEGER NOT NULL DEFAULT 1,
                last_execution_at INTEGER,
                last_execution_status TEXT,
                last_execution_session_id TEXT,
                next_trigger_at INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_tasks_is_enabled ON scheduled_tasks(is_enabled)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_tasks_next_trigger_at ON scheduled_tasks(next_trigger_at)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // RFC-019: Add schedule_task to General Assistant's tool_ids
        db.execSQL(
            """UPDATE agents
               SET tool_ids = '["get_current_time","read_file","write_file","http_request","load_skill","schedule_task"]'
               WHERE id = 'agent-general-assistant'
               AND tool_ids NOT LIKE '%schedule_task%'"""
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Remove per-agent tool selection: all agents now use all registered tools
        db.execSQL("UPDATE agents SET tool_ids = '[]'")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // RFC-031: Add web_search_enabled to agents
        db.execSQL("ALTER TABLE agents ADD COLUMN web_search_enabled INTEGER NOT NULL DEFAULT 0")
        // RFC-031: Add citations to messages
        db.execSQL("ALTER TABLE messages ADD COLUMN citations TEXT")
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add context_window_size to models
        db.execSQL("ALTER TABLE models ADD COLUMN context_window_size INTEGER DEFAULT NULL")

        // Populate preset model context window sizes
        db.execSQL("UPDATE models SET context_window_size = 128000 WHERE id = 'gpt-4o'")
        db.execSQL("UPDATE models SET context_window_size = 128000 WHERE id = 'gpt-4o-mini'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'o1'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'o3-mini'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'claude-opus-4-5-20251101'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'claude-sonnet-4-5-20250929'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'claude-haiku-4-5-20251001'")
        db.execSQL("UPDATE models SET context_window_size = 1048576 WHERE id = 'gemini-2.0-flash'")
        db.execSQL("UPDATE models SET context_window_size = 1048576 WHERE id = 'gemini-2.5-pro'")

        // Add compact fields to sessions
        db.execSQL("ALTER TABLE sessions ADD COLUMN compacted_summary TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE sessions ADD COLUMN compact_boundary_timestamp INTEGER DEFAULT NULL")
    }
}
