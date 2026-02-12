package com.tomandy.palmclaw.engine

import android.content.Context

/**
 * High-level API for the plugin system.
 *
 * PluginEngine orchestrates the complete plugin lifecycle:
 * 1. Check cache (CacheManager)
 * 2. Compile KTS to JAR (KtsCompiler) - if needed
 * 3. Convert JAR to DEX (DexConverter) - if needed
 * 4. Load DEX (PluginLoader)
 * 5. Initialize plugin (call Plugin.onLoad)
 *
 * Performance Targets:
 * - **Cold path** (first load): < 5s (compilation + dexing)
 * - **Hot path** (cached): < 150ms (DEX loading only)
 *
 * This is the main entry point for plugin management. Other classes are
 * implementation details.
 *
 * Example Usage:
 * ```kotlin
 * val pluginEngine = PluginEngine(context)
 *
 * // Load a plugin
 * val metadata = Json.decodeFromString<PluginMetadata>(
 *     File("gmail_api/plugin.json").readText()
 * )
 * val source = File("gmail_api/GmailPlugin.kts").readText()
 *
 * val result = pluginEngine.loadPlugin(source, metadata)
 * when (result) {
 *     is Result.success -> {
 *         val plugin = result.value
 *         plugin.instance.execute("search_gmail", args)
 *     }
 *     is Result.failure -> println("Error: ${result.exceptionOrNull()}")
 * }
 * ```
 */
