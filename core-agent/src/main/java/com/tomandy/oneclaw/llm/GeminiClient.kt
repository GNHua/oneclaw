package com.tomandy.oneclaw.llm

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.FunctionResponse
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.google.genai.types.Tool as GeminiTool

/**
 * Google Gemini client using the official java-genai SDK.
 *
 * Fully stateless: every [complete] call rebuilds the Gemini Content list
 * from the supplied [Message] list, including function-call and tool-result
 * messages.  This makes the client safe for concurrent use by multiple
 * agent executions sharing a single instance.
 *
 * Trade-off: thoughtSignature fields from thinking-model responses are not
 * preserved across round-trips because the intermediate Message format does
 * not carry them.  This has no effect on non-thinking models and only
 * disables server-side thought-chain verification for thinking models.
 */
class GeminiClient(
    private var apiKey: String = "",
    private var baseModel: String = "gemini-3-flash"
) : LlmClient {

    private var client: Client? = null

    @Synchronized
    override fun setApiKey(apiKey: String) {
        this.apiKey = apiKey
        this.client = if (apiKey.isNotEmpty()) {
            Client.builder().apiKey(apiKey).build()
        } else null
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
    ): Result<LlmResponse> = runInterruptible(Dispatchers.IO) {
        try {
            val genaiClient = client
                ?: return@runInterruptible Result.failure(
                    Exception("API key not set. Please configure your Google AI API key in Settings.")
                )

            val modelName = if (model.isNotEmpty()) model else baseModel

            // Stateless: rebuild the full Gemini Content list from messages every call
            val contents = buildContentsFromMessages(messages)

            // Build tools
            val geminiTools = tools?.takeIf { it.isNotEmpty() }?.let { buildGeminiTools(it) }

            // Build config
            val config = GenerateContentConfig.builder().apply {
                geminiTools?.let { tools(it) }
            }.build()

            // Make API call
            val response = genaiClient.models.generateContent(
                modelName,
                contents,
                config
            )

            // Extract candidate content
            val candidate = response.candidates()?.orElse(null)?.firstOrNull()
                ?: return@runInterruptible Result.failure(Exception("No candidates in Gemini response"))

            val contentObj = candidate.content()?.orElse(null)
            val parts = contentObj?.parts()?.orElse(null) ?: emptyList()

            // Check for function calls
            val functionCalls = response.functionCalls() ?: emptyList()
            val hasFunctionCalls = functionCalls.isNotEmpty()

            // Extract text content
            val textContent = parts
                .filter { it.text()?.isPresent == true }
                .filter { it.thought()?.orElse(false) != true }
                .joinToString("") { it.text().get() }

            // Convert function calls to our ToolCall format
            val toolCalls = functionCalls.map { fc ->
                val name = fc.name()?.orElse("unknown") ?: "unknown"
                val args = fc.args()?.orElse(null) ?: emptyMap()
                val argsJson = mapToJsonString(args)
                ToolCall(
                    id = fc.id()?.orElse(null) ?: "gemini-func-${System.nanoTime()}",
                    type = "function",
                    function = FunctionCall(
                        name = name,
                        arguments = argsJson
                    )
                )
            }

            val finishReason = if (hasFunctionCalls) "tool_calls"
            else candidate.finishReason()?.orElse(null)?.knownEnum()?.name?.lowercase() ?: "stop"

            // Extract usage metadata
            val usageMeta = response.usageMetadata()?.orElse(null)
            val usage = usageMeta?.let {
                Usage(
                    prompt_tokens = it.promptTokenCount()?.orElse(0) ?: 0,
                    completion_tokens = it.candidatesTokenCount()?.orElse(0) ?: 0,
                    total_tokens = it.totalTokenCount()?.orElse(0) ?: 0
                )
            }

            Result.success(
                LlmResponse(
                    id = "gemini-${System.currentTimeMillis()}",
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = MessageResponse(
                                role = "assistant",
                                content = textContent.ifEmpty { null },
                                tool_calls = toolCalls.takeIf { it.isNotEmpty() },
                                providerMeta = if (hasFunctionCalls) contentObj else null
                            ),
                            finish_reason = finishReason
                        )
                    ),
                    usage = usage
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("request payload size exceeds") ||
                msg.contains("too many tokens") ||
                msg.contains("exceeds the maximum") ||
                msg.contains("prompt is too long")
            ) {
                Result.failure(ContextOverflowException("Gemini: ${e.message}", e))
            } else {
                Result.failure(Exception("Gemini API error: ${e.message}", e))
            }
        }
    }

    /**
     * Build Gemini Content list from our Message list.
     * Handles all message types: system, user, assistant (with/without tool_calls), and tool.
     */
    private fun buildContentsFromMessages(messages: List<Message>): List<Content> {
        val contents = mutableListOf<Content>()

        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            when (msg.role) {
                "system" -> {
                    if (!msg.content.isNullOrBlank()) {
                        contents.add(
                            Content.builder()
                                .role("user")
                                .parts(Part.fromText(msg.content))
                                .build()
                        )
                        contents.add(
                            Content.builder()
                                .role("model")
                                .parts(Part.fromText("Understood."))
                                .build()
                        )
                    }
                }
                "user" -> {
                    val parts = mutableListOf<Part>()
                    if (!msg.content.isNullOrBlank()) {
                        parts.add(Part.fromText(msg.content))
                    }
                    msg.mediaData?.forEach { media ->
                        val bytes = java.util.Base64.getDecoder().decode(media.base64)
                        parts.add(Part.fromBytes(bytes, media.mimeType))
                    }
                    if (parts.isNotEmpty()) {
                        contents.add(
                            Content.builder()
                                .role("user")
                                .parts(parts)
                                .build()
                        )
                    }
                }
                "assistant" -> {
                    val tcList = msg.tool_calls
                    if (!tcList.isNullOrEmpty()) {
                        // Prefer the original Content (preserves thoughtSignature)
                        val rawContent = msg.providerMeta as? Content
                        if (rawContent != null) {
                            contents.add(rawContent)
                        } else {
                            // Reconstruct from tool_calls (thoughtSignature lost)
                            val parts = mutableListOf<Part>()
                            if (!msg.content.isNullOrBlank()) {
                                parts.add(Part.fromText(msg.content))
                            }
                            for (tc in tcList) {
                                val argsMap = parseJsonToMap(tc.function.arguments)
                                parts.add(
                                    Part.fromFunctionCall(tc.function.name, argsMap)
                                )
                            }
                            contents.add(
                                Content.builder()
                                    .role("model")
                                    .parts(parts)
                                    .build()
                            )
                        }

                        // Collect the immediately following tool-result messages
                        val responseParts = mutableListOf<Part>()
                        while (i + 1 < messages.size && messages[i + 1].role == "tool") {
                            i++
                            val toolMsg = messages[i]
                            responseParts.add(
                                Part.builder()
                                    .functionResponse(
                                        FunctionResponse.builder()
                                            .name(toolMsg.name ?: "unknown")
                                            .response(mapOf("result" to (toolMsg.content ?: "")))
                                            .build()
                                    )
                                    .build()
                            )
                        }
                        if (responseParts.isNotEmpty()) {
                            contents.add(
                                Content.builder()
                                    .role("user")
                                    .parts(responseParts)
                                    .build()
                            )
                        }
                    } else if (!msg.content.isNullOrBlank()) {
                        contents.add(
                            Content.builder()
                                .role("model")
                                .parts(Part.fromText(msg.content))
                                .build()
                        )
                    }
                }
                "tool" -> {
                    // Orphaned tool message (not preceded by an assistant with tool_calls).
                    // Shouldn't happen in normal flow, but handle gracefully.
                    contents.add(
                        Content.builder()
                            .role("user")
                            .parts(
                                Part.builder()
                                    .functionResponse(
                                        FunctionResponse.builder()
                                            .name(msg.name ?: "unknown")
                                            .response(mapOf("result" to (msg.content ?: "")))
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                }
            }
            i++
        }

        return contents
    }

    /**
     * Parse a JSON arguments string into a Map for Part.fromFunctionCall.
     */
    private fun parseJsonToMap(jsonStr: String): Map<String, String> {
        return try {
            val obj = Json.parseToJsonElement(jsonStr) as? JsonObject ?: return emptyMap()
            obj.entries.associate { (k, v) ->
                k to v.toString().removeSurrounding("\"")
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Convert our Tool format to Gemini SDK Tool format.
     */
    private fun buildGeminiTools(tools: List<Tool>): List<GeminiTool> {
        val declarations = tools.map { tool ->
            val params = jsonObjectToMap(tool.function.parameters)
            FunctionDeclaration.builder()
                .name(tool.function.name)
                .description(tool.function.description)
                .parametersJsonSchema(params)
                .build()
        }

        return listOf(
            GeminiTool.builder()
                .functionDeclarations(declarations)
                .build()
        )
    }

    /**
     * Convert a kotlinx.serialization JsonObject to a Map<String, Any?> for the Gemini SDK.
     */
    private fun jsonObjectToMap(json: JsonObject): Map<String, Any?> {
        return json.entries.associate { (key, value) ->
            key to jsonElementToAny(value)
        }
    }

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
            is kotlinx.serialization.json.JsonArray -> element.map { jsonElementToAny(it) }
            else -> null
        }
    }

    /**
     * Convert a Map<String, Object> to a JSON string for our FunctionCall.arguments.
     */
    private fun mapToJsonString(map: Map<String, Any?>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            "\"$k\":${valueToJson(v)}"
        }
        return "{$entries}"
    }

    private fun valueToJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"${value.replace("\"", "\\\"")}\""
            is Number -> value.toString()
            is Boolean -> value.toString()
            is Map<*, *> -> {
                val entries = value.entries.joinToString(",") { (k, v) ->
                    "\"$k\":${valueToJson(v)}"
                }
                "{$entries}"
            }
            is List<*> -> {
                val items = value.joinToString(",") { valueToJson(it) }
                "[$items]"
            }
            else -> "\"$value\""
        }
    }
}
