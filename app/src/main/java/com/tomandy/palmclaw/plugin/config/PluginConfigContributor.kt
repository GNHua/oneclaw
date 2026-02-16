package com.tomandy.palmclaw.plugin.config

import com.tomandy.palmclaw.agent.ToolRegistry
import com.tomandy.palmclaw.engine.PluginEngine
import com.tomandy.palmclaw.engine.ToolResult
import com.tomandy.palmclaw.plugin.ConfigContributor
import com.tomandy.palmclaw.plugin.ConfigEntry
import com.tomandy.palmclaw.plugin.ConfigType
import com.tomandy.palmclaw.pluginmanager.PluginPreferences

class PluginConfigContributor(
    private val pluginEngine: PluginEngine,
    private val pluginPreferences: PluginPreferences,
    private val toolRegistry: ToolRegistry
) : ConfigContributor {

    override fun contribute(): List<ConfigEntry> = listOf(
        ConfigEntry(
            key = "plugins",
            displayName = "Plugins",
            description = "Enable/disable plugins. Set value as \"plugin_id:true\" or \"plugin_id:false\".",
            type = ConfigType.StringType,
            getter = {
                val all = pluginEngine.getAllPlugins()
                if (all.isEmpty()) {
                    "No plugins loaded."
                } else {
                    all.joinToString("\n    ") { p ->
                        val enabled = pluginPreferences.isPluginEnabled(p.metadata.id)
                        "${p.metadata.id}: ${if (enabled) "enabled" else "disabled"} - ${p.metadata.name}"
                    }
                }
            },
            setter = {},
            customHandler = { value -> handlePluginToggle(value) }
        )
    )

    private fun handlePluginToggle(value: String): ToolResult {
        val parts = value.split(":", limit = 2)
        if (parts.size != 2) {
            return ToolResult.Failure(
                "Invalid format. Use \"plugin_id:true\" or \"plugin_id:false\"."
            )
        }
        val pluginId = parts[0].trim()
        val enabled = when (parts[1].trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> return ToolResult.Failure(
                "Invalid value \"${parts[1]}\". Use true/false."
            )
        }
        val plugin = pluginEngine.getLoadedPlugin(pluginId)
            ?: return ToolResult.Failure("Unknown plugin: \"$pluginId\".")
        pluginPreferences.setPluginEnabled(pluginId, enabled)
        if (enabled) {
            toolRegistry.registerPlugin(plugin)
        } else {
            toolRegistry.unregisterPlugin(pluginId)
        }
        val state = if (enabled) "enabled" else "disabled"
        return ToolResult.Success(
            "Plugin \"${plugin.metadata.name}\" ($pluginId) $state. Takes effect on the next message."
        )
    }
}