class PluginEngine(
    context: Context
) {
    private val cacheManager = CacheManager(context)
    private val compiler = KtsCompiler(cacheManager.getJarFile("").parentFile!!)
    private val dexConverter = DexConverter(cacheManager.getDexFile("").parentFile!!)
    private val loader = PluginLoader(context, cacheManager.getDexFile("").parentFile!!)

    /**
     * Load a plugin from source code.
     *
     * This is the main entry point for loading plugins. The engine automatically:
     * - Checks if the plugin is already loaded (instant return)
     * - Checks if compilation is needed (via MD5 hash)
     * - Compiles and caches if needed (cold path)
     * - Loads from cache if available (hot path)
     *
     * @param scriptSource The plugin's .kts source code
     * @param metadata The plugin's metadata from plugin.json
     * @return Result containing LoadedPlugin or error
     *
     * Performance:
     * - First load (cold): 2-5 seconds
     * - Subsequent loads (hot): < 150ms
     *
     * Example:
     * ```kotlin
     * val source = """
     *     import com.tomandy.palmclaw.engine.*
     *
     *     class MyPlugin : Plugin {
     *         override suspend fun onLoad(context: PluginContext) { }
     *         override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
     *             return ToolResult.Success("Hello from plugin!")
     *         }
     *         override suspend fun onUnload() { }
     *     }
     * """.trimIndent()
     *
     * val metadata = PluginMetadata(
     *     id = "my_plugin",
     *     name = "My Plugin",
     *     version = "1.0.0",
     *     description = "A test plugin",
     *     author = "Me",
     *     entryPoint = "MyPlugin",
     *     tools = listOf(...)
     * )
     *
     * pluginEngine.loadPlugin(source, metadata)
     * ```
     */
    suspend fun loadPlugin(
        scriptSource: String,
        metadata: PluginMetadata
    ): Result<LoadedPlugin> {
        val pluginId = metadata.id

        return try {
            // Check if already loaded
            loader.getPlugin(pluginId)?.let {
                return Result.success(it)
            }

            // Check if we need to recompile
            val needsCompilation = cacheManager.shouldRecompile(pluginId, scriptSource)

            val dexFile = if (needsCompilation) {
                // Cold path: Compile, convert, and cache

                // Step 1: Compile KTS to JAR
                val compiledScript = compiler.compile(scriptSource, pluginId)
                    .getOrElse { return Result.failure(it) }

                // Step 2: Convert JAR to DEX
                val dex = dexConverter.convertToDex(compiledScript.jarFile, pluginId)
                    .getOrElse { return Result.failure(it) }

                // Step 3: Save cache metadata
                cacheManager.saveCacheMetadata(pluginId, scriptSource)

                dex
            } else {
                // Hot path: Load from cache
                cacheManager.getDexFile(pluginId)
            }

            // Step 4: Load the plugin
            loader.load(pluginId, dexFile, metadata)

        } catch (e: Exception) {
            Result.failure(
                PluginException(
                    "Failed to load plugin '${metadata.id}': ${e.message}",
                    e
                )
            )
        }
    }

    /**
     * Reload a plugin, forcing recompilation.
     *
     * This is useful when:
     * - Plugin source code changed
     * - Plugin had an error and was fixed
     * - Cache is suspected to be corrupt
     *
     * @param pluginId The plugin to reload
     * @param scriptSource The updated source code
     * @param metadata The updated metadata
     * @return Result containing LoadedPlugin or error
     */
    suspend fun reloadPlugin(
        pluginId: String,
        scriptSource: String,
        metadata: PluginMetadata
    ): Result<LoadedPlugin> {
        // Unload existing plugin
        unloadPlugin(pluginId)

        // Clear cache to force recompilation
        cacheManager.clearCache(pluginId)

        // Load plugin (will recompile)
        return loadPlugin(scriptSource, metadata)
    }

    /**
     * Unload a plugin.
     *
     * Removes the plugin from memory. The cache remains intact, so reloading
     * the same plugin will use the hot path.
     *
     * @param pluginId The plugin to unload
     */
    fun unloadPlugin(pluginId: String) {
        loader.unload(pluginId)
    }

    /**
     * Get a loaded plugin.
     *
     * @param pluginId The plugin identifier
     * @return The LoadedPlugin, or null if not loaded
     */
    fun getLoadedPlugin(pluginId: String): LoadedPlugin? {
        return loader.getPlugin(pluginId)
    }

    /**
     * Get all loaded plugins.
     *
     * @return List of all currently loaded plugins
     */
    fun getAllPlugins(): List<LoadedPlugin> {
        return loader.getAllPlugins()
    }

    /**
     * Check if a plugin is loaded.
     *
     * @param pluginId The plugin identifier
     * @return True if the plugin is loaded
     */
    fun isPluginLoaded(pluginId: String): Boolean {
        return loader.isLoaded(pluginId)
    }

    /**
     * Clear all caches.
     *
     * This removes all compiled and cached plugins. Next load will use cold path.
     * Useful for:
     * - Freeing disk space
     * - Resetting after corruption
     * - Testing
     */
    fun clearAllCache() {
        cacheManager.clearCache()
    }

    /**
     * Get cache statistics.
     *
     * @return CacheStatistics with size and plugin info
     */
    fun getCacheStatistics(): CacheStatistics {
        val totalSize = cacheManager.getCacheSize()
        val pluginIds = cacheManager.getCachedPluginIds()
        val pluginSizes = pluginIds.associateWith { pluginId ->
            cacheManager.getPluginCacheSize(pluginId)
        }

        return CacheStatistics(
            totalSizeBytes = totalSize,
            pluginCount = pluginIds.size,
            cachedPlugins = pluginIds,
            pluginSizes = pluginSizes
        )
    }

    /**
     * Unload all plugins.
     *
     * Removes all plugins from memory but keeps cache intact.
     */
    fun unloadAllPlugins() {
        loader.clear()
    }
}

/**
 * Statistics about the plugin cache.
 *
 * @param totalSizeBytes Total cache size in bytes
 * @param pluginCount Number of cached plugins
 * @param cachedPlugins List of cached plugin IDs
 * @param pluginSizes Map of plugin ID to cache size in bytes
 */
data class CacheStatistics(
    val totalSizeBytes: Long,
    val pluginCount: Int,
    val cachedPlugins: List<String>,
    val pluginSizes: Map<String, Long>
) {
    /**
     * Total cache size in megabytes.
     */
    val totalSizeMB: Double
        get() = totalSizeBytes / (1024.0 * 1024.0)

    /**
     * Human-readable size string.
     */
    fun formatSize(): String {
        return when {
            totalSizeBytes < 1024 -> "$totalSizeBytes B"
            totalSizeBytes < 1024 * 1024 -> "%.2f KB".format(totalSizeBytes / 1024.0)
            else -> "%.2f MB".format(totalSizeMB)
        }
    }
}
