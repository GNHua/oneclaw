package com.tomandy.palmclaw

import android.app.Application
import android.util.Log
import com.tomandy.palmclaw.agent.ToolExecutor
import com.tomandy.palmclaw.agent.ToolRegistry
import com.tomandy.palmclaw.data.AppDatabase
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.PluginEngine
import com.tomandy.palmclaw.llm.GeminiClient
import com.tomandy.palmclaw.llm.LlmClient
import com.tomandy.palmclaw.llm.LlmProvider
import com.tomandy.palmclaw.llm.OpenAiClient
import com.tomandy.palmclaw.security.CredentialVault
import com.tomandy.palmclaw.security.CredentialVaultImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application class for PalmClaw.
 *
 * This serves as the dependency injection container for the entire application.
 * It initializes and provides access to:
 * - Database (Room)
 * - Credential vault (encrypted storage)
 * - LLM clients (OpenAI, Gemini, etc.)
 * - Tool registry (plugin management)
 * - Tool executor (tool execution)
 *
 * All dependencies are initialized once on app startup and shared across components.
 */
class PalmClawApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var credentialVault: CredentialVault
        private set

    lateinit var modelPreferences: ModelPreferences
        private set

    private val _selectedProvider = MutableStateFlow(LlmProvider.OPENAI)
    val selectedProvider: StateFlow<LlmProvider> = _selectedProvider.asStateFlow()

    private val openAiClient = OpenAiClient(apiKey = "")
    private val geminiClient = GeminiClient(apiKey = "")

    lateinit var toolRegistry: ToolRegistry
        private set

    lateinit var toolExecutor: ToolExecutor
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize database
        database = AppDatabase.getInstance(this)

        // Initialize credential vault
        credentialVault = CredentialVaultImpl(this)

        // Initialize model preferences
        modelPreferences = ModelPreferences(this)

        // Initialize tool registry (shared across all conversations)
        toolRegistry = ToolRegistry()

        // Initialize tool executor (shared, uses database for persistence)
        toolExecutor = ToolExecutor(
            toolRegistry = toolRegistry,
            messageDao = database.messageDao()
        )

        // Load API keys from vault and set active provider
        CoroutineScope(Dispatchers.Main).launch {
            loadApiKeys()
        }

        // Load sample plugins
        CoroutineScope(Dispatchers.Main).launch {
            loadSamplePlugins()
        }
    }

    /**
     * Load API keys for all providers and determine which one to use
     */
    private suspend fun loadApiKeys() {
        val providers = credentialVault.listProviders()

        // Load OpenAI key if available
        credentialVault.getApiKey(LlmProvider.OPENAI.displayName)?.let { key ->
            openAiClient.setApiKey(key)
        }

        // Load Gemini key if available
        credentialVault.getApiKey(LlmProvider.GEMINI.displayName)?.let { key ->
            geminiClient.setApiKey(key)
        }

        // Set default provider based on which keys are available
        val activeProvider = when {
            providers.contains(LlmProvider.GEMINI.displayName) -> LlmProvider.GEMINI
            providers.contains(LlmProvider.OPENAI.displayName) -> LlmProvider.OPENAI
            else -> LlmProvider.OPENAI // Default to OpenAI
        }

        setActiveProvider(activeProvider)
    }

    /**
     * Set the active LLM provider
     */
    fun setActiveProvider(provider: LlmProvider) {
        _selectedProvider.value = provider
    }

    /**
     * Get the current active LLM client
     */
    fun getCurrentLlmClient(): LlmClient {
        return when (_selectedProvider.value) {
            LlmProvider.OPENAI -> openAiClient
            LlmProvider.GEMINI -> geminiClient
        }
    }

    /**
     * Reload API keys after settings changes
     */
    suspend fun reloadApiKeys() {
        loadApiKeys()
    }

    /**
     * Get all available models from providers that have API keys configured
     */
    suspend fun getAvailableModels(): List<Pair<String, LlmProvider>> {
        val providers = credentialVault.listProviders()
        val models = mutableListOf<Pair<String, LlmProvider>>()

        LlmProvider.entries.forEach { provider ->
            if (providers.contains(provider.displayName)) {
                provider.availableModels.forEach { model ->
                    models.add(model to provider)
                }
            }
        }

        return models
    }

    /**
     * Get the provider for a given model name
     */
    fun getProviderForModel(model: String): LlmProvider? {
        return LlmProvider.entries.find { provider ->
            provider.availableModels.contains(model)
        }
    }

    /**
     * Set active provider and model
     */
    fun setModelAndProvider(model: String) {
        val provider = getProviderForModel(model)
        if (provider != null) {
            _selectedProvider.value = provider
            modelPreferences.saveSelectedModel(model)
        }
    }

    /**
     * Load sample plugins from JavaScript files in assets.
     *
     * Plugins loaded:
     * - calculator: Basic math operations
     * - time: Current time and date
     * - notes: Simple note-taking
     * - echo: Echo back messages
     */
    private suspend fun loadSamplePlugins() {
        val pluginEngine = PluginEngine(this)
        val pluginPaths = listOf(
            "plugins/calculator",
            "plugins/time",
            "plugins/notes",
            "plugins/echo"
        )

        pluginPaths.forEach { path ->
            val pluginId = path.substringAfterLast("/")
            val pluginContext = PluginContext(this, pluginId, credentialVault)
            pluginEngine.loadFromAssets(path, pluginContext)
                .onSuccess { loadedPlugin ->
                    toolRegistry.registerPlugin(loadedPlugin)
                    Log.i("PalmClaw", "Loaded plugin: ${loadedPlugin.metadata.name}")
                }
                .onFailure { error ->
                    Log.e("PalmClaw", "Failed to load $pluginId: ${error.message}", error)
                }
        }
    }
}
