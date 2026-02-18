package com.tomandy.oneclaw.agent

import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.serialization.json.*

/**
 * Meta-tool that lets the LLM dynamically activate on-demand tool categories.
 *
 * Core tools are always available. Domain-specific tools (e.g., gmail, calendar)
 * are only loaded when the LLM calls activate_tools with the desired categories.
 *
 * The [activeCategories] var is swapped per execution by AgentCoordinator so that
 * each conversation tracks its own activated categories.
 */
class ActivateToolsPlugin(
    private val toolRegistry: ToolRegistry
) : Plugin {

    /** Set by AgentCoordinator before each execution. */
    @Volatile
    var activeCategories: MutableSet<String>? = null

    override suspend fun onLoad(context: PluginContext) {}

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        if (toolName != "activate_tools") {
            return ToolResult.Failure("Unknown tool: $toolName")
        }

        val target = activeCategories
            ?: return ToolResult.Failure("Tool activation not available in this context")

        val categoriesArray = arguments["categories"]?.jsonArray
            ?: return ToolResult.Failure("Missing required field: categories")

        val requested = categoriesArray.map { it.jsonPrimitive.content }.toSet()
        val available = toolRegistry.getOnDemandCategories()
        val valid = requested.intersect(available)
        val invalid = requested - available

        if (valid.isEmpty()) {
            return ToolResult.Failure(
                "No valid categories found. Available: ${available.joinToString(", ")}"
            )
        }

        target.addAll(valid)

        val activatedTools = toolRegistry.getToolDefinitions(valid)
            .filter { tool ->
                val reg = toolRegistry.getTool(tool.name)
                reg != null && reg.category in valid
            }

        val output = buildString {
            append("Activated ${valid.size} category(s): ${valid.joinToString(", ")}\n\n")
            append("New tools now available:\n")
            activatedTools.forEach { tool ->
                append("- ${tool.name}: ${tool.description.take(100)}\n")
            }
            if (invalid.isNotEmpty()) {
                append("\nUnknown categories (ignored): ${invalid.joinToString(", ")}")
            }
        }

        return ToolResult.Success(output)
    }

    override suspend fun onUnload() {}

    companion object {
        fun metadata(toolRegistry: ToolRegistry): PluginMetadata {
            val categories = toolRegistry.getOnDemandCategories()
            val categoryList = if (categories.isNotEmpty()) {
                categories.joinToString("\n") { cat ->
                    "- $cat: ${toolRegistry.getCategoryDescription(cat)}"
                }
            } else {
                "(none currently registered)"
            }

            return PluginMetadata(
                id = "activate_tools",
                name = "Tool Activator",
                version = "1.0.0",
                description = "Activates on-demand tool categories for the current conversation",
                author = "OneClaw",
                entryPoint = "ActivateToolsPlugin",
                tools = listOf(
                    ToolDefinition(
                        name = "activate_tools",
                        description = """Activate additional tool categories for this conversation.

Core tools are always available. Call this to load domain-specific tools when needed.

Available categories:
$categoryList

Once activated, the tools remain available for the rest of this conversation.""",
                        parameters = buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            putJsonObject("properties") {
                                putJsonObject("categories") {
                                    put("type", JsonPrimitive("array"))
                                    putJsonObject("items") {
                                        put("type", JsonPrimitive("string"))
                                        if (categories.isNotEmpty()) {
                                            putJsonArray("enum") {
                                                categories.forEach { add(JsonPrimitive(it)) }
                                            }
                                        }
                                    }
                                    put("description", JsonPrimitive("Tool categories to activate"))
                                }
                            }
                            putJsonArray("required") {
                                add(JsonPrimitive("categories"))
                            }
                        }
                    )
                ),
                category = "core"
            )
        }
    }
}
