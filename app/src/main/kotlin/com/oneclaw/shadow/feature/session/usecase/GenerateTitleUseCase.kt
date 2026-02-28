package com.oneclaw.shadow.feature.session.usecase

import com.oneclaw.shadow.core.model.ProviderType
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.data.remote.adapter.ModelApiAdapterFactory
import com.oneclaw.shadow.data.security.ApiKeyStorage

/**
 * Two-phase title generation for a session.
 *
 * Phase 1: Immediate truncated title from first user message (synchronous).
 * Phase 2: Async AI-generated title using a lightweight model.
 */
class GenerateTitleUseCase(
    private val sessionRepository: SessionRepository,
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val adapterFactory: ModelApiAdapterFactory
) {

    companion object {
        private const val TRUNCATED_TITLE_MAX_LENGTH = 50
        private const val AI_RESPONSE_CONTEXT_MAX_LENGTH = 200

        private val LIGHTWEIGHT_MODELS = mapOf(
            ProviderType.OPENAI to "gpt-4o-mini",
            ProviderType.ANTHROPIC to "claude-haiku-4-5-20251001",
            ProviderType.GEMINI to "gemini-2.0-flash"
        )
    }

    /**
     * Phase 1: Generate a truncated title from the first user message.
     * Synchronous and instant — call immediately when the first message is sent.
     */
    fun generateTruncatedTitle(firstMessage: String): String {
        val trimmed = firstMessage.trim()
        if (trimmed.length <= TRUNCATED_TITLE_MAX_LENGTH) return trimmed

        val truncated = trimmed.substring(0, TRUNCATED_TITLE_MAX_LENGTH)
        val lastSpace = truncated.lastIndexOf(' ')
        return if (lastSpace > TRUNCATED_TITLE_MAX_LENGTH / 2) {
            truncated.substring(0, lastSpace) + "..."
        } else {
            "$truncated..."
        }
    }

    /**
     * Phase 2: Generate an AI-powered title using a lightweight model.
     * Call asynchronously after the first AI response is received.
     * Silently fails on any error — keeps the truncated title.
     */
    suspend fun generateAiTitle(
        sessionId: String,
        firstUserMessage: String,
        firstAiResponse: String,
        currentModelId: String,
        currentProviderId: String
    ) {
        try {
            val provider = providerRepository.getProviderById(currentProviderId) ?: return
            val apiKey = apiKeyStorage.getApiKey(currentProviderId) ?: return
            val modelId = resolveLightweightModel(provider.id, provider.type, currentModelId)

            val truncatedResponse = if (firstAiResponse.length > AI_RESPONSE_CONTEXT_MAX_LENGTH) {
                firstAiResponse.substring(0, AI_RESPONSE_CONTEXT_MAX_LENGTH) + "..."
            } else {
                firstAiResponse
            }

            val prompt = buildTitlePrompt(firstUserMessage, truncatedResponse)
            val adapter = adapterFactory.getAdapter(provider.type)

            val titleResult = adapter.generateSimpleCompletion(
                apiBaseUrl = provider.apiBaseUrl,
                apiKey = apiKey,
                modelId = modelId,
                prompt = prompt,
                maxTokens = 30
            )

            when (titleResult) {
                is AppResult.Success -> {
                    val generatedTitle = titleResult.data
                        .trim()
                        .removeSurrounding("\"")
                        .take(200)
                    if (generatedTitle.isNotBlank()) {
                        sessionRepository.setGeneratedTitle(sessionId, generatedTitle)
                    }
                }
                is AppResult.Error -> { /* silently fail, keep truncated title */ }
            }
        } catch (_: Exception) {
            // silently fail
        }
    }

    /**
     * Resolve which model to use for title generation.
     * Prefers the hardcoded lightweight model if it exists in the DB for this provider;
     * otherwise falls back to the model currently in use for the conversation.
     */
    suspend fun resolveLightweightModel(
        providerId: String,
        providerType: ProviderType,
        currentModelId: String
    ): String {
        val lightweightId = LIGHTWEIGHT_MODELS[providerType] ?: return currentModelId
        val models = providerRepository.getModelsForProvider(providerId)
        return if (models.any { it.id == lightweightId }) lightweightId else currentModelId
    }

    private fun buildTitlePrompt(userMessage: String, aiResponse: String): String =
        """Generate a short title (5-10 words) for this conversation. Return only the title text, no quotes or extra formatting.

User: $userMessage
Assistant: $aiResponse"""
}
