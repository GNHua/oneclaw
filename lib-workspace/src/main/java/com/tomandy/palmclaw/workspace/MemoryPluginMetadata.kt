package com.tomandy.palmclaw.workspace

import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
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
            author = "PalmClaw Team",
            entryPoint = "MemoryPlugin",
            tools = listOf(
                searchMemoryTool()
            )
        )
    }

    private fun searchMemoryTool() = ToolDefinition(
        name = "search_memory",
        description = """Search across all memory files for a keyword or phrase.
            |
            |Searches both the long-term memory (MEMORY.md) and all daily memory
            |files (memory/*.md) for matching content. Returns snippets with
            |file paths and line numbers.
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
            }
            putJsonArray("required") {
                add(JsonPrimitive("query"))
            }
        }
    )
}
