package com.oneclaw.shadow.feature.settings

import com.oneclaw.shadow.data.sync.BackupManager
import com.oneclaw.shadow.data.sync.SyncManager
import com.oneclaw.shadow.data.sync.SyncResult
import com.oneclaw.shadow.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.File

@ExtendWith(MainDispatcherRule::class)
class SyncSettingsViewModelTest {

    private lateinit var syncManager: SyncManager
    private lateinit var backupManager: BackupManager
    private lateinit var viewModel: SyncSettingsViewModel

    @BeforeEach
    fun setup() {
        syncManager = mockk(relaxed = true)
        backupManager = mockk(relaxed = true)
        every { syncManager.getSignedInAccount() } returns null
        every { syncManager.getLastSyncTimestamp() } returns 0L
    }

    private fun createViewModel(): SyncSettingsViewModel {
        return SyncSettingsViewModel(syncManager, backupManager)
    }

    @Test
    fun `initial state has isSignedIn false when no account exists`() {
        every { syncManager.getSignedInAccount() } returns null
        viewModel = createViewModel()
        assertFalse(viewModel.uiState.value.isSignedIn)
    }

    @Test
    fun `initial state has accountEmail null when no account exists`() {
        every { syncManager.getSignedInAccount() } returns null
        viewModel = createViewModel()
        assertNull(viewModel.uiState.value.accountEmail)
    }

    @Test
    fun `initial state has lastSyncTimestamp 0 when no sync occurred`() {
        every { syncManager.getLastSyncTimestamp() } returns 0L
        viewModel = createViewModel()
        assertEquals(0L, viewModel.uiState.value.lastSyncTimestamp)
    }

    @Test
    fun `syncNow sets isSyncing true then false on success`() = runTest {
        coEvery { syncManager.upload() } returns SyncResult.Success
        every { syncManager.getLastSyncTimestamp() } returns 1700000000000L
        viewModel = createViewModel()

        viewModel.syncNow()

        assertFalse(viewModel.uiState.value.isSyncing)
    }

    @Test
    fun `syncNow sets syncError on failure`() = runTest {
        val errorMessage = "Upload failed"
        coEvery { syncManager.upload() } returns SyncResult.Error(errorMessage)
        viewModel = createViewModel()

        viewModel.syncNow()

        assertEquals(errorMessage, viewModel.uiState.value.syncError)
    }

    @Test
    fun `syncNow clears syncError before attempting sync`() = runTest {
        coEvery { syncManager.upload() } returns SyncResult.Success
        viewModel = createViewModel()

        viewModel.syncNow()

        assertNull(viewModel.uiState.value.syncError)
    }

    @Test
    fun `signOut resets isSignedIn to false`() = runTest {
        viewModel = createViewModel()
        viewModel.signOut()
        assertFalse(viewModel.uiState.value.isSignedIn)
    }

    @Test
    fun `signOut resets accountEmail to null`() = runTest {
        viewModel = createViewModel()
        viewModel.signOut()
        assertNull(viewModel.uiState.value.accountEmail)
    }

    @Test
    fun `signOut resets lastSyncTimestamp to 0`() = runTest {
        viewModel = createViewModel()
        viewModel.signOut()
        assertEquals(0L, viewModel.uiState.value.lastSyncTimestamp)
    }

    @Test
    fun `dismissRestorePrompt sets showRestorePrompt to false`() = runTest {
        coEvery { syncManager.upload() } returns SyncResult.Success
        viewModel = createViewModel()

        viewModel.dismissRestorePrompt()

        assertFalse(viewModel.uiState.value.showRestorePrompt)
    }

    @Test
    fun `exportBackup delegates to BackupManager export`() = runTest {
        val fakeFile = mockk<File>()
        coEvery { backupManager.export() } returns fakeFile
        viewModel = createViewModel()

        val result = viewModel.exportBackup()

        assertEquals(fakeFile, result)
    }

    @Test
    fun `showImportConfirmation sets showImportConfirmation to true`() {
        viewModel = createViewModel()
        viewModel.showImportConfirmation()
        assertTrue(viewModel.uiState.value.showImportConfirmation)
    }

    @Test
    fun `dismissImportConfirmation sets showImportConfirmation to false`() {
        viewModel = createViewModel()
        viewModel.showImportConfirmation()
        viewModel.dismissImportConfirmation()
        assertFalse(viewModel.uiState.value.showImportConfirmation)
    }
}
