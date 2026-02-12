package com.tomandy.palmclaw.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Manages plugin compilation cache with MD5-based invalidation.
 *
 * The cache manager determines when plugins need recompilation by comparing
 * MD5 hashes of the source code. This enables the fast path:
 * - Source unchanged → Load from cache (< 150ms)
 * - Source changed → Recompile (2-5s)
 *
 * Cache Structure:
 * ```
 * /cache/plugins/
 * ├── gmail_api.jar          # Compiled JVM bytecode
 * ├── gmail_api/
 * │   └── classes.dex        # Converted DEX file
 * ├── web_search.jar
 * └── web_search/
 *     └── classes.dex
 * ```
 *
 * Metadata (SharedPreferences):
 * - `{pluginId}_hash`: MD5 hash of source code
 * - `{pluginId}_timestamp`: Last compilation time
 *
 * Thread Safety:
 * - All operations use IO dispatcher
 * - SharedPreferences is thread-safe
 */
class CacheManager(
    context: Context
) {
    private val cacheDir = File(context.cacheDir, "plugins")
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "plugin_cache",
        Context.MODE_PRIVATE
    )

    init {
        // Ensure cache directory exists
        cacheDir.mkdirs()
    }

    /**
     * Check if a plugin needs recompilation.
     *
     * A plugin needs recompilation if:
     * - Source code hash changed
     * - DEX file doesn't exist
     * - Cache metadata is missing
     *
     * @param pluginId The plugin identifier
     * @param scriptSource The current plugin source code
     * @return True if recompilation is needed
     *
     * Example:
     * ```kotlin
     * val source = File("GmailPlugin.kts").readText()
     * if (cacheManager.shouldRecompile("gmail_api", source)) {
     *     // Recompile plugin
     *     compiler.compile(source, "gmail_api")
     * } else {
     *     // Load from cache (fast!)
     *     loader.load("gmail_api", dexFile, metadata)
     * }
     * ```
     */
    suspend fun shouldRecompile(
        pluginId: String,
        scriptSource: String
    ): Boolean = withContext(Dispatchers.IO) {
        // Calculate current hash
        val currentHash = scriptSource.md5()

        // Get cached hash
        val cachedHash = prefs.getString("${pluginId}_hash", null)

        // Check if DEX file exists
        val dexFile = getDexFile(pluginId)

        // Recompile if:
        // 1. Hash changed
        // 2. DEX file doesn't exist
        // 3. No cached hash (first time)
        currentHash != cachedHash || !dexFile.exists()
    }

    /**
     * Save cache metadata after successful compilation.
     *
     * Stores:
     * - MD5 hash of source code
     * - Compilation timestamp
     *
     * @param pluginId The plugin identifier
     * @param scriptSource The compiled source code
     */
    suspend fun saveCacheMetadata(
        pluginId: String,
        scriptSource: String
    ) = withContext(Dispatchers.IO) {
        val hash = scriptSource.md5()
        prefs.edit()
            .putString("${pluginId}_hash", hash)
            .putLong("${pluginId}_timestamp", System.currentTimeMillis())
            .apply()
    }

    /**
     * Get the JAR file path for a plugin.
     *
     * @param pluginId The plugin identifier
     * @return The JAR file (may not exist yet)
     */
    fun getJarFile(pluginId: String): File {
        return File(cacheDir, "$pluginId.jar")
    }

    /**
     * Get the DEX file path for a plugin.
     *
     * @param pluginId The plugin identifier
     * @return The DEX file (may not exist yet)
     */
    fun getDexFile(pluginId: String): File {
        return File(cacheDir, "$pluginId/classes.dex")
    }

    /**
     * Get cache metadata for a plugin.
     *
     * @param pluginId The plugin identifier
     * @return CacheMetadata or null if not cached
     */
    fun getCacheMetadata(pluginId: String): CacheMetadata? {
        val hash = prefs.getString("${pluginId}_hash", null) ?: return null
        val timestamp = prefs.getLong("${pluginId}_timestamp", 0L)
        return CacheMetadata(hash, timestamp)
    }

    /**
     * Clear cache for a specific plugin.
     *
     * Removes:
     * - JAR file
     * - DEX files
     * - Cache metadata
     *
     * @param pluginId The plugin identifier (null to clear all)
     */
    fun clearCache(pluginId: String? = null) {
        if (pluginId != null) {
            // Clear specific plugin
            getJarFile(pluginId).delete()
            File(cacheDir, pluginId).deleteRecursively()
            prefs.edit()
                .remove("${pluginId}_hash")
                .remove("${pluginId}_timestamp")
                .apply()
        } else {
            // Clear all plugins
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            prefs.edit().clear().apply()
        }
    }

    /**
     * Get total cache size in bytes.
     *
     * @return Total size of all cached files
     */
    fun getCacheSize(): Long {
        return cacheDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * Get cache size for a specific plugin.
     *
     * @param pluginId The plugin identifier
     * @return Size in bytes
     */
    fun getPluginCacheSize(pluginId: String): Long {
        val jarSize = getJarFile(pluginId).takeIf { it.exists() }?.length() ?: 0
        val dexSize = File(cacheDir, pluginId).walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        return jarSize + dexSize
    }

    /**
     * Get all cached plugin IDs.
     *
     * @return List of plugin IDs with cache data
     */
    fun getCachedPluginIds(): List<String> {
        return prefs.all.keys
            .filter { it.endsWith("_hash") }
            .map { it.removeSuffix("_hash") }
    }
}

/**
 * Cache metadata for a plugin.
 *
 * @param hash MD5 hash of the source code
 * @param timestamp When the plugin was last compiled (milliseconds since epoch)
 */
data class CacheMetadata(
    val hash: String,
    val timestamp: Long
)

/**
 * Calculate MD5 hash of a string.
 *
 * @return Hex-encoded MD5 hash
 */
private fun String.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
