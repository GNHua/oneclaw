package com.tomandy.oneclaw.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginMetadataTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleMetadata() = PluginMetadata(
        id = "test_plugin",
        name = "Test Plugin",
        version = "1.0.0",
        description = "A test plugin",
        author = "Test Author",
        entryPoint = "TestPlugin",
        tools = listOf(sampleToolDef()),
        permissions = listOf("INTERNET"),
        dependencies = listOf("other_plugin"),
        category = "gmail"
    )

    private fun sampleToolDef() = ToolDefinition(
        name = "test_tool",
        description = "A test tool",
        parameters = buildJsonObject {
            put("type", "object")
        }
    )

    @Test
    fun `serialization round-trip preserves all fields`() {
        val original = sampleMetadata()
        val serialized = json.encodeToString(PluginMetadata.serializer(), original)
        val deserialized = json.decodeFromString(PluginMetadata.serializer(), serialized)

        assertEquals(original.id, deserialized.id)
        assertEquals(original.name, deserialized.name)
        assertEquals(original.version, deserialized.version)
        assertEquals(original.description, deserialized.description)
        assertEquals(original.author, deserialized.author)
        assertEquals(original.entryPoint, deserialized.entryPoint)
        assertEquals(original.permissions, deserialized.permissions)
        assertEquals(original.dependencies, deserialized.dependencies)
        assertEquals(original.category, deserialized.category)
        assertEquals(original.tools.size, deserialized.tools.size)
        assertEquals(original.tools[0].name, deserialized.tools[0].name)
    }

    @Test
    fun `deserialization applies default values for optional fields`() {
        val minimal = """
            {
                "id": "min",
                "name": "Minimal",
                "version": "0.1.0",
                "description": "Minimal plugin",
                "author": "Nobody",
                "entryPoint": "MinPlugin",
                "tools": []
            }
        """.trimIndent()

        val result = json.decodeFromString(PluginMetadata.serializer(), minimal)

        assertEquals(emptyList<String>(), result.permissions)
        assertEquals(emptyList<String>(), result.dependencies)
        assertEquals("core", result.category)
    }

    @Test
    fun `ToolDefinition serialization round-trip`() {
        val original = sampleToolDef()
        val serialized = json.encodeToString(ToolDefinition.serializer(), original)
        val deserialized = json.decodeFromString(ToolDefinition.serializer(), serialized)

        assertEquals(original.name, deserialized.name)
        assertEquals(original.description, deserialized.description)
        assertEquals(original.parameters, deserialized.parameters)
        assertEquals(0L, deserialized.timeoutMs) // @Transient default
    }
}
