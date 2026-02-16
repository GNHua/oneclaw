package com.tomandy.palmclaw.plugin

import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject

object ConfigPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "config",
            name = "App Configuration",
            version = "2.0.0",
            description = "Read and modify app settings",
            author = "PalmClaw",
            entryPoint = "ConfigPlugin",
            tools = listOf(
                setAppConfigTool(),
                getAppConfigTool()
            )
        )
    }

    private fun setAppConfigTool() = ToolDefinition(
        name = "set_app_config",
        description = """Update one or more app settings.
            |
            |Pass a JSON object where each key is a setting name and each value
            |is the new value (as a string).
            |
            |IMPORTANT: Always call get_app_config first to discover available
            |settings, their current values, types, and constraints.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {}
            put("additionalProperties", JsonPrimitive(true))
        }
    )

    private fun getAppConfigTool() = ToolDefinition(
        name = "get_app_config",
        description = """Get all app configuration settings.
            |
            |Returns each setting's current value, description, and type constraints.
            |Call this before using configure_app to know what settings exist.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {}
        }
    )
}
