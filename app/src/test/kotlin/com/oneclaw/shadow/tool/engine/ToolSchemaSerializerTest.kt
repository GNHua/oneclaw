package com.oneclaw.shadow.tool.engine

import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolSchemaSerializerTest {

    private val schema = ToolParametersSchema(
        properties = mapOf(
            "path" to ToolParameter(type = "string", description = "The file path"),
            "encoding" to ToolParameter(
                type = "string",
                description = "File encoding",
                enum = listOf("UTF-8", "UTF-16"),
                default = "UTF-8"
            )
        ),
        required = listOf("path")
    )

    @Test
    fun `toJsonSchemaMap produces correct top-level structure`() {
        val result = ToolSchemaSerializer.toJsonSchemaMap(schema)

        assertEquals("object", result["type"])
        assertTrue(result.containsKey("properties"))
        assertEquals(listOf("path"), result["required"])
    }

    @Test
    fun `toJsonSchemaMap includes all properties`() {
        val result = ToolSchemaSerializer.toJsonSchemaMap(schema)

        @Suppress("UNCHECKED_CAST")
        val properties = result["properties"] as Map<String, Any>
        assertTrue(properties.containsKey("path"))
        assertTrue(properties.containsKey("encoding"))
    }

    @Test
    fun `toJsonSchemaMap includes enum values when present`() {
        val result = ToolSchemaSerializer.toJsonSchemaMap(schema)

        @Suppress("UNCHECKED_CAST")
        val properties = result["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val encodingProp = properties["encoding"] as Map<String, Any>

        assertEquals(listOf("UTF-8", "UTF-16"), encodingProp["enum"])
    }

    @Test
    fun `toJsonSchemaMap excludes enum key when null`() {
        val result = ToolSchemaSerializer.toJsonSchemaMap(schema)

        @Suppress("UNCHECKED_CAST")
        val properties = result["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val pathProp = properties["path"] as Map<String, Any>

        assertFalse(pathProp.containsKey("enum"))
    }

    @Test
    fun `toJsonSchemaMap omits required key when empty`() {
        val schemaNoRequired = ToolParametersSchema(
            properties = mapOf("x" to ToolParameter(type = "string", description = "desc")),
            required = emptyList()
        )
        val result = ToolSchemaSerializer.toJsonSchemaMap(schemaNoRequired)

        assertFalse(result.containsKey("required"))
    }

    @Test
    fun `toGeminiSchemaMap uses uppercase type names`() {
        val result = ToolSchemaSerializer.toGeminiSchemaMap(schema)

        assertEquals("OBJECT", result["type"])

        @Suppress("UNCHECKED_CAST")
        val properties = result["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val pathProp = properties["path"] as Map<String, Any>
        assertEquals("STRING", pathProp["type"])
    }

    @Test
    fun `toJsonSchemaMap includes items for array type with explicit items`() {
        val arraySchema = ToolParametersSchema(
            properties = mapOf(
                "ids" to ToolParameter(
                    type = "array",
                    description = "List of IDs",
                    items = ToolParameter(type = "string", description = "")
                )
            )
        )
        val result = ToolSchemaSerializer.toJsonSchemaMap(arraySchema)

        @Suppress("UNCHECKED_CAST")
        val properties = result["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val idsProp = properties["ids"] as Map<String, Any>

        assertEquals("array", idsProp["type"])
        @Suppress("UNCHECKED_CAST")
        val items = idsProp["items"] as Map<String, Any>
        assertEquals("string", items["type"])
    }

    @Test
    fun `toJsonSchemaMap defaults items to string when array type has no items`() {
        val arraySchema = ToolParametersSchema(
            properties = mapOf(
                "tags" to ToolParameter(type = "array", description = "Tags", items = null)
            )
        )
        val result = ToolSchemaSerializer.toJsonSchemaMap(arraySchema)

        @Suppress("UNCHECKED_CAST")
        val properties = result["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val tagsProp = properties["tags"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val items = tagsProp["items"] as Map<String, Any>
        assertEquals("string", items["type"])
    }

    @Test
    fun `toJsonSchemaMap does not include items for non-array types`() {
        val result = ToolSchemaSerializer.toJsonSchemaMap(schema)

        @Suppress("UNCHECKED_CAST")
        val properties = result["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val pathProp = properties["path"] as Map<String, Any>
        assertFalse(pathProp.containsKey("items"))
    }

    @Test
    fun `toGeminiSchemaMap includes items with uppercase type for array`() {
        val arraySchema = ToolParametersSchema(
            properties = mapOf(
                "message_ids" to ToolParameter(
                    type = "array",
                    description = "List of message IDs",
                    items = ToolParameter(type = "string", description = "")
                )
            )
        )
        val result = ToolSchemaSerializer.toGeminiSchemaMap(arraySchema)

        @Suppress("UNCHECKED_CAST")
        val properties = result["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val prop = properties["message_ids"] as Map<String, Any>

        assertEquals("ARRAY", prop["type"])
        @Suppress("UNCHECKED_CAST")
        val items = prop["items"] as Map<String, Any>
        assertEquals("STRING", items["type"])
    }

    @Test
    fun `toGeminiSchemaMap defaults items to STRING when array has no items`() {
        val arraySchema = ToolParametersSchema(
            properties = mapOf(
                "labels" to ToolParameter(type = "array", description = "Labels", items = null)
            )
        )
        val result = ToolSchemaSerializer.toGeminiSchemaMap(arraySchema)

        @Suppress("UNCHECKED_CAST")
        val properties = result["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val prop = properties["labels"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val items = prop["items"] as Map<String, Any>
        assertEquals("STRING", items["type"])
    }
}
