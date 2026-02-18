package com.tomandy.oneclaw.agent

import com.tomandy.oneclaw.engine.LoadedPlugin
import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject

/**
 * Built-in plugin that exposes conversation summarization as an LLM tool.
 *
 * The LLM can call `summarize_conversation` when the user requests to
 * summarize, compact, or compress the conversation context.
 *
 * @param onSummarize Suspend lambda that performs the actual summarization.
 *                    Returns a status message on success.
 */
class SummarizationPlugin(
    private val onSummarize: suspend () -> String
) : Plugin {

    override suspend fun onLoad(context: PluginContext) {}

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        if (toolName != TOOL_NAME) {
            return ToolResult.Failure("Unknown tool: $toolName")
        }
        return try {
            val result = onSummarize()
            ToolResult.Success(output = result)
        } catch (e: Exception) {
            ToolResult.Failure(
                error = "Summarization failed: ${e.message}",
                exception = e
            )
        }
    }

    override suspend fun onUnload() {}

    companion object {
        const val PLUGIN_ID = "summarization"
        const val TOOL_NAME = "summarize_conversation"

        fun metadata(): PluginMetadata = PluginMetadata(
            id = PLUGIN_ID,
            name = "Conversation Summarization",
            version = "1.0.0",
            description = "Summarize and compact the conversation context",
            author = "OneClaw",
            entryPoint = "SummarizationPlugin",
            tools = listOf(toolDefinition())
        )

        fun createLoadedPlugin(onSummarize: suspend () -> String): LoadedPlugin {
            return LoadedPlugin(
                metadata = metadata(),
                instance = SummarizationPlugin(onSummarize)
            )
        }

        private fun toolDefinition() = ToolDefinition(
            name = TOOL_NAME,
            description = """Summarize and compact the current conversation context.
                |
                |Call this tool when the user asks to:
                |- Summarize the conversation
                |- Compact or compress the context
                |- Free up context space
                |- Start fresh while keeping key information
                |
                |This replaces older messages with a concise summary, preserving
                |key topics, decisions, and pending tasks. Recent messages are kept intact.
                |
                |No parameters are required.""".trimMargin(),
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                putJsonObject("properties") {}
            }
        )
    }
}
