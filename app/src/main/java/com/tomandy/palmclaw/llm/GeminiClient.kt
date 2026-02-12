package com.tomandy.palmclaw.llm

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.content

/**
 * Google Gemini client implementation using the official Generative AI SDK
 */
class GeminiClient(
    private var apiKey: String = "",
    private var baseModel: String = "gemini-3-flash"
) : LlmClient {

    @Volatile
    private var generativeModel: GenerativeModel? = null

    init {
        if (apiKey.isNotEmpty()) {
            initializeModel()
        }
    }

    private fun initializeModel() {
        generativeModel = GenerativeModel(
            modelName = baseModel,
            apiKey = apiKey
        )
    }

    @Synchronized
    override fun setApiKey(apiKey: String) {
        this.apiKey = apiKey
        initializeModel()
    }

    @Synchronized
    override fun setBaseUrl(baseUrl: String) {
        // Gemini SDK doesn't support custom base URLs
        // This method is kept for interface compatibility
    }

    override suspend fun complete(
        messages: List<Message>,
        model: String,
        temperature: Float,
        maxTokens: Int?,
        tools: List<Tool>?
    ): Result<LlmResponse> {
        return try {
            val currentModel = generativeModel
                ?: return Result.failure(Exception("API key not set. Please configure your Google AI API key in Settings."))

            // Convert messages to Gemini format (filter out tool messages and messages without content)
            val history = messages.dropLast(1)
                .filter { it.role != "tool" && !it.content.isNullOrBlank() }
                .map { msg ->
                    content(role = if (msg.role == "user") "user" else "model") {
                        text(msg.content!!)
                    }
                }

            val lastMessage = messages.lastOrNull()
                ?: return Result.failure(Exception("No messages provided"))

            // Create model (tools not yet supported in Phase 1)
            val modelToUse = if (model.isNotEmpty() && model != baseModel) {
                GenerativeModel(
                    modelName = model,
                    apiKey = apiKey
                )
            } else {
                currentModel
            }

            // Start chat with history
            val chat = modelToUse.startChat(history = history)

            // Send the last message
            val messageContent = lastMessage.content
                ?: return Result.failure(Exception("Last message has no content"))
            val response = chat.sendMessage(messageContent)

            // Convert response
            Result.success(convertResponse(response))
        } catch (e: Exception) {
            Result.failure(
                Exception("Gemini API error: ${e.message}", e)
            )
        }
    }

    private fun convertResponse(response: GenerateContentResponse): LlmResponse {
        val candidate = response.candidates.firstOrNull()
        val content = candidate?.content?.parts?.filterIsInstance<TextPart>()?.firstOrNull()?.text ?: ""

        // Check for function calls
        val functionCalls = candidate?.content?.parts?.mapNotNull { part ->
            // Gemini SDK structure for function calls
            try {
                if (part is com.google.ai.client.generativeai.type.FunctionCallPart) {
                    ToolCall(
                        id = "gemini-func-${System.currentTimeMillis()}",
                        type = "function",
                        function = FunctionCall(
                            name = part.name,
                            arguments = part.args.toString()
                        )
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }

        return LlmResponse(
            id = "gemini-${System.currentTimeMillis()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = MessageResponse(
                        role = "assistant",
                        content = content.ifEmpty { null },
                        tool_calls = functionCalls?.takeIf { it.isNotEmpty() }
                    ),
                    finish_reason = candidate?.finishReason?.name?.lowercase() ?: "stop"
                )
            ),
            usage = null // Gemini SDK doesn't expose token usage in the same way
        )
    }
}
