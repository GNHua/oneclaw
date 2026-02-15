package com.tomandy.palmclaw.llm

import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.security.CredentialVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LlmClientProvider(
    private val credentialVault: CredentialVault,
    private val modelPreferences: ModelPreferences
) {
    private val openAiClient = OpenAiClient(apiKey = "")
    private val geminiClient = GeminiClient(apiKey = "")
    private val anthropicClient = AnthropicClient()

    private val _selectedProvider = MutableStateFlow(LlmProvider.OPENAI)
    val selectedProvider: StateFlow<LlmProvider> = _selectedProvider.asStateFlow()

    fun getCurrentLlmClient(): LlmClient {
        return when (_selectedProvider.value) {
            LlmProvider.OPENAI -> openAiClient
            LlmProvider.GEMINI -> geminiClient
            LlmProvider.ANTHROPIC -> anthropicClient
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
