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
            .setApplicationName("OneClawShadow")
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
