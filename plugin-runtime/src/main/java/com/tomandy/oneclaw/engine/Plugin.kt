package com.tomandy.oneclaw.engine

import kotlinx.serialization.json.JsonObject

/**
 * Base interface for all plugins.
 *
 * Plugins extend the agent's capabilities by providing tools that can be invoked
 * by the LLM. Each plugin must implement this interface and provide a no-arg constructor.
 *
 * Lifecycle:
 * 1. Plugin is loaded via PluginEngine
 * 2. onLoad() is called with PluginContext
 * 3. execute() is called when the LLM invokes a tool
 * 4. onUnload() is called when the plugin is removed
 *
 * Example implementation:
 * ```kotlin
 * class GmailPlugin : Plugin {
 *     private lateinit var context: PluginContext
 *     private lateinit var accessToken: String
 *
 *     override suspend fun onLoad(context: PluginContext) {
 *         this.context = context
 *         this.accessToken = context.getCredential("access_token")
 *             ?: throw IllegalStateException("Gmail access token not configured")
 *     }
 *
 *     override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
 *         return when (toolName) {
 *             "search_gmail" -> searchGmail(arguments)
 *             "send_gmail" -> sendGmail(arguments)
 *             else -> ToolResult.Failure("Unknown tool: $toolName")
 *         }
 *     }
 *
 *     override suspend fun onUnload() {
 *         // Cleanup resources
 *     }
 * }
 * ```
 */
interface Plugin {
    /**
     * Called when the plugin is loaded.
     *
     * Use this to initialize resources, load credentials, or set up connections.
     *
     * @param context The plugin context providing access to Android APIs,
     *                storage, networking, and credentials.
     */
    suspend fun onLoad(context: PluginContext)

    /**
     * Execute a tool defined by this plugin.
     *
     * The LLM agent calls this method when it wants to use one of the plugin's tools.
     *
     * @param toolName The name of the tool to execute (matches ToolDefinition.name)
     * @param arguments JSON object containing the tool's arguments
     * @return ToolResult indicating success or failure
     */
    suspend fun execute(toolName: String, arguments: JsonObject): ToolResult

    /**
     * Called when the plugin is unloaded.
     *
     * Use this to cleanup resources, close connections, or save state.
     */
    suspend fun onUnload()
}

/**
 * Result of a tool execution.
 *
 * Tools return either Success (with output) or Failure (with error message).
 */
sealed class ToolResult {
    /**
     * Tool executed successfully.
     *
     * @param output The tool's output (will be sent to the LLM as an observation)
     * @param metadata Optional metadata for debugging or analytics
     */
    data class Success(
        val output: String,
        val metadata: Map<String, String> = emptyMap(),
        val imagePaths: List<String> = emptyList()
    ) : ToolResult()

    /**
     * Tool execution failed.
     *
     * @param error User-friendly error message (will be sent to the LLM)
     * @param exception Optional exception for debugging
     */
    data class Failure(
        val error: String,
        val exception: Throwable? = null
    ) : ToolResult()
}
