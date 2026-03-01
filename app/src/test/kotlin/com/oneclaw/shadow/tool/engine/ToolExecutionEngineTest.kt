package com.oneclaw.shadow.tool.engine

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherRule::class)
class ToolExecutionEngineTest {

    private lateinit var registry: ToolRegistry
    private lateinit var permissionChecker: PermissionChecker
    private lateinit var enabledStateStore: ToolEnabledStateStore
    private lateinit var engine: ToolExecutionEngine

    private val allTools = listOf("test_tool", "no_permission_tool")

    private fun makeSuccessTool(name: String): Tool = object : Tool {
        override val definition = ToolDefinition(
            name = name,
            description = "Test tool",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "input" to ToolParameter(type = "string", description = "input")
                ),
                required = listOf("input")
            ),
            requiredPermissions = emptyList(),
            timeoutSeconds = 5
        )
        override suspend fun execute(parameters: Map<String, Any?>) =
            ToolResult.success("result:${parameters["input"]}")
    }

    private fun makePermissionTool(name: String, permission: String): Tool = object : Tool {
        override val definition = ToolDefinition(
            name = name,
            description = "Permission-requiring tool",
            parametersSchema = ToolParametersSchema(properties = emptyMap()),
            requiredPermissions = listOf(permission),
            timeoutSeconds = 5
        )
        override suspend fun execute(parameters: Map<String, Any?>) = ToolResult.success("done")
    }

    @BeforeEach
    fun setup() {
        registry = ToolRegistry()
        permissionChecker = mockk()
        enabledStateStore = mockk {
            every { isToolEffectivelyEnabled(any(), any()) } returns true
        }
        engine = ToolExecutionEngine(registry, permissionChecker, enabledStateStore)
    }

    @Test
    fun `executeTool returns error when tool not found`() = runTest {
        val result = engine.executeTool("nonexistent", emptyMap(), allTools)

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("tool_not_found", result.errorType)
    }

    @Test
    fun `executeTool returns error when tool not in available set`() = runTest {
        registry.register(makeSuccessTool("test_tool"))

        val result = engine.executeTool("test_tool", mapOf("input" to "hi"), emptyList())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("tool_not_available", result.errorType)
    }

    @Test
    fun `executeTool returns error when required parameter missing`() = runTest {
        registry.register(makeSuccessTool("test_tool"))
        every { permissionChecker.getMissingPermissions(emptyList()) } returns emptyList()

        val result = engine.executeTool("test_tool", emptyMap(), allTools)

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("input"))
    }

    @Test
    fun `executeTool returns error on wrong parameter type`() = runTest {
        registry.register(makeSuccessTool("test_tool"))
        every { permissionChecker.getMissingPermissions(emptyList()) } returns emptyList()

        val result = engine.executeTool("test_tool", mapOf("input" to 42), allTools)

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `executeTool succeeds when parameters are valid`() = runTest {
        registry.register(makeSuccessTool("test_tool"))
        every { permissionChecker.getMissingPermissions(emptyList()) } returns emptyList()

        val result = engine.executeTool("test_tool", mapOf("input" to "hello"), allTools)

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("result:hello", result.result)
    }

    @Test
    fun `executeTool returns permission_denied when permissions are denied`() = runTest {
        val tool = makePermissionTool("no_permission_tool", "android.permission.READ_EXTERNAL_STORAGE")
        registry.register(tool)
        every {
            permissionChecker.getMissingPermissions(listOf("android.permission.READ_EXTERNAL_STORAGE"))
        } returns listOf("android.permission.READ_EXTERNAL_STORAGE")
        coEvery {
            permissionChecker.requestPermissions(listOf("android.permission.READ_EXTERNAL_STORAGE"))
        } returns false

        val result = engine.executeTool("no_permission_tool", emptyMap(), allTools)

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("permission_denied", result.errorType)
    }

    @Test
    fun `executeTool proceeds when permissions are granted`() = runTest {
        val tool = makePermissionTool("no_permission_tool", "android.permission.READ_EXTERNAL_STORAGE")
        registry.register(tool)
        every {
            permissionChecker.getMissingPermissions(listOf("android.permission.READ_EXTERNAL_STORAGE"))
        } returns listOf("android.permission.READ_EXTERNAL_STORAGE")
        coEvery {
            permissionChecker.requestPermissions(listOf("android.permission.READ_EXTERNAL_STORAGE"))
        } returns true

        val result = engine.executeTool("no_permission_tool", emptyMap(), allTools)

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }

    @Test
    fun `executeToolTimed returns result and non-negative duration`() = runTest {
        registry.register(makeSuccessTool("test_tool"))
        every { permissionChecker.getMissingPermissions(emptyList()) } returns emptyList()

        val (result, duration) = engine.executeToolTimed(
            "test_tool", mapOf("input" to "hi"), allTools
        )

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(duration >= 0)
    }

    @Test
    fun `executeToolsParallel returns results for all tool calls`() = runTest {
        registry.register(makeSuccessTool("test_tool"))
        every { permissionChecker.getMissingPermissions(emptyList()) } returns emptyList()

        val calls = listOf(
            ToolCallRequest("id1", "test_tool", mapOf("input" to "a")),
            ToolCallRequest("id2", "test_tool", mapOf("input" to "b"))
        )
        val responses = engine.executeToolsParallel(calls, allTools)

        assertEquals(2, responses.size)
        assertEquals("id1", responses[0].toolCallId)
        assertEquals("id2", responses[1].toolCallId)
        assertEquals(ToolResultStatus.SUCCESS, responses[0].result.status)
        assertEquals(ToolResultStatus.SUCCESS, responses[1].result.status)
    }

    @Test
    fun `enum validation rejects value not in enum list`() = runTest {
        val enumTool = object : Tool {
            override val definition = ToolDefinition(
                name = "test_tool",
                description = "tool with enum",
                parametersSchema = ToolParametersSchema(
                    properties = mapOf(
                        "format" to ToolParameter(
                            type = "string",
                            description = "format",
                            enum = listOf("json", "xml")
                        )
                    ),
                    required = listOf("format")
                ),
                requiredPermissions = emptyList(),
                timeoutSeconds = 5
            )
            override suspend fun execute(parameters: Map<String, Any?>) = ToolResult.success("ok")
        }
        registry.register(enumTool)
        every { permissionChecker.getMissingPermissions(emptyList()) } returns emptyList()

        val result = engine.executeTool("test_tool", mapOf("format" to "csv"), allTools)

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("json"))
    }
}
