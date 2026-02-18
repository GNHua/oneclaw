package com.tomandy.oneclaw.pluginmanager

import android.content.Context
import android.util.Log
import com.tomandy.oneclaw.agent.ToolRegistry
import com.tomandy.oneclaw.engine.CredentialVault
import com.tomandy.oneclaw.engine.GoogleAuthProvider
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.PluginEngine

/**
 * Manages loading of built-in JavaScript plugins bundled in assets.
 */
class BuiltInPluginManager(
    private val context: Context,
    private val pluginEngine: PluginEngine,
    private val toolRegistry: ToolRegistry,
    private val pluginPreferences: PluginPreferences,
    private val credentialVault: CredentialVault,
    private val googleAuthProvider: GoogleAuthProvider? = null
) {
    companion object {
        private const val TAG = "BuiltInPluginManager"

        private val GOOGLE_PLUGIN_IDS = setOf(
            "google-gmail", "google-gmail-settings", "google-calendar",
            "google-tasks", "google-contacts", "google-drive",
            "google-docs", "google-sheets", "google-slides", "google-forms"
        )

        private val PLUGIN_CREDENTIAL_KEYS = mapOf(
            "smart-home" to "api_key",
            "notion" to "api_key"
        )

        private val PROVIDER_KEY_PLUGIN_IDS = mapOf(
            "google-places" to "GoogleMaps"
        )

        private val BUILT_IN_PLUGIN_PATHS = listOf(
            "plugins/time",
            "plugins/web-fetch",
            "plugins/google-gmail",
            "plugins/google-gmail-settings",
            "plugins/google-calendar",
            "plugins/google-tasks",
            "plugins/google-contacts",
            "plugins/google-drive",
            "plugins/google-docs",
            "plugins/google-sheets",
            "plugins/google-slides",
            "plugins/google-forms",
            "plugins/image-gen",
            "plugins/smart-home",
            "plugins/notion",
            "plugins/google-places"
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
            val pluginContext = PluginContext(context, pluginId, credentialVault, googleAuthProvider)
            pluginEngine.loadFromAssets(path, pluginContext)
                .onSuccess { loadedPlugin ->
                    // Auto-disable image-gen if OpenAI API key is not configured
                    if (loadedPlugin.metadata.id == "image-gen") {
                        val hasOpenAiKey = !credentialVault.getApiKey("OpenAI").isNullOrBlank()
                        if (!hasOpenAiKey && pluginPreferences.isPluginEnabled(loadedPlugin.metadata.id)) {
                            pluginPreferences.setPluginEnabled(loadedPlugin.metadata.id, false)
                            Log.i(TAG, "Auto-disabled image-gen: OpenAI API key not configured")
                        }
                    }

                    // Auto-disable Google plugins if not signed in
                    if (loadedPlugin.metadata.id in GOOGLE_PLUGIN_IDS) {
                        val signedIn = googleAuthProvider?.isSignedIn() == true
                        if (!signedIn && pluginPreferences.isPluginEnabled(loadedPlugin.metadata.id)) {
                            pluginPreferences.setPluginEnabled(loadedPlugin.metadata.id, false)
                            Log.i(TAG, "Auto-disabled ${loadedPlugin.metadata.id}: Google sign-in required")
                        }
                    }

                    // Auto-disable plugins with missing per-plugin credentials
                    PLUGIN_CREDENTIAL_KEYS[loadedPlugin.metadata.id]?.let { credKey ->
                        val hasKey = !credentialVault.getApiKey("plugin.${loadedPlugin.metadata.id}.$credKey").isNullOrBlank()
                        if (!hasKey && pluginPreferences.isPluginEnabled(loadedPlugin.metadata.id)) {
                            pluginPreferences.setPluginEnabled(loadedPlugin.metadata.id, false)
                            Log.i(TAG, "Auto-disabled ${loadedPlugin.metadata.id}: API key not configured")
                        }
                    }

                    // Auto-disable plugins that require a provider API key
                    PROVIDER_KEY_PLUGIN_IDS[loadedPlugin.metadata.id]?.let { provider ->
                        val hasKey = !credentialVault.getApiKey(provider).isNullOrBlank()
                        if (!hasKey && pluginPreferences.isPluginEnabled(loadedPlugin.metadata.id)) {
                            pluginPreferences.setPluginEnabled(loadedPlugin.metadata.id, false)
                            Log.i(TAG, "Auto-disabled ${loadedPlugin.metadata.id}: $provider API key not configured")
                        }
                    }

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
