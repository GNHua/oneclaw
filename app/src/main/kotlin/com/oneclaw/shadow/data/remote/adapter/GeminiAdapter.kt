package com.oneclaw.shadow.data.remote.adapter

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.ConnectionErrorType
import com.oneclaw.shadow.core.model.ConnectionTestResult
import com.oneclaw.shadow.core.model.ModelSource
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.data.remote.dto.gemini.GeminiModelListResponse
import com.oneclaw.shadow.data.remote.sse.asSseFlow
import com.oneclaw.shadow.tool.engine.ToolSchemaSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiAdapter(
    private val client: OkHttpClient
) : ModelApiAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listModels(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val url = "${apiBaseUrl.trimEnd('/')}/models?key=$apiKey"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()

            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                        ?: return@withContext AppResult.Error(message = "Empty response body", code = ErrorCode.PROVIDER_ERROR)
                    val parsed = json.decodeFromString<GeminiModelListResponse>(body)
                    val models = parsed.models
                        .filter { "generateContent" in it.supportedGenerationMethods }
                        .map { dto ->
                            val modelId = dto.name.removePrefix("models/")
                            AiModel(id = modelId, displayName = dto.displayName, providerId = "", isDefault = false, source = ModelSource.DYNAMIC)
                        }
                    AppResult.Success(models)
                }
                response.code == 400 || response.code == 401 || response.code == 403 -> AppResult.Error(
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
        systemPrompt: String?
    ): Flow<StreamEvent> = flow {
        val requestBody = buildGeminiRequest(messages, tools, systemPrompt)
        val url = "${apiBaseUrl.trimEnd('/')}/models/${modelId}:streamGenerateContent?key=$apiKey&alt=sse"
        val request = Request.Builder()
            .url(url)
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

        var inputTokens = 0
        var outputTokens = 0
        var doneEmitted = false

        body.asSseFlow().collect { sseEvent ->
            try {
                val jsonObj = json.parseToJsonElement(sseEvent.data).jsonObject

                // Usage metadata
                jsonObj["usageMetadata"]?.jsonObject?.let { usage ->
                    inputTokens = usage["promptTokenCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: inputTokens
                    outputTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: outputTokens
                }

                val candidate = jsonObj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@collect
                val finishReason = candidate["finishReason"]?.jsonPrimitive?.content
                val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray

                parts?.forEach { partElement ->
                    val part = partElement.jsonObject
                    // Text
                    part["text"]?.jsonPrimitive?.content?.let { text ->
                        if (text.isNotEmpty()) emit(StreamEvent.TextDelta(text))
                    }
                    // Function call
                    part["functionCall"]?.jsonObject?.let { fc ->
                        val name = fc["name"]?.jsonPrimitive?.content ?: return@let
                        val callId = "call_${name}_${System.currentTimeMillis()}"
                        val argsJson = fc["args"]?.toString() ?: "{}"
                        emit(StreamEvent.ToolCallStart(toolCallId = callId, toolName = name))
                        emit(StreamEvent.ToolCallDelta(toolCallId = callId, argumentsDelta = argsJson))
                        emit(StreamEvent.ToolCallEnd(toolCallId = callId))
                    }
                }

                if (finishReason != null && finishReason != "null") {
                    if (inputTokens > 0 || outputTokens > 0) {
                        emit(StreamEvent.Usage(inputTokens, outputTokens))
                    }
                    emit(StreamEvent.Done)
                    doneEmitted = true
                }
            } catch (e: Exception) {
                // Skip malformed events
            }
        }

        if (!doneEmitted) {
            if (inputTokens > 0 || outputTokens > 0) {
                emit(StreamEvent.Usage(inputTokens, outputTokens))
            }
            emit(StreamEvent.Done)
        }
    }

    override fun formatToolDefinitions(tools: List<ToolDefinition>): Any {
        val declarations = tools.map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to ToolSchemaSerializer.toGeminiSchemaMap(tool.parametersSchema)
            )
        }
        return mapOf("function_declarations" to declarations)
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
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", buildJsonObject {
                    put("maxOutputTokens", maxTokens)
                })
            }

            val url = "${apiBaseUrl.trimEnd('/')}/models/${modelId}:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
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
            val content = jsonObj["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: return@withContext AppResult.Error(message = "No content in response", code = ErrorCode.PROVIDER_ERROR)
            AppResult.Success(content.trim())
        } catch (e: Exception) {
            AppResult.Error(message = "Error: ${e.message}", code = ErrorCode.UNKNOWN, exception = e)
        }
    }

    private fun buildGeminiRequest(
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?
    ): JsonObject = buildJsonObject {
        if (!systemPrompt.isNullOrBlank()) {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
        }

        put("contents", buildJsonArray {
            messages.forEach { msg ->
                when (msg) {
                    is ApiMessage.User -> add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", msg.content) })
                        })
                    })
                    is ApiMessage.Assistant -> {
                        add(buildJsonObject {
                            put("role", "model")
                            put("parts", buildJsonArray {
                                if (!msg.content.isNullOrEmpty()) {
                                    add(buildJsonObject { put("text", msg.content) })
                                }
                                msg.toolCalls?.forEach { tc ->
                                    add(buildJsonObject {
                                        put("functionCall", buildJsonObject {
                                            put("name", tc.name)
                                            put("args", json.parseToJsonElement(tc.arguments.ifBlank { "{}" }))
                                        })
                                    })
                                }
                            })
                        })
                    }
                    is ApiMessage.ToolResult -> add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("functionResponse", buildJsonObject {
                                    put("name", msg.toolCallId) // Gemini uses tool name here
                                    put("response", buildJsonObject {
                                        put("result", msg.content)
                                    })
                                })
                            })
                        })
                    })
                }
            }
        })

        if (!tools.isNullOrEmpty()) {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("function_declarations", buildJsonArray {
                        tools.forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", anyToJsonElement(
                                    ToolSchemaSerializer.toGeminiSchemaMap(tool.parametersSchema)
                                ))
                            })
                        }
                    })
                })
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
