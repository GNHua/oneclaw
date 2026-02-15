package com.tomandy.palmclaw.plugin

import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object ConfigPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "config",
            name = "App Configuration",
            version = "1.0.0",
            description = "Read and modify app settings like model selection and max iterations",
            author = "PalmClaw",
            entryPoint = "ConfigPlugin",
            tools = listOf(
                configureAppTool(),
                getAppConfigTool()
            )
        )
    }

    private fun configureAppTool() = ToolDefinition(
        name = "configure_app",
        description = """Change an app setting.
            |
            |Supported settings:
            |- "model": Switch the active LLM model. The value can be a partial match
            |  (e.g., "claude-sonnet" will match "claude-sonnet-4-5").
            |  The change takes effect on the next message.
            |- "max_iterations": Set the maximum number of ReAct loop iterations (1-500).
            |
            |Always call get_app_config first to see available models and current values
            |before making changes.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("setting") {
                    put("type", JsonPrimitive("string"))
                    putJsonArray("enum") {
                        add(JsonPrimitive("model"))
                        add(JsonPrimitive("max_iterations"))
                    }
                    put("description", JsonPrimitive("The setting to change"))
                }
                putJsonObject("value") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The new value for the setting"))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("setting"))
                add(JsonPrimitive("value"))
            }
        }
    )

    private fun getAppConfigTool() = ToolDefinition(
        name = "get_app_config",
        description = """Get the current app configuration.
            |
            |Returns the current model, provider, max iterations,
            |and the list of all available models.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {}
        }
    )
}
