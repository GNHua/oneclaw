package com.tomandy.palmclaw.pluginmanager

import android.content.Context
import android.util.Log
import com.tomandy.palmclaw.agent.ToolRegistry
import com.tomandy.palmclaw.engine.CredentialVault
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.PluginEngine

/**
 * Manages loading of built-in JavaScript plugins bundled in assets.
 */
class BuiltInPluginManager(
    private val context: Context,
    private val pluginEngine: PluginEngine,
    private val toolRegistry: ToolRegistry,
    private val pluginPreferences: PluginPreferences,
    private val credentialVault: CredentialVault
) {
    companion object {
        private const val TAG = "BuiltInPluginManager"

        private val BUILT_IN_PLUGIN_PATHS = listOf(
            "plugins/calculator",
            "plugins/time",
            "plugins/notes",
            "plugins/echo",
            "plugins/workspace-notes",
            "plugins/web-fetch"
        )
    }

    /**
     * Load all built-in JavaScript plugins from assets.
     * All plugins are loaded into PluginEngine (for metadata), but only
     * enabled plugins are registered with ToolRegistry.
     */
    suspend fun loadBuiltInPlugins() {
        BUILT_IN_PLUGIN_PATHS.forEach { path ->
            val pluginId = path.substringAfterLast("/")
            val pluginContext = PluginContext(context, pluginId, credentialVault)
            pluginEngine.loadFromAssets(path, pluginContext)
                .onSuccess { loadedPlugin ->
                    if (pluginPreferences.isPluginEnabled(loadedPlugin.metadata.id)) {
                        toolRegistry.registerPlugin(loadedPlugin)
                    }
                    Log.i(TAG, "Loaded plugin: ${loadedPlugin.metadata.name} (enabled=${pluginPreferences.isPluginEnabled(loadedPlugin.metadata.id)})")
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to load $pluginId: ${error.message}", error)
                }
        }
    }
}
