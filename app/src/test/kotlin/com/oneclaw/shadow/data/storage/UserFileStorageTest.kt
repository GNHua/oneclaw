package com.oneclaw.shadow.data.storage

import com.oneclaw.shadow.core.model.FileContent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Tests for UserFileStorage using a TestableUserFileStorage that overrides rootDir
 * to use a real temp directory without needing Android Context.
 */
class UserFileStorageTest {

    private lateinit var tempRootDir: File
    private lateinit var storage: TestableUserFileStorage

    @BeforeEach
    fun setUp() {
        tempRootDir = createTempDir("user_files_test")
        storage = TestableUserFileStorage(tempRootDir)
    }

    @AfterEach
    fun tearDown() {
        tempRootDir.deleteRecursively()
    }

    @Test
    fun `listFiles returns empty list for empty directory`() {
        val files = storage.listFiles()
        assertEquals(emptyList<Any>(), files)
    }

    @Test
    fun `listFiles returns empty list for non-existent path`() {
        val files = storage.listFiles("nonexistent/path")
        assertEquals(emptyList<Any>(), files)
    }

    @Test
    fun `listFiles returns directories before files sorted alphabetically`() {
        val root = storage.rootDir
        File(root, "z_folder").mkdirs()
        File(root, "a_folder").mkdirs()
        File(root, "m_file.txt").writeText("content")
        File(root, "a_file.py").writeText("content")

        val files = storage.listFiles()

        assertEquals(4, files.size)
        // Directories first
        assertTrue(files[0].isDirectory)
        assertTrue(files[1].isDirectory)
        // Then files
        assertFalse(files[2].isDirectory)
        assertFalse(files[3].isDirectory)
        // Alphabetical within directories
        assertEquals("a_folder", files[0].name)
        assertEquals("z_folder", files[1].name)
        // Alphabetical within files
        assertEquals("a_file.py", files[2].name)
        assertEquals("m_file.txt", files[3].name)
    }

    @Test
    fun `listFiles returns correct FileInfo for file`() {
        val root = storage.rootDir
        val file = File(root, "notes.txt")
        file.writeText("Hello world")

        val files = storage.listFiles()

        assertEquals(1, files.size)
        val info = files[0]
        assertEquals("notes.txt", info.name)
        assertEquals("notes.txt", info.relativePath)
        assertFalse(info.isDirectory)
        assertEquals(11L, info.size)
        assertEquals("text/plain", info.mimeType)
        assertEquals(0, info.childCount)
    }

    @Test
    fun `listFiles returns correct FileInfo for directory`() {
        val root = storage.rootDir
        val dir = File(root, "scripts")
        dir.mkdirs()
        File(dir, "run.sh").writeText("#!/bin/bash")
        File(dir, "test.sh").writeText("#!/bin/bash")

        val files = storage.listFiles()

        assertEquals(1, files.size)
        val info = files[0]
        assertEquals("scripts", info.name)
        assertEquals("scripts", info.relativePath)
        assertTrue(info.isDirectory)
        assertEquals(0L, info.size)
        assertEquals(2, info.childCount)
    }

    @Test
    fun `listFiles with subdirectory path lists contents`() {
        val root = storage.rootDir
        val subDir = File(root, "python")
        subDir.mkdirs()
        File(subDir, "sort.py").writeText("def sort(): pass")
        File(subDir, "search.py").writeText("def search(): pass")

        val files = storage.listFiles("python")

        assertEquals(2, files.size)
        assertTrue(files.any { it.name == "sort.py" })
        assertTrue(files.any { it.name == "search.py" })
    }

    @Test
    fun `readFileContent returns Text for txt file`() {
        val root = storage.rootDir
        File(root, "readme.txt").writeText("Hello World")

        val content = storage.readFileContent("readme.txt")

        assertInstanceOf(FileContent.Text::class.java, content)
        val text = content as FileContent.Text
        assertEquals("Hello World", text.content)
        assertEquals(1, text.lineCount)
    }

    @Test
    fun `readFileContent returns Text for py file`() {
        val root = storage.rootDir
        File(root, "script.py").writeText("print('hello')\nprint('world')")

        val content = storage.readFileContent("script.py")

        assertInstanceOf(FileContent.Text::class.java, content)
        val text = content as FileContent.Text
        assertEquals(2, text.lineCount)
    }

    @Test
    fun `readFileContent returns Text for md file`() {
        val root = storage.rootDir
        File(root, "notes.md").writeText("# Title\n\nContent here")

        val content = storage.readFileContent("notes.md")

        assertInstanceOf(FileContent.Text::class.java, content)
    }

