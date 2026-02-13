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
        defaultModel = "gpt-4o-mini",
        availableModels = listOf(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4-turbo",
            "gpt-3.5-turbo"
        )
    ),
    GEMINI(
        displayName = "Google Gemini",
        apiKeyLabel = "Google AI API Key",
        defaultModel = "gemini-3-flash",
        availableModels = listOf(
            "gemini-3-flash",
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
        fun fromDisplayName(name: String): LlmProvider? {
            return entries.find { it.displayName == name }
        }
    }
}
