package com.oneclaw.shadow.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
