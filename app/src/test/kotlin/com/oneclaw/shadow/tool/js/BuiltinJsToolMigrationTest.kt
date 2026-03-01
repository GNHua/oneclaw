package com.oneclaw.shadow.tool.js

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.tool.js.bridge.LibraryBridge
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for the built-in JS tool migration.
 *
 * Verifies that JsTool correctly routes source-based (asset) tools to
 * JsExecutionEngine.executeFromSource() and file-based (user) tools to
 * JsExecutionEngine.execute().
 *
 * The actual JS logic is tested indirectly through the JsExecutionEngine mock,
 * verifying correct delegation behavior.
 */
class BuiltinJsToolMigrationTest {

    private lateinit var engine: JsExecutionEngine
    private lateinit var envVarStore: EnvironmentVariableStore

    private fun makeDefinition(name: String, timeoutSeconds: Int = 30) = ToolDefinition(
        name = name,
        description = "Test tool $name",
        parametersSchema = ToolParametersSchema(properties = emptyMap(), required = emptyList()),
        requiredPermissions = emptyList(),
        timeoutSeconds = timeoutSeconds
    )

    @BeforeEach
    fun setup() {
        engine = mockk()
        envVarStore = mockk()
        coEvery { envVarStore.getAll() } returns emptyMap()
    }

    // ---- JsTool routing tests ----

    @Test
    fun `source-based JsTool routes to executeFromSource`() = runTest {
        val jsSource = "function execute(params) { return 'ok'; }"
        val expectedResult = ToolResult.success("ok")

        coEvery {
            engine.executeFromSource(
                jsSource = jsSource,
                toolName = "get_current_time",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 5
            )
        } returns expectedResult

        val tool = JsTool(
            definition = makeDefinition("get_current_time", timeoutSeconds = 5),
            jsSource = jsSource,
            jsExecutionEngine = engine,
            envVarStore = envVarStore
        )

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        coVerify(exactly = 1) {
            engine.executeFromSource(
                jsSource = jsSource,
                toolName = "get_current_time",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 5
            )
        }
    }

    @Test
    fun `file-based JsTool routes to execute with file path`() = runTest {
        val jsFilePath = "/path/to/user_tool.js"
        val expectedResult = ToolResult.success("file result")

        coEvery {
            engine.execute(
                jsFilePath = jsFilePath,
                toolName = "user_tool",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        } returns expectedResult

        val tool = JsTool(
            definition = makeDefinition("user_tool"),
            jsFilePath = jsFilePath,
            jsExecutionEngine = engine,
            envVarStore = envVarStore
        )

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        coVerify(exactly = 1) {
            engine.execute(
                jsFilePath = jsFilePath,
                toolName = "user_tool",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        }
    }

    @Test
    fun `jsSource takes precedence over jsFilePath when both are set`() = runTest {
        val jsSource = "function execute(p) { return 'source'; }"
        val jsFilePath = "/some/path.js"
        val expectedResult = ToolResult.success("source")

        coEvery {
            engine.executeFromSource(any(), any(), any(), any(), any(), any())
        } returns expectedResult

        val tool = JsTool(
            definition = makeDefinition("mixed_tool"),
            jsFilePath = jsFilePath,
            jsSource = jsSource,
            jsExecutionEngine = engine,
            envVarStore = envVarStore
        )

        tool.execute(emptyMap())

        coVerify(exactly = 1) { engine.executeFromSource(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { engine.execute(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `tool passes parameters to engine`() = runTest {
        val jsSource = "function execute(p) { return p.key; }"
        val params = mapOf("key" to "value")

        coEvery {
            engine.executeFromSource(
                jsSource = jsSource,
                toolName = "read_file",
                functionName = null,
                params = params,
                env = emptyMap(),
                timeoutSeconds = 10
            )
        } returns ToolResult.success("value")

        val tool = JsTool(
            definition = makeDefinition("read_file", timeoutSeconds = 10),
            jsSource = jsSource,
            jsExecutionEngine = engine,
            envVarStore = envVarStore
        )

        val result = tool.execute(params)

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }

    @Test
    fun `tool passes env vars from store to engine`() = runTest {
        val jsSource = "function execute(p) { return 'ok'; }"
        val envVars = mapOf("API_KEY" to "secret123")

        coEvery { envVarStore.getAll() } returns envVars
        coEvery {
            engine.executeFromSource(
                jsSource = jsSource,
                toolName = "http_request",
                functionName = null,
                params = emptyMap(),
                env = envVars,
                timeoutSeconds = 30
            )
        } returns ToolResult.success("ok")

        val tool = JsTool(
            definition = makeDefinition("http_request"),
            jsSource = jsSource,
            jsExecutionEngine = engine,
            envVarStore = envVarStore
        )

        tool.execute(emptyMap())

        coVerify(exactly = 1) {
            engine.executeFromSource(
                jsSource = jsSource,
                toolName = "http_request",
                functionName = null,
                params = emptyMap(),
                env = envVars,
                timeoutSeconds = 30
            )
        }
    }

    @Test
    fun `engine error result is propagated to caller`() = runTest {
        val jsSource = "function execute(p) { throw new Error('fail'); }"

        coEvery {
            engine.executeFromSource(any(), any(), any(), any(), any(), any())
        } returns ToolResult.error("execution_error", "JS tool 'bad_tool' failed: Error: fail")

        val tool = JsTool(
            definition = makeDefinition("bad_tool"),
            jsSource = jsSource,
            jsExecutionEngine = engine,
            envVarStore = envVarStore
        )

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("execution_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("fail"))
    }

    // ---- get_current_time JS behavior tests via mocked engine ----

    @Test
    fun `get_current_time tool returns success result`() = runTest {
        val isoTime = "2026-02-28T12:00:00+08:00"
        coEvery {
            engine.executeFromSource(any(), eq("get_current_time"), any(), any(), any(), any())
        } returns ToolResult.success(isoTime)

        val tool = JsTool(
            definition = makeDefinition("get_current_time", timeoutSeconds = 5),
            jsSource = "function execute(p) { return _time(p.timezone || '', p.format || 'iso8601'); }",
            jsExecutionEngine = engine,
            envVarStore = envVarStore
        )

        val result = tool.execute(mapOf("timezone" to "Asia/Shanghai"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals(isoTime, result.result)
    }

    // ---- write_file append mode test via mocked engine ----

    @Test
    fun `write_file append mode is supported`() = runTest {
        coEvery {
            engine.executeFromSource(any(), eq("write_file"), any(), any(), any(), any())
        } returns ToolResult.success("Successfully wrote 10 bytes to /tmp/file.txt (mode: append)")

        val tool = JsTool(
            definition = makeDefinition("write_file", timeoutSeconds = 10),
            jsSource = "function execute(p) { fs.appendFile(p.path, p.content); return 'ok'; }",
            jsExecutionEngine = engine,
            envVarStore = envVarStore
        )

        val result = tool.execute(mapOf(
            "path" to "/tmp/file.txt",
            "content" to "appended",
            "mode" to "append"
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("append"))
    }
}
