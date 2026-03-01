package com.oneclaw.shadow.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.oneclaw.shadow.data.local.db.AppDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SyncManagerTest {

    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var database: AppDatabase
    private lateinit var syncManager: SyncManager

    @BeforeEach
    fun setup() {
        sharedPrefs = mockk(relaxed = true)
        context = mockk(relaxed = true)
        database = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs
        every { sharedPrefs.getLong("last_sync_timestamp", 0L) } returns 0L

        syncManager = SyncManager(context, database)
    }

    @Test
    fun `getLastSyncTimestamp returns 0L when no sync has occurred`() {
        every { sharedPrefs.getLong("last_sync_timestamp", 0L) } returns 0L
        val timestamp = syncManager.getLastSyncTimestamp()
        assertEquals(0L, timestamp)
    }

    @Test
    fun `getLastSyncTimestamp returns stored value after sync`() {
        val expectedTimestamp = 1700000000000L
        every { sharedPrefs.getLong("last_sync_timestamp", 0L) } returns expectedTimestamp
        val timestamp = syncManager.getLastSyncTimestamp()
        assertEquals(expectedTimestamp, timestamp)
    }

    @Test
    fun `upload returns NotSignedIn when getSignedInAccount returns null`() = runTest {
        // Use spyk to override getSignedInAccount only
        val spy = spyk(syncManager)
        every { spy.getSignedInAccount() } returns null

        val result = spy.upload()
        assert(result is SyncResult.NotSignedIn)
    }

    @Test
    fun `upload returns NotSignedIn via spyk when account is null`() = runTest {
        // Verify the full upload flow returns NotSignedIn when no account
        val spy = spyk(syncManager)
        every { spy.getSignedInAccount() } returns null

        val result = spy.upload()
        assertEquals(SyncResult.NotSignedIn, result)
    }
}
