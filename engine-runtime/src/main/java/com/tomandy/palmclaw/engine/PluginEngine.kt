package com.tomandy.palmclaw.engine

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * High-level API for the plugin system.
 *
 * PluginEngine loads JavaScript plugins from app assets using QuickJS.
 * Each plugin is a plain .js file alongside a plugin.json metadata file.
 *
 * Expected asset structure:
 * ```
 * assets/plugins/{pluginId}/
 *   ├── plugin.json
 *   └── plugin.js
 * ```
 */
class PluginEngine(
    private val context: Context
) {
    private val loadedPlugins = mutableMapOf<String, LoadedPlugin>()

    /**
     * Load a plugin from app assets.
     *
     * @param assetPath Path to the plugin directory in assets (e.g., "plugins/calculator")
     * @param pluginContext The context to pass to the plugin's onLoad method
     * @return Result containing LoadedPlugin or error
     */
    suspend fun loadFromAssets(
        assetPath: String,
        pluginContext: PluginContext
    ): Result<LoadedPlugin> {
        return try {
            val assets = context.assets

            // 1. Read plugin.json
            val metadataJson = assets.open("$assetPath/plugin.json")
                .bufferedReader()
                .use { it.readText() }
            val metadata = Json.decodeFromString<PluginMetadata>(metadataJson)

            // Check if already loaded
            loadedPlugins[metadata.id]?.let {
                return Result.success(it)
            }

            // 2. Read plugin.js
            val scriptSource = assets.open("$assetPath/plugin.js")
                .bufferedReader()
                .use { it.readText() }

            // 3. Create JsPlugin and initialize
            val jsPlugin = JsPlugin(scriptSource, metadata)
            jsPlugin.onLoad(pluginContext)

            // 4. Wrap and cache
            val loadedPlugin = LoadedPlugin(
                metadata = metadata,
                instance = jsPlugin
            )
            loadedPlugins[metadata.id] = loadedPlugin

            Result.success(loadedPlugin)

        } catch (e: Exception) {
            Result.failure(
                PluginException(
                    "Failed to load plugin from assets '$assetPath': ${e.message}",
                    e
                )
            )
        }
    }

    /**
     * Get a loaded plugin.
     */
    fun getLoadedPlugin(pluginId: String): LoadedPlugin? {
        return loadedPlugins[pluginId]
    }

    /**
     * Get all loaded plugins.
     */
    fun getAllPlugins(): List<LoadedPlugin> {
        return loadedPlugins.values.toList()
    }

    /**
     * Unload a plugin.
     */
    suspend fun unloadPlugin(pluginId: String) {
        loadedPlugins.remove(pluginId)?.instance?.onUnload()
    }

    /**
     * Unload all plugins.
     */
    suspend fun unloadAllPlugins() {
        loadedPlugins.values.forEach { it.instance.onUnload() }
        loadedPlugins.clear()
    }
}
