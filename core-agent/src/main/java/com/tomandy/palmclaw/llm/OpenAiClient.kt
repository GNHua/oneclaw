package com.tomandy.palmclaw.llm

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.SocketTimeoutException

class OpenAiClient(
    private var apiKey: String = "",
    private var baseUrl: String = "https://api.openai.com/v1/"
) : LlmClient {

    @Volatile
    private var httpClient: OkHttpClient

    @Volatile
    private var retrofit: Retrofit

    @Volatile
    private var api: OpenAiApi

    init {
        httpClient = createHttpClient()
        retrofit = createRetrofit(httpClient)
        api = retrofit.create(OpenAiApi::class.java)
    }

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
            .readTimeout(NetworkConfig.DEFAULT_READ_TIMEOUT, NetworkConfig.TIMEOUT_UNIT)
            .writeTimeout(NetworkConfig.DEFAULT_WRITE_TIMEOUT, NetworkConfig.TIMEOUT_UNIT)

        NetworkConfig.createLoggingInterceptor()?.let { loggingInterceptor ->
            clientBuilder.addInterceptor(loggingInterceptor)
        }

        return clientBuilder.build()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(
                NetworkConfig.json.asConverterFactory("application/json".toMediaType())
            )
            .build()
    }

    override suspend fun complete(
        messages: List<Message>,
        model: String,
        temperature: Float,
        maxTokens: Int?,
        tools: List<Tool>?
    ): Result<LlmResponse> {
        val hasMedia = messages.any { !it.mediaData.isNullOrEmpty() }
        return if (hasMedia) {
            completeWithMedia(messages, model, temperature, maxTokens, tools)
        } else {
            completeText(messages, model, temperature, maxTokens, tools)
        }
    }

    private suspend fun completeText(
        messages: List<Message>,
        model: String,
        temperature: Float,
        maxTokens: Int?,
        tools: List<Tool>?
    ): Result<LlmResponse> {
        return try {
            val modelToUse = model.ifEmpty { "gpt-4o-mini" }

            val request = LlmRequest(
                model = modelToUse,
                messages = messages,
                temperature = temperature,
                max_tokens = maxTokens,
                tools = tools,
                stream = false
            )

            val response = api.createChatCompletion(request)
            Result.success(response)

        } catch (e: HttpException) {
            val errorMessage = when (e.code()) {
                401 -> "Unauthorized: Invalid API key"
                429 -> "Rate limit exceeded: ${parseApiError(e.response()?.errorBody()?.string())}"
                500 -> "Server error: ${parseApiError(e.response()?.errorBody()?.string())}"
                502, 503, 504 -> "Service unavailable: ${e.code()}"
                else -> "HTTP error ${e.code()}: ${parseApiError(e.response()?.errorBody()?.string())}"
            }
            Result.failure(IOException(errorMessage, e))

        } catch (e: SocketTimeoutException) {
            Result.failure(IOException("Request timed out: ${e.message}", e))

        } catch (e: IOException) {
            Result.failure(IOException("Network error: ${e.message}", e))

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IOException("Unexpected error: ${e.message}", e))
        }
    }

    /**
     * Send a request with media content blocks (images, audio).
     * Bypasses Retrofit serialization to build the content-parts array format
     * required by the OpenAI vision/audio API.
     */
    private suspend fun completeWithMedia(
        messages: List<Message>,
        model: String,
        temperature: Float,
        maxTokens: Int?,
        tools: List<Tool>?
    ): Result<LlmResponse> = runInterruptible(Dispatchers.IO) {
        try {
            val modelToUse = model.ifEmpty { "gpt-4o-mini" }

            val body = buildJsonObject {
                put("model", modelToUse)
                put("temperature", temperature)
                maxTokens?.let { put("max_tokens", it) }
                put("stream", false)

                put("messages", buildJsonArray {
                    for (msg in messages) {
                        add(buildJsonObject {
                            put("role", msg.role)
                            if (!msg.mediaData.isNullOrEmpty()) {
                                // Content as array of blocks
                                put("content", buildJsonArray {
                                    if (!msg.content.isNullOrBlank()) {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", msg.content)
                                        })
                                    }
                                    for (media in msg.mediaData) {
                                        if (media.isImage) {
                                            add(buildJsonObject {
                                                put("type", "image_url")
                                                put("image_url", buildJsonObject {
                                                    put("url", "data:${media.mimeType};base64,${media.base64}")
                                                })
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
                                                put("type", "file")
                                                put("file", buildJsonObject {
                                                    put("filename", media.fileName ?: "document.pdf")
                                                    put("file_data", "data:${media.mimeType};base64,${media.base64}")
                                                })
                                            })
                                        }
                                    }
                                })
                            } else {
                                put("content", msg.content ?: "")
                            }
                            // Tool calls for assistant messages
                            msg.tool_calls?.let { tcs ->
                                put("tool_calls", NetworkConfig.json.encodeToJsonElement(
                                    kotlinx.serialization.builtins.ListSerializer(ToolCall.serializer()),
                                    tcs
                                ))
                            }
                            msg.tool_call_id?.let { put("tool_call_id", it) }
                            msg.name?.let { put("name", it) }
                        })
                    }
                })

                tools?.takeIf { it.isNotEmpty() }?.let { toolList ->
                    put("tools", NetworkConfig.json.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(Tool.serializer()),
                        toolList
                    ))
                }
            }

            val requestBody = body.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${baseUrl}chat/completions")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
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

            val llmResponse = NetworkConfig.json.decodeFromString<LlmResponse>(responseBody)
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

    @Synchronized
    override fun setApiKey(apiKey: String) {
        this.apiKey = apiKey
        httpClient = createHttpClient()
        retrofit = createRetrofit(httpClient)
        api = retrofit.create(OpenAiApi::class.java)
    }

    @Synchronized
    override fun setBaseUrl(baseUrl: String) {
        this.baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        httpClient = createHttpClient()
        retrofit = createRetrofit(httpClient)
        api = retrofit.create(OpenAiApi::class.java)
    }

    override fun cancel() {
        httpClient.dispatcher.cancelAll()
    }

    private fun openAiAudioFormat(mimeType: String): String {
        // OpenAI input_audio accepts: wav, mp3, flac, opus, pcm16
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

interface OpenAiApi {
    @POST("chat/completions")
    suspend fun createChatCompletion(@Body request: LlmRequest): LlmResponse
}
