package com.tomandy.palmclaw.llm

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.ExperimentalSerializationApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import kotlinx.coroutines.CancellationException
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
        return try {
            // Use provider's default model if empty
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
}

interface OpenAiApi {
    @POST("chat/completions")
    suspend fun createChatCompletion(@Body request: LlmRequest): LlmResponse
}
