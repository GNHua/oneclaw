package com.oneclaw.shadow.data.remote.adapter

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FormatToolDefinitionsTest {

    private val tool = ToolDefinition(
        name = "read_file",
        description = "Read the contents of a file",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "path" to ToolParameter(type = "string", description = "The file path")
            ),
            required = listOf("path")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    @Test
    fun `OpenAI format wraps in function type`() {
        val adapter = OpenAiAdapter(OkHttpClient())
        @Suppress("UNCHECKED_CAST")
        val result = adapter.formatToolDefinitions(listOf(tool)) as List<Map<String, Any>>

        assertEquals(1, result.size)
        assertEquals("function", result[0]["type"])

        @Suppress("UNCHECKED_CAST")
        val function = result[0]["function"] as Map<String, Any>
        assertEquals("read_file", function["name"])
        assertEquals("Read the contents of a file", function["description"])
        assertTrue(function.containsKey("parameters"))
    }

    @Test
    fun `Anthropic format uses input_schema key`() {
        val adapter = AnthropicAdapter(OkHttpClient())
        @Suppress("UNCHECKED_CAST")
        val result = adapter.formatToolDefinitions(listOf(tool)) as List<Map<String, Any>>

        assertEquals(1, result.size)
        val item = result[0]
        assertEquals("read_file", item["name"])
        assertEquals("Read the contents of a file", item["description"])
        assertTrue(item.containsKey("input_schema"))
        // No "type" key at root level
        assertTrue(!item.containsKey("type"))
    }

    @Test
    fun `Gemini format wraps in function_declarations`() {
        val adapter = GeminiAdapter(OkHttpClient())
        @Suppress("UNCHECKED_CAST")
        val result = adapter.formatToolDefinitions(listOf(tool)) as Map<String, Any>

        assertTrue(result.containsKey("function_declarations"))
        @Suppress("UNCHECKED_CAST")
        val declarations = result["function_declarations"] as List<Map<String, Any>>
        assertEquals(1, declarations.size)

        val decl = declarations[0]
        assertEquals("read_file", decl["name"])

        @Suppress("UNCHECKED_CAST")
        val parameters = decl["parameters"] as Map<String, Any>
        // Gemini uses uppercase type
        assertEquals("OBJECT", parameters["type"])
    }

    @Test
    fun `OpenAI format with empty tools list returns empty list`() {
        val adapter = OpenAiAdapter(OkHttpClient())
        @Suppress("UNCHECKED_CAST")
        val result = adapter.formatToolDefinitions(emptyList()) as List<*>

        assertTrue(result.isEmpty())
    }

    @Test
    fun `Gemini format with empty tools list has empty function_declarations`() {
        val adapter = GeminiAdapter(OkHttpClient())
        @Suppress("UNCHECKED_CAST")
        val result = adapter.formatToolDefinitions(emptyList()) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val declarations = result["function_declarations"] as List<*>

        assertTrue(declarations.isEmpty())
    }
}
