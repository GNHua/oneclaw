package com.tomandy.palmclaw.llm

/**
 * Supported LLM providers
 */
enum class LlmProvider(
    val displayName: String,
    val apiKeyLabel: String,
    val defaultModel: String,
    val availableModels: List<String>,
    val supportsBaseUrl: Boolean = false
) {
    OPENAI(
        displayName = "OpenAI",
        apiKeyLabel = "OpenAI API Key",
        defaultModel = "gpt-4.1-mini",
        availableModels = listOf(
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4.1-nano",
            "gpt-4o",
            "gpt-4o-mini"
        )
    ),
    GEMINI(
        displayName = "Google Gemini",
        apiKeyLabel = "Google AI API Key",
        defaultModel = "gemini-2.5-flash",
        availableModels = listOf(
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-3-pro-preview",
            "gemini-3-flash-preview"
        )
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        apiKeyLabel = "Anthropic API Key",
        defaultModel = "claude-sonnet-4-5",
        availableModels = listOf(
            "claude-sonnet-4-5",
            "claude-opus-4-6",
            "claude-haiku-4-5"
        ),
        supportsBaseUrl = true
    );

    companion object {
        private val contextWindows = mapOf(
            // OpenAI
            "gpt-4.1" to 1_000_000,
            "gpt-4.1-mini" to 1_000_000,
            "gpt-4.1-nano" to 1_000_000,
            "gpt-4o" to 128_000,
            "gpt-4o-mini" to 128_000,
            // Gemini
            "gemini-2.5-pro" to 1_000_000,
            "gemini-2.5-flash" to 1_000_000,
            "gemini-3-pro-preview" to 1_000_000,
            "gemini-3-flash-preview" to 1_000_000,
            // Anthropic
            "claude-sonnet-4-5" to 200_000,
            "claude-opus-4-6" to 200_000,
            "claude-haiku-4-5" to 200_000
        )

        fun getContextWindow(model: String): Int {
            return contextWindows[model] ?: 200_000
        }

        fun fromDisplayName(name: String): LlmProvider? {
            return entries.find { it.displayName == name }
        }
    }
}
