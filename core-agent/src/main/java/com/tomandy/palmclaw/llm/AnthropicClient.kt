package com.tomandy.palmclaw.llm

import com.anthropic.client.AnthropicClient as SdkClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.anthropic.models.messages.Tool as AnthropicTool

/**
 * Anthropic/Claude client using the official anthropic-java SDK.
 *
 * Converts between our internal Message/Tool format and the Anthropic SDK types.
 * System messages are extracted and passed via the `system()` parameter.
 * Tool results are sent as user messages with ToolResultBlockParam content.
 */
class AnthropicClient(
    private var apiKey: String = "",
    private var baseUrl: String = ""
) : LlmClient {

    @Volatile
    private var client: SdkClient? = null

    @Synchronized
    override fun setApiKey(apiKey: String) {
        this.apiKey = apiKey
        rebuildClient()
    }

    @Synchronized
    override fun setBaseUrl(baseUrl: String) {
        this.baseUrl = baseUrl
        rebuildClient()
    }

    private fun rebuildClient() {
        client = if (apiKey.isNotEmpty()) {
            val builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
            if (baseUrl.isNotEmpty()) {
                builder.baseUrl(baseUrl)
            }
            builder.build()
        } else null
    }

    override suspend fun complete(
        messages: List<Message>,
        model: String,
        temperature: Float,
        maxTokens: Int?,
        tools: List<Tool>?
    ): Result<LlmResponse> = runInterruptible(Dispatchers.IO) {
        try {
            val sdkClient = client
                ?: return@runInterruptible Result.failure(
                    Exception("API key not set. Please configure your Anthropic API key in Settings.")
                )

            val modelToUse = model.ifEmpty { "claude-sonnet-4-5" }
            val effectiveMaxTokens = (maxTokens ?: 4096).toLong()

            // Separate system messages from conversation messages
            val systemText = messages
                .filter { it.role == "system" && !it.content.isNullOrBlank() }
                .joinToString("\n\n") { it.content!! }

            // Build conversation MessageParams (user, assistant, tool)
            val messageParams = buildMessageParams(messages)

            // Build request
            val paramsBuilder = MessageCreateParams.builder()
                .model(modelToUse)
                .maxTokens(effectiveMaxTokens)

            if (systemText.isNotBlank()) {
                paramsBuilder.systemOfTextBlockParams(
                    listOf(TextBlockParam.builder().text(systemText).build())
                )
            }

            paramsBuilder.messages(messageParams)

            // Add tools if provided
            if (!tools.isNullOrEmpty()) {
                buildAnthropicTools(tools).forEach { tool ->
                    paramsBuilder.addTool(tool)
                }
            }

            val params = paramsBuilder.build()
            val response = sdkClient.messages().create(params)

            // Extract text content and tool use blocks from response
            val textParts = mutableListOf<String>()
            val toolCalls = mutableListOf<ToolCall>()

            for (block in response.content()) {
                if (block.isText()) {
                    textParts.add(block.asText().text())
                } else if (block.isToolUse()) {
                    val toolUse = block.asToolUse()
                    toolCalls.add(
                        ToolCall(
                            id = toolUse.id(),
                            type = "function",
                            function = FunctionCall(
                                name = toolUse.name(),
                                arguments = jsonValueToString(toolUse._input())
                            )
                        )
                    )
                }
            }

            // Map stop reason
            val stopReason = response.stopReason()
            val finishReason = when {
                stopReason.isPresent && stopReason.get() == StopReason.TOOL_USE -> "tool_calls"
                stopReason.isPresent && stopReason.get() == StopReason.END_TURN -> "stop"
                stopReason.isPresent && stopReason.get() == StopReason.MAX_TOKENS -> "length"
                else -> "stop"
            }

            val textContent = textParts.joinToString("").ifEmpty { null }

            Result.success(
                LlmResponse(
                    id = response.id(),
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = MessageResponse(
                                role = "assistant",
                                content = textContent,
                                tool_calls = toolCalls.takeIf { it.isNotEmpty() }
                            ),
                            finish_reason = finishReason
                        )
                    ),
                    usage = response.usage().let { u ->
                        Usage(
                            prompt_tokens = u.inputTokens().toInt(),
                            completion_tokens = u.outputTokens().toInt(),
                            total_tokens = (u.inputTokens() + u.outputTokens()).toInt()
                        )
                    }
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("Anthropic API error: ${e.message}", e))
        }
    }

    /**
     * Build SDK MessageParam list from our internal Message list.
     * Skips system messages (handled separately).
     * Groups consecutive tool messages into a single user message with ToolResultBlockParams.
     */
    private fun buildMessageParams(messages: List<Message>): List<MessageParam> {
        val params = mutableListOf<MessageParam>()
        var i = 0
        val nonSystemMessages = messages.filter { it.role != "system" }

        while (i < nonSystemMessages.size) {
            val msg = nonSystemMessages[i]

            when (msg.role) {
                "user" -> {
                    if (!msg.mediaData.isNullOrEmpty()) {
                        // Build content blocks with text + images (audio is filtered out -- Anthropic does not support it)
                        val contentBlocks = mutableListOf<ContentBlockParam>()
                        if (!msg.content.isNullOrBlank()) {
                            contentBlocks.add(
                                ContentBlockParam.ofText(
                                    TextBlockParam.builder().text(msg.content).build()
                                )
                            )
                        }
                        for (media in msg.mediaData) {
                            if (media.isImage) {
                                contentBlocks.add(
                                    ContentBlockParam.ofImage(
                                        ImageBlockParam.builder()
                                            .source(
                                                ImageBlockParam.Source.ofBase64(
                                                    Base64ImageSource.builder()
                                                        .data(media.base64)
                                                        .mediaType(toAnthropicMediaType(media.mimeType))
                                                        .build()
                                                )
                                            )
                                            .build()
                                    )
                                )
                            } else if (media.isDocument) {
                                contentBlocks.add(
                                    ContentBlockParam.ofDocument(
                                        DocumentBlockParam.builder()
                                            .source(
                                                Base64PdfSource.builder()
                                                    .data(media.base64)
                                                    .build()
                                            )
                                            .build()
                                    )
                                )
                            }
                            // Audio/video media silently skipped -- Anthropic does not support them
                        }
                        params.add(
                            MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content(MessageParam.Content.ofBlockParams(contentBlocks))
                                .build()
                        )
                    } else {
                        params.add(
                            MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content(msg.content ?: "")
                                .build()
                        )
                    }
                    i++
                }
                "assistant" -> {
                    val contentBlocks = mutableListOf<ContentBlockParam>()

                    // Add text content if present
                    if (!msg.content.isNullOrBlank()) {
                        contentBlocks.add(
                            ContentBlockParam.ofText(
                                TextBlockParam.builder().text(msg.content).build()
                            )
                        )
                    }

                    // Add tool use blocks if present
                    msg.tool_calls?.forEach { toolCall ->
                        contentBlocks.add(
                            ContentBlockParam.ofToolUse(
                                ToolUseBlockParam.builder()
                                    .id(toolCall.id)
                                    .name(toolCall.function.name)
                                    .input(jsonStringToJsonValue(toolCall.function.arguments))
                                    .build()
                            )
                        )
                    }

                    if (contentBlocks.isNotEmpty()) {
                        params.add(
                            MessageParam.builder()
                                .role(MessageParam.Role.ASSISTANT)
                                .content(MessageParam.Content.ofBlockParams(contentBlocks))
                                .build()
                        )
                    }
                    i++
                }
                "tool" -> {
                    // Collect consecutive tool messages into one user message
                    val toolResults = mutableListOf<ContentBlockParam>()
                    while (i < nonSystemMessages.size && nonSystemMessages[i].role == "tool") {
                        val toolMsg = nonSystemMessages[i]
                        toolResults.add(
                            ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                    .toolUseId(toolMsg.tool_call_id ?: "")
                                    .content(toolMsg.content ?: "")
                                    .build()
                            )
                        )
                        i++
                    }
                    params.add(
                        MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(MessageParam.Content.ofBlockParams(toolResults))
                            .build()
                    )
                }
                else -> i++ // skip unknown roles
            }
        }

        return params
    }

    /**
     * Convert our Tool format to Anthropic SDK Tool format.
     */
    private fun buildAnthropicTools(tools: List<Tool>): List<AnthropicTool> {
        return tools.map { tool ->
            val schema = tool.function.parameters
            val propertiesJson = schema["properties"]?.jsonObject

            val propsBuilder = AnthropicTool.InputSchema.Properties.builder()
            propertiesJson?.forEach { (key, value) ->
                propsBuilder.putAdditionalProperty(key, JsonValue.from(jsonElementToAny(value)))
            }

            val schemaBuilder = AnthropicTool.InputSchema.builder()
                .properties(propsBuilder.build())

            // Add required fields if present
            val required = schema["required"]
            if (required is JsonArray) {
                required.forEach { element ->
                    schemaBuilder.addRequired(element.jsonPrimitive.content)
                }
            }

            AnthropicTool.builder()
                .name(tool.function.name)
                .description(tool.function.description)
                .inputSchema(schemaBuilder.build())
                .build()
        }
    }

    /**
     * Convert a kotlinx.serialization JsonElement to a plain Java object for JsonValue.from().
     */
    private fun jsonElementToAny(element: kotlinx.serialization.json.JsonElement): Any? {
        return when (element) {
            is JsonPrimitive -> {
                if (element.isString) element.content
                else element.content.toBooleanStrictOrNull()
                    ?: element.content.toLongOrNull()
                    ?: element.content.toDoubleOrNull()
                    ?: element.content
            }
            is JsonObject -> element.entries.associate { (k, v) -> k to jsonElementToAny(v) }
            is JsonArray -> element.map { jsonElementToAny(it) }
        }
    }

    /**
     * Convert an Anthropic SDK JsonValue to a JSON string for our FunctionCall.arguments.
     */
    private fun jsonValueToString(value: JsonValue): String {
        val obj = value.asObject()
        if (obj.isPresent) {
            val entries = obj.get().entries.joinToString(",") { (k, v) ->
                "\"$k\":${jsonValueToString(v)}"
            }
            return "{$entries}"
        }
        val arr = value.asArray()
        if (arr.isPresent) {
            val items = arr.get().joinToString(",") { jsonValueToString(it) }
            return "[$items]"
        }
        val str = value.asString()
        if (str.isPresent) {
            return "\"${str.get().replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        val num = value.asNumber()
        if (num.isPresent) {
            return num.get().toString()
        }
        val bool = value.asBoolean()
        if (bool.isPresent) {
            return bool.get().toString()
        }
        return "null"
    }

    /**
     * Parse a JSON string into a JsonValue for tool use input reconstruction.
     */
    private fun jsonStringToJsonValue(json: String): JsonValue {
        return try {
            val parsed = NetworkConfig.json.parseToJsonElement(json)
            JsonValue.from(jsonElementToAny(parsed))
        } catch (e: Exception) {
            JsonValue.from(mapOf<String, Any?>())
        }
    }

    private fun toAnthropicMediaType(mimeType: String): Base64ImageSource.MediaType {
        return when (mimeType) {
            "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG
            "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP
            "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF
            else -> Base64ImageSource.MediaType.IMAGE_JPEG
        }
    }
}
