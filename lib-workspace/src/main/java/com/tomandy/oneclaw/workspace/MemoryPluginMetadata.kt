package com.tomandy.oneclaw.workspace

import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object MemoryPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "memory",
            name = "Memory",
            version = "1.0.0",
            description = "Persistent memory across conversations",
            author = "OneClaw Team",
            entryPoint = "MemoryPlugin",
            tools = listOf(
                searchMemoryTool()
            )
        )
    }

    private fun searchMemoryTool() = ToolDefinition(
        name = "search_memory",
        description = """Search across all memory files using semantic similarity and keyword matching.
            |
            |Searches both the long-term memory (MEMORY.md) and all daily memory
            |files (memory/*.md). When a Gemini API key is configured, uses vector
            |embeddings for semantic search (e.g., "database choice" finds "PostgreSQL").
            |Falls back to keyword matching when embeddings are unavailable.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("The search term or phrase to find in memory")
                    )
                }
                putJsonObject("mode") {
                    put("type", JsonPrimitive("string"))
                    putJsonArray("enum") {
                        add(JsonPrimitive("auto"))
                        add(JsonPrimitive("keyword"))
                        add(JsonPrimitive("semantic"))
                    }
                    put(
                        "description",
                        JsonPrimitive(
                            "Search mode: auto (default, uses both semantic and keyword), " +
                                "keyword (exact substring match only), " +
                                "semantic (vector similarity only)"
                        )
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("query"))
            }
        }
    )
}
