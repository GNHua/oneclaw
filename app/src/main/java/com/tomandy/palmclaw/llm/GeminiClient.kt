package com.tomandy.palmclaw.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Google Gemini client using REST API directly.
 *
 * Uses OkHttp instead of the Gemini SDK to properly handle thought_signatures
 * required by thinking models (e.g., gemini-3-flash-preview).
 * The raw model response JSON is stored and echoed back during function call
 * round-trips, preserving all fields including thoughtSignature.
 */
class GeminiClient(
    private var apiKey: String = "",
    private var baseModel: String = "gemini-3-flash"
) : LlmClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // State for function call round-trips:
    // Store the raw model response content (with thoughtSignature) and conversation
    private var pendingModelContent: JsonObject? = null
    private var conversationContents: MutableList<JsonObject> = mutableListOf()

    @Synchronized
    override fun setApiKey(apiKey: String) {
        this.apiKey = apiKey
    }

    @Synchronized
    override fun setBaseUrl(baseUrl: String) {
        // Not applicable for Gemini
    }

    override suspend fun complete(
        messages: List<Message>,
        model: String,
        temperature: Float,
        maxTokens: Int?,
        tools: List<Tool>?
    ): Result<LlmResponse> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isEmpty()) {
                return@withContext Result.failure(Exception("API key not set. Please configure your Google AI API key in Settings."))
            }

            val modelName = if (model.isNotEmpty()) model else baseModel

            if (pendingModelContent != null) {
                // Follow-up after function call: add stored model content + function response
                val toolMessages = messages.filter { it.role == "tool" }
                if (toolMessages.isNotEmpty()) {
                    conversationContents.add(pendingModelContent!!)
                    conversationContents.add(buildFunctionResponseContent(toolMessages))
                    pendingModelContent = null
                }
            } else {
                // New conversation turn: rebuild from messages
                conversationContents = buildContentsFromMessages(messages)
            }

            // Build tools JSON
            val toolsJson = tools?.takeIf { it.isNotEmpty() }?.let { buildToolsJson(it) }

            // Build request body
            val requestBody = buildJsonObject {
                put("contents", JsonArray(conversationContents))
                toolsJson?.let { put("tools", it) }
            }

            // Make API call
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response from Gemini API"))

            if (!response.isSuccessful) {
                pendingModelContent = null
                return@withContext Result.failure(Exception("Gemini API error (${response.code}): $responseBody"))
            }

            val responseJson = json.parseToJsonElement(responseBody).jsonObject

            // Check for errors
            responseJson["error"]?.let { error ->
                pendingModelContent = null
                val errorMsg = error.jsonObject["message"]?.jsonPrimitive?.content ?: responseBody
                return@withContext Result.failure(Exception("Gemini API error: $errorMsg"))
            }

            // Parse candidates
            val candidates = responseJson["candidates"]?.jsonArray
                ?: return@withContext Result.failure(Exception("No candidates in Gemini response"))

            val candidate = candidates.firstOrNull()?.jsonObject
                ?: return@withContext Result.failure(Exception("Empty candidates in Gemini response"))

            val contentObj = candidate["content"]?.jsonObject
            val parts = contentObj?.get("parts")?.jsonArray ?: JsonArray(emptyList())

            // Check for function calls
            val functionCallParts = parts.filter { it.jsonObject.containsKey("functionCall") }
            val hasFunctionCalls = functionCallParts.isNotEmpty()

            if (hasFunctionCalls) {
                // Store the RAW model content for the next round-trip (preserves thoughtSignature)
                pendingModelContent = contentObj
            } else {
                // Final answer - add to conversation history
                contentObj?.let { conversationContents.add(it) }
                pendingModelContent = null
            }

            // Convert to LlmResponse
            val textContent = parts
                .filter { it.jsonObject.containsKey("text") }
                .joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }

            val toolCalls = functionCallParts.map { part ->
                val fc = part.jsonObject["functionCall"]!!.jsonObject
                val name = fc["name"]!!.jsonPrimitive.content
                val args = fc["args"]?.jsonObject ?: JsonObject(emptyMap())
                ToolCall(
                    id = "gemini-func-${System.nanoTime()}",
                    type = "function",
                    function = FunctionCall(
                        name = name,
                        arguments = args.toString()
                    )
                )
            }

            val finishReason = if (hasFunctionCalls) "tool_calls"
                else candidate["finishReason"]?.jsonPrimitive?.content?.lowercase() ?: "stop"

            Result.success(
                LlmResponse(
                    id = "gemini-${System.currentTimeMillis()}",
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = MessageResponse(
                                role = "assistant",
                                content = textContent.ifEmpty { null },
                                tool_calls = toolCalls.takeIf { it.isNotEmpty() }
                            ),
                            finish_reason = finishReason
                        )
                    ),
                    usage = null
                )
            )
        } catch (e: Exception) {
            pendingModelContent = null
            Result.failure(Exception("Gemini API error: ${e.message}", e))
        }
    }

    /**
     * Build Gemini contents array from our Message list.
     * Only includes text messages (system/user/assistant).
     */
    private fun buildContentsFromMessages(messages: List<Message>): MutableList<JsonObject> {
        val contents = mutableListOf<JsonObject>()

        for (msg in messages) {
            when (msg.role) {
                "system" -> {
                    if (!msg.content.isNullOrBlank()) {
                        contents.add(buildTextContent("user", msg.content))
                        contents.add(buildTextContent("model", "Understood."))
                    }
                }
                "user" -> {
                    if (!msg.content.isNullOrBlank()) {
                        contents.add(buildTextContent("user", msg.content))
                    }
                }
                "assistant" -> {
                    // Only include text content (skip tool_calls - handled via pendingModelContent)
                    if (!msg.content.isNullOrBlank() && (msg.tool_calls == null || msg.tool_calls.isEmpty())) {
                        contents.add(buildTextContent("model", msg.content))
                    }
                }
                // Skip "tool" messages - handled via pendingModelContent flow
            }
        }

        return contents
    }

    private fun buildTextContent(role: String, text: String): JsonObject {
        return buildJsonObject {
            put("role", role)
            put("parts", buildJsonArray {
                add(buildJsonObject { put("text", text) })
            })
        }
    }

    /**
     * Build function response content from tool messages.
     */
    private fun buildFunctionResponseContent(toolMessages: List<Message>): JsonObject {
        return buildJsonObject {
            put("role", "function")
            put("parts", buildJsonArray {
                toolMessages.forEach { toolMsg ->
                    add(buildJsonObject {
                        put("functionResponse", buildJsonObject {
                            put("name", toolMsg.name ?: "unknown")
                            put("response", buildJsonObject {
                                put("result", toolMsg.content ?: "")
                            })
                        })
                    })
                }
            })
        }
    }

    /**
     * Convert our Tool format to Gemini REST API tools JSON.
     */
    private fun buildToolsJson(tools: List<Tool>): JsonArray {
        val declarations = tools.map { tool ->
            val params = tool.function.parameters
            buildJsonObject {
                put("name", tool.function.name)
                put("description", tool.function.description)
                put("parameters", params)
            }
        }

        return buildJsonArray {
            add(buildJsonObject {
                put("functionDeclarations", JsonArray(declarations))
            })
        }
    }
}
