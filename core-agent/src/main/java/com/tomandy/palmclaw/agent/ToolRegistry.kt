package com.tomandy.palmclaw.agent

import com.tomandy.palmclaw.engine.LoadedPlugin
import com.tomandy.palmclaw.engine.Plugin
import com.tomandy.palmclaw.engine.ToolDefinition

/**
 * Registry for managing tools from loaded plugins.
 *
 * The ToolRegistry maintains a mapping from tool names to their plugin sources,
 * allowing the agent to discover available tools and execute them.
 *
 * Usage:
 * ```kotlin
 * val registry = ToolRegistry()
 *
 * // Register a plugin
 * registry.registerPlugin(loadedPlugin)
 *
 * // Get tool definitions for LLM
 * val tools = registry.getToolDefinitions()
 *
 * // Execute a tool
 * val tool = registry.getTool("search_gmail")
 * if (tool != null) {
 *     val result = tool.plugin.execute("search_gmail", arguments)
 * }
 *
 * // Unregister a plugin
 * registry.unregisterPlugin("gmail_api")
 * ```
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, RegisteredTool>()
    private val categoryDescriptions = mutableMapOf<String, String>()

    /**
     * Register all tools from a loaded plugin.
     *
     * If a tool with the same name already exists, it will be overwritten.
     * This allows plugin hot-reloading.
     *
     * @param loadedPlugin The plugin to register
     */
    fun registerPlugin(loadedPlugin: LoadedPlugin) {
        val category = loadedPlugin.metadata.category
        loadedPlugin.metadata.tools.forEach { toolDef ->
            tools[toolDef.name] = RegisteredTool(
                pluginId = loadedPlugin.metadata.id,
                definition = toolDef,
                plugin = loadedPlugin.instance,
                category = category
            )
        }
        // Track category descriptions for activate_tools
        if (category != "core") {
            categoryDescriptions[category] = loadedPlugin.metadata.description
        }
    }

    /**
     * Unregister all tools from a plugin.
     *
     * Removes all tools associated with the given plugin ID.
     *
     * @param pluginId The plugin identifier
     */
    fun unregisterPlugin(pluginId: String) {
        tools.entries.removeIf { it.value.pluginId == pluginId }
    }

    /**
     * Get a registered tool by name.
     *
     * @param toolName The tool name
     * @return The RegisteredTool, or null if not found
     */
    fun getTool(toolName: String): RegisteredTool? {
        return tools[toolName]
    }

    /**
     * Get all tool definitions for the LLM.
     *
     * Returns a list of ToolDefinition objects that can be passed to the LLM
     * in the `tools` parameter of the completion request.
     *
     * @return List of all registered tool definitions
     */
    fun getToolDefinitions(): List<ToolDefinition> {
        return tools.values.map { it.definition }
    }

    /**
     * Get tool definitions filtered by active categories.
     *
     * Returns tools where category is "core" or in the given active set.
     */
    fun getToolDefinitions(activeCategories: Set<String>): List<ToolDefinition> {
        return tools.values
            .filter { it.category == "core" || it.category in activeCategories }
            .map { it.definition }
    }

    /**
     * Get all non-core categories that have registered tools.
     */
    fun getOnDemandCategories(): Set<String> {
        return tools.values
            .map { it.category }
            .filter { it != "core" }
            .toSet()
    }

    /**
     * Get the description for a category (from the plugin that registered it).
     */
    fun getCategoryDescription(category: String): String {
        return categoryDescriptions[category] ?: category
    }

    /**
     * Get all registered tools.
     *
     * @return List of all registered tools with their metadata
     */
    fun getAllTools(): List<RegisteredTool> {
        return tools.values.toList()
    }

    /**
     * Clear all registered tools.
     *
     * Useful for testing or complete resets.
     */
    fun clear() {
        tools.clear()
        categoryDescriptions.clear()
    }

    /**
     * Get the number of registered tools.
     *
     * @return Count of registered tools
     */
    fun size(): Int {
        return tools.size
    }

    /**
     * Check if a tool is registered.
     *
     * @param toolName The tool name
     * @return True if the tool exists
     */
    fun hasTool(toolName: String): Boolean {
        return tools.containsKey(toolName)
    }

    /**
     * Create an isolated copy of this registry with only tools matching [filter].
     *
     * Useful for sub-agent execution where the sub-agent needs its own registry
     * to avoid conflicts with the parent agent's plugin registrations.
     */
    fun copyFiltered(filter: (RegisteredTool) -> Boolean): ToolRegistry {
        val copy = ToolRegistry()
        tools.values.filter(filter).forEach { tool ->
            copy.tools[tool.definition.name] = tool
        }
        categoryDescriptions.forEach { (k, v) -> copy.categoryDescriptions[k] = v }
        return copy
    }
}

/**
 * A registered tool with its source plugin.
 *
 * @param pluginId The ID of the plugin that provides this tool
 * @param definition The tool's definition (name, description, parameters)
 * @param plugin The plugin instance that can execute this tool
 */
data class RegisteredTool(
    val pluginId: String,
    val definition: ToolDefinition,
    val plugin: Plugin,
    val category: String = "core"
)
