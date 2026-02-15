package com.tomandy.palmclaw

import android.app.Application
import android.util.Log
import com.tomandy.palmclaw.agent.MessageStore
import com.tomandy.palmclaw.agent.ScheduledAgentExecutor
import com.tomandy.palmclaw.agent.ToolExecutor
import com.tomandy.palmclaw.agent.ToolRegistry
import com.tomandy.palmclaw.pluginmanager.InstallPluginTool
import com.tomandy.palmclaw.data.AppDatabase
import com.tomandy.palmclaw.data.ConversationPreferences
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.pluginmanager.PluginPreferences
import com.tomandy.palmclaw.data.RoomMessageStore
import com.tomandy.palmclaw.pluginmanager.UserPluginManager
import com.tomandy.palmclaw.engine.LoadedPlugin
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.PluginEngine
import com.tomandy.palmclaw.llm.AnthropicClient
import com.tomandy.palmclaw.llm.GeminiClient
import com.tomandy.palmclaw.llm.LlmClient
import com.tomandy.palmclaw.llm.LlmProvider
import com.tomandy.palmclaw.llm.OpenAiClient
import com.tomandy.palmclaw.scheduler.AgentExecutor
import com.tomandy.palmclaw.scheduler.CronjobManager
import com.tomandy.palmclaw.scheduler.plugin.SchedulerPlugin
import com.tomandy.palmclaw.scheduler.plugin.SchedulerPluginMetadata
import com.tomandy.palmclaw.security.CredentialVault
import com.tomandy.palmclaw.security.CredentialVaultAdapter
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

    lateinit var pluginPreferences: PluginPreferences
        private set

    lateinit var pluginEngine: PluginEngine
        private set

    private val _selectedProvider = MutableStateFlow(LlmProvider.OPENAI)
    val selectedProvider: StateFlow<LlmProvider> = _selectedProvider.asStateFlow()

    private val openAiClient = OpenAiClient(apiKey = "")
    private val geminiClient = GeminiClient(apiKey = "")
    private val anthropicClient = AnthropicClient()

    lateinit var toolRegistry: ToolRegistry
        private set

    lateinit var messageStore: MessageStore
        private set

    lateinit var toolExecutor: ToolExecutor
        private set

    lateinit var conversationPreferences: ConversationPreferences
        private set

    lateinit var cronjobManager: CronjobManager
        private set

    lateinit var userPluginManager: UserPluginManager
        private set

    val pendingConversationId = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()

        // Initialize database
        database = AppDatabase.getInstance(this)

        // Initialize credential vault
        credentialVault = CredentialVaultImpl(this)

        // Initialize model preferences
        modelPreferences = ModelPreferences(this)

        // Initialize conversation preferences
        conversationPreferences = ConversationPreferences(this)

        // Initialize plugin preferences and engine
        pluginPreferences = PluginPreferences(this)
        pluginEngine = PluginEngine(this)

        // Initialize tool registry (shared across all conversations)
        toolRegistry = ToolRegistry()

        // Initialize message store (bridges core-agent to Room)
        messageStore = RoomMessageStore(database.messageDao())

        // Initialize tool executor (shared, uses message store for persistence)
        toolExecutor = ToolExecutor(
            toolRegistry = toolRegistry,
            messageStore = messageStore
        )

        // Initialize cronjob manager
        cronjobManager = CronjobManager(this)

        // Initialize user plugin manager
        userPluginManager = UserPluginManager(
            context = this,
            pluginEngine = pluginEngine,
            toolRegistry = toolRegistry,
            pluginPreferences = pluginPreferences,
            credentialVault = credentialVault
        )

        // Initialize agent executor for scheduled tasks
        AgentExecutor.instance = ScheduledAgentExecutor(this)

        // Register built-in plugins
        CoroutineScope(Dispatchers.Main).launch {
            registerBuiltInPlugins()
            registerInstallPluginTool()
            loadApiKeys()
        }

        // Load sample plugins and user plugins
        CoroutineScope(Dispatchers.Main).launch {
            loadSamplePlugins()
            userPluginManager.loadAllUserPlugins()
        }
    }

    /**
     * Register built-in plugins (plugins that come with the app)
     */
    private suspend fun registerBuiltInPlugins() {
        // Register SchedulerPlugin
        try {
            val schedulerPlugin = SchedulerPlugin()
            val pluginContext = PluginContext.create(
                androidContext = applicationContext,
                pluginId = "scheduler",
                credentialVault = CredentialVaultAdapter(credentialVault)
            )

            // Initialize the plugin
            schedulerPlugin.onLoad(pluginContext)

            // Create LoadedPlugin wrapper
            val loadedPlugin = LoadedPlugin(
                metadata = SchedulerPluginMetadata.get(),
                instance = schedulerPlugin
            )

            // Register with tool registry
            toolRegistry.registerPlugin(loadedPlugin)

        } catch (e: Exception) {
            // Log error but don't crash the app
            e.printStackTrace()
        }
    }

    /**
     * Register the install_plugin tool so the LLM can create plugins.
     */
    private fun registerInstallPluginTool() {
        val installTool = InstallPluginTool(userPluginManager)
        val loadedPlugin = LoadedPlugin(
            metadata = InstallPluginTool.metadata(),
            instance = installTool
        )
        toolRegistry.registerPlugin(loadedPlugin)
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

        // Load Anthropic key + base URL if available
        credentialVault.getApiKey(LlmProvider.ANTHROPIC.displayName)?.let { key ->
            anthropicClient.setApiKey(key)
        }
        credentialVault.getApiKey("${LlmProvider.ANTHROPIC.displayName}_baseUrl")?.let { url ->
            anthropicClient.setBaseUrl(url)
        }

        // Restore provider from saved model selection, falling back to key-based priority
        val savedModel = modelPreferences.getSelectedModel()
        val restoredProvider = savedModel?.let { getProviderForModel(it) }
            ?.takeIf { providers.contains(it.displayName) }

        val activeProvider = restoredProvider ?: when {
            providers.contains(LlmProvider.ANTHROPIC.displayName) -> LlmProvider.ANTHROPIC
            providers.contains(LlmProvider.GEMINI.displayName) -> LlmProvider.GEMINI
            providers.contains(LlmProvider.OPENAI.displayName) -> LlmProvider.OPENAI
            else -> LlmProvider.OPENAI
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
            LlmProvider.ANTHROPIC -> anthropicClient
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
     * All plugins are loaded into PluginEngine (for metadata), but only
     * enabled plugins are registered with ToolRegistry.
     */
    private suspend fun loadSamplePlugins() {
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
                    if (pluginPreferences.isPluginEnabled(loadedPlugin.metadata.id)) {
                        toolRegistry.registerPlugin(loadedPlugin)
                    }
                    Log.i("PalmClaw", "Loaded plugin: ${loadedPlugin.metadata.name} (enabled=${pluginPreferences.isPluginEnabled(loadedPlugin.metadata.id)})")
                }
                .onFailure { error ->
                    Log.e("PalmClaw", "Failed to load $pluginId: ${error.message}", error)
                }
        }
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        pluginPreferences.setPluginEnabled(pluginId, enabled)
        if (enabled) {
            pluginEngine.getLoadedPlugin(pluginId)?.let { loadedPlugin ->
                toolRegistry.registerPlugin(loadedPlugin)
            }
        } else {
            toolRegistry.unregisterPlugin(pluginId)
        }
    }
}
