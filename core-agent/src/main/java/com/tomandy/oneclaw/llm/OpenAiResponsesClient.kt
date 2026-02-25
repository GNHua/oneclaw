package com.tomandy.oneclaw.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * OpenAI client using the Responses API (`POST /v1/responses`).
 *
 * Replaces the previous Chat Completions-based [OpenAiClient].
 * Supports web search via the `web_search_preview` built-in tool.
 */
class OpenAiResponsesClient(
    private var apiKey: String = "",
    private var baseUrl: String = "https://api.openai.com/v1/"
) : LlmClient {

    @Volatile
    private var httpClient: OkHttpClient = createHttpClient()

    private fun createHttpClient(): OkHttpClient {
        val clientBuilder = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(NetworkConfig.DEFAULT_CONNECT_TIMEOUT, NetworkConfig.TIMEOUT_UNIT)
            .readTimeout(NetworkConfig.LLM_REQUEST_TIMEOUT, NetworkConfig.TIMEOUT_UNIT)
            .writeTimeout(NetworkConfig.DEFAULT_WRITE_TIMEOUT, NetworkConfig.TIMEOUT_UNIT)

        NetworkConfig.createLoggingInterceptor()?.let { loggingInterceptor ->
            clientBuilder.addInterceptor(loggingInterceptor)
        }

        return clientBuilder.build()
    }

    @Synchronized
    override fun setApiKey(apiKey: String) {
        this.apiKey = apiKey
        httpClient = createHttpClient()
    }

    @Synchronized
    override fun setBaseUrl(baseUrl: String) {
        this.baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        httpClient = createHttpClient()
    }

    override suspend fun complete(
        messages: List<Message>,
        model: String,
        temperature: Float,
        maxTokens: Int?,
        tools: List<Tool>?,
        enableWebSearch: Boolean
    ): Result<LlmResponse> = runInterruptible(Dispatchers.IO) {
        try {
            val modelToUse = model.ifEmpty { "gpt-4o-mini" }

            val body = buildRequestBody(
                messages, modelToUse, temperature, maxTokens, tools, enableWebSearch
            )

            val requestBody = body.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${baseUrl}responses")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                if (response.code == 400 && isContextOverflow(responseBody)) {
                    return@runInterruptible Result.failure(
                        ContextOverflowException("OpenAI: ${parseApiError(responseBody)}")
                    )
                }
                val errorMessage = when (response.code) {
                    401 -> "Unauthorized: Invalid API key"
                    429 -> "Rate limit exceeded: ${parseApiError(responseBody)}"
                    500 -> "Server error: ${parseApiError(responseBody)}"
                    502, 503, 504 -> "Service unavailable: ${response.code}"
                    else -> "HTTP error ${response.code}: ${parseApiError(responseBody)}"
                }
                return@runInterruptible Result.failure(IOException(errorMessage))
            }

            if (responseBody == null) {
                return@runInterruptible Result.failure(IOException("Empty response body"))
            }

            val parsed = NetworkConfig.json.parseToJsonElement(responseBody).jsonObject
            val llmResponse = parseResponsesApiOutput(parsed)
            Result.success(llmResponse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            Result.failure(IOException("Request timed out: ${e.message}", e))
        } catch (e: IOException) {
            Result.failure(IOException("Network error: ${e.message}", e))
        } catch (e: Exception) {
            Result.failure(IOException("Unexpected error: ${e.message}", e))
        }
    }

    // -- Request building ----------------------------------------------------

    private fun buildRequestBody(
        messages: List<Message>,
        model: String,
        temperature: Float,
        maxTokens: Int?,
        tools: List<Tool>?,
        enableWebSearch: Boolean
    ): JsonObject {
        return buildJsonObject {
            put("model", model)
            put("temperature", temperature)
            maxTokens?.let { put("max_output_tokens", it) }
            put("stream", false)

            // Input: convert Message list to Responses API input array
            put("input", buildInputArray(messages))

            // Tools
            val toolsArray = buildToolsArray(tools, enableWebSearch)
            if (toolsArray.isNotEmpty()) {
                put("tools", toolsArray)
            }
        }
    }

    private fun buildInputArray(messages: List<Message>): JsonArray {
        return buildJsonArray {
            for (msg in messages) {
                when (msg.role) {
                    "system" -> {
                        if (!msg.content.isNullOrBlank()) {
                            add(buildJsonObject {
                                put("role", "developer")
                                put("content", msg.content)
                            })
                        }
                    }
                    "user" -> {
                        if (!msg.mediaData.isNullOrEmpty()) {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", buildJsonArray {
                                    if (!msg.content.isNullOrBlank()) {
                                        add(buildJsonObject {
                                            put("type", "input_text")
                                            put("text", msg.content)
                                        })
                                    }
                                    for (media in msg.mediaData) {
                                        if (media.isImage) {
                                            add(buildJsonObject {
                                                put("type", "input_image")
                                                put("image_url", "data:${media.mimeType};base64,${media.base64}")
                                            })
                                        } else if (media.isAudio) {
                                            add(buildJsonObject {
                                                put("type", "input_audio")
                                                put("input_audio", buildJsonObject {
                                                    put("data", media.base64)
                                                    put("format", openAiAudioFormat(media.mimeType))
                                                })
                                            })
                                        } else if (media.isDocument) {
                                            add(buildJsonObject {
                                                put("type", "input_file")
                                                put("filename", media.fileName ?: "document.pdf")
                                                put("file_data", "data:${media.mimeType};base64,${media.base64}")
                                            })
                                        }
                                    }
                                })
                            })
                        } else {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", msg.content ?: "")
                            })
                        }
                    }
                    "assistant" -> {
                        // Text content as a message item
                        if (!msg.content.isNullOrBlank() && msg.tool_calls.isNullOrEmpty()) {
                            add(buildJsonObject {
                                put("type", "message")
                                put("role", "assistant")
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "output_text")
                                        put("text", msg.content)
                                    })
                                })
                            })
                        }
                        // Tool calls as individual function_call items
                        msg.tool_calls?.forEach { tc ->
                            add(buildJsonObject {
                                put("type", "function_call")
                                put("call_id", tc.id)
                                put("name", tc.function.name)
                                put("arguments", tc.function.arguments)
                            })
                        }
                        // If there's both text and tool calls, include text as a message
                        if (!msg.content.isNullOrBlank() && !msg.tool_calls.isNullOrEmpty()) {
                            add(buildJsonObject {
                                put("type", "message")
                                put("role", "assistant")
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "output_text")
                                        put("text", msg.content)
                                    })
                                })
                            })
                        }
                    }
                    "tool" -> {
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", msg.tool_call_id ?: "")
                            put("output", msg.content ?: "")
                        })
                    }
                }
            }
        }
    }

    private fun buildToolsArray(
        tools: List<Tool>?,
        enableWebSearch: Boolean
    ): JsonArray {
        return buildJsonArray {
            if (enableWebSearch) {
                add(buildJsonObject {
                    put("type", "web_search_preview")
                })
            }
            tools?.forEach { tool ->
                add(buildJsonObject {
                    put("type", "function")
                    put("name", tool.function.name)
                    put("description", tool.function.description)
                    put("parameters", tool.function.parameters)
                })
            }
        }
    }

    // -- Response parsing ----------------------------------------------------

    private fun parseResponsesApiOutput(json: JsonObject): LlmResponse {
        val id = json["id"]?.jsonPrimitive?.content ?: "openai-${System.currentTimeMillis()}"
        val output = json["output"]?.jsonArray ?: JsonArray(emptyList())

        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()
        val citations = mutableListOf<Pair<String, String>>() // title, url

        for (item in output) {
            val obj = item.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: continue

            when (type) {
                "message" -> {
                    val contentBlocks = obj["content"]?.jsonArray ?: continue
                    for (block in contentBlocks) {
                        val blockObj = block.jsonObject
                        val blockType = blockObj["type"]?.jsonPrimitive?.content
                        if (blockType == "output_text") {
                            val text = blockObj["text"]?.jsonPrimitive?.content ?: ""
                            textParts.add(text)
                            // Extract url_citation annotations
                            blockObj["annotations"]?.jsonArray?.forEach { ann ->
                                val annObj = ann.jsonObject
                                if (annObj["type"]?.jsonPrimitive?.content == "url_citation") {
                                    val url = annObj["url"]?.jsonPrimitive?.content ?: return@forEach
                                    val title = annObj["title"]?.jsonPrimitive?.content ?: url
                                    citations.add(title to url)
                                }
                            }
                        }
                    }
                }
                "function_call" -> {
                    val callId = obj["call_id"]?.jsonPrimitive?.content ?: "call-${System.nanoTime()}"
                    val name = obj["name"]?.jsonPrimitive?.content ?: "unknown"
                    val arguments = obj["arguments"]?.jsonPrimitive?.content ?: "{}"
                    toolCalls.add(
                        ToolCall(
                            id = callId,
                            type = "function",
                            function = FunctionCall(
                                name = name,
                                arguments = arguments
                            )
                        )
                    )
                }
                "web_search_call" -> {
                    // Server-side web search -- skip
                }
            }
        }

        // Append citations as markdown links
        val textContent = buildString {
            append(textParts.joinToString(""))
            if (citations.isNotEmpty()) {
                val uniqueCitations = citations.distinctBy { it.second }
                append("\n\nSources:\n")
                uniqueCitations.forEach { (title, url) ->
                    append("- [$title]($url)\n")
                }
            }
        }.ifEmpty { null }

        val finishReason = if (toolCalls.isNotEmpty()) "tool_calls" else "stop"

        // Parse usage
        val usageObj = json["usage"]?.jsonObject
        val usage = usageObj?.let {
            Usage(
                prompt_tokens = it["input_tokens"]?.jsonPrimitive?.int ?: 0,
                completion_tokens = it["output_tokens"]?.jsonPrimitive?.int ?: 0,
                total_tokens = (it["input_tokens"]?.jsonPrimitive?.int ?: 0) +
                    (it["output_tokens"]?.jsonPrimitive?.int ?: 0)
            )
        }

        return LlmResponse(
            id = id,
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
            usage = usage
        )
    }

    // -- Helpers -------------------------------------------------------------

    private fun isContextOverflow(body: String?): Boolean {
        if (body == null) return false
        val lower = body.lowercase()
        return lower.contains("context_length_exceeded") ||
            lower.contains("maximum context length") ||
            lower.contains("too many tokens") ||
            lower.contains("prompt is too long")
    }

    private fun openAiAudioFormat(mimeType: String): String {
        return when {
            mimeType.contains("wav") -> "wav"
            mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
            mimeType.contains("flac") -> "flac"
            mimeType.contains("opus") -> "opus"
            mimeType.contains("pcm") -> "pcm16"
            else -> "wav"
        }
    }
}
