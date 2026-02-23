package com.tomandy.oneclaw.llm

/**
 * Metadata for a specific LLM model.
 */
data class LlmModel(
    val name: String,
    val contextWindow: Int,
    val supportsThinking: Boolean = false
)

/**
 * Supported LLM providers
 */
enum class LlmProvider(
    val displayName: String,
    val apiKeyLabel: String,
    val defaultModel: String,
    val models: List<LlmModel>,
    val supportsAudioInput: Boolean = false
) {
    OPENAI(
        displayName = "OpenAI",
        apiKeyLabel = "OpenAI API Key",
        defaultModel = "gpt-4.1-mini",
        models = listOf(
            LlmModel("gpt-4.1", 1_000_000),
            LlmModel("gpt-4.1-mini", 1_000_000),
            LlmModel("gpt-4.1-nano", 1_000_000),
            LlmModel("gpt-4o", 128_000),
            LlmModel("gpt-4o-mini", 128_000)
        ),
        supportsAudioInput = true
    ),
    GEMINI(
        displayName = "Google Gemini",
        apiKeyLabel = "Google AI API Key",
        defaultModel = "gemini-2.5-flash",
        models = listOf(
            LlmModel("gemini-2.5-pro", 1_000_000),
            LlmModel("gemini-2.5-flash", 1_000_000),
            LlmModel("gemini-3-pro-preview", 1_000_000),
            LlmModel("gemini-3-flash-preview", 1_000_000)
        ),
        supportsAudioInput = true
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        apiKeyLabel = "Anthropic API Key",
        defaultModel = "claude-sonnet-4-5",
        models = listOf(
            LlmModel("claude-sonnet-4-5", 200_000, supportsThinking = true),
            LlmModel("claude-opus-4-6", 200_000, supportsThinking = true),
            LlmModel("claude-haiku-4-5", 200_000)
        ),
        supportsAudioInput = false
    ),
    ANTIGRAVITY(
        displayName = "Antigravity",
        apiKeyLabel = "Google Account",
        defaultModel = "ag/claude-sonnet-4-5",
        models = listOf(
            LlmModel("ag/claude-opus-4-6-thinking", 200_000, supportsThinking = true),
            LlmModel("ag/claude-sonnet-4-5", 200_000, supportsThinking = true),
            LlmModel("ag/claude-sonnet-4-5-thinking", 200_000, supportsThinking = true),
            LlmModel("ag/gemini-3-flash", 1_000_000),
            LlmModel("ag/gemini-3-pro-high", 1_000_000),
            LlmModel("ag/gemini-3-pro-low", 1_000_000)
        ),
        supportsAudioInput = false
    );

    val availableModels: List<String> get() = models.map { it.name }

    companion object {
        private val allModels: Map<String, LlmModel> by lazy {
            entries.flatMap { it.models }.associateBy { it.name }
        }

        private val fallbackModel = LlmModel("unknown", 200_000)

        fun getModel(model: String): LlmModel {
            return allModels[model] ?: fallbackModel
        }

        fun getContextWindow(model: String): Int {
            return getModel(model).contextWindow
        }

        fun fromDisplayName(name: String): LlmProvider? {
            return entries.find { it.displayName == name }
        }
    }
}
