package com.tomandy.oneclaw.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

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
    private val _pluginsFlow = MutableStateFlow<List<LoadedPlugin>>(emptyList())
    val pluginsFlow: StateFlow<List<LoadedPlugin>> = _pluginsFlow.asStateFlow()

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
            _pluginsFlow.value = loadedPlugins.values.toList()

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
     * Load a plugin from a filesystem directory.
     *
     * @param directory Directory containing plugin.json and plugin.js
     * @param pluginContext The context to pass to the plugin's onLoad method
     * @return Result containing LoadedPlugin or error
     */
    suspend fun loadFromDirectory(
        directory: File,
        pluginContext: PluginContext
    ): Result<LoadedPlugin> {
        return try {
            val metadataFile = File(directory, "plugin.json")
            val scriptFile = File(directory, "plugin.js")

            if (!metadataFile.exists()) {
                return Result.failure(PluginException("plugin.json not found in ${directory.absolutePath}"))
            }
            if (!scriptFile.exists()) {
                return Result.failure(PluginException("plugin.js not found in ${directory.absolutePath}"))
            }

            val metadataJson = metadataFile.readText()
            val metadata = Json.decodeFromString<PluginMetadata>(metadataJson)

            // Check if already loaded
            loadedPlugins[metadata.id]?.let {
                return Result.success(it)
            }

            val scriptSource = scriptFile.readText()

            val jsPlugin = JsPlugin(scriptSource, metadata)
            jsPlugin.onLoad(pluginContext)

            val loadedPlugin = LoadedPlugin(
                metadata = metadata,
                instance = jsPlugin
            )
            loadedPlugins[metadata.id] = loadedPlugin
            _pluginsFlow.value = loadedPlugins.values.toList()

            Result.success(loadedPlugin)

        } catch (e: Exception) {
            Result.failure(
                PluginException(
                    "Failed to load plugin from directory '${directory.absolutePath}': ${e.message}",
                    e
                )
            )
        }
    }

    /**
     * Manually register a pre-built LoadedPlugin (e.g., a built-in Kotlin plugin)
     * so it appears in [getAllPlugins].
     */
    fun registerLoadedPlugin(plugin: LoadedPlugin) {
        loadedPlugins[plugin.metadata.id] = plugin
        _pluginsFlow.value = loadedPlugins.values.toList()
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
        _pluginsFlow.value = loadedPlugins.values.toList()
    }

    /**
     * Unload all plugins.
     */
    suspend fun unloadAllPlugins() {
        loadedPlugins.values.forEach { it.instance.onUnload() }
        loadedPlugins.clear()
        _pluginsFlow.value = emptyList()
    }
}
