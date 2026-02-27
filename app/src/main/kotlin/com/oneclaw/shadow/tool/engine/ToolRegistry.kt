package com.oneclaw.shadow.tool.engine

import com.oneclaw.shadow.core.model.ToolDefinition

/**
 * Registry of all available tools. Singleton, created at app startup.
 */
class ToolRegistry {

    private val tools = mutableMapOf<String, Tool>()

    /**
     * Register a tool. Throws IllegalArgumentException if a tool with the same name
     * is already registered.
     */
    fun register(tool: Tool) {
        val name = tool.definition.name
        require(!tools.containsKey(name)) {
            "Tool '$name' is already registered"
        }
        tools[name] = tool
    }

    /** Get a tool by name. Returns null if not found. */
    fun getTool(name: String): Tool? = tools[name]

    /** Get all registered tool definitions. */
    fun getAllToolDefinitions(): List<ToolDefinition> = tools.values.map { it.definition }

    /**
     * Get tool definitions for a specific set of tool names.
     * Unknown names are silently ignored.
     */
    fun getToolDefinitionsByNames(names: List<String>): List<ToolDefinition> =
        names.mapNotNull { tools[it]?.definition }

    /** Check if a tool name exists in the registry. */
    fun hasTool(name: String): Boolean = tools.containsKey(name)

    /** Get all registered tool names. */
    fun getAllToolNames(): List<String> = tools.keys.toList()
}