    @Test
    fun `readFileContent returns Image for png file`() {
        val root = storage.rootDir
        val pngFile = File(root, "image.png")
        pngFile.writeBytes(ByteArray(100))

        val content = storage.readFileContent("image.png")

        assertInstanceOf(FileContent.Image::class.java, content)
        val image = content as FileContent.Image
        assertEquals(pngFile.canonicalPath, image.file.canonicalPath)
    }

    @Test
    fun `readFileContent returns Image for jpg file`() {
        val root = storage.rootDir
        File(root, "photo.jpg").writeBytes(ByteArray(100))

        val content = storage.readFileContent("photo.jpg")

        assertInstanceOf(FileContent.Image::class.java, content)
    }

    @Test
    fun `readFileContent returns Unsupported for unknown file type`() {
        val root = storage.rootDir
        File(root, "archive.zip").writeBytes(ByteArray(100))

        val content = storage.readFileContent("archive.zip")

        assertInstanceOf(FileContent.Unsupported::class.java, content)
    }

    @Test
    fun `readFileContent truncates text files larger than 1MB`() {
        val root = storage.rootDir
        val largeFile = File(root, "large.txt")
        val chunkSize = 1024
        val chunks = (UserFileStorage.MAX_TEXT_SIZE / chunkSize + 2).toInt()
        largeFile.outputStream().bufferedWriter().use { writer ->
            repeat(chunks) {
                writer.write("a".repeat(chunkSize))
            }
        }

        val content = storage.readFileContent("large.txt")

        assertInstanceOf(FileContent.Text::class.java, content)
        val text = content as FileContent.Text
        assertEquals(-1, text.lineCount)
        assertEquals(UserFileStorage.MAX_TEXT_SIZE.toInt(), text.content.length)
    }

    @Test
    fun `delete removes file successfully`() {
        val root = storage.rootDir
        File(root, "to_delete.txt").writeText("delete me")

        val result = storage.delete("to_delete.txt")

        assertTrue(result)
        assertFalse(File(root, "to_delete.txt").exists())
    }

    @Test
    fun `delete removes directory recursively`() {
        val root = storage.rootDir
        val dir = File(root, "folder")
        dir.mkdirs()
        File(dir, "file1.txt").writeText("content")
        File(dir, "file2.txt").writeText("content")

        val result = storage.delete("folder")

        assertTrue(result)
        assertFalse(dir.exists())
    }

    @Test
    fun `resolveFile throws on path traversal attempt`() {
        assertThrows<IllegalArgumentException> {
            storage.resolveFile("../../etc/passwd")
        }
    }

    @Test
    fun `resolveFile returns valid file under root`() {
        val file = storage.resolveFile("subdir/file.txt")
        assertNotNull(file)
        assertTrue(file.canonicalPath.startsWith(storage.rootDir.canonicalPath))
    }

    @Test
    fun `getMimeType returns correct type for txt`() {
        assertEquals("text/plain", UserFileStorage.getMimeType(File("test.txt")))
    }

    @Test
    fun `getMimeType returns correct type for py`() {
        assertEquals("text/x-python", UserFileStorage.getMimeType(File("script.py")))
    }

    @Test
    fun `getMimeType returns correct type for png`() {
        assertEquals("image/png", UserFileStorage.getMimeType(File("image.png")))
    }

    @Test
    fun `getMimeType returns correct type for jpg`() {
        assertEquals("image/jpeg", UserFileStorage.getMimeType(File("photo.jpg")))
    }

    @Test
    fun `getMimeType returns null for unknown extension`() {
        assertEquals(null, UserFileStorage.getMimeType(File("archive.zip")))
    }

    @Test
    fun `getTotalSize sums file sizes`() {
        val root = storage.rootDir
        File(root, "file1.txt").writeText("1234567890") // 10 bytes
        File(root, "file2.txt").writeText("12345")      // 5 bytes

        val totalSize = storage.getTotalSize()

        assertEquals(15L, totalSize)
    }

    @Test
    fun `isTextFile returns true for text mime type`() {
        assertTrue(UserFileStorage.isTextFile("text/plain", File("file.bin")))
    }

    @Test
    fun `isTextFile returns true for known text extension`() {
        assertTrue(UserFileStorage.isTextFile(null, File("script.py")))
    }

    @Test
    fun `isTextFile returns false for unknown extension with no mime`() {
        assertFalse(UserFileStorage.isTextFile(null, File("archive.zip")))
    }

    @Test
    fun `isImageFile returns true for image mime type`() {
        assertTrue(UserFileStorage.isImageFile("image/png"))
    }

    @Test
    fun `isImageFile returns false for null mime type`() {
        assertFalse(UserFileStorage.isImageFile(null))
    }
}
