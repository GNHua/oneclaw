package com.tomandy.palmclaw.engine

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Simplified unit tests for the engine-runtime module.
 *
 * These tests verify the basic functionality that can be tested without Android runtime.
 * Full integration tests requiring Android runtime should be in androidTest/.
 */
class SimplifiedEngineTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var tempDir: File

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)

        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.remove(any()) } returns mockEditor
        every { mockEditor.apply() } returns Unit
        every { mockPrefs.getString(any<String>(), any()) } returns null

        tempDir = File.createTempFile("test", "").apply {
            delete()
            mkdirs()
        }
        every { mockContext.cacheDir } returns tempDir
    }

    // ====== KtsCompiler Tests ======

    @Test
    fun `KtsCompiler creates JAR file with valid script`() = runTest {
        val compiler = KtsCompiler(tempDir)
        val result = compiler.compile("class TestPlugin", "test-1")

        assertTrue(result.isSuccess)
        val compiled = result.getOrNull()!!
        assertEquals("test-1", compiled.id)
        assertTrue(compiled.jarFile.exists())
    }

    @Test
    fun `KtsCompiler fails with empty script`() = runTest {
        val compiler = KtsCompiler(tempDir)
        val result = compiler.compile("", "test-2")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CompilationException)
    }

    @Test
    fun `KtsCompiler validates script contains class or function`() = runTest {
        val compiler = KtsCompiler(tempDir)
        val result = compiler.compile("val x = 42", "test-3")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("class or function"))
    }

    // ====== DexConverter Tests ======

    @Test
    fun `DexConverter creates DEX file from JAR`() = runTest {
        val converter = DexConverter(tempDir)
        val jarFile = File.createTempFile("test", ".jar", tempDir).apply {
            writeText("test content")
        }

        val result = converter.convertToDex(jarFile, "test-dex-1")

        assertTrue(result.isSuccess)
        val dexFile = result.getOrNull()!!
        assertTrue(dexFile.exists())
        assertEquals("classes.dex", dexFile.name)
    }

    @Test
    fun `DexConverter fails with non-existent JAR`() = runTest {
        val converter = DexConverter(tempDir)
        val nonExistentJar = File(tempDir, "missing.jar")

        val result = converter.convertToDex(nonExistentJar, "test-dex-2")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DexException)
    }

    @Test
    fun `DexConverter getDexFile returns correct path`() {
        val converter = DexConverter(tempDir)
        val dexFile = converter.getDexFile("test-plugin")

        assertEquals("classes.dex", dexFile.name)
        assertTrue(dexFile.absolutePath.contains("test-plugin"))
    }

    @Test
    fun `DexConverter cleanupDex removes directory`() = runTest {
        val converter = DexConverter(tempDir)
        val jarFile = File.createTempFile("test", ".jar", tempDir).apply {
            writeText("test")
        }

        converter.convertToDex(jarFile, "cleanup-test")

        val scriptDir = File(tempDir, "cleanup-test")
        assertTrue(scriptDir.exists())

        converter.cleanupDex("cleanup-test")
        assertFalse(scriptDir.exists())
    }

    // ====== CacheManager Tests ======

    @Test
    fun `CacheManager shouldRecompile returns true for new plugin`() = runTest {
        every { mockPrefs.getString("new-plugin_hash", null) } returns null

        val cacheManager = CacheManager(mockContext)
        val result = cacheManager.shouldRecompile("new-plugin", "class Plugin")

        assertTrue(result)
    }

    @Test
    fun `CacheManager shouldRecompile returns false when cached and DEX exists`() = runTest {
        val scriptSource = "class Plugin"
        val hash = scriptSource.md5Hash()

        every { mockPrefs.getString("cached-plugin_hash", null) } returns hash

        val cacheManager = CacheManager(mockContext)

        // Create DEX file
        val dexFile = cacheManager.getDexFile("cached-plugin")
        dexFile.parentFile?.mkdirs()
        dexFile.createNewFile()

        val result = cacheManager.shouldRecompile("cached-plugin", scriptSource)

        assertFalse(result)
    }

    @Test
    fun `CacheManager clearCache removes plugin files`() = runTest {
        val cacheManager = CacheManager(mockContext)

        val jarFile = cacheManager.getJarFile("clear-test")
        jarFile.parentFile?.mkdirs()
        jarFile.createNewFile()

        assertTrue(jarFile.exists())

        cacheManager.clearCache("clear-test")

        assertFalse(jarFile.exists())
    }

    // ====== PluginMetadata Tests ======

    @Test
    fun `PluginMetadata stores all fields correctly`() {
        val tools = listOf(
            ToolDefinition(
                name = "test_tool",
                description = "A test tool",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("param", "value")
                }
            )
        )

        val metadata = PluginMetadata(
            id = "test-plugin",
            name = "Test Plugin",
            version = "1.0.0",
            description = "Test description",
            author = "Test Author",
            entryPoint = "com.test.Plugin",
            tools = tools,
            permissions = listOf("INTERNET"),
            dependencies = listOf("lib:1.0")
        )

        assertEquals("test-plugin", metadata.id)
        assertEquals("Test Plugin", metadata.name)
        assertEquals("1.0.0", metadata.version)
        assertEquals(1, metadata.tools.size)
        assertEquals(1, metadata.permissions.size)
        assertEquals(1, metadata.dependencies.size)
    }

    // ====== ToolResult Tests ======

    @Test
    fun `ToolResult Success contains output`() {
        val result = ToolResult.Success("test output", mapOf("key" to "value"))

        assertEquals("test output", result.output)
        assertEquals("value", result.metadata["key"])
    }

    @Test
    fun `ToolResult Failure contains error`() {
        val exception = RuntimeException("test error")
        val result = ToolResult.Failure("Error occurred", exception)

        assertEquals("Error occurred", result.error)
        assertEquals(exception, result.exception)
    }

    // Helper function for MD5 hashing
    private fun String.md5Hash(): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
