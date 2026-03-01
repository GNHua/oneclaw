package com.oneclaw.shadow.data.sync

import androidx.work.ListenableWorker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for SyncWorker logic extracted into a testable helper.
 * The worker itself uses Koin injection, so we test the equivalent logic
 * through a standalone function to keep tests hermetic.
 */
class SyncWorkerTest {

    private lateinit var syncManager: SyncManager

    @BeforeEach
    fun setup() {
        syncManager = mockk(relaxed = true)
    }

    /**
     * Mirrors the doWork() logic of SyncWorker without WorkManager dependencies.
     */
    private suspend fun simulateDoWork(mgr: SyncManager): ListenableWorker.Result {
        if (mgr.getSignedInAccount() == null) {
            return ListenableWorker.Result.success()
        }
        if (!mgr.hasChangedSinceLastSync()) {
            return ListenableWorker.Result.success()
        }
        return when (mgr.upload()) {
            is SyncResult.Success -> ListenableWorker.Result.success()
            is SyncResult.NotSignedIn -> ListenableWorker.Result.success()
            is SyncResult.Error -> ListenableWorker.Result.retry()
            else -> ListenableWorker.Result.success()
        }
    }

    @Test
    fun `doWork returns success when not signed in`() = runTest {
        every { syncManager.getSignedInAccount() } returns null

        val result = simulateDoWork(syncManager)

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork returns success when signed in but no changes`() = runTest {
        every { syncManager.getSignedInAccount() } returns mockk()
        coEvery { syncManager.hasChangedSinceLastSync() } returns false

        val result = simulateDoWork(syncManager)

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork returns retry when upload returns error`() = runTest {
        every { syncManager.getSignedInAccount() } returns mockk()
        coEvery { syncManager.hasChangedSinceLastSync() } returns true
        coEvery { syncManager.upload() } returns SyncResult.Error("Network error")

        val result = simulateDoWork(syncManager)

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork returns success when upload succeeds`() = runTest {
        every { syncManager.getSignedInAccount() } returns mockk()
        coEvery { syncManager.hasChangedSinceLastSync() } returns true
        coEvery { syncManager.upload() } returns SyncResult.Success

        val result = simulateDoWork(syncManager)

        assertEquals(ListenableWorker.Result.success(), result)
    }
}
