package com.tomandy.oneclaw.pluginmanager

import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.PluginEngine
import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class PluginManagementTool(
    private val userPluginManager: UserPluginManager,
    private val pluginEngine: PluginEngine
) : Plugin {

    override suspend fun onLoad(context: PluginContext) {}

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "install_plugin" -> installPlugin(arguments)
            "remove_plugin" -> removePlugin(arguments)
            "list_user_plugins" -> listUserPlugins()
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private suspend fun installPlugin(arguments: JsonObject): ToolResult {
        val metadata = arguments["metadata"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing 'metadata' parameter (JSON string of plugin.json content)")

        val source = arguments["source"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing 'source' parameter (JavaScript source code)")

        return withContext(Dispatchers.IO) {
            userPluginManager.installFromSource(metadata, source)
                .fold(
                    onSuccess = { loaded ->
                        ToolResult.Success(
                            "Plugin '${loaded.metadata.name}' (${loaded.metadata.id}) installed successfully with ${loaded.metadata.tools.size} tool(s): ${loaded.metadata.tools.joinToString { it.name }}"
                        )
                    },
                    onFailure = { error ->
                        ToolResult.Failure("Failed to install plugin: ${error.message}")
                    }
                )
        }
    }

    private suspend fun removePlugin(arguments: JsonObject): ToolResult {
        val pluginId = arguments["plugin_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing 'plugin_id' parameter")

        if (!userPluginManager.isUserPlugin(pluginId)) {
            return ToolResult.Failure("Plugin '$pluginId' is not a user-installed plugin and cannot be removed")
        }

        return withContext(Dispatchers.IO) {
            val deleted = userPluginManager.deletePlugin(pluginId)
            if (deleted) {
                ToolResult.Success("Plugin '$pluginId' removed successfully")
            } else {
                ToolResult.Failure("Failed to remove plugin '$pluginId'")
            }
        }
    }

    private fun listUserPlugins(): ToolResult {
        val pluginIds = userPluginManager.getUserPluginIds()
        if (pluginIds.isEmpty()) {
            return ToolResult.Success("No user-installed plugins found.")
        }

        val entries = pluginIds.sorted().map { id ->
            val loaded = pluginEngine.getLoadedPlugin(id)
            if (loaded != null) {
                val m = loaded.metadata
                "- ${m.name} (${m.id}) v${m.version}: ${m.description} [${m.tools.size} tool(s): ${m.tools.joinToString { it.name }}]"
            } else {
                "- $id (not loaded)"
            }
        }

        return ToolResult.Success("User-installed plugins:\n${entries.joinToString("\n")}")
    }

    companion object {
        fun metadata(): PluginMetadata {
            return PluginMetadata(
                id = "plugin_management",
                name = "Plugin Manager",
                version = "1.0.0",
                description = "Install, list, and remove custom JavaScript plugins at runtime",
                author = "OneClaw",
                entryPoint = "PluginManagementTool",
                tools = listOf(
                    installPluginToolDef(),
                    removePluginToolDef(),
                    listUserPluginsToolDef()
                )
            )
        }

        private fun installPluginToolDef() = ToolDefinition(
            name = "install_plugin",
            description = """Install or update a custom JavaScript plugin at runtime.
                |
                |Create a plugin by providing:
                |1. A plugin.json metadata string defining the plugin's ID, name, version, description, and tools
                |2. A JavaScript source string implementing the execute(toolName, args) function
                |
                |The plugin will be installed and its tools will become available immediately.
                |If a plugin with the same ID already exists, it will be replaced.
                |
                |Example metadata:
                |{
                |  "id": "my_plugin",
                |  "name": "My Plugin",
                |  "version": "1.0.0",
                |  "description": "Does something useful",
                |  "author": "AI",
                |  "entryPoint": "MyPlugin",
                |  "tools": [{"name": "my_tool", "description": "Does X", "parameters": {"type": "object", "properties": {"input": {"type": "string"}}}}]
                |}
                |
                |Example source:
                |function execute(toolName, args) {
                |  if (toolName === "my_tool") return { output: "Result: " + args.input };
                |  return { error: "Unknown tool" };
                |}
            """.trimMargin(),
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                putJsonObject("properties") {
                    putJsonObject("metadata") {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("JSON string containing the plugin.json metadata"))
                    }
                    putJsonObject("source") {
                        put("type", JsonPrimitive("string"))
                        put(
                            "description",
                            JsonPrimitive("JavaScript source code implementing the execute(toolName, args) function")
                        )
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("metadata"))
                    add(JsonPrimitive("source"))
                }
            }
        )

        private fun removePluginToolDef() = ToolDefinition(
            name = "remove_plugin",
            description = """Remove a user-installed JavaScript plugin.
                |
                |Only user-installed plugins can be removed. Built-in plugins cannot be removed.
                |The plugin's tools will be unregistered immediately.
            """.trimMargin(),
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                putJsonObject("properties") {
                    putJsonObject("plugin_id") {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("The ID of the user-installed plugin to remove"))
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("plugin_id"))
                }
            }
        )

        private fun listUserPluginsToolDef() = ToolDefinition(
            name = "list_user_plugins",
            description = """List all user-installed JavaScript plugins with their metadata.
                |
                |Returns each plugin's ID, name, version, description, and tool count.
            """.trimMargin(),
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                putJsonObject("properties") {}
            }
        )
    }
}
