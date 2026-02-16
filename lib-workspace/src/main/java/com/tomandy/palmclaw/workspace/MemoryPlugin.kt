package com.tomandy.palmclaw.workspace

import com.tomandy.palmclaw.engine.Plugin
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class MemoryPlugin : Plugin {

    private lateinit var workspaceRoot: File
    private lateinit var memoryDir: File

    override suspend fun onLoad(context: PluginContext) {
        workspaceRoot = File(
            context.getApplicationContext().filesDir, "workspace"
        ).also { it.mkdirs() }
        memoryDir = File(workspaceRoot, "memory").also { it.mkdirs() }
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "search_memory" -> searchMemory(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private fun searchMemory(arguments: JsonObject): ToolResult {
        return try {
            val query = arguments["query"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: query")

            val results = mutableListOf<String>()
            val maxResults = 20
            val contextLines = 1

            // Search MEMORY.md
            val longTermFile = File(workspaceRoot, "MEMORY.md")
            if (longTermFile.exists()) {
                searchFile(longTermFile, "MEMORY.md", query, contextLines)
                    .let { results.addAll(it) }
            }

            // Search daily memory files (newest first)
            val dailyFiles = memoryDir.listFiles { f ->
                f.isFile && f.name.endsWith(".md")
            }?.sortedByDescending { it.name } ?: emptyArray<File>().toList()

            for (file in dailyFiles) {
                if (results.size >= maxResults) break
                searchFile(file, "memory/${file.name}", query, contextLines)
                    .let { results.addAll(it) }
            }

            if (results.isEmpty()) {
                ToolResult.Success(output = "No results found for: $query")
            } else {
                val output = results.take(maxResults).joinToString("\n---\n")
                ToolResult.Success(output = "${results.size.coerceAtMost(maxResults)} result(s) for \"$query\":\n\n$output")
            }
        } catch (e: Exception) {
            ToolResult.Failure("Failed to search memory: ${e.message}", e)
        }
    }

    private fun searchFile(
        file: File,
        displayPath: String,
        query: String,
        contextLines: Int
    ): List<String> {
        val lines = file.readLines()
        val lowerQuery = query.lowercase()
        val matches = mutableListOf<String>()

        for ((index, line) in lines.withIndex()) {
            if (line.lowercase().contains(lowerQuery)) {
                val start = (index - contextLines).coerceAtLeast(0)
                val end = (index + contextLines).coerceAtMost(lines.size - 1)
                val snippet = (start..end).joinToString("\n") { i ->
                    val prefix = if (i == index) ">" else " "
                    "$prefix ${i + 1}: ${lines[i]}"
                }
                matches.add("**$displayPath** (line ${index + 1}):\n```\n$snippet\n```")
            }
        }
        return matches
    }
}
