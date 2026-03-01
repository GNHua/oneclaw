package com.oneclaw.shadow.tool.js

import android.app.Application
import android.content.Context
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.model.ToolSourceInfo
import com.oneclaw.shadow.core.model.ToolSourceType
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.tool.engine.Tool
import com.oneclaw.shadow.tool.engine.ToolRegistry
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UserToolManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var jsExecutionEngine: JsExecutionEngine
    private lateinit var envVarStore: EnvironmentVariableStore
    private lateinit var manager: UserToolManager

    private val simpleSchema = ToolParametersSchema(
        properties = mapOf(
            "input" to ToolParameter(type = "string", description = "Input text")
        ),
        required = listOf("input")
    )

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        jsExecutionEngine = mockk()
        envVarStore = mockk()
        toolRegistry = ToolRegistry()

        manager = UserToolManager(
            context = context,
            toolRegistryProvider = { toolRegistry },
            jsExecutionEngine = jsExecutionEngine,
            envVarStore = envVarStore
        )
    }

    // --- Create tests ---

    @Test
    fun `create success writes files and registers tool`() {
        val result = manager.create(
            name = "my_tool",
            description = "A test tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return params.input; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )

        assertTrue("Expected success but got: $result", result is AppResult.Success)
        assertTrue((result as AppResult.Success).data.contains("my_tool"))

        // Check files were written
        val toolsDir = File(context.filesDir, "tools")
        assertTrue("JSON manifest should exist", File(toolsDir, "my_tool.json").exists())
        assertTrue("JS file should exist", File(toolsDir, "my_tool.js").exists())

        // Check tool is registered
        assertTrue(toolRegistry.hasTool("my_tool"))
    }

    @Test
    fun `create invalidName rejects name starting with digit`() {
        val result = manager.create(
            name = "1tool",
            description = "A test tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'ok'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("Invalid tool name"))
    }

    @Test
    fun `create invalidName rejects single char name`() {
        val result = manager.create(
            name = "a",
            description = "A test tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'ok'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )
        assertTrue(result is AppResult.Error)
    }

    @Test
    fun `create invalidName rejects uppercase name`() {
        val result = manager.create(
            name = "myTool",
            description = "A test tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'ok'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )
        assertTrue(result is AppResult.Error)
    }

    @Test
    fun `create invalidName rejects name with spaces`() {
        val result = manager.create(
            name = "my tool",
            description = "A test tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'ok'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )
        assertTrue(result is AppResult.Error)
    }

    @Test
    fun `create validTwoCharName succeeds`() {
        val result = manager.create(
            name = "ab",
            description = "A test tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'ok'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )
        assertTrue("Two-char name should be valid. Got: $result", result is AppResult.Success)
    }

    @Test
    fun `create duplicateName rejects names already in registry`() {
        // Create tool first time - should succeed
        val result1 = manager.create(
            name = "my_tool",
            description = "A test tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'ok'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )
        assertTrue(result1 is AppResult.Success)

        // Attempt to create again with same name - should fail
        val result2 = manager.create(
            name = "my_tool",
            description = "Another test tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'ok2'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )
        assertTrue(result2 is AppResult.Error)
        assertTrue((result2 as AppResult.Error).message.contains("already exists"))
    }

    @Test
    fun `create emptyJsCode rejects blank JS code`() {
        val result = manager.create(
            name = "my_tool",
            description = "A test tool",
            parametersSchema = simpleSchema,
            jsCode = "   ",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("empty"))
    }

    @Test
    fun `create cleansUpOnFailure does not leave files for duplicate name`() {
        // Register a conflicting tool to force failure
        val fakeTool = object : Tool {
            override val definition = ToolDefinition(
                name = "collision_tool",
                description = "Fake",
                parametersSchema = ToolParametersSchema(properties = emptyMap(), required = emptyList()),
                requiredPermissions = emptyList(),
                timeoutSeconds = 5
            )
            override suspend fun execute(parameters: Map<String, Any?>) = ToolResult.success("ok")
        }
        toolRegistry.register(fakeTool)

        // Try to create with same name - duplicate check happens before file writes
        val result = manager.create(
            name = "collision_tool",
            description = "A test tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'ok'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )

        assertTrue(result is AppResult.Error)

        // Verify no partial files were created
        val toolsDir = File(context.filesDir, "tools")
        assertFalse(File(toolsDir, "collision_tool.json").exists())
        assertFalse(File(toolsDir, "collision_tool.js").exists())
    }

    // --- List tests ---

    @Test
    fun `listUserTools empty returns empty list when no user tools`() {
        val tools = manager.listUserTools()
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `listUserTools filtersBuiltIn does not include built-in tools`() {
        // Register a built-in tool (BUILTIN source type)
        val builtinTool = object : Tool {
            override val definition = ToolDefinition(
                name = "builtin_tool",
                description = "Built-in",
                parametersSchema = ToolParametersSchema(properties = emptyMap(), required = emptyList()),
                requiredPermissions = emptyList(),
                timeoutSeconds = 5
            )
            override suspend fun execute(parameters: Map<String, Any?>) = ToolResult.success("ok")
        }
        toolRegistry.register(builtinTool, ToolSourceInfo.BUILTIN)

        val tools = manager.listUserTools()
        assertTrue("Built-in tools should not appear in user tool list", tools.isEmpty())
    }

    @Test
    fun `listUserTools returnsUserTools shows user-created tools`() {
        manager.create(
            name = "tool_alpha",
            description = "Alpha tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'alpha'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )
        manager.create(
            name = "tool_beta",
            description = "Beta tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'beta'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )

        val tools = manager.listUserTools()
        assertEquals(2, tools.size)
        val names = tools.map { it.name }
        assertTrue(names.contains("tool_alpha"))
        assertTrue(names.contains("tool_beta"))
    }

    @Test
    fun `listUserTools returns results sorted by name`() {
        manager.create(
            name = "zz_tool",
            description = "Z tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'z'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )
        manager.create(
            name = "aa_tool",
            description = "A tool",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'a'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )

        val tools = manager.listUserTools()
        assertEquals(2, tools.size)
        assertEquals("aa_tool", tools[0].name)
        assertEquals("zz_tool", tools[1].name)
    }

    // --- Update tests ---

    @Test
    fun `update success updates description and re-registers tool`() {
        // First create
        manager.create(
            name = "update_me",
            description = "Original description",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'original'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )

        // Now update description
        val result = manager.update(
            name = "update_me",
            description = "Updated description",
            parametersSchema = null,
            jsCode = null,
            requiredPermissions = null,
            timeoutSeconds = null
        )

        assertTrue("Update should succeed. Got: $result", result is AppResult.Success)
        val tool = toolRegistry.getTool("update_me")
        assertEquals("Updated description", tool?.definition?.description)
    }

    @Test
    fun `update toolNotFound returns error for nonexistent tool`() {
        val result = manager.update(
            name = "nonexistent_tool",
            description = "New description",
            parametersSchema = null,
            jsCode = null,
            requiredPermissions = null,
            timeoutSeconds = null
        )

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("not found"))
    }

    @Test
    fun `update protectedTool returns error for built-in tool`() {
        // Register a built-in Kotlin tool
        val builtinTool = object : Tool {
            override val definition = ToolDefinition(
                name = "protected_builtin",
                description = "Built-in",
                parametersSchema = ToolParametersSchema(properties = emptyMap(), required = emptyList()),
                requiredPermissions = emptyList(),
                timeoutSeconds = 5
            )
            override suspend fun execute(parameters: Map<String, Any?>) = ToolResult.success("ok")
        }
        toolRegistry.register(builtinTool, ToolSourceInfo.BUILTIN)

        val result = manager.update(
            name = "protected_builtin",
            description = "Hacked",
            parametersSchema = null,
            jsCode = null,
            requiredPermissions = null,
            timeoutSeconds = null
        )

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("not a user-created tool"))
    }

    @Test
    fun `update partialUpdate only updates fields that are non-null`() {
        // Create tool
        manager.create(
            name = "partial_tool",
            description = "Original",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'original'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )

        // Update only JS code
        val result = manager.update(
            name = "partial_tool",
            description = null, // Keep original
            parametersSchema = null, // Keep original
            jsCode = "function execute(params) { return 'updated'; }",
            requiredPermissions = null,
            timeoutSeconds = null
        )

        assertTrue(result is AppResult.Success)
        val tool = toolRegistry.getTool("partial_tool")
        // Description should remain unchanged
        assertEquals("Original", tool?.definition?.description)
        // JS file should be updated
        val jsFile = File(File(context.filesDir, "tools"), "partial_tool.js")
        assertTrue(jsFile.readText().contains("updated"))
    }

    // --- Delete tests ---

    @Test
    fun `delete success unregisters tool and deletes files`() {
        // Create first
        manager.create(
            name = "delete_me",
            description = "To be deleted",
            parametersSchema = simpleSchema,
            jsCode = "function execute(params) { return 'bye'; }",
            requiredPermissions = emptyList(),
            timeoutSeconds = 30
        )

        val result = manager.delete("delete_me")

        assertTrue("Delete should succeed. Got: $result", result is AppResult.Success)
        assertTrue((result as AppResult.Success).data.contains("delete_me"))

        // Tool should be unregistered
        assertFalse(toolRegistry.hasTool("delete_me"))

        // Files should be deleted
        val toolsDir = File(context.filesDir, "tools")
        assertFalse(File(toolsDir, "delete_me.json").exists())
        assertFalse(File(toolsDir, "delete_me.js").exists())
    }

    @Test
    fun `delete toolNotFound returns error for nonexistent tool`() {
        val result = manager.delete("nonexistent")

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("not found"))
    }

    @Test
    fun `delete protectedTool returns error for built-in tool`() {
        // Register a built-in Kotlin tool
        val builtinTool = object : Tool {
            override val definition = ToolDefinition(
                name = "protected_delete",
                description = "Built-in",
                parametersSchema = ToolParametersSchema(properties = emptyMap(), required = emptyList()),
                requiredPermissions = emptyList(),
                timeoutSeconds = 5
            )
            override suspend fun execute(parameters: Map<String, Any?>) = ToolResult.success("ok")
        }
        toolRegistry.register(builtinTool, ToolSourceInfo.BUILTIN)

        val result = manager.delete("protected_delete")

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("not a user-created tool"))

        // Tool should still be registered
        assertTrue(toolRegistry.hasTool("protected_delete"))
    }
}
