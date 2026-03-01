package com.oneclaw.shadow.tool.js

import android.content.Context
import android.util.Log
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.model.ToolResultStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for JS tool group (array manifest) functionality.
 *
 * Covers:
 * - JSON format detection (object = single-tool, array = group)
 * - Group manifest parsing with valid and invalid entries
 * - Function name validation
 * - Duplicate name handling within a group
 * - JsTool functionName passthrough to execution engine
 */
class JsToolGroupTest {

    private lateinit var context: Context
    private lateinit var engine: JsExecutionEngine
    private lateinit var envVarStore: EnvironmentVariableStore
    private lateinit var loader: JsToolLoader

    @BeforeEach
    fun setup() {
        // android.util.Log is not available in JVM unit tests -- mock all static methods
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0

        context = mockk(relaxed = true)
        engine = mockk()
        envVarStore = mockk()
        coEvery { envVarStore.getAll() } returns emptyMap()
        loader = JsToolLoader(context, engine, envVarStore)
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Log::class)
    }

    private fun makeDefinition(name: String, timeoutSeconds: Int = 30) = ToolDefinition(
        name = name,
        description = "Test tool $name",
        parametersSchema = ToolParametersSchema(properties = emptyMap(), required = emptyList()),
        requiredPermissions = emptyList(),
        timeoutSeconds = timeoutSeconds
    )

    // ---- Format detection ----

    @Test
    fun `JSON object format is detected as single-tool and returns functionName null`() {
        val json = """
            {
              "name": "my_tool",
              "description": "A simple tool"
            }
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "my_tool", "my_tool.json")

        assertEquals(1, result.size)
        assertEquals("my_tool", result[0].first.name)
        assertNull(result[0].second, "Single-tool mode should have functionName=null")
    }

    @Test
    fun `JSON array format is detected as group and returns multiple tools with functionNames`() {
        val json = """
            [
              {"name": "drive_list", "description": "List files", "function": "listFiles"},
              {"name": "drive_read", "description": "Read file", "function": "readFile"}
            ]
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "google_drive", "google_drive.json")

        assertEquals(2, result.size)
        assertEquals("drive_list", result[0].first.name)
        assertEquals("listFiles", result[0].second)
        assertEquals("drive_read", result[1].first.name)
        assertEquals("readFile", result[1].second)
    }

    // ---- Group parsing ----

    @Test
    fun `valid group with 3 entries returns 3 definition-functionName pairs`() {
        val json = """
            [
              {"name": "gdrive_list", "description": "List files", "function": "listFiles"},
              {"name": "gdrive_read", "description": "Read a file", "function": "readFile"},
              {"name": "gdrive_upload", "description": "Upload a file", "function": "uploadFile"}
            ]
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "google_drive", "google_drive.json")

        assertEquals(3, result.size)
        assertEquals("gdrive_list", result[0].first.name)
        assertEquals("listFiles", result[0].second)
        assertEquals("gdrive_read", result[1].first.name)
        assertEquals("readFile", result[1].second)
        assertEquals("gdrive_upload", result[2].first.name)
        assertEquals("uploadFile", result[2].second)
    }

    @Test
    fun `group entry missing function field is skipped, others are loaded`() {
        val json = """
            [
              {"name": "tool_a", "description": "Tool A", "function": "funcA"},
              {"name": "tool_b", "description": "Tool B"},
              {"name": "tool_c", "description": "Tool C", "function": "funcC"}
            ]
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "multi", "multi.json")

        assertEquals(2, result.size, "Entry missing 'function' should be skipped")
        assertEquals("tool_a", result[0].first.name)
        assertEquals("tool_c", result[1].first.name)
    }

    @Test
    fun `group entry missing name field is skipped, others are loaded`() {
        val json = """
            [
              {"name": "tool_a", "description": "Tool A", "function": "funcA"},
              {"description": "No name here", "function": "funcB"},
              {"name": "tool_c", "description": "Tool C", "function": "funcC"}
            ]
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "multi", "multi.json")

        assertEquals(2, result.size, "Entry missing 'name' should be skipped")
        assertEquals("tool_a", result[0].first.name)
        assertEquals("tool_c", result[1].first.name)
    }

    @Test
    fun `duplicate tool name in group - first wins, second is skipped`() {
        val json = """
            [
              {"name": "search", "description": "First search", "function": "searchV1"},
              {"name": "search", "description": "Second search", "function": "searchV2"},
              {"name": "fetch", "description": "Fetch tool", "function": "fetchData"}
            ]
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "toolkit", "toolkit.json")

        assertEquals(2, result.size, "Duplicate name should cause second entry to be skipped")
        assertEquals("search", result[0].first.name)
        assertEquals("searchV1", result[0].second, "First occurrence wins")
        assertEquals("fetch", result[1].first.name)
    }

    @Test
    fun `empty array returns empty list`() {
        val json = "[]"

        val result = loader.parseJsonManifest(json, "empty_group", "empty_group.json")

        assertTrue(result.isEmpty(), "Empty array should produce empty list")
    }

    @Test
    fun `array exceeding MAX_GROUP_SIZE throws exception`() {
        val entries = (1..51).joinToString(",\n") { i ->
            """{"name": "tool_$i", "description": "Tool $i", "function": "func$i"}"""
        }
        val json = "[$entries]"

        assertThrows<IllegalArgumentException> {
            loader.parseJsonManifest(json, "huge_group", "huge_group.json")
        }
    }

    @Test
    fun `array at exactly MAX_GROUP_SIZE is accepted`() {
        val entries = (1..50).joinToString(",\n") { i ->
            """{"name": "tool_$i", "description": "Tool $i", "function": "func$i"}"""
        }
        val json = "[$entries]"

        val result = loader.parseJsonManifest(json, "max_group", "max_group.json")

        assertEquals(50, result.size)
    }

    // ---- Function name validation ----

    @Test
    fun `valid camelCase function name is accepted`() {
        val json = """
            [{"name": "drive_list", "description": "List files", "function": "listFiles"}]
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "drive", "drive.json")

        assertEquals(1, result.size)
        assertEquals("listFiles", result[0].second)
    }

    @Test
    fun `valid single-word function name is accepted`() {
        val json = """
            [{"name": "my_search", "description": "Search", "function": "search"}]
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "tools", "tools.json")

        assertEquals(1, result.size)
        assertEquals("search", result[0].second)
    }

    @Test
    fun `valid function name with underscore prefix is accepted`() {
        val json = """
            [{"name": "helper_tool", "description": "Helper", "function": "_privateHelper"}]
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "tools", "tools.json")

        assertEquals(1, result.size)
        assertEquals("_privateHelper", result[0].second)
    }

    @Test
    fun `invalid function name with path traversal is rejected - entry skipped`() {
        val json = """
            [
              {"name": "safe_tool", "description": "Safe", "function": "safeFn"},
              {"name": "bad_tool", "description": "Bad", "function": "../inject"},
              {"name": "another_safe", "description": "Also safe", "function": "alsoSafe"}
            ]
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "tools", "tools.json")

        assertEquals(2, result.size, "Entry with invalid function name should be skipped")
        assertEquals("safe_tool", result[0].first.name)
        assertEquals("another_safe", result[1].first.name)
    }

    @Test
    fun `empty function name is rejected - entry skipped`() {
        val json = """
            [
              {"name": "good_tool", "description": "Good", "function": "goodFn"},
              {"name": "bad_tool", "description": "Bad", "function": ""}
            ]
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "tools", "tools.json")

        assertEquals(1, result.size, "Entry with empty function name should be skipped")
        assertEquals("good_tool", result[0].first.name)
    }

    @Test
    fun `function name with spaces is rejected - entry skipped`() {
        val json = """
            [
              {"name": "bad_tool", "description": "Bad", "function": "bad function"},
              {"name": "good_tool", "description": "Good", "function": "goodFn"}
            ]
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "tools", "tools.json")

        assertEquals(1, result.size)
        assertEquals("good_tool", result[0].first.name)
    }

    // ---- JsTool functionName passthrough to engine ----

    @Test
    fun `JsTool with functionName passes it through to executeFromSource`() = runTest {
        val jsSource = "async function readFile(params) { return 'content'; }"
        val expectedResult = ToolResult.success("content")

        coEvery {
            engine.executeFromSource(
                jsSource = jsSource,
                toolName = "drive_read",
                functionName = "readFile",
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        } returns expectedResult

        val tool = JsTool(
            definition = makeDefinition("drive_read"),
            jsSource = jsSource,
            functionName = "readFile",
            jsExecutionEngine = engine,
            envVarStore = envVarStore
        )

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        coVerify(exactly = 1) {
            engine.executeFromSource(
                jsSource = jsSource,
                toolName = "drive_read",
                functionName = "readFile",
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        }
    }

    @Test
    fun `JsTool with null functionName passes null through to executeFromSource - backward compat`() = runTest {
        val jsSource = "function execute(params) { return 'ok'; }"
        val expectedResult = ToolResult.success("ok")

        coEvery {
            engine.executeFromSource(
                jsSource = jsSource,
                toolName = "legacy_tool",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        } returns expectedResult

        val tool = JsTool(
            definition = makeDefinition("legacy_tool"),
            jsSource = jsSource,
            // functionName not specified -- defaults to null
            jsExecutionEngine = engine,
            envVarStore = envVarStore
        )

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        coVerify(exactly = 1) {
            engine.executeFromSource(
                jsSource = jsSource,
                toolName = "legacy_tool",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        }
    }

    @Test
    fun `JsTool with functionName passes it through to execute for file-based tools`() = runTest {
        val jsFilePath = "/path/to/google_drive.js"
        val expectedResult = ToolResult.success("file list")

        coEvery {
            engine.execute(
                jsFilePath = jsFilePath,
                toolName = "gdrive_list",
                functionName = "listFiles",
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        } returns expectedResult

        val tool = JsTool(
            definition = makeDefinition("gdrive_list"),
            jsFilePath = jsFilePath,
            functionName = "listFiles",
            jsExecutionEngine = engine,
            envVarStore = envVarStore
        )

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        coVerify(exactly = 1) {
            engine.execute(
                jsFilePath = jsFilePath,
                toolName = "gdrive_list",
                functionName = "listFiles",
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        }
    }

    // ---- Single-tool backward compatibility ----

    @Test
    fun `single-tool JSON object still loads correctly with name matching filename`() {
        val json = """
            {
              "name": "weather",
              "description": "Get weather",
              "timeoutSeconds": 15
            }
        """.trimIndent()

        val result = loader.parseJsonManifest(json, "weather", "weather.json")

        assertEquals(1, result.size)
        assertEquals("weather", result[0].first.name)
        assertEquals("Get weather", result[0].first.description)
        assertEquals(15, result[0].first.timeoutSeconds)
        assertNull(result[0].second, "Single-tool entry should have functionName=null")
    }

    @Test
    fun `single-tool JSON object throws when name does not match filename`() {
        val json = """
            {"name": "wrong_name", "description": "Mismatch"}
        """.trimIndent()

        assertThrows<IllegalArgumentException> {
            loader.parseJsonManifest(json, "correct_name", "correct_name.json")
        }
    }

    @Test
    fun `group entries are not subject to filename-name matching`() {
        val json = """
            [
              {"name": "alpha", "description": "Alpha tool", "function": "doAlpha"},
              {"name": "beta", "description": "Beta tool", "function": "doBeta"}
            ]
        """.trimIndent()

        // baseName is "bundle" but entry names are "alpha" and "beta" -- this should be fine
        val result = loader.parseJsonManifest(json, "bundle", "bundle.json")

        assertEquals(2, result.size)
        assertEquals("alpha", result[0].first.name)
        assertEquals("beta", result[1].first.name)
    }
}
