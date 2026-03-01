package com.oneclaw.shadow.data.sync

import android.content.Context
import com.oneclaw.shadow.data.local.db.AppDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupManagerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var backupManager: BackupManager

    @BeforeEach
    fun setup() {
        database = mockk(relaxed = true)
        context = mockk(relaxed = true)

        // Mock cacheDir to use our temp directory
        every { context.cacheDir } returns tempDir

        // Mock getDatabasePath to return a temp file
        val dbFile = File(tempDir, "oneclaw.db")
        dbFile.writeText("fake-db-content")
        every { context.getDatabasePath("oneclaw.db") } returns dbFile

        backupManager = BackupManager(context, database)
    }

    @Test
    fun `export produces a ZIP file in cache directory`() = runTest {
        val zipFile = backupManager.export()
        assertNotNull(zipFile)
        assertTrue(zipFile.exists())
        assertTrue(zipFile.name.endsWith(".zip"))
    }

    @Test
    fun `export produces a ZIP file containing oneclaw dot db entry`() = runTest {
        val zipFile = backupManager.export()
        assertTrue(zipFile.exists())

        val zipInputStream = java.util.zip.ZipInputStream(zipFile.inputStream())
        var foundEntry = false
        var entry = zipInputStream.nextEntry
        while (entry != null) {
            if (entry.name == "oneclaw.db") {
                foundEntry = true
                break
            }
            entry = zipInputStream.nextEntry
        }
        zipInputStream.close()
        assertTrue(foundEntry)
    }

    @Test
    fun `import with valid ZIP containing oneclaw dot db returns true`() = runTest {
        // Create a valid ZIP with oneclaw.db entry
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("oneclaw.db"))
            zos.write("fake-restored-db-content".toByteArray())
            zos.closeEntry()
        }

        val inputStream = ByteArrayInputStream(baos.toByteArray())
        val result = backupManager.import(inputStream)
        assertTrue(result)
    }

    @Test
    fun `import with ZIP missing oneclaw dot db entry returns false`() = runTest {
        // Create a ZIP without the expected entry
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("some_other_file.txt"))
            zos.write("some content".toByteArray())
            zos.closeEntry()
        }

        val inputStream = ByteArrayInputStream(baos.toByteArray())
        val result = backupManager.import(inputStream)
        assertFalse(result)
    }

    @Test
    fun `import with corrupt input stream returns false`() = runTest {
        // Provide invalid/corrupt ZIP data
        val invalidData = ByteArrayInputStream("not-a-zip".toByteArray())
        val result = backupManager.import(invalidData)
        assertFalse(result)
    }
}
