package com.tomandy.palmclaw.agent

import com.tomandy.palmclaw.data.UserPluginManager
import com.tomandy.palmclaw.engine.Plugin
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import com.tomandy.palmclaw.engine.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class InstallPluginTool(
    private val userPluginManager: UserPluginManager
) : Plugin {

    override suspend fun onLoad(context: PluginContext) {}

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "install_plugin" -> installPlugin(arguments)
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

    companion object {
        fun metadata(): PluginMetadata {
            return PluginMetadata(
                id = "install_plugin_tool",
                name = "Plugin Installer",
                version = "1.0.0",
                description = "Install custom JavaScript plugins at runtime",
                author = "PalmClaw",
                entryPoint = "InstallPluginTool",
                tools = listOf(installPluginToolDef())
            )
        }

        private fun installPluginToolDef() = ToolDefinition(
            name = "install_plugin",
            description = """Install a custom JavaScript plugin at runtime.
                |
                |Create a plugin by providing:
                |1. A plugin.json metadata string defining the plugin's ID, name, version, description, and tools
                |2. A JavaScript source string implementing the execute(toolName, args) function
                |
                |The plugin will be installed and its tools will become available immediately.
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
                        put("description", JsonPrimitive("JavaScript source code implementing the execute(toolName, args) function"))
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("metadata"))
                    add(JsonPrimitive("source"))
                }
            }
        )
    }
}
