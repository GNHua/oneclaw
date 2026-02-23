package com.tomandy.oneclaw.llm

import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.security.CredentialVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LlmClientProvider(
    private val credentialVault: CredentialVault,
    private val modelPreferences: ModelPreferences,
    antigravityTokenProvider: (suspend () -> String?)? = null,
    antigravityProjectIdProvider: (suspend () -> String?)? = null
) {
    private val openAiClient = OpenAiClient()
    private val geminiClient = GeminiClient()
    private val anthropicClient = AnthropicClient()
    private val antigravityClient: AntigravityClient? =
        if (antigravityTokenProvider != null &&
            antigravityProjectIdProvider != null
        ) {
            AntigravityClient(
                antigravityTokenProvider,
                antigravityProjectIdProvider
            )
        } else {
            null
        }

    private val _selectedProvider = MutableStateFlow(LlmProvider.OPENAI)
    val selectedProvider: StateFlow<LlmProvider> = _selectedProvider.asStateFlow()

    fun getCurrentLlmClient(): LlmClient {
        return when (_selectedProvider.value) {
            LlmProvider.OPENAI -> openAiClient
            LlmProvider.GEMINI -> geminiClient
            LlmProvider.ANTHROPIC -> anthropicClient
            LlmProvider.ANTIGRAVITY -> antigravityClient
                ?: throw IllegalStateException(
                    "Antigravity not configured"
                )
        }
    }

    fun setActiveProvider(provider: LlmProvider) {
        _selectedProvider.value = provider
    }

    suspend fun loadApiKeys() {
        val providers = credentialVault.listProviders()

        // Load OpenAI key + base URL if available
        credentialVault.getApiKey(LlmProvider.OPENAI.displayName)?.let { key ->
            openAiClient.setApiKey(key)
        }
        credentialVault.getApiKey("${LlmProvider.OPENAI.displayName}_baseUrl")?.let { url ->
            openAiClient.setBaseUrl(url)
        }

        // Load Gemini key + base URL if available
        credentialVault.getApiKey(LlmProvider.GEMINI.displayName)?.let { key ->
            geminiClient.setApiKey(key)
        }
        credentialVault.getApiKey("${LlmProvider.GEMINI.displayName}_baseUrl")?.let { url ->
            geminiClient.setBaseUrl(url)
        }

        // Load Anthropic key + base URL if available
        credentialVault.getApiKey(LlmProvider.ANTHROPIC.displayName)?.let { key ->
            anthropicClient.setApiKey(key)
        }
        credentialVault.getApiKey("${LlmProvider.ANTHROPIC.displayName}_baseUrl")?.let { url ->
            anthropicClient.setBaseUrl(url)
        }

        // Antigravity: no API key to load (auth handled by AntigravityAuthManager)

        // Restore provider from saved model selection, falling back to key-based priority
        val savedModel = modelPreferences.getSelectedModel()
        val restoredProvider = savedModel?.let { getProviderForModel(it) }
            ?.takeIf { providers.contains(it.displayName) }

        val activeProvider = restoredProvider ?: when {
            providers.contains(LlmProvider.ANTHROPIC.displayName) -> LlmProvider.ANTHROPIC
            providers.contains(LlmProvider.ANTIGRAVITY.displayName) -> LlmProvider.ANTIGRAVITY
            providers.contains(LlmProvider.GEMINI.displayName) -> LlmProvider.GEMINI
            providers.contains(LlmProvider.OPENAI.displayName) -> LlmProvider.OPENAI
            else -> LlmProvider.OPENAI
        }

        setActiveProvider(activeProvider)
    }

    suspend fun reloadApiKeys() = loadApiKeys()

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

    fun getProviderForModel(model: String): LlmProvider? {
        return LlmProvider.entries.find { provider ->
            provider.availableModels.contains(model)
        }
    }

    fun setModelAndProvider(model: String) {
        val provider = getProviderForModel(model)
        if (provider != null) {
            _selectedProvider.value = provider
            modelPreferences.saveSelectedModel(model)
        }
    }
}
