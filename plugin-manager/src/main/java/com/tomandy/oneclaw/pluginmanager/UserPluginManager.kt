package com.tomandy.oneclaw.pluginmanager

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tomandy.oneclaw.agent.ToolRegistry
import com.tomandy.oneclaw.engine.CredentialVault
import com.tomandy.oneclaw.engine.GoogleAuthProvider
import com.tomandy.oneclaw.engine.LoadedPlugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.PluginEngine
import com.tomandy.oneclaw.engine.PluginMetadata
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class UserPluginManager(
    private val context: Context,
    private val pluginEngine: PluginEngine,
    private val toolRegistry: ToolRegistry,
    private val pluginPreferences: PluginPreferences,
    private val credentialVault: CredentialVault,
    private val googleAuthProvider: GoogleAuthProvider? = null
) {
    companion object {
        private const val TAG = "UserPluginManager"
        private const val USER_PLUGINS_DIR = "workspace/plugins"
        private const val MAX_ZIP_SIZE = 1_048_576L // 1MB
    }

    val userPluginsDir: File = File(context.filesDir, USER_PLUGINS_DIR).apply { mkdirs() }

    private val builtInPluginIds = setOf("scheduler", "time")

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Import a plugin from a zip Uri (e.g. from file picker).
     */
    suspend fun importFromZip(uri: Uri): Result<LoadedPlugin> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(Exception("Cannot open file"))
            val result = installFromZipStream(inputStream)
            inputStream.close()
            result
        } catch (e: Exception) {
            Result.failure(Exception("Failed to import plugin: ${e.message}", e))
        }
    }

    /**
     * Import a plugin from a URL.
     */
    suspend fun importFromUrl(url: String): Result<LoadedPlugin> {
        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(Exception("Download failed: HTTP ${response.code}"))
            }
            val body = response.body ?: return Result.failure(Exception("Empty response body"))
            val contentLength = body.contentLength()
            if (contentLength > MAX_ZIP_SIZE) {
                body.close()
                return Result.failure(Exception("File too large (${contentLength} bytes, max ${MAX_ZIP_SIZE})"))
            }
            val result = installFromZipStream(body.byteStream())
            body.close()
            result
        } catch (e: Exception) {
            Result.failure(Exception("Failed to download plugin: ${e.message}", e))
        }
    }

    /**
     * Install a plugin from raw source files (for AI-generated plugins).
     */
    suspend fun installFromSource(metadataJson: String, jsSource: String): Result<LoadedPlugin> {
        return try {
            val metadata = Json.decodeFromString<PluginMetadata>(metadataJson)
            validateMetadata(metadata).onFailure { return Result.failure(it) }

            if (jsSource.isBlank()) {
                return Result.failure(Exception("JavaScript source cannot be empty"))
            }

            val pluginDir = File(userPluginsDir, metadata.id)
            if (pluginDir.exists()) {
                // Unload existing version first
                pluginEngine.unloadPlugin(metadata.id)
                toolRegistry.unregisterPlugin(metadata.id)
                pluginDir.deleteRecursively()
            }
            pluginDir.mkdirs()

            File(pluginDir, "plugin.json").writeText(metadataJson)
            File(pluginDir, "plugin.js").writeText(jsSource)

            loadPluginFromDir(pluginDir).also { result ->
                result.onSuccess { loaded ->
                    toolRegistry.registerPlugin(loaded)
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Failed to install plugin: ${e.message}", e))
        }
    }

    /**
     * Delete a user plugin. Returns false if plugin is built-in or not found.
     */
    suspend fun deletePlugin(pluginId: String): Boolean {
        if (!isUserPlugin(pluginId)) return false

        pluginEngine.unloadPlugin(pluginId)
        toolRegistry.unregisterPlugin(pluginId)

        val pluginDir = File(userPluginsDir, pluginId)
        pluginDir.deleteRecursively()

        // Clean up preferences
        pluginPreferences.setPluginEnabled(pluginId, true)

        Log.i(TAG, "Deleted user plugin: $pluginId")
        return true
    }

    /**
     * Load all user plugins from the user_plugins directory.
     */
    suspend fun loadAllUserPlugins() {
        val dirs = userPluginsDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in dirs) {
            loadPluginFromDir(dir)
                .onSuccess { loaded ->
                    if (pluginPreferences.isPluginEnabled(loaded.metadata.id)) {
                        toolRegistry.registerPlugin(loaded)
                    }
                    Log.i(TAG, "Loaded user plugin: ${loaded.metadata.name}")
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to load user plugin from ${dir.name}: ${error.message}")
                }
        }
    }

    /**
     * Check if a plugin is a user-installed plugin.
     */
    fun isUserPlugin(pluginId: String): Boolean {
        return File(userPluginsDir, pluginId).exists()
    }

    /**
     * Get IDs of all installed user plugins.
     */
    fun getUserPluginIds(): Set<String> {
        return userPluginsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
    }

    private suspend fun installFromZipStream(inputStream: InputStream): Result<LoadedPlugin> {
        val tempDir = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            var totalSize = 0L
            val zipInput = ZipInputStream(inputStream)
            var entry = zipInput.nextEntry

            if (entry == null) {
                tempDir.deleteRecursively()
                return Result.failure(Exception("Empty or invalid zip file"))
            }

            while (entry != null) {
                if (entry.isDirectory) {
                    entry = zipInput.nextEntry
                    continue
                }

                // Security: prevent zip traversal
                val name = entry.name
                if (name.contains("..")) {
                    tempDir.deleteRecursively()
                    return Result.failure(Exception("Invalid zip entry: $name"))
                }

                val outFile = File(tempDir, name)
                outFile.parentFile?.mkdirs()

                val bytes = zipInput.readBytes()
                totalSize += bytes.size
                if (totalSize > MAX_ZIP_SIZE) {
                    tempDir.deleteRecursively()
                    return Result.failure(Exception("Zip contents exceed ${MAX_ZIP_SIZE / 1024}KB limit"))
                }

                outFile.writeBytes(bytes)
                entry = zipInput.nextEntry
            }
            zipInput.close()

            // Find plugin.json - either at root or in a single subfolder
            val pluginDir = findPluginRoot(tempDir)
                ?: run {
                    tempDir.deleteRecursively()
                    return Result.failure(Exception("Zip must contain plugin.json and plugin.js"))
                }

            // Validate metadata
            val metadataJson = File(pluginDir, "plugin.json").readText()
            val metadata = Json.decodeFromString<PluginMetadata>(metadataJson)
            validateMetadata(metadata).onFailure {
                tempDir.deleteRecursively()
                return Result.failure(it)
            }

            val jsFile = File(pluginDir, "plugin.js")
            if (!jsFile.exists() || jsFile.readText().isBlank()) {
                tempDir.deleteRecursively()
                return Result.failure(Exception("plugin.js is missing or empty"))
            }

            // Copy to final location
            val targetDir = File(userPluginsDir, metadata.id)
            if (targetDir.exists()) {
                // Unload existing version
                pluginEngine.unloadPlugin(metadata.id)
                toolRegistry.unregisterPlugin(metadata.id)
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            File(pluginDir, "plugin.json").copyTo(File(targetDir, "plugin.json"))
            File(pluginDir, "plugin.js").copyTo(File(targetDir, "plugin.js"))

            tempDir.deleteRecursively()

            return loadPluginFromDir(targetDir)
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            return Result.failure(Exception("Failed to extract zip: ${e.message}", e))
        }
    }

    private fun findPluginRoot(dir: File): File? {
        // Check if plugin.json is at root
        if (File(dir, "plugin.json").exists()) return dir

        // Check single subfolder
        val subdirs = dir.listFiles()?.filter { it.isDirectory } ?: return null
        if (subdirs.size == 1 && File(subdirs[0], "plugin.json").exists()) {
            return subdirs[0]
        }

        return null
    }

    private fun validateMetadata(metadata: PluginMetadata): Result<Unit> {
        if (metadata.id.isBlank()) {
            return Result.failure(Exception("Plugin ID cannot be empty"))
        }
        if (metadata.name.isBlank()) {
            return Result.failure(Exception("Plugin name cannot be empty"))
        }
        if (metadata.version.isBlank()) {
            return Result.failure(Exception("Plugin version cannot be empty"))
        }
        if (metadata.tools.isEmpty()) {
            return Result.failure(Exception("Plugin must define at least one tool"))
        }
        if (metadata.id in builtInPluginIds) {
            return Result.failure(Exception("Plugin ID '${metadata.id}' conflicts with a built-in plugin"))
        }
        return Result.success(Unit)
    }

    private suspend fun loadPluginFromDir(directory: File): Result<LoadedPlugin> {
        val metadataJson = File(directory, "plugin.json").readText()
        val metadata = Json.decodeFromString<PluginMetadata>(metadataJson)

        val pluginContext = PluginContext(context, metadata.id, credentialVault, googleAuthProvider)
        return pluginEngine.loadFromDirectory(directory, pluginContext)
    }
}
