package com.tomandy.oneclaw.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * LLM client for Google Antigravity (Cloud Code Assist).
 *
 * Communicates with the Cloud Code Assist API using OAuth2 Bearer tokens.
 * The API accepts Gemini-format payloads wrapped in a Cloud Code Assist envelope
 * and returns Server-Sent Events (SSE).
 *
 * Preserves thought signatures across function-call round-trips by storing the
 * raw model content JSON (including thoughtSignature fields) and replaying it
 * in the next request.
 *
 * Auth is handled externally via the [tokenProvider] and [projectIdProvider]
 * lambdas, keeping this class free of Android dependencies.
 */
class AntigravityClient(
    private val tokenProvider: suspend () -> String?,
    private val projectIdProvider: suspend () -> String?
) : LlmClient {

    companion object {
        private const val TAG = "AntigravityClient"
        private const val ENDPOINT =
            "https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse"
        private const val MODEL_PREFIX = "ag/"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(NetworkConfig.DEFAULT_CONNECT_TIMEOUT, NetworkConfig.TIMEOUT_UNIT)
        .readTimeout(NetworkConfig.LLM_REQUEST_TIMEOUT, NetworkConfig.TIMEOUT_UNIT)
        .writeTimeout(NetworkConfig.DEFAULT_WRITE_TIMEOUT, NetworkConfig.TIMEOUT_UNIT)
        .build()

    // State for function-call round-trips:
    // Store the raw model content JSON (preserves thoughtSignature) and conversation history
    private var pendingModelContent: JsonObject? = null
    private var conversationContents: MutableList<JsonElement> = mutableListOf()

    override fun setApiKey(apiKey: String) { /* no-op: uses tokenProvider */ }
    override fun setBaseUrl(baseUrl: String) { /* no-op: fixed endpoint */ }

    override suspend fun complete(
        messages: List<Message>,
        model: String,
        temperature: Float,
        maxTokens: Int?,
        tools: List<Tool>?
    ): Result<LlmResponse> = withContext(Dispatchers.IO) {
        try {
            val accessToken = tokenProvider()
                ?: return@withContext Result.failure(
                    Exception(
                        "Antigravity not authenticated. " +
                            "Please sign in via Settings > Providers."
                    )
                )
            val projectId = projectIdProvider()
                ?: return@withContext Result.failure(
                    Exception("Antigravity project ID not available.")
                )

            val apiModel = model.removePrefix(MODEL_PREFIX)

            // Build conversation contents with thought signature preservation
            if (pendingModelContent != null) {
                // Follow-up after function call: add stored model content + function responses
                val toolMessages = messages.filter { it.role == "tool" }
                if (toolMessages.isNotEmpty()) {
                    conversationContents.add(pendingModelContent!!)
                    conversationContents.add(buildFunctionResponseContent(toolMessages))
                    pendingModelContent = null
                }
            } else {
                // New conversation turn: rebuild from messages
                conversationContents = buildContentsJson(messages)
            }

            val requestBody = buildRequestBody(
                conversationContents, messages, apiModel, temperature,
                maxTokens, tools, projectId
            )

            val request = Request.Builder()
                .url(ENDPOINT)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .addHeader("User-Agent", "antigravity")
                .addHeader(
                    "X-Goog-Api-Client",
                    "google-cloud-sdk vscode_cloudshelleditor/0.1"
                )
                .addHeader(
                    "Client-Metadata",
                    """{"ideType":"IDE_UNSPECIFIED",""" +
                        """"platform":"PLATFORM_UNSPECIFIED",""" +
                        """"pluginType":"GEMINI"}"""
                )
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                val code = response.code
                response.close()

                if (code == 401 || code == 403) {
                    return@withContext Result.failure(
                        Exception(
                            "Antigravity auth failed ($code). " +
                                "Please re-authenticate in Settings > Providers."
                        )
                    )
                }

                val errorLower = errorBody.lowercase()
                if (errorLower.contains("token count exceeds") ||
                    errorLower.contains("too many tokens") ||
                    errorLower.contains("exceeds the maximum") ||
                    errorLower.contains("request payload size exceeds") ||
                    errorLower.contains("prompt is too long") ||
                    errorLower.contains("context_length_exceeded")
                ) {
                    return@withContext Result.failure(
                        ContextOverflowException(
                            "Antigravity: $errorBody"
                        )
                    )
                }

                return@withContext Result.failure(
                    Exception("Antigravity API error ($code): $errorBody")
                )
            }

            val sseResult = parseSseResponse(response)

            // If the response has function calls, store the raw content for the next round-trip
            val hasToolCalls = sseResult.choices.firstOrNull()
                ?.message?.tool_calls?.isNotEmpty() == true
            if (hasToolCalls) {
                // pendingModelContent was set during parseSseResponse
            } else {
                // Final answer: add to conversation history and clear pending
                pendingModelContent?.let { conversationContents.add(it) }
                pendingModelContent = null
            }

            Result.success(sseResult)
        } catch (e: CancellationException) {
            pendingModelContent = null
            throw e
        } catch (e: Exception) {
            pendingModelContent = null
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("request payload size exceeds") ||
                msg.contains("too many tokens") ||
                msg.contains("exceeds the maximum") ||
                msg.contains("prompt is too long") ||
                msg.contains("context_length_exceeded")
            ) {
                Result.failure(
                    ContextOverflowException("Antigravity: ${e.message}", e)
                )
            } else {
                Result.failure(
                    Exception("Antigravity API error: ${e.message}", e)
                )
            }
        }
    }

    private fun buildRequestBody(
        contents: List<JsonElement>,
        messages: List<Message>,
        model: String,
        temperature: Float,
        maxTokens: Int?,
        tools: List<Tool>?,
        projectId: String
    ): String {
        val systemInstruction = buildSystemInstructionJson(messages)

        val genConfig = buildMap<String, JsonElement> {
            put("temperature", JsonPrimitive(temperature))
            if (maxTokens != null) {
                put("maxOutputTokens", JsonPrimitive(maxTokens))
            }
        }

        val innerRequest = buildMap<String, JsonElement> {
            put("contents", JsonArray(contents))
            if (systemInstruction != null) {
                put("systemInstruction", systemInstruction)
            }
            if (genConfig.isNotEmpty()) {
                put("generationConfig", JsonObject(genConfig))
            }
            val toolsJson = buildToolsJson(tools)
            if (toolsJson != null) {
                put("tools", toolsJson)
            }
        }

        val envelope = JsonObject(
            mapOf(
                "project" to JsonPrimitive(projectId),
                "model" to JsonPrimitive(model),
                "request" to JsonObject(innerRequest),
                "requestType" to JsonPrimitive("agent"),
                "userAgent" to JsonPrimitive("antigravity"),
                "requestId" to JsonPrimitive(
                    "ag-${System.currentTimeMillis()}-${
                        (Math.random() * 1_000_000).toLong()
                    }"
                )
            )
        )

        return envelope.toString()
    }

    /**
     * Build Gemini Content list from Message list.
     * Only used for the initial turn (not function-call follow-ups).
     */
    private fun buildContentsJson(messages: List<Message>): MutableList<JsonElement> {
        val contents = mutableListOf<JsonElement>()

        for (msg in messages) {
            when (msg.role) {
                "system" -> {
                    // Handled separately via systemInstruction
                }
                "user" -> {
                    val parts = mutableListOf<JsonElement>()
                    if (!msg.content.isNullOrBlank()) {
                        parts.add(
                            JsonObject(mapOf("text" to JsonPrimitive(msg.content)))
                        )
                    }
                    msg.mediaData?.forEach { media ->
                        parts.add(
                            JsonObject(
                                mapOf(
                                    "inlineData" to JsonObject(
                                        mapOf(
                                            "mimeType" to JsonPrimitive(
                                                media.mimeType
                                            ),
                                            "data" to JsonPrimitive(media.base64)
                                        )
                                    )
                                )
                            )
                        )
                    }
                    if (parts.isNotEmpty()) {
                        contents.add(
                            JsonObject(
                                mapOf(
                                    "role" to JsonPrimitive("user"),
                                    "parts" to JsonArray(parts)
                                )
                            )
                        )
                    }
                }
                "assistant" -> {
                    val parts = mutableListOf<JsonElement>()
                    // Only include text content (skip tool_calls -- handled via pendingModelContent)
                    if (!msg.content.isNullOrBlank() &&
                        (msg.tool_calls == null || msg.tool_calls.isEmpty())
                    ) {
                        parts.add(
                            JsonObject(mapOf("text" to JsonPrimitive(msg.content)))
                        )
                    }
                    if (parts.isNotEmpty()) {
                        contents.add(
                            JsonObject(
                                mapOf(
                                    "role" to JsonPrimitive("model"),
                                    "parts" to JsonArray(parts)
                                )
                            )
                        )
                    }
                }
                // Skip "tool" messages -- handled via pendingModelContent flow
            }
        }

        return contents
    }

    /**
     * Build function response Content from tool messages.
     */
    private fun buildFunctionResponseContent(toolMessages: List<Message>): JsonObject {
        val parts = toolMessages.map { toolMsg ->
            JsonObject(
                mapOf(
                    "functionResponse" to JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(toolMsg.name ?: "unknown"),
                            "response" to JsonObject(
                                mapOf(
                                    "result" to JsonPrimitive(
                                        toolMsg.content ?: ""
                                    )
                                )
                            )
                        )
                    )
                )
            )
        }

        return JsonObject(
            mapOf(
                "role" to JsonPrimitive("user"),
                "parts" to JsonArray(parts)
            )
        )
    }

    private fun buildSystemInstructionJson(
        messages: List<Message>
    ): JsonObject? {
        val systemTexts = messages
            .filter { it.role == "system" && !it.content.isNullOrBlank() }
            .joinToString("\n\n") { it.content!! }

        if (systemTexts.isBlank()) return null

        return JsonObject(
            mapOf(
                "parts" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf("text" to JsonPrimitive(systemTexts))
                        )
                    )
                )
            )
        )
    }

    private fun buildToolsJson(tools: List<Tool>?): JsonArray? {
        if (tools.isNullOrEmpty()) return null

        val declarations = tools.map { tool ->
            val decl = buildMap<String, JsonElement> {
                put("name", JsonPrimitive(tool.function.name))
                put("description", JsonPrimitive(tool.function.description))
                put("parameters", tool.function.parameters)
            }
            JsonObject(decl)
        }

        return JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "functionDeclarations" to JsonArray(declarations)
                    )
                )
            )
        )
    }

    private fun parseSseResponse(response: okhttp3.Response): LlmResponse {
        val textParts = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        // Accumulate ALL raw parts (including thought + thoughtSignature) for preservation
        val rawModelParts = mutableListOf<JsonElement>()
        var promptTokens = 0
        var completionTokens = 0
        var totalTokens = 0
        var chunkCount = 0

        response.body?.let { body ->
            BufferedReader(InputStreamReader(body.byteStream())).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val data = line ?: continue
                    if (!data.startsWith("data:")) continue
                    val jsonStr = data.removePrefix("data:").trim()
                    if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue

                    chunkCount++
                    Log.d(TAG, "SSE raw chunk $chunkCount: ${jsonStr.take(500)}")
                    try {
                        val chunk = json.parseToJsonElement(jsonStr).jsonObject
                        val resp = chunk["response"]?.jsonObject
                        if (resp == null) {
                            Log.d(TAG, "SSE chunk $chunkCount: no 'response' key. Keys: ${chunk.keys}")
                            continue
                        }
                        parseResponseChunk(
                            resp, textParts, toolCalls, rawModelParts
                        )

                        resp["usageMetadata"]?.jsonObject?.let { usage ->
                            usage["promptTokenCount"]
                                ?.jsonPrimitive?.intOrNull
                                ?.let { promptTokens = it }
                            usage["candidatesTokenCount"]
                                ?.jsonPrimitive?.intOrNull
                                ?.let { completionTokens = it }
                            usage["totalTokenCount"]
                                ?.jsonPrimitive?.intOrNull
                                ?.let { totalTokens = it }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "SSE chunk $chunkCount parse error: ${e.message}")
                    }
                }
            }
        }

        Log.d(
            TAG,
            "SSE done: chunks=$chunkCount, textLen=${textParts.length}, " +
                "toolCalls=${toolCalls.size}, rawParts=${rawModelParts.size}"
        )

        val hasFunctionCalls = toolCalls.isNotEmpty()
        val finishReason = if (hasFunctionCalls) "tool_calls" else "stop"

        // Store raw model content with thought signatures for function-call round-trips
        if (rawModelParts.isNotEmpty()) {
            pendingModelContent = JsonObject(
                mapOf(
                    "role" to JsonPrimitive("model"),
                    "parts" to JsonArray(rawModelParts)
                )
            )
        }

        return LlmResponse(
            id = "antigravity-${System.currentTimeMillis()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = MessageResponse(
                        role = "assistant",
                        content = textParts.toString().ifEmpty { null },
                        tool_calls = toolCalls.takeIf { it.isNotEmpty() }
                    ),
                    finish_reason = finishReason
                )
            ),
            usage = Usage(
                prompt_tokens = promptTokens,
                completion_tokens = completionTokens,
                total_tokens = totalTokens
            )
        )
    }

    private fun parseResponseChunk(
        resp: JsonObject,
        textParts: StringBuilder,
        toolCalls: MutableList<ToolCall>,
        rawModelParts: MutableList<JsonElement>
    ) {
        val candidates = resp["candidates"]?.jsonArray ?: return
        for (candidate in candidates) {
            val content = candidate.jsonObject["content"]?.jsonObject ?: continue
            val parts = content["parts"]?.jsonArray ?: continue

            for (part in parts) {
                val partObj = part.jsonObject

                // Always store the raw part (preserves thoughtSignature)
                rawModelParts.add(part)

                val partKeys = partObj.keys.toList()
                Log.d(TAG, "Part keys: $partKeys")

                // Skip thought parts for text extraction
                val isThought = partObj["thought"]
                    ?.jsonPrimitive?.booleanOrNull == true
                if (isThought) {
                    Log.d(TAG, "Skipping thought part")
                    continue
                }

                // Text content
                partObj["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    Log.d(TAG, "Text part: ${text.take(100)}")
                    textParts.append(text)
                }

                // Function call
                partObj["functionCall"]?.jsonObject?.let { fc ->
                    val name = fc["name"]?.jsonPrimitive?.contentOrNull
                        ?: "unknown"
                    val args = fc["args"]?.jsonObject ?: JsonObject(emptyMap())
                    toolCalls.add(
                        ToolCall(
                            id = "ag-func-${System.nanoTime()}",
                            type = "function",
                            function = FunctionCall(
                                name = name,
                                arguments = args.toString()
                            )
                        )
                    )
                }
            }
        }
    }
}
