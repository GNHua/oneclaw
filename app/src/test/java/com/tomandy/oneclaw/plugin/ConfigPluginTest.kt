package com.tomandy.oneclaw.plugin

import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigPluginTest {

    private var capturedValue: String? = null

    private fun entry(
        key: String,
        type: ConfigType,
        currentValue: String = "current",
        customHandler: (suspend (String) -> ToolResult)? = null
    ) = ConfigEntry(
        key = key,
        displayName = key.replace("_", " ").replaceFirstChar { it.uppercase() },
        description = "Description for $key",
        type = type,
        getter = { currentValue },
        setter = { capturedValue = it },
        customHandler = customHandler
    )

    private fun registry(vararg entries: ConfigEntry): ConfigRegistry {
        val contributor = object : ConfigContributor {
            override fun contribute() = entries.toList()
        }
        return ConfigRegistry(listOf(contributor))
    }

    private fun plugin(vararg entries: ConfigEntry) = ConfigPlugin(registry(*entries))

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    @Test
    fun `execute returns Failure for unknown tool name`() = runTest {
        val result = plugin().execute("unknown_tool", buildJsonObject {})
        assertTrue(result is ToolResult.Failure)
    }

    @Test
    fun `set_app_config fails when no settings provided`() = runTest {
        val result = plugin().execute("set_app_config", buildJsonObject {})
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("No settings"))
    }

    @Test
    fun `set_app_config ignores keys prefixed with underscore`() = runTest {
        val e = entry("name", ConfigType.StringType)
        val result = plugin(e).execute("set_app_config", args("_conversation_id" to "abc", "name" to "new"))
        assertTrue(result is ToolResult.Success)
        assertEquals("new", capturedValue)
    }

    @Test
    fun `set_app_config reports error for unknown setting key`() = runTest {
        val result = plugin().execute("set_app_config", args("nonexistent" to "value"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Unknown setting"))
    }

    // --- StringType ---

    @Test
    fun `set_app_config accepts any string for StringType`() = runTest {
        val e = entry("name", ConfigType.StringType)
        val result = plugin(e).execute("set_app_config", args("name" to "hello world"))
        assertTrue(result is ToolResult.Success)
        assertEquals("hello world", capturedValue)
    }

    // --- IntType ---

    @Test
    fun `set_app_config accepts valid integer within range`() = runTest {
        val e = entry("iterations", ConfigType.IntType(min = 1, max = 500))
        val result = plugin(e).execute("set_app_config", args("iterations" to "100"))
        assertTrue(result is ToolResult.Success)
        assertEquals("100", capturedValue)
    }

    @Test
    fun `set_app_config rejects non-integer for IntType`() = runTest {
        val e = entry("iterations", ConfigType.IntType(min = 1, max = 500))
        val result = plugin(e).execute("set_app_config", args("iterations" to "abc"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Expected an integer"))
    }

    @Test
    fun `set_app_config rejects integer below minimum`() = runTest {
        val e = entry("iterations", ConfigType.IntType(min = 1))
        val result = plugin(e).execute("set_app_config", args("iterations" to "0"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains(">= 1"))
    }

    @Test
    fun `set_app_config rejects integer above maximum`() = runTest {
        val e = entry("iterations", ConfigType.IntType(max = 500))
        val result = plugin(e).execute("set_app_config", args("iterations" to "501"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("<= 500"))
    }

    // --- BooleanType ---

    @Test
    fun `set_app_config accepts truthy values for BooleanType`() = runTest {
        val e = entry("enabled", ConfigType.BooleanType)
        for (truthy in listOf("true", "1", "yes", "on")) {
            val result = plugin(e).execute("set_app_config", args("enabled" to truthy))
            assertTrue("Failed for '$truthy'", result is ToolResult.Success)
        }
    }

    @Test
    fun `set_app_config accepts falsy values for BooleanType`() = runTest {
        val e = entry("enabled", ConfigType.BooleanType)
        for (falsy in listOf("false", "0", "no", "off")) {
            val result = plugin(e).execute("set_app_config", args("enabled" to falsy))
            assertTrue("Failed for '$falsy'", result is ToolResult.Success)
        }
    }

    @Test
    fun `set_app_config rejects invalid boolean value`() = runTest {
        val e = entry("enabled", ConfigType.BooleanType)
        val result = plugin(e).execute("set_app_config", args("enabled" to "maybe"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Expected true/false"))
    }

    // --- EnumType ---

    @Test
    fun `set_app_config accepts valid enum value`() = runTest {
        val e = entry("priority", ConfigType.EnumType(listOf("low", "medium", "high")))
        val result = plugin(e).execute("set_app_config", args("priority" to "medium"))
        assertTrue(result is ToolResult.Success)
        assertEquals("medium", capturedValue)
    }

    @Test
    fun `set_app_config rejects invalid enum value`() = runTest {
        val e = entry("priority", ConfigType.EnumType(listOf("low", "medium", "high")))
        val result = plugin(e).execute("set_app_config", args("priority" to "extreme"))
        assertTrue(result is ToolResult.Failure)
        val error = (result as ToolResult.Failure).error
        assertTrue(error.contains("low"))
        assertTrue(error.contains("medium"))
        assertTrue(error.contains("high"))
    }

    // --- Batch ---

    @Test
    fun `set_app_config processes multiple settings in one call`() = runTest {
        val e1 = entry("name", ConfigType.StringType)
        var secondValue: String? = null
        val e2 = ConfigEntry(
            key = "count", displayName = "Count", description = "desc",
            type = ConfigType.IntType(min = 0), getter = { "0" },
            setter = { secondValue = it }
        )
        val result = plugin(e1, e2).execute("set_app_config", args("name" to "foo", "count" to "50"))
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("Updated:"))
    }

    @Test
    fun `set_app_config returns Success with mixed results when some succeed and some fail`() = runTest {
        val e = entry("name", ConfigType.StringType)
        val result = plugin(e).execute("set_app_config", args("name" to "ok", "bad_key" to "x"))
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("Updated:"))
        assertTrue(output.contains("Errors:"))
    }

    @Test
    fun `set_app_config returns Failure when all settings fail`() = runTest {
        val result = plugin().execute("set_app_config", args("bad1" to "x", "bad2" to "y"))
        assertTrue(result is ToolResult.Failure)
    }

    // --- Custom handler ---

    @Test
    fun `set_app_config delegates to customHandler when present`() = runTest {
        var handlerCalled = false
        val e = entry("special", ConfigType.StringType, customHandler = { value ->
            handlerCalled = true
            ToolResult.Success("Custom: $value")
        })
        val result = plugin(e).execute("set_app_config", args("special" to "test"))
        assertTrue(handlerCalled)
        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("Custom: test"))
    }

    // --- get_app_config ---

    @Test
    fun `get_app_config returns formatted config with current values`() = runTest {
        val e = entry("max_iterations", ConfigType.IntType(min = 1, max = 500), currentValue = "100")
        val result = plugin(e).execute("get_app_config", buildJsonObject {})
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("max_iterations"))
        assertTrue(output.contains("100"))
        assertTrue(output.contains("integer"))
    }

    @Test
    fun `get_app_config returns empty message for empty registry`() = runTest {
        val result = plugin().execute("get_app_config", buildJsonObject {})
        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("No configuration settings"))
    }
}
