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
