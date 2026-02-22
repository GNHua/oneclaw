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
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * LLM client for Google Antigravity (Cloud Code Assist).
 *
 * Communicates with the Cloud Code Assist API using OAuth2 Bearer tokens.
 * The API accepts Gemini-format payloads wrapped in a Cloud Code Assist envelope
 * and returns Server-Sent Events (SSE).
 *
 * Auth is handled externally via the [tokenProvider] and [projectIdProvider]
 * lambdas, keeping this class free of Android dependencies.
 */
class AntigravityClient(
    private val tokenProvider: suspend () -> String?,
    private val projectIdProvider: suspend () -> String?
) : LlmClient {

    companion object {
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
            val requestBody = buildRequestBody(
                messages, apiModel, temperature, maxTokens, tools, projectId
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
                return@withContext Result.failure(
                    Exception("Antigravity API error ($code): $errorBody")
                )
            }

            val sseResult = parseSseResponse(response)
            Result.success(sseResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
        messages: List<Message>,
        model: String,
        temperature: Float,
        maxTokens: Int?,
        tools: List<Tool>?,
        projectId: String
    ): String {
        val contents = buildContentsJson(messages)
        val systemInstruction = buildSystemInstructionJson(messages)

        val genConfig = buildMap<String, JsonElement> {
            put("temperature", JsonPrimitive(temperature))
            if (maxTokens != null) {
                put("maxOutputTokens", JsonPrimitive(maxTokens))
            }
        }

        val innerRequest = buildMap<String, JsonElement> {
            put("contents", contents)
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

    private fun buildContentsJson(messages: List<Message>): JsonArray {
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
                    if (!msg.content.isNullOrBlank()) {
                        parts.add(
                            JsonObject(mapOf("text" to JsonPrimitive(msg.content)))
                        )
                    }
                    msg.tool_calls?.forEach { tc ->
                        val argsObj = try {
                            json.parseToJsonElement(tc.function.arguments).jsonObject
                        } catch (_: Exception) {
                            JsonObject(emptyMap())
                        }
                        parts.add(
                            JsonObject(
                                mapOf(
                                    "functionCall" to JsonObject(
                                        mapOf(
                                            "name" to JsonPrimitive(
                                                tc.function.name
                                            ),
                                            "args" to argsObj
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
                                    "role" to JsonPrimitive("model"),
                                    "parts" to JsonArray(parts)
                                )
                            )
                        )
                    }
                }
                "tool" -> {
                    val responsePart = JsonObject(
                        mapOf(
                            "functionResponse" to JsonObject(
                                mapOf(
                                    "name" to JsonPrimitive(msg.name ?: "unknown"),
                                    "response" to JsonObject(
                                        mapOf(
                                            "result" to JsonPrimitive(
                                                msg.content ?: ""
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                    contents.add(
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive("user"),
                                "parts" to JsonArray(listOf(responsePart))
                            )
                        )
                    )
                }
            }
        }

        return JsonArray(contents)
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
        var promptTokens = 0
        var completionTokens = 0
        var totalTokens = 0

        response.body?.let { body ->
            BufferedReader(InputStreamReader(body.byteStream())).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val data = line ?: continue
                    if (!data.startsWith("data:")) continue
                    val jsonStr = data.removePrefix("data:").trim()
                    if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue

                    try {
                        val chunk = json.parseToJsonElement(jsonStr).jsonObject
                        val resp = chunk["response"]?.jsonObject ?: continue
                        parseResponseChunk(
                            resp, textParts, toolCalls
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
                    } catch (_: Exception) {
                        // Skip malformed chunks
                    }
                }
            }
        }

        val finishReason = if (toolCalls.isNotEmpty()) "tool_calls" else "stop"

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
        toolCalls: MutableList<ToolCall>
    ) {
        val candidates = resp["candidates"]?.jsonArray ?: return
        for (candidate in candidates) {
            val content = candidate.jsonObject["content"]?.jsonObject ?: continue
            val parts = content["parts"]?.jsonArray ?: continue

            for (part in parts) {
                val partObj = part.jsonObject

                // Skip thought parts
                val isThought = partObj["thought"]
                    ?.jsonPrimitive?.booleanOrNull == true
                if (isThought) continue

                // Text content
                partObj["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
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
