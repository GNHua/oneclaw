package com.oneclaw.shadow.tool.engine

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolGroupDefinition
import com.oneclaw.shadow.core.model.ToolSourceInfo
import com.oneclaw.shadow.core.model.ToolSourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registry of all available tools. Singleton, created at app startup.
 */
class ToolRegistry {

    @PublishedApi
    internal val tools = mutableMapOf<String, Tool>()

    @PublishedApi
    internal val sourceInfoMap = mutableMapOf<String, ToolSourceInfo>()

    @PublishedApi
    internal val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val groupDefinitions = mutableMapOf<String, ToolGroupDefinition>()

    /**
     * Register a tool with optional source info.
     * Throws IllegalArgumentException if a tool with the same name is already registered.
     *
     * @param tool The tool to register
     * @param sourceInfo Metadata about where this tool came from. Defaults to BUILTIN.
     */
    fun register(tool: Tool, sourceInfo: ToolSourceInfo = ToolSourceInfo.BUILTIN) {
        val name = tool.definition.name
        require(!tools.containsKey(name)) {
            "Tool '$name' is already registered"
        }
        tools[name] = tool
        sourceInfoMap[name] = sourceInfo
        _version.value++
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

    /**
     * Get the source info for a specific tool.
     * Returns BUILTIN if the tool is registered but has no explicit source info.
     */
    fun getToolSourceInfo(name: String): ToolSourceInfo =
        sourceInfoMap[name] ?: ToolSourceInfo.BUILTIN

    /**
     * Get a map of all tool names to their source info.
     */
    fun getAllToolSourceInfo(): Map<String, ToolSourceInfo> = sourceInfoMap.toMap()

    /** Register group metadata. */
    fun registerGroup(group: ToolGroupDefinition) {
        groupDefinitions[group.name] = group
    }

    /** Get a single group definition by name. Returns null if not found. */
    fun getGroupDefinition(name: String): ToolGroupDefinition? = groupDefinitions[name]

    /** Get all registered group definitions. */
    fun getAllGroupDefinitions(): List<ToolGroupDefinition> = groupDefinitions.values.toList()

    /** Get tool definitions that are NOT in any group (core tools). */
    fun getCoreToolDefinitions(): List<ToolDefinition> {
        return tools.entries
            .filter { (name, _) -> sourceInfoMap[name]?.groupName == null }
            .map { (_, tool) -> tool.definition }
    }

    /** Get tool definitions belonging to a specific group. */
    fun getGroupToolDefinitions(groupName: String): List<ToolDefinition> {
        return tools.entries
            .filter { (name, _) -> sourceInfoMap[name]?.groupName == groupName }
            .map { (_, tool) -> tool.definition }
    }

    /**
     * Get all distinct tool group names.
     * Returns group name -> list of tool names in that group.
     * Includes tools with any groupName set, regardless of source type.
     */
    fun getToolGroups(): Map<String, List<String>> {
        val groups = mutableMapOf<String, MutableList<String>>()
        for ((toolName, info) in sourceInfoMap) {
            if (info.groupName != null) {
                groups.getOrPut(info.groupName) { mutableListOf() }.add(toolName)
            }
        }
        return groups
    }

    /**
     * Remove a tool by name. Used when a user tool overrides a built-in tool.
     */
    fun unregister(name: String) {
        tools.remove(name)
        sourceInfoMap.remove(name)
        _version.value++
    }

    /**
     * Unregister all tools of a specific type.
     * Used by JS tool reload to remove old JS tools before re-scanning.
     */
    inline fun <reified T : Tool> unregisterByType() {
        val keysToRemove = tools.entries
            .filter { it.value is T }
            .map { it.key }
        if (keysToRemove.isNotEmpty()) {
            keysToRemove.forEach { key ->
                tools.remove(key)
                sourceInfoMap.remove(key)
            }
            _version.value++
        }
    }
}
