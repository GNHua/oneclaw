package com.tomandy.palmclaw.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.pluginmanager.PluginPreferences
import com.tomandy.palmclaw.pluginmanager.UserPluginManager
import com.tomandy.palmclaw.engine.GoogleAuthProvider
import com.tomandy.palmclaw.engine.LoadedPlugin
import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.llm.LlmProvider
import com.tomandy.palmclaw.security.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing API key settings and model selection.
 */
class SettingsViewModel(
    private val credentialVault: CredentialVault,
    private val modelPreferences: ModelPreferences,
    private val pluginPreferences: PluginPreferences,
    private val loadedPlugins: List<LoadedPlugin>,
    private val userPluginManager: UserPluginManager,
    private val onApiKeyChanged: suspend () -> Unit,
    private val onPluginToggled: (String, Boolean) -> Unit,
    private val googleAuthProvider: GoogleAuthProvider
) : ViewModel() {

    companion object {
        private val GOOGLE_PLUGIN_IDS = setOf(
            "google-gmail", "google-gmail-settings", "google-calendar",
            "google-tasks", "google-contacts", "google-drive",
            "google-docs", "google-sheets", "google-slides", "google-forms"
        )

        private val PLUGIN_CREDENTIAL_IDS = setOf("web", "smart-home", "notion")
    }

    private val _providers = MutableStateFlow<List<String>>(emptyList())
    val providers: StateFlow<List<String>> = _providers.asStateFlow()

    private val _saveStatus = MutableStateFlow<SaveStatus>(SaveStatus.Idle)
    val saveStatus: StateFlow<SaveStatus> = _saveStatus.asStateFlow()

    private val _deleteStatus = MutableStateFlow<DeleteStatus>(DeleteStatus.Idle)
    val deleteStatus: StateFlow<DeleteStatus> = _deleteStatus.asStateFlow()

    private val _plugins = MutableStateFlow<List<PluginUiState>>(emptyList())
    val plugins: StateFlow<List<PluginUiState>> = _plugins.asStateFlow()

    private val _importStatus = MutableStateFlow<ImportStatus>(ImportStatus.Idle)
    val importStatus: StateFlow<ImportStatus> = _importStatus.asStateFlow()

    init {
        loadProviders()
        loadPlugins()
    }

    private fun loadPlugins() {
        val userPluginIds = userPluginManager.getUserPluginIds()
        _plugins.value = loadedPlugins.map { loaded ->
            PluginUiState(
                metadata = loaded.metadata,
                enabled = pluginPreferences.isPluginEnabled(loaded.metadata.id),
                isUserPlugin = loaded.metadata.id in userPluginIds
            )
        }
        viewModelScope.launch { refreshPluginStates() }
    }

    private suspend fun refreshPluginStates() {
        val hasOpenAiKey = !credentialVault.getApiKey("OpenAI").isNullOrBlank()
        val googleSignedIn = googleAuthProvider.isSignedIn()

        _plugins.value = _plugins.value.map { state ->
            when {
                state.metadata.id == "image-gen" && !hasOpenAiKey -> state.copy(
                    toggleable = false,
                    toggleDisabledReason = "Requires OpenAI API key"
                )
                state.metadata.id in GOOGLE_PLUGIN_IDS && !googleSignedIn -> state.copy(
                    toggleable = false,
                    toggleDisabledReason = "Requires Google sign-in"
                )
                state.metadata.id in PLUGIN_CREDENTIAL_IDS &&
                    isPluginMissingCredentials(state) -> state.copy(
                    toggleable = false,
                    toggleDisabledReason = "Requires API key configuration"
                )
                else -> state.copy(toggleable = true, toggleDisabledReason = null)
            }
        }
    }

    private suspend fun isPluginMissingCredentials(state: PluginUiState): Boolean {
        val creds = state.metadata.credentials
        if (creds.isEmpty()) return false
        val pluginId = state.metadata.id
        // Resolve dropdown/scope values first
        val scopeValues = mutableMapOf<String, String>()
        creds.filter { it.options.isNotEmpty() }.forEach { cred ->
            scopeValues[cred.key] =
                credentialVault.getApiKey("plugin.$pluginId.${cred.key}") ?: ""
        }
        // Check non-dropdown credentials using scoped storage keys
        return creds.filter { it.options.isEmpty() }.any { cred ->
            val storageKey = if (cred.scopedBy.isNotEmpty()) {
                val scope = scopeValues[cred.scopedBy] ?: ""
                if (scope.isNotEmpty()) "${scope}_${cred.key}" else cred.key
            } else {
                cred.key
            }
            credentialVault.getApiKey("plugin.$pluginId.$storageKey").isNullOrBlank()
        }
    }

    fun togglePlugin(pluginId: String, enabled: Boolean) {
        onPluginToggled(pluginId, enabled)
        _plugins.value = _plugins.value.map { state ->
            if (state.metadata.id == pluginId) state.copy(enabled = enabled) else state
        }
    }

    fun onGoogleSignInChanged(signedIn: Boolean) {
        viewModelScope.launch {
            GOOGLE_PLUGIN_IDS.forEach { id ->
                togglePlugin(id, signedIn)
            }
            refreshPluginStates()
        }
    }

    fun importPluginFromZip(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _importStatus.value = ImportStatus.Importing
            userPluginManager.importFromZip(uri)
                .onSuccess { loaded ->
                    addPluginToList(loaded)
                    _importStatus.value = ImportStatus.Success(loaded.metadata.name)
                    kotlinx.coroutines.delay(2000)
                    _importStatus.value = ImportStatus.Idle
                }
                .onFailure { error ->
                    _importStatus.value = ImportStatus.Error(error.message ?: "Unknown error")
                }
        }
    }

    fun importPluginFromUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _importStatus.value = ImportStatus.Importing
            userPluginManager.importFromUrl(url)
                .onSuccess { loaded ->
                    addPluginToList(loaded)
                    _importStatus.value = ImportStatus.Success(loaded.metadata.name)
                    kotlinx.coroutines.delay(2000)
                    _importStatus.value = ImportStatus.Idle
                }
                .onFailure { error ->
                    _importStatus.value = ImportStatus.Error(error.message ?: "Unknown error")
                }
        }
    }

    fun deletePlugin(pluginId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (userPluginManager.deletePlugin(pluginId)) {
                _plugins.value = _plugins.value.filter { it.metadata.id != pluginId }
            }
        }
    }

    fun resetImportStatus() {
        _importStatus.value = ImportStatus.Idle
    }

    private fun addPluginToList(loaded: LoadedPlugin) {
        val existing = _plugins.value.filter { it.metadata.id != loaded.metadata.id }
        _plugins.value = existing + PluginUiState(
            metadata = loaded.metadata,
            enabled = pluginPreferences.isPluginEnabled(loaded.metadata.id),
            isUserPlugin = true
        )
        // Auto-enable newly imported plugins
        onPluginToggled(loaded.metadata.id, true)
    }

    /**
     * Loads the list of providers with saved API keys.
     */
    fun loadProviders() {
        viewModelScope.launch {
            try {
                val providerList = credentialVault.listProviders()
                _providers.value = providerList
            } catch (e: Exception) {
                // Silent failure on load - UI will show empty list
                _providers.value = emptyList()
            }
        }
    }

    /**
     * Saves an API key for a specific provider.
     * @param provider The provider name (e.g., "OpenAI", "Anthropic")
     * @param key The API key to save
     */
    fun saveApiKey(provider: String, key: String) {
        viewModelScope.launch {
            _saveStatus.value = SaveStatus.Saving

            try {
                if (provider.trim().isEmpty()) {
                    _saveStatus.value = SaveStatus.Error("Provider name cannot be empty")
                    return@launch
                }

                if (key.trim().isEmpty()) {
                    _saveStatus.value = SaveStatus.Error("API key cannot be empty")
                    return@launch
                }

                credentialVault.saveApiKey(provider, key)
                _saveStatus.value = SaveStatus.Success

                // Reload providers list to show newly added provider
                loadProviders()

                // Notify app that API key changed
                onApiKeyChanged()

                // Auto-enable image-gen when OpenAI key is added
                if (provider == "OpenAI") {
                    togglePlugin("image-gen", true)
                    refreshPluginStates()
                }

                // Reset status after a delay
                kotlinx.coroutines.delay(2000)
                _saveStatus.value = SaveStatus.Idle
            } catch (e: SecurityException) {
                _saveStatus.value = SaveStatus.Error("Security error: ${e.message}")
            } catch (e: Exception) {
                _saveStatus.value = SaveStatus.Error("Failed to save: ${e.message}")
            }
        }
    }

    /**
     * Deletes the API key for a specific provider.
     * @param provider The provider name
     */
    fun deleteApiKey(provider: String) {
        viewModelScope.launch {
            _deleteStatus.value = DeleteStatus.Deleting

            try {
                credentialVault.deleteApiKey(provider)
                credentialVault.deleteApiKey("${provider}_baseUrl")
                _deleteStatus.value = DeleteStatus.Success

                // Reload providers list to remove deleted provider
                loadProviders()

                // Auto-disable image-gen when OpenAI key is removed
                if (provider == "OpenAI") {
                    togglePlugin("image-gen", false)
                    refreshPluginStates()
                }

                // Reset status after a delay
                kotlinx.coroutines.delay(1500)
                _deleteStatus.value = DeleteStatus.Idle
            } catch (e: SecurityException) {
                _deleteStatus.value = DeleteStatus.Error("Security error: ${e.message}")
            } catch (e: Exception) {
                _deleteStatus.value = DeleteStatus.Error("Failed to delete: ${e.message}")
            }
        }
    }

    /**
     * Resets the save status to Idle.
     * Useful for dismissing error messages.
     */
    fun resetSaveStatus() {
        _saveStatus.value = SaveStatus.Idle
    }

    /**
     * Resets the delete status to Idle.
     * Useful for dismissing error messages.
     */
    fun resetDeleteStatus() {
        _deleteStatus.value = DeleteStatus.Idle
    }

    /**
     * Saves a base URL for a specific provider.
     */
    fun saveBaseUrl(provider: String, url: String) {
        viewModelScope.launch {
            try {
                credentialVault.saveApiKey("${provider}_baseUrl", url)
            } catch (_: Exception) {
                // Base URL save failure is non-critical
            }
        }
    }

    /**
     * Gets the saved base URL for a specific provider.
     */
    suspend fun getBaseUrl(provider: String): String {
        return credentialVault.getApiKey("${provider}_baseUrl") ?: ""
    }

    /**
     * Saves a plugin-specific credential.
     */
    fun savePluginCredential(pluginId: String, key: String, value: String) {
        viewModelScope.launch {
            val fullKey = "plugin.${pluginId}.${key}"
            if (value.isBlank()) {
                credentialVault.deleteApiKey(fullKey)
            } else {
                credentialVault.saveApiKey(fullKey, value)
            }
            if (pluginId in PLUGIN_CREDENTIAL_IDS) {
                val pluginState = _plugins.value.find { it.metadata.id == pluginId }
                if (pluginState != null) {
                    val missing = isPluginMissingCredentials(pluginState)
                    togglePlugin(pluginId, !missing)
                }
                refreshPluginStates()
            }
        }
    }

    /**
     * Retrieves a plugin-specific credential.
     */
    suspend fun getPluginCredential(pluginId: String, key: String): String {
        return credentialVault.getApiKey("plugin.${pluginId}.${key}") ?: ""
    }

    /**
     * Save the selected model for a provider
     */
    fun saveModel(provider: LlmProvider, model: String) {
        modelPreferences.saveModel(provider, model)
    }

    /**
     * Get the selected model for a provider
     */
    fun getModel(provider: LlmProvider): String {
        return modelPreferences.getModel(provider)
    }
}

/**
 * Represents the status of a save operation.
 */
sealed class SaveStatus {
    object Idle : SaveStatus()
    object Saving : SaveStatus()
    object Success : SaveStatus()
    data class Error(val message: String) : SaveStatus()
}

/**
 * Represents the status of a delete operation.
 */
sealed class DeleteStatus {
    object Idle : DeleteStatus()
    object Deleting : DeleteStatus()
    object Success : DeleteStatus()
    data class Error(val message: String) : DeleteStatus()
}

sealed class ImportStatus {
    object Idle : ImportStatus()
    object Importing : ImportStatus()
    data class Success(val pluginName: String) : ImportStatus()
    data class Error(val message: String) : ImportStatus()
}

data class PluginUiState(
    val metadata: PluginMetadata,
    val enabled: Boolean,
    val isUserPlugin: Boolean = false,
    val toggleable: Boolean = true,
    val toggleDisabledReason: String? = null
)
