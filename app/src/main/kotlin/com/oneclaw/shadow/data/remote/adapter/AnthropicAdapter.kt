package com.oneclaw.shadow.data.remote.adapter

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.AttachmentType
import com.oneclaw.shadow.core.model.Citation
import com.oneclaw.shadow.core.model.ConnectionErrorType
import com.oneclaw.shadow.core.model.ConnectionTestResult
import com.oneclaw.shadow.core.model.ModelSource
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.core.util.extractDomain
import com.oneclaw.shadow.data.remote.dto.anthropic.AnthropicModelListResponse
import com.oneclaw.shadow.data.remote.sse.asSseFlow
import com.oneclaw.shadow.tool.engine.ToolSchemaSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AnthropicAdapter(
    private val client: OkHttpClient
) : ModelApiAdapter {

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listModels(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${apiBaseUrl.trimEnd('/')}/models")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()

            val response = client.newCall(request).execute()

            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                        ?: return@withContext AppResult.Error(message = "Empty response body", code = ErrorCode.PROVIDER_ERROR)
                    val parsed = json.decodeFromString<AnthropicModelListResponse>(body)
                    val models = parsed.data
                        .filter { it.type == "model" }
                        .map { dto ->
                            AiModel(id = dto.id, displayName = dto.displayName, providerId = "", isDefault = false, source = ModelSource.DYNAMIC)
                        }
                    AppResult.Success(models)
                }
                response.code == 401 || response.code == 403 -> AppResult.Error(
                    message = "Authentication failed. Please check your API key.", code = ErrorCode.AUTH_ERROR
                )
                else -> AppResult.Error(message = "API error: ${response.code} ${response.message}", code = ErrorCode.PROVIDER_ERROR)
            }
        } catch (e: java.net.UnknownHostException) {
            AppResult.Error(message = "Cannot reach the server.", code = ErrorCode.NETWORK_ERROR, exception = e)
        } catch (e: java.net.SocketTimeoutException) {
            AppResult.Error(message = "Connection timed out.", code = ErrorCode.TIMEOUT_ERROR, exception = e)
        } catch (e: Exception) {
            AppResult.Error(message = "Unexpected error: ${e.message}", code = ErrorCode.UNKNOWN, exception = e)
        }
    }

    override suspend fun testConnection(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<ConnectionTestResult> {
        return when (val result = listModels(apiBaseUrl, apiKey)) {
            is AppResult.Success -> AppResult.Success(
                ConnectionTestResult(success = true, modelCount = result.data.size, errorType = null, errorMessage = null)
            )
            is AppResult.Error -> {
                val errorType = when (result.code) {
                    ErrorCode.AUTH_ERROR -> ConnectionErrorType.AUTH_FAILURE
                    ErrorCode.NETWORK_ERROR -> ConnectionErrorType.NETWORK_FAILURE
                    ErrorCode.TIMEOUT_ERROR -> ConnectionErrorType.TIMEOUT
                    else -> ConnectionErrorType.UNKNOWN
                }
                AppResult.Success(ConnectionTestResult(success = false, modelCount = null, errorType = errorType, errorMessage = result.message))
            }
        }
    }

    override fun sendMessageStream(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?,
        webSearchEnabled: Boolean
    ): Flow<StreamEvent> = flow {
        val requestBody = buildAnthropicRequest(modelId, messages, tools, systemPrompt, webSearchEnabled)
        val betaHeader = if (webSearchEnabled) {
            "interleaved-thinking-2025-05-14,web-search-2025-03-05"
        } else {
            "interleaved-thinking-2025-05-14"
        }
        val request = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("anthropic-beta", betaHeader)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            emit(StreamEvent.Error(message = "API error ${response.code}: $errorBody", code = response.code.toString()))
            emit(StreamEvent.Done)
            return@flow
        }

        val body = response.body ?: run {
            emit(StreamEvent.Error("Empty response body", null))
            emit(StreamEvent.Done)
            return@flow
        }

        // Track content block types by index
        data class ContentBlock(val type: String, val id: String? = null, val name: String? = null)
        val contentBlocks = mutableMapOf<Int, ContentBlock>()
        // Track server_tool_use block indices (for web search -- do NOT execute client-side)
        val serverToolUseIndices = mutableSetOf<Int>()

        body.asSseFlow().collect { sseEvent ->
            try {
                val jsonObj = json.parseToJsonElement(sseEvent.data).jsonObject
                val type = jsonObj["type"]?.jsonPrimitive?.content ?: return@collect

                when (type) {
                    "content_block_start" -> {
                        val index = jsonObj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val block = jsonObj["content_block"]?.jsonObject ?: return@collect
                        val blockType = block["type"]?.jsonPrimitive?.content ?: ""
                        val blockId = block["id"]?.jsonPrimitive?.content
                        val blockName = block["name"]?.jsonPrimitive?.content
                        contentBlocks[index] = ContentBlock(type = blockType, id = blockId, name = blockName)

                        when (blockType) {
                            "tool_use" -> {
                                if (blockId != null && blockName != null) {
                                    emit(StreamEvent.ToolCallStart(toolCallId = blockId, toolName = blockName))
                                }
                            }
                            "server_tool_use" -> {
                                // Server-side tool (web search) -- track index, do NOT emit ToolCallStart
                                serverToolUseIndices.add(index)
                                if (blockName == "web_search") {
                                    emit(StreamEvent.WebSearchStart(null))
                                }
                            }
                            "web_search_tool_result" -> {
                                // Extract citations from search results
                                val content = block["content"]?.jsonArray
                                val citations = content?.mapNotNull { result ->
                                    val obj = result.jsonObject
                                    if (obj["type"]?.jsonPrimitive?.content == "web_search_result") {
                                        val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                        Citation(
                                            url = url,
                                            title = obj["title"]?.jsonPrimitive?.content ?: url,
                                            domain = extractDomain(url)
                                        )
                                    } else null
                                } ?: emptyList()
                                if (citations.isNotEmpty()) {
                                    emit(StreamEvent.Citations(citations))
                                }
                            }
                            "text" -> {
                                // Parse inline citations from text block start
                                val textCitations = block["citations"]?.jsonArray?.mapNotNull { c ->
                                    val obj = c.jsonObject
                                    if (obj["type"]?.jsonPrimitive?.content == "web_search_result_location") {
                                        val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                        Citation(
                                            url = url,
                                            title = obj["title"]?.jsonPrimitive?.content ?: url,
                                            domain = extractDomain(url)
                                        )
                                    } else null
                                }
                                if (!textCitations.isNullOrEmpty()) {
                                    emit(StreamEvent.Citations(textCitations))
                                }
                            }
                        }
                    }

                    "content_block_delta" -> {
                        val index = jsonObj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val delta = jsonObj["delta"]?.jsonObject ?: return@collect
                        val deltaType = delta["type"]?.jsonPrimitive?.content ?: ""
                        val block = contentBlocks[index]

                        when (deltaType) {
                            "text_delta" -> {
                                val text = delta["text"]?.jsonPrimitive?.content ?: ""
                                if (text.isNotEmpty()) emit(StreamEvent.TextDelta(text))
                            }
                            "thinking_delta" -> {
                                val thinking = delta["thinking"]?.jsonPrimitive?.content ?: ""
                                if (thinking.isNotEmpty()) emit(StreamEvent.ThinkingDelta(thinking))
                            }
                            "input_json_delta" -> {
                                val partialJson = delta["partial_json"]?.jsonPrimitive?.content ?: ""
                                // Only emit ToolCallDelta for regular tool_use blocks, not server_tool_use
                                if (index !in serverToolUseIndices) {
                                    val blockId = block?.id
                                    if (partialJson.isNotEmpty() && blockId != null) {
                                        emit(StreamEvent.ToolCallDelta(toolCallId = blockId, argumentsDelta = partialJson))
                                    }
                                }
                            }
                        }
                    }

                    "content_block_stop" -> {
                        val index = jsonObj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val block = contentBlocks[index]
                        // Only emit ToolCallEnd for regular tool_use blocks
                        if (block?.type == "tool_use" && block.id != null) {
                            emit(StreamEvent.ToolCallEnd(toolCallId = block.id))
                        }
                    }

                    "message_delta" -> {
                        jsonObj["usage"]?.jsonObject?.let { usage ->
                            val outputTokens = usage["output_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            // Input tokens come from message_start
                        }
                    }

                    "message_start" -> {
                        jsonObj["message"]?.jsonObject?.get("usage")?.jsonObject?.let { usage ->
                            val inputTokens = usage["input_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val outputTokens = usage["output_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            if (inputTokens > 0 || outputTokens > 0) {
                                emit(StreamEvent.Usage(inputTokens, outputTokens))
                            }
                        }
                    }

                    "message_stop" -> {
                        emit(StreamEvent.Done)
                    }

                    "error" -> {
                        val errorMsg = jsonObj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                        emit(StreamEvent.Error(message = errorMsg, code = null))
                        emit(StreamEvent.Done)
                    }
                }
            } catch (e: Exception) {
                // Skip malformed events
            }
        }
    }

    override fun formatToolDefinitions(tools: List<ToolDefinition>): Any {
        return tools.map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "input_schema" to ToolSchemaSerializer.toJsonSchemaMap(tool.parametersSchema)
            )
        }
    }

    override suspend fun generateSimpleCompletion(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        prompt: String,
        maxTokens: Int
    ): AppResult<String> = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("model", modelId)
                put("max_tokens", maxTokens)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            val request = Request.Builder()
                .url("${apiBaseUrl.trimEnd('/')}/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext AppResult.Error(message = "API error ${response.code}", code = ErrorCode.PROVIDER_ERROR)
            }
            val responseBody = response.body?.string() ?: return@withContext AppResult.Error(
                message = "Empty response", code = ErrorCode.PROVIDER_ERROR
            )
            val jsonObj = json.parseToJsonElement(responseBody).jsonObject
            val content = jsonObj["content"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: return@withContext AppResult.Error(message = "No content in response", code = ErrorCode.PROVIDER_ERROR)
            AppResult.Success(content.trim())
        } catch (e: Exception) {
            AppResult.Error(message = "Error: ${e.message}", code = ErrorCode.UNKNOWN, exception = e)
        }
    }

    private fun buildAnthropicRequest(
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?,
        webSearchEnabled: Boolean = false
    ): JsonObject = buildJsonObject {
        put("model", modelId)
        put("max_tokens", 16000)
        put("stream", true)

        // Enable extended thinking for models that support it.
        // Requires max_tokens >= budget_tokens + expected output tokens.
        val supportsThinking = modelId.contains("opus-4") || modelId.contains("sonnet-4")
        if (supportsThinking) {
            put("thinking", buildJsonObject {
                put("type", "enabled")
                put("budget_tokens", 10000)
            })
        }

        if (!systemPrompt.isNullOrBlank()) {
            put("system", systemPrompt)
        }

        put("messages", buildJsonArray {
            var i = 0
            while (i < messages.size) {
                when (val msg = messages[i]) {
                    is ApiMessage.User -> {
                        add(buildUserMessage(msg))
                        i++
                    }
                    is ApiMessage.Assistant -> {
                        add(buildJsonObject {
                            put("role", "assistant")
                            if (msg.toolCalls != null) {
                                put("content", buildJsonArray {
                                    if (!msg.content.isNullOrEmpty()) {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", msg.content)
                                        })
                                    }
                                    msg.toolCalls.forEach { tc ->
                                        add(buildJsonObject {
                                            put("type", "tool_use")
                                            put("id", tc.id)
                                            put("name", tc.name)
                                            put("input", json.parseToJsonElement(
                                                tc.arguments.ifBlank { "{}" }
                                            ))
                                        })
                                    }
                                })
                            } else {
                                put("content", msg.content ?: "")
                            }
                        })
                        i++
                    }
                    is ApiMessage.ToolResult -> {
                        // Collect all consecutive ToolResult messages and merge into a single
                        // user message. Anthropic requires all tool_result blocks for a parallel
                        // tool_use to be in the same message, not separate messages.
                        val toolResults = mutableListOf<ApiMessage.ToolResult>()
                        while (i < messages.size && messages[i] is ApiMessage.ToolResult) {
                            toolResults.add(messages[i] as ApiMessage.ToolResult)
                            i++
                        }
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                toolResults.forEach { tr ->
                                    add(buildJsonObject {
                                        put("type", "tool_result")
                                        put("tool_use_id", tr.toolCallId)
                                        put("content", tr.content)
                                    })
                                }
                            })
                        })
                    }
                }
            }
        })

        val hasTools = !tools.isNullOrEmpty()
        if (hasTools || webSearchEnabled) {
            put("tools", buildJsonArray {
                tools?.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", anyToJsonElement(
                            ToolSchemaSerializer.toJsonSchemaMap(tool.parametersSchema)
                        ))
                    })
                }
                if (webSearchEnabled) {
                    add(buildJsonObject {
                        put("type", "web_search_20250305")
                        put("name", "web_search")
                        put("max_uses", 5)
                    })
                }
            })
        }
    }

    private fun buildUserMessage(message: ApiMessage.User): JsonObject {
        if (message.attachments.isEmpty()) {
            return buildJsonObject {
                put("role", "user")
                put("content", message.content)
            }
        }

        return buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                message.attachments
                    .filter {
                        it.type == AttachmentType.IMAGE ||
                            (it.type == AttachmentType.FILE && it.mimeType == "application/pdf")
                    }
                    .forEach { attachment ->
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", attachment.mimeType)
                                put("data", attachment.base64Data)
                            }
                        }
                    }
                if (message.content.isNotBlank()) {
                    addJsonObject {
                        put("type", "text")
                        put("text", message.content)
                    }
                }
            })
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyToJsonElement(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        is Number -> kotlinx.serialization.json.JsonPrimitive(value)
        is String -> kotlinx.serialization.json.JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            (value as Map<String, Any?>).forEach { (k, v) -> put(k, anyToJsonElement(v)) }
        }
        is List<*> -> buildJsonArray {
            value.forEach { add(anyToJsonElement(it)) }
        }
        else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
    }

}
