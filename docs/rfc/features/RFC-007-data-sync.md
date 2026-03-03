# RFC-007: Data Storage & Sync

## Document Information
- **RFC ID**: RFC-007
- **Related PRD**: [FEAT-007 (Data Storage & Sync)](../../prd/features/FEAT-007-data-sync.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Depends On**: [RFC-005 (Session Management)](RFC-005-session-management.md)
- **Depended On By**: None
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

OneClaw stores all user data locally in a Room database (`oneclaw.db`). This includes sessions, messages, agent configurations, provider configurations, model preferences, and app settings. API keys are stored separately in `EncryptedSharedPreferences` via `ApiKeyStorage` and are never part of the Room database.

Currently, there is no way for users to back up their data or transfer it to another device. If the user clears app data, uninstalls the app, or switches to a new phone, all sessions, agent configurations, and settings are lost. The only data that is trivially recoverable is the API keys themselves (by re-entering them).

This RFC introduces two backup mechanisms: (1) automatic Google Drive sync that uploads the entire SQLite database file to the user's Drive `appdata` folder on an hourly schedule, and (2) manual local export/import of a ZIP file containing the database. Both mechanisms inherently exclude API keys because keys live in `EncryptedSharedPreferences`, not in the Room database.

### Goals

1. Allow users to sign in with Google and automatically sync the Room database to Google Drive every hour.
2. Detect changes via timestamp comparison (`updatedAt`/`createdAt` vs. `lastSyncTimestamp`) to avoid uploading when nothing has changed.
3. Support restore from Google Drive on a new device (download `.db` file, replace local database, restart).
4. Provide manual "Export Backup" (ZIP containing `.db` file) and "Import Backup" (select ZIP, extract, replace local DB) in Settings.
5. Add a "Data & Backup" section to the Settings screen showing Google Drive sign-in status, last sync time, Sync Now button, and export/import entries.
6. Ensure API keys are never included in any sync or export -- they are excluded by design since they are not in the Room database.

### Non-Goals

- Selective sync (choose which tables or records to sync).
- Additional cloud providers (Dropbox, OneDrive, iCloud).
- End-to-end encryption of synced data (relies on Google Drive's built-in encryption).
- Merge conflict UI (always last-write-wins, no user prompt for individual conflicts).
- Sync scheduling configuration (fixed at 1 hour).
- Record-level merging or JSON serialization of individual rows.

## Technical Design

### Architecture Overview

```
Sync and backup are two independent paths, both operating on the same SQLite .db file:

1. Google Drive Sync (automatic, hourly)
   SyncWorker (WorkManager) -> SyncManager.sync()
     -> Check: any record updatedAt/createdAt > lastSyncTimestamp?
     -> If yes: close DB checkpoint -> copy .db file -> upload to Drive appdata
     -> If no: skip
   Restore: SyncManager.restore() -> download .db -> replace local -> restart

2. Local Export/Import (manual)
   BackupManager.export() -> copy .db file -> ZIP -> share sheet
   BackupManager.import() -> pick ZIP -> extract .db -> confirmation -> replace local -> restart

3. Settings UI
   SettingsScreen "Data & Backup" section
     -> Google sign-in status, last sync time, Sync Now button
     -> Export Backup, Import Backup entries
```

### Database File Details

The Room database is stored at the standard Android path:

```
/data/data/com.oneclaw.shadow/databases/oneclaw.db
```

The database name `"oneclaw.db"` is defined in `DatabaseModule.kt`:

```kotlin
Room.databaseBuilder(
    androidContext(),
    AppDatabase::class.java,
    "oneclaw.db"
)
```

The database contains six tables: `agents`, `providers`, `models`, `sessions`, `messages`, and `app_settings`. All user data lives here. API keys are stored separately in `EncryptedSharedPreferences` (file `oneclaw_api_keys`) and are never part of this database.

### Change 1: SyncManager -- Google Drive Upload/Download

New file: `data/sync/SyncManager.kt`

`SyncManager` handles Google Drive authentication, upload, download, and restore logic.

```kotlin
package com.oneclaw.shadow.data.sync

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.oneclaw.shadow.data.local.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

class SyncManager(
    private val context: Context,
    private val database: AppDatabase
) {
    companion object {
        private const val BACKUP_FILE_NAME = "oneclaw_backup.db"
        private const val MIME_TYPE = "application/x-sqlite3"
        private const val PREF_NAME = "oneclaw_sync_prefs"
        private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
    }

    private val syncPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // --- Google Sign-In ---

    fun buildGoogleSignInClient(): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    fun getSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    fun getSignInIntent(): Intent {
        return buildGoogleSignInClient().signInIntent
    }

    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            buildGoogleSignInClient().signOut()
        }
    }

    // --- Drive Service ---

    private fun buildDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, Collections.singleton(DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account.account
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("OneClaw")
            .build()
    }

    // --- Sync Logic ---

    /**
     * Check if any record has been created or updated since the last sync.
     * Queries the maximum updatedAt/createdAt across all relevant tables.
     */
    suspend fun hasChangedSinceLastSync(): Boolean = withContext(Dispatchers.IO) {
        val lastSync = syncPrefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
        val db = database.openHelper.readableDatabase
        val cursor = db.query(
            """
            SELECT MAX(ts) FROM (
                SELECT MAX(updated_at) AS ts FROM sessions
                UNION ALL
                SELECT MAX(created_at) AS ts FROM messages
                UNION ALL
                SELECT MAX(updated_at) AS ts FROM agents
                UNION ALL
                SELECT MAX(updated_at) AS ts FROM providers
            )
            """.trimIndent()
        )
        val maxTimestamp = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        cursor.close()
        maxTimestamp > lastSync
    }

    /**
     * Upload the entire .db file to Google Drive appdata folder.
     * Steps:
     * 1. Checkpoint the WAL to ensure all data is in the main .db file.
     * 2. Copy the .db file to a temp location (to avoid locking issues).
     * 3. Upload or update the file on Drive.
     */
    suspend fun upload(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val account = getSignedInAccount() ?: return@withContext SyncResult.NotSignedIn

            // Step 1: Checkpoint WAL
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { /* execute */ }

            // Step 2: Copy .db to temp file
            val dbFile = context.getDatabasePath("oneclaw.db")
            val tempFile = File(context.cacheDir, "oneclaw_sync_temp.db")
            dbFile.copyTo(tempFile, overwrite = true)

            // Step 3: Upload to Drive
            val driveService = buildDriveService(account)
            val existingFileId = findBackupFileId(driveService)

            val mediaContent = FileContent(MIME_TYPE, tempFile)

            if (existingFileId != null) {
                // Update existing file
                driveService.files().update(existingFileId, null, mediaContent).execute()
            } else {
                // Create new file in appdata
                val fileMetadata = com.google.api.services.drive.model.File().apply {
                    name = BACKUP_FILE_NAME
                    parents = listOf("appDataFolder")
                }
                driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id")
                    .execute()
            }

            // Update last sync timestamp
            syncPrefs.edit()
                .putLong(KEY_LAST_SYNC_TIMESTAMP, System.currentTimeMillis())
                .apply()

            tempFile.delete()
            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Download the backup .db file from Drive and replace the local database.
     * After replacing, the app must restart or re-initialize the database.
     */
    suspend fun restore(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val account = getSignedInAccount() ?: return@withContext SyncResult.NotSignedIn
            val driveService = buildDriveService(account)
            val fileId = findBackupFileId(driveService)
                ?: return@withContext SyncResult.NoBackupFound

            // Download to temp file
            val tempFile = File(context.cacheDir, "oneclaw_restore_temp.db")
            FileOutputStream(tempFile).use { outputStream ->
                driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            }

            // Close the current database
            database.close()

            // Replace local database
            val dbFile = context.getDatabasePath("oneclaw.db")
            tempFile.copyTo(dbFile, overwrite = true)
            tempFile.delete()

            // Delete WAL and SHM files to avoid stale state
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()

            SyncResult.RestoreSuccess
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Check if a backup exists on Drive.
     */
    suspend fun hasRemoteBackup(): Boolean = withContext(Dispatchers.IO) {
        val account = getSignedInAccount() ?: return@withContext false
        val driveService = buildDriveService(account)
        findBackupFileId(driveService) != null
    }

    fun getLastSyncTimestamp(): Long {
        return syncPrefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
    }

    private fun findBackupFileId(driveService: Drive): String? {
        val result = driveService.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$BACKUP_FILE_NAME'")
            .setFields("files(id, name)")
            .execute()
        return result.files?.firstOrNull()?.id
    }
}

sealed class SyncResult {
    data object Success : SyncResult()
    data object RestoreSuccess : SyncResult()
    data object NotSignedIn : SyncResult()
    data object NoBackupFound : SyncResult()
    data class Error(val message: String) : SyncResult()
}
```

### Change 2: SyncWorker -- Periodic Background Sync

New file: `data/sync/SyncWorker.kt`

`SyncWorker` is a WorkManager `CoroutineWorker` that runs every 1 hour. It checks if any data has changed since the last sync, and if so, uploads the database.

```kotlin
package com.oneclaw.shadow.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val syncManager: SyncManager by inject()

    override suspend fun doWork(): Result {
        // Skip if not signed in
        if (syncManager.getSignedInAccount() == null) {
            return Result.success()
        }

        // Skip if no changes since last sync
        if (!syncManager.hasChangedSinceLastSync()) {
            return Result.success()
        }

        // Upload
        return when (syncManager.upload()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.NotSignedIn -> Result.success() // No action needed
            is SyncResult.Error -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "oneclaw_sync_periodic"
    }
}
```

Scheduling is done in `OneclawApplication.onCreate()`:

```kotlin
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.oneclaw.shadow.data.sync.SyncWorker
import java.util.concurrent.TimeUnit

// In OneclawApplication.onCreate(), after startKoin:
val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
    .build()
WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    SyncWorker.WORK_NAME,
    ExistingPeriodicWorkPolicy.KEEP,
    syncRequest
)
```

### Change 3: BackupManager -- Local ZIP Export/Import

New file: `data/sync/BackupManager.kt`

`BackupManager` handles local ZIP export and import of the database file.

```kotlin
package com.oneclaw.shadow.data.sync

import android.content.Context
import com.oneclaw.shadow.data.local.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val database: AppDatabase
) {
    companion object {
        private const val DB_ENTRY_NAME = "oneclaw.db"
        private const val EXPORT_FILE_PREFIX = "oneclaw-backup-"
    }

    /**
     * Export the database as a ZIP file.
     * Returns the path to the generated ZIP file in the cache directory.
     */
    suspend fun export(): File = withContext(Dispatchers.IO) {
        // Checkpoint WAL
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { /* execute */ }

        val dbFile = context.getDatabasePath("oneclaw.db")
        val timestamp = System.currentTimeMillis()
        val zipFile = File(context.cacheDir, "${EXPORT_FILE_PREFIX}${timestamp}.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry(DB_ENTRY_NAME))
            FileInputStream(dbFile).use { fis ->
                fis.copyTo(zos)
            }
            zos.closeEntry()
        }

        zipFile
    }

    /**
     * Import a database from a ZIP file input stream.
     * Replaces the local database. The app must restart or re-initialize afterwards.
     */
    suspend fun import(inputStream: InputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempDbFile = File(context.cacheDir, "oneclaw_import_temp.db")

            // Extract the .db file from the ZIP
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                var found = false
                while (entry != null) {
                    if (entry.name == DB_ENTRY_NAME) {
                        FileOutputStream(tempDbFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        found = true
                        break
                    }
                    entry = zis.nextEntry
                }
                if (!found) return@withContext false
            }

            // Close the current database
            database.close()

            // Replace local database
            val dbFile = context.getDatabasePath("oneclaw.db")
            tempDbFile.copyTo(dbFile, overwrite = true)
            tempDbFile.delete()

            // Delete WAL and SHM files
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()

            true
        } catch (e: Exception) {
            false
        }
    }
}
```

### Change 4: Settings UI -- Data & Backup Section

#### 4a. Add `SyncSettingsViewModel`

New file: `feature/settings/SyncSettingsViewModel.kt`

```kotlin
package com.oneclaw.shadow.feature.settings

import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.oneclaw.shadow.data.sync.BackupManager
import com.oneclaw.shadow.data.sync.SyncManager
import com.oneclaw.shadow.data.sync.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

class SyncSettingsViewModel(
    private val syncManager: SyncManager,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncSettingsUiState())
    val uiState: StateFlow<SyncSettingsUiState> = _uiState.asStateFlow()

    init {
        refreshSignInStatus()
    }

    fun getSignInIntent(): Intent = syncManager.getSignInIntent()

    fun handleSignInResult(result: ActivityResult) {
        viewModelScope.launch {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            if (task.isSuccessful) {
                val account = task.result
                _uiState.update {
                    it.copy(
                        isSignedIn = true,
                        accountEmail = account?.email,
                        signInError = null
                    )
                }
                // Check for existing backup
                val hasBackup = syncManager.hasRemoteBackup()
                if (hasBackup) {
                    _uiState.update { it.copy(showRestorePrompt = true) }
                } else {
                    // Trigger initial sync
                    syncNow()
                }
            } else {
                _uiState.update {
                    it.copy(signInError = "Sign-in failed. Please try again.")
                }
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncError = null) }
            when (val result = syncManager.upload()) {
                is SyncResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            lastSyncTimestamp = syncManager.getLastSyncTimestamp()
                        )
                    }
                }
                is SyncResult.Error -> {
                    _uiState.update {
                        it.copy(isSyncing = false, syncError = result.message)
                    }
                }
                else -> {
                    _uiState.update { it.copy(isSyncing = false) }
                }
            }
        }
    }

    fun restore() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRestoring = true, showRestorePrompt = false) }
            when (val result = syncManager.restore()) {
                is SyncResult.RestoreSuccess -> {
                    _uiState.update {
                        it.copy(isRestoring = false, restoreComplete = true)
                    }
                }
                is SyncResult.Error -> {
                    _uiState.update {
                        it.copy(isRestoring = false, syncError = result.message)
                    }
                }
                else -> {
                    _uiState.update { it.copy(isRestoring = false) }
                }
            }
        }
    }

    fun dismissRestorePrompt() {
        _uiState.update { it.copy(showRestorePrompt = false) }
        // Trigger initial sync instead
        syncNow()
    }

    fun signOut() {
        viewModelScope.launch {
            syncManager.signOut()
            _uiState.update {
                it.copy(
                    isSignedIn = false,
                    accountEmail = null,
                    lastSyncTimestamp = 0L
                )
            }
        }
    }

    suspend fun exportBackup(): java.io.File {
        return backupManager.export()
    }

    suspend fun importBackup(inputStream: InputStream): Boolean {
        _uiState.update { it.copy(isRestoring = true) }
        val success = backupManager.import(inputStream)
        _uiState.update {
            it.copy(
                isRestoring = false,
                restoreComplete = if (success) true else it.restoreComplete,
                syncError = if (!success) "Import failed. The file may be invalid." else null
            )
        }
        return success
    }

    fun showImportConfirmation() {
        _uiState.update { it.copy(showImportConfirmation = true) }
    }

    fun dismissImportConfirmation() {
        _uiState.update { it.copy(showImportConfirmation = false) }
    }

    private fun refreshSignInStatus() {
        val account = syncManager.getSignedInAccount()
        _uiState.update {
            it.copy(
                isSignedIn = account != null,
                accountEmail = account?.email,
                lastSyncTimestamp = syncManager.getLastSyncTimestamp()
            )
        }
    }
}

data class SyncSettingsUiState(
    val isSignedIn: Boolean = false,
    val accountEmail: String? = null,
    val isSyncing: Boolean = false,
    val isRestoring: Boolean = false,
    val lastSyncTimestamp: Long = 0L,
    val syncError: String? = null,
    val signInError: String? = null,
    val showRestorePrompt: Boolean = false,
    val showImportConfirmation: Boolean = false,
    val restoreComplete: Boolean = false
)
```

#### 4b. Add "Data & Backup" section to `SettingsScreen`

Update `SettingsScreen` to include the new section and callbacks:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onManageProviders: () -> Unit,
    onManageAgents: () -> Unit = {},
    onDataBackup: () -> Unit = {}  // NEW: navigate to Data & Backup
) {
    Scaffold(
        topBar = { /* ... unchanged ... */ }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SettingsItem(
                title = "Manage Agents",
                subtitle = "Create and configure AI agents",
                onClick = onManageAgents
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                title = "Manage Providers",
                subtitle = "Add API keys, configure models",
                onClick = onManageProviders
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            // NEW
            SettingsItem(
                title = "Data & Backup",
                subtitle = "Google Drive sync, export/import backup",
                onClick = onDataBackup
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}
```

#### 4c. New `DataBackupScreen`

New file: `feature/settings/DataBackupScreen.kt`

This screen displays Google Drive sync status, Sync Now button, and export/import entries.

```kotlin
package com.oneclaw.shadow.feature.settings

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataBackupScreen(
    onNavigateBack: () -> Unit,
    onRestartApp: () -> Unit,
    viewModel: SyncSettingsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Google Sign-In launcher
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleSignInResult(result)
    }

    // File picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.showImportConfirmation()
        }
    }

    // Restore prompt dialog
    if (uiState.showRestorePrompt) {
        RestoreConfirmationDialog(
            onConfirm = {
                viewModel.restore()
            },
            onDismiss = { viewModel.dismissRestorePrompt() }
        )
    }

    // Import confirmation dialog
    if (uiState.showImportConfirmation) {
        RestoreConfirmationDialog(
            onConfirm = {
                viewModel.dismissImportConfirmation()
                // Actual import logic triggered after confirmation
            },
            onDismiss = { viewModel.dismissImportConfirmation() }
        )
    }

    // Restart app after restore
    if (uiState.restoreComplete) {
        onRestartApp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data & Backup") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // --- Google Drive Sync Section ---
            Text(
                text = "Google Drive Sync",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            if (uiState.isSignedIn) {
                // Signed-in state
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "Connected: ${uiState.accountEmail ?: ""}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (uiState.lastSyncTimestamp > 0L) {
                        Text(
                            text = "Last synced: ${
                                DateUtils.getRelativeTimeSpanString(
                                    uiState.lastSyncTimestamp,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS
                                )
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Not yet synced",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { viewModel.syncNow() },
                            enabled = !uiState.isSyncing
                        ) {
                            if (uiState.isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Sync Now")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = { viewModel.signOut() }) {
                            Text("Sign Out")
                        }
                    }

                    if (uiState.syncError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.syncError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                // Not signed-in state
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "Not connected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { signInLauncher.launch(viewModel.getSignInIntent()) }
                    ) {
                        Text("Sign in with Google")
                    }
                    if (uiState.signInError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.signInError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // --- Local Backup Section ---
            Text(
                text = "Local Backup",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            val zipFile = viewModel.exportBackup()
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                zipFile
                            )
                            val shareIntent = android.content.Intent(
                                android.content.Intent.ACTION_SEND
                            ).apply {
                                type = "application/zip"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                android.content.Intent.createChooser(shareIntent, "Export Backup")
                            )
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Export Backup", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Save all data to a file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        importLauncher.launch("application/zip")
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Import Backup", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Restore from a backup file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Restoring indicator
            if (uiState.isRestoring) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Restoring data...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RestoreConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from Backup?") },
        text = {
            Text(
                "This will replace all current data with the backup. " +
                    "API keys will not be restored -- you will need to " +
                    "re-enter them in provider settings."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
```

#### 4d. New Route for Data & Backup

Add to `Routes.kt`:

```kotlin
sealed class Route(val path: String) {
    // ... existing routes ...
    data object DataBackup : Route("data-backup")  // NEW
}
```

#### 4e. Register in NavGraph

```kotlin
composable(Route.DataBackup.path) {
    DataBackupScreen(
        onNavigateBack = { navController.popBackStack() },
        onRestartApp = {
            // Restart the app to re-initialize the database
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Runtime.getRuntime().exit(0)
        }
    )
}
```

Update the `SettingsScreen` composable call site:

```kotlin
composable(Route.Settings.path) {
    SettingsScreen(
        onNavigateBack = { navController.popBackStack() },
        onManageProviders = { navController.navigate(Route.ProviderList.path) },
        onManageAgents = { navController.navigate(Route.AgentList.path) },
        onDataBackup = { navController.navigate(Route.DataBackup.path) }
    )
}
```

### Change 5: DI Registration

#### 5a. Add `SyncManager` and `BackupManager` to `AppModule`

```kotlin
val appModule = module {
    single { ApiKeyStorage(androidContext()) }
    single { ModelApiAdapterFactory(get()) }
    // RFC-007: Sync and backup
    single { SyncManager(androidContext(), get()) }
    single { BackupManager(androidContext(), get()) }
}
```

#### 5b. Add `SyncSettingsViewModel` to `FeatureModule`

```kotlin
val featureModule = module {
    // ... existing registrations ...

    // RFC-007: Data & Backup
    viewModelOf(::SyncSettingsViewModel)
}
```

### Change 6: Dependencies -- build.gradle.kts

Add these new dependencies:

```kotlin
dependencies {
    // ... existing dependencies ...

    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    // Google Drive API
    implementation("com.google.api-client:google-api-client-android:2.7.1")
    implementation("com.google.apis:google-api-services-drive:v3-rev20241206-2.0.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
```

### Change 7: AndroidManifest.xml -- FileProvider

For local export to work with the system share sheet, a `FileProvider` declaration is needed:

```xml
<application ...>
    <!-- Existing content ... -->

    <provider
        android:name="androidx.core.content.FileProvider"
        android:authorities="${applicationId}.fileprovider"
        android:exported="false"
        android:grantUriPermissions="true">
        <meta-data
            android:name="android.support.FILE_PROVIDER_PATHS"
            android:resource="@xml/file_paths" />
    </provider>
</application>
```

New file: `app/src/main/res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="backups" path="." />
</paths>
```

## Implementation Steps

### Step 1: Add Gradle dependencies
- File: `app/build.gradle.kts`
- Add `play-services-auth`, `google-api-client-android`, `google-api-services-drive`, `work-runtime-ktx`

### Step 2: Create `SyncManager`
- File: `app/src/main/kotlin/com/oneclaw/shadow/data/sync/SyncManager.kt` (new)
- Google Sign-In client setup, sign-in/sign-out methods
- `buildDriveService()` to create a Drive API client from a signed-in account
- `hasChangedSinceLastSync()`: query max `updatedAt`/`createdAt` across tables, compare to `lastSyncTimestamp` in SharedPreferences
- `upload()`: checkpoint WAL, copy `.db` to temp, upload to Drive `appdata` folder
- `restore()`: download `.db` from Drive, close database, replace local file, delete WAL/SHM
- `hasRemoteBackup()`: check if backup file exists on Drive
- `SyncResult` sealed class for result types

### Step 3: Create `SyncWorker`
- File: `app/src/main/kotlin/com/oneclaw/shadow/data/sync/SyncWorker.kt` (new)
- `CoroutineWorker` that runs `SyncManager.upload()` if signed in and data has changed
- Returns `Result.retry()` on error, `Result.success()` otherwise

### Step 4: Schedule periodic sync in `OneclawApplication`
- File: `app/src/main/kotlin/com/oneclaw/shadow/OneclawApplication.kt`
- Add `WorkManager.enqueueUniquePeriodicWork()` with 1-hour interval in `onCreate()`

### Step 5: Create `BackupManager`
- File: `app/src/main/kotlin/com/oneclaw/shadow/data/sync/BackupManager.kt` (new)
- `export()`: checkpoint WAL, copy `.db` into a ZIP, return ZIP file path
- `import()`: extract `.db` from ZIP, close database, replace local file, delete WAL/SHM

### Step 6: Add FileProvider for sharing export files
- File: `app/src/main/AndroidManifest.xml`
  - Add `<provider>` declaration for `FileProvider`
- File: `app/src/main/res/xml/file_paths.xml` (new)
  - Declare `<cache-path>` for backup ZIP files

### Step 7: Create `SyncSettingsViewModel`
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/settings/SyncSettingsViewModel.kt` (new)
- Manages sign-in flow, sync-now, restore, export, import, UI state
- `SyncSettingsUiState` data class with sign-in status, sync status, error messages, dialog flags

### Step 8: Create `DataBackupScreen`
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/settings/DataBackupScreen.kt` (new)
- "Google Drive Sync" section: sign-in/sign-out, connected status, last sync time, Sync Now button
- "Local Backup" section: Export Backup (share sheet), Import Backup (file picker)
- Restore confirmation dialog
- Restoring progress indicator

### Step 9: Add route, navigation, and settings entry
- File: `app/src/main/kotlin/com/oneclaw/shadow/navigation/Routes.kt`
  - Add `data object DataBackup : Route("data-backup")`
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/provider/SettingsScreen.kt`
  - Add `onDataBackup` callback parameter
  - Add "Data & Backup" settings item
- File: `app/src/main/kotlin/com/oneclaw/shadow/navigation/NavGraph.kt`
  - Add `composable(Route.DataBackup.path)` block for `DataBackupScreen`
  - Pass `onDataBackup` to `SettingsScreen`

### Step 10: Register DI components
- File: `app/src/main/kotlin/com/oneclaw/shadow/di/AppModule.kt`
  - Add `single { SyncManager(androidContext(), get()) }`
  - Add `single { BackupManager(androidContext(), get()) }`
- File: `app/src/main/kotlin/com/oneclaw/shadow/di/FeatureModule.kt`
  - Add `viewModelOf(::SyncSettingsViewModel)`

## Test Strategy

### Layer 1A -- Unit Tests

**`SyncManagerTest`** (`app/src/test/kotlin/.../data/sync/`):
- Test: `hasChangedSinceLastSync()` returns `true` when a session `updatedAt` exceeds `lastSyncTimestamp`
- Test: `hasChangedSinceLastSync()` returns `false` when all timestamps are at or before `lastSyncTimestamp`
- Test: `getSignedInAccount()` returns `null` when no account is signed in
- Test: `getLastSyncTimestamp()` returns `0L` when no sync has occurred
- Test: `upload()` returns `SyncResult.NotSignedIn` when no account is signed in

**`SyncWorkerTest`** (`app/src/test/kotlin/.../data/sync/`):
- Test: `doWork()` returns `Result.success()` when not signed in (no-op)
- Test: `doWork()` returns `Result.success()` when signed in but no changes
- Test: `doWork()` returns `Result.retry()` when upload returns an error

**`BackupManagerTest`** (`app/src/test/kotlin/.../data/sync/`):
- Test: `export()` produces a ZIP file containing `oneclaw.db`
- Test: `import()` with a valid ZIP returns `true` and replaces the database file
- Test: `import()` with a ZIP missing the expected entry returns `false`
- Test: `import()` with a corrupt input stream returns `false`

**`SyncSettingsViewModelTest`** (`app/src/test/kotlin/.../feature/settings/`):
- Test: initial state has `isSignedIn = false` when no account exists
- Test: `syncNow()` sets `isSyncing = true` then `false` on success
- Test: `syncNow()` sets `syncError` on failure
- Test: `signOut()` resets `isSignedIn` to `false`
- Test: `dismissRestorePrompt()` sets `showRestorePrompt = false`
- Test: `exportBackup()` delegates to `BackupManager.export()`

### Layer 1C -- Screenshot Tests

- `DataBackupScreen` not signed in: verify "Not connected" text and "Sign in with Google" button
- `DataBackupScreen` signed in: verify connected email, last sync time, "Sync Now" button, "Sign Out" button
- `DataBackupScreen` syncing: verify `CircularProgressIndicator` visible next to "Sync Now"
- `DataBackupScreen` with sync error: verify red error text
- `RestoreConfirmationDialog`: verify title, body text, Cancel and Restore buttons
- `SettingsScreen` with "Data & Backup" entry: verify the new item appears

### Layer 2 -- adb Visual Verification

**Flow 7-1: Google Drive sign-in and initial sync**
1. Open Settings
2. Tap "Data & Backup"
3. Verify "Not connected" and "Sign in with Google" button visible
4. Tap "Sign in with Google"
5. Complete Google sign-in flow
6. Verify status changes to "Connected (user@gmail.com)"
7. Verify "Sync Now" and "Sign Out" buttons visible
8. Wait for initial sync to complete
9. Verify "Last synced: just now" appears

**Flow 7-2: Manual sync trigger**
1. With Google Drive connected, send some messages in a chat
2. Navigate to Settings > Data & Backup
3. Tap "Sync Now"
4. Verify syncing indicator appears
5. Verify "Last synced: just now" updates after completion

**Flow 7-3: Local export and import**
1. Go to Settings > Data & Backup
2. Tap "Export Backup"
3. Verify system share sheet opens with a ZIP file
4. Save the file
5. Clear app data: `adb shell pm clear com.oneclaw.shadow`
6. Go through setup, then Settings > Data & Backup
7. Tap "Import Backup"
8. Select the previously saved ZIP file
9. Verify confirmation dialog appears
10. Tap "Restore"
11. Verify app restarts and data is restored
12. Verify API keys are empty (providers show no key configured)

**Flow 7-4: Restore from Google Drive on new device**
1. On a new/reset device, complete initial setup
2. Go to Settings > Data & Backup
3. Sign in with the same Google account
4. Verify "Restore from Backup?" dialog appears
5. Tap "Restore"
6. Verify app restarts with restored data
7. Verify API keys are empty

**Flow 7-5: API keys are not in export**
1. Configure a provider with an API key
2. Go to Settings > Data & Backup > Export Backup
3. Save the ZIP file
4. Extract the ZIP and open the `.db` file with a SQLite viewer
5. Verify the `providers` table has no API key column
6. Verify no API key values appear anywhere in the database

## Security Considerations

1. **API keys are excluded by design.** API keys are stored in `EncryptedSharedPreferences` (`ApiKeyStorage`), which is a completely separate file from the Room database. When the `.db` file is uploaded to Drive or exported as ZIP, API keys are never part of it.

2. **Google Drive `appdata` scope.** The app requests only `drive.appdata` scope, which grants access to a hidden app-specific folder on the user's Drive. Other apps cannot read this folder. The user cannot see it in the normal Drive UI (only via "Manage storage").

3. **Data in transit.** Google Drive API uses HTTPS for all transfers. The database file is encrypted in transit.

4. **Data at rest.** Google Drive encrypts data at rest on its servers. The local ZIP export is not encrypted -- this is acceptable because the user is explicitly choosing to save it and can control where they store it.

5. **Database file contains no secrets.** The `ProviderEntity` schema stores `id`, `name`, `type`, `api_base_url`, `is_pre_configured`, `is_active`, `created_at`, `updated_at`. No API key field exists in the Room schema.

## Change History

| Date | Version | Change | Author |
|------|---------|--------|--------|
| 2026-02-28 | 0.1 | Initial draft | TBD |
