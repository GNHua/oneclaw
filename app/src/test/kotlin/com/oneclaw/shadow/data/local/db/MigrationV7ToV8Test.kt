package com.oneclaw.shadow.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * RFC-031: Unit test for MIGRATION_7_8.
 *
 * Verifies that the migration adds web_search_enabled to agents and citations to messages.
 */
class MigrationV7ToV8Test {

    @Test
    fun `MIGRATION_7_8 does not throw during migration`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        assertDoesNotThrow { MIGRATION_7_8.migrate(db) }
    }

    @Test
    fun `MIGRATION_7_8 calls execSQL at least twice for two column additions`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        MIGRATION_7_8.migrate(db)

        verify(atLeast = 2) { db.execSQL(any()) }
    }

    @Test
    fun `MIGRATION_7_8 adds web_search_enabled column to agents`() {
        val allSql = mutableListOf<String>()
        val db = mockk<SupportSQLiteDatabase> {
            every { execSQL(any()) } answers {
                allSql.add(firstArg())
                Unit
            }
        }

        MIGRATION_7_8.migrate(db)

        val agentsMigration = allSql.find { it.contains("agents") && it.contains("web_search_enabled") }
        assertEquals(true, agentsMigration != null)
    }

    @Test
    fun `MIGRATION_7_8 adds citations column to messages`() {
        val allSql = mutableListOf<String>()
        val db = mockk<SupportSQLiteDatabase> {
            every { execSQL(any()) } answers {
                allSql.add(firstArg())
                Unit
            }
        }

        MIGRATION_7_8.migrate(db)

        val messagesMigration = allSql.find { it.contains("messages") && it.contains("citations") }
        assertEquals(true, messagesMigration != null)
    }
}
