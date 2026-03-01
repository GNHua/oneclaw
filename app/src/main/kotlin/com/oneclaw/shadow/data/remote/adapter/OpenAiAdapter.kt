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
import com.oneclaw.shadow.data.remote.dto.openai.OpenAiModelListResponse
import com.oneclaw.shadow.data.remote.sse.asSseFlow
import com.oneclaw.shadow.tool.engine.ToolSchemaSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiAdapter(
    private val client: OkHttpClient
) : ModelApiAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listModels(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${apiBaseUrl.trimEnd('/')}/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()

            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                        ?: return@withContext AppResult.Error(
                            message = "Empty response body",
                            code = ErrorCode.PROVIDER_ERROR
                        )
                    val parsed = json.decodeFromString<OpenAiModelListResponse>(body)
                    val models = parsed.data
                        .filter { isRelevantOpenAiModel(it.id) }
                        .map { dto ->
                            AiModel(
                                id = dto.id,
                                displayName = formatOpenAiModelName(dto.id),
                                providerId = "",
                                isDefault = false,
                                source = ModelSource.DYNAMIC
                            )
                        }
                    AppResult.Success(models)
                }
                response.code == 401 || response.code == 403 -> AppResult.Error(
                    message = "Authentication failed. Please check your API key.",
                    code = ErrorCode.AUTH_ERROR
                )
                else -> AppResult.Error(
                    message = "API error: ${response.code} ${response.message}",
                    code = ErrorCode.PROVIDER_ERROR
                )
            }
        } catch (e: java.net.UnknownHostException) {
            AppResult.Error(message = "Cannot reach the server. Please check the URL and your network.", code = ErrorCode.NETWORK_ERROR, exception = e)
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
        val requestBody = buildOpenAiRequest(modelId, messages, tools, systemPrompt, webSearchEnabled)
        val request = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            val code = response.code.toString()
            emit(StreamEvent.Error(message = "API error ${response.code}: $errorBody", code = code))
            emit(StreamEvent.Done)
            return@flow
        }

        val body = response.body ?: run {
            emit(StreamEvent.Error("Empty response body", null))
            emit(StreamEvent.Done)
            return@flow
        }

        // Track tool calls by index (OpenAI streams tool calls with index)
        val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()
        // Accumulate annotations across chunks
        val accumulatedAnnotations = mutableListOf<kotlinx.serialization.json.JsonElement>()

        body.asSseFlow().collect { sseEvent ->
            val data = sseEvent.data
            if (data == "[DONE]") {
                // Finalize any pending tool calls
                for ((_, tc) in toolCallBuilders) {
                    emit(StreamEvent.ToolCallEnd(tc.id))
                }
                // Emit citations from accumulated annotations
                if (accumulatedAnnotations.isNotEmpty()) {
                    val citations = parseAnnotations(accumulatedAnnotations)
                    if (citations.isNotEmpty()) {
                        emit(StreamEvent.Citations(citations))
                    }
                }
                emit(StreamEvent.Done)
                return@collect
            }

            try {
                val jsonObj = json.parseToJsonElement(data).jsonObject

                // Usage
                jsonObj["usage"]?.jsonObject?.let { usage ->
                    val inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val outputTokens = usage["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    emit(StreamEvent.Usage(inputTokens, outputTokens))
                }

                val choice = jsonObj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@collect
                val delta = choice["delta"]?.jsonObject ?: return@collect

                // Text delta
                delta["content"]?.jsonPrimitive?.content?.let { text ->
                    if (text.isNotEmpty()) emit(StreamEvent.TextDelta(text))
                }

                // Accumulate annotations from delta
                delta["annotations"]?.jsonArray?.let { annotations ->
                    accumulatedAnnotations.addAll(annotations)
                }

                // Tool calls
                delta["tool_calls"]?.jsonArray?.forEach { tcElement ->
                    val tc = tcElement.jsonObject
                    val index = tc["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

                    if (!toolCallBuilders.containsKey(index)) {
                        val id = tc["id"]?.jsonPrimitive?.content ?: "call_$index"
                        val name = tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
                        toolCallBuilders[index] = ToolCallBuilder(id = id, name = name)
                        emit(StreamEvent.ToolCallStart(toolCallId = id, toolName = name))
                    }

                    val argsDelta = tc["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content ?: ""
                    if (argsDelta.isNotEmpty()) {
                        val tcBuilder = toolCallBuilders[index]!!
                        tcBuilder.arguments.append(argsDelta)
                        emit(StreamEvent.ToolCallDelta(toolCallId = tcBuilder.id, argumentsDelta = argsDelta))
                    }
                }
            } catch (e: Exception) {
                // Skip malformed SSE events
            }
        }
    }

    override fun formatToolDefinitions(tools: List<ToolDefinition>): Any {
        return tools.map { tool ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to ToolSchemaSerializer.toJsonSchemaMap(tool.parametersSchema)
                )
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
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("max_tokens", maxTokens)
                put("stream", false)
            }

            val request = Request.Builder()
                .url("${apiBaseUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext AppResult.Error(
                    message = "API error ${response.code}",
                    code = ErrorCode.PROVIDER_ERROR
                )
            }
            val responseBody = response.body?.string() ?: return@withContext AppResult.Error(
                message = "Empty response",
                code = ErrorCode.PROVIDER_ERROR
            )
            val jsonObj = json.parseToJsonElement(responseBody).jsonObject
            val content = jsonObj["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: return@withContext AppResult.Error(message = "No content in response", code = ErrorCode.PROVIDER_ERROR)
            AppResult.Success(content.trim())
        } catch (e: Exception) {
            AppResult.Error(message = "Error: ${e.message}", code = ErrorCode.UNKNOWN, exception = e)
        }
    }

    private fun parseAnnotations(annotations: List<kotlinx.serialization.json.JsonElement>): List<Citation> {
        return annotations.mapNotNull { ann ->
            val obj = ann.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "url_citation") {
                val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                Citation(
                    url = url,
                    title = obj["title"]?.jsonPrimitive?.content ?: url,
                    domain = extractDomain(url)
                )
            } else null
        }.distinctBy { it.url }
    }

    private fun buildOpenAiRequest(
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?,
        webSearchEnabled: Boolean = false
    ): JsonObject = buildJsonObject {
        put("model", modelId)
        put("stream", true)
        put("stream_options", buildJsonObject { put("include_usage", true) })

        if (webSearchEnabled) {
            put("web_search_options", buildJsonObject {
                put("search_context_size", "medium")
            })
        }

        put("messages", buildJsonArray {
            if (!systemPrompt.isNullOrBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            messages.forEach { msg ->
                when (msg) {
                    is ApiMessage.User -> add(buildUserMessage(msg))
                    is ApiMessage.Assistant -> {
                        if (msg.toolCalls != null) {
                            add(buildJsonObject {
                                put("role", "assistant")
                                if (!msg.content.isNullOrEmpty()) put("content", msg.content)
                                put("tool_calls", buildJsonArray {
                                    msg.toolCalls.forEach { tc ->
                                        add(buildJsonObject {
                                            put("id", tc.id)
                                            put("type", "function")
                                            put("function", buildJsonObject {
                                                put("name", tc.name)
                                                put("arguments", tc.arguments)
                                            })
                                        })
                                    }
                                })
                            })
                        } else {
                            add(buildJsonObject {
                                put("role", "assistant")
                                put("content", msg.content ?: "")
                            })
                        }
                    }
                    is ApiMessage.ToolResult -> add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", msg.toolCallId)
                        put("content", msg.content)
                    })
                }
            }
        })

        if (!tools.isNullOrEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", anyToJsonElement(
                                ToolSchemaSerializer.toJsonSchemaMap(tool.parametersSchema)
                            ))
                        })
                    })
                }
            })
            put("tool_choice", "auto")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyToJsonElement(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            (value as Map<String, Any?>).forEach { (k, v) -> put(k, anyToJsonElement(v)) }
        }
        is List<*> -> buildJsonArray {
            value.forEach { add(anyToJsonElement(it)) }
        }
        else -> JsonPrimitive(value.toString())
    }

    private fun isRelevantOpenAiModel(modelId: String): Boolean {
        val chatPrefixes = listOf("gpt-", "o1", "o3", "o4", "chatgpt-")
        return chatPrefixes.any { modelId.startsWith(it) }
    }

    private fun formatOpenAiModelName(modelId: String): String {
        return modelId.replace("gpt-", "GPT-").replace("-mini", " Mini")
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
            putJsonArray("content") {
                if (message.content.isNotBlank()) {
                    addJsonObject {
                        put("type", "text")
                        put("text", message.content)
                    }
                }
                message.attachments
                    .filter { it.type == AttachmentType.IMAGE }
                    .forEach { attachment ->
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:${attachment.mimeType};base64,${attachment.base64Data}")
                            }
                        }
                    }
            }
        }
    }

    private data class ToolCallBuilder(val id: String, val name: String, val arguments: StringBuilder = StringBuilder())
}
