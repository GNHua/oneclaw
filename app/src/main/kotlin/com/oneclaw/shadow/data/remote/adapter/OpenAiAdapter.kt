package com.oneclaw.shadow.data.remote.adapter

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.ConnectionErrorType
import com.oneclaw.shadow.core.model.ConnectionTestResult
import com.oneclaw.shadow.core.model.ModelSource
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.data.remote.dto.openai.OpenAiModelListResponse
import com.oneclaw.shadow.tool.engine.ToolSchemaSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

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
                response.code == 401 || response.code == 403 -> {
                    AppResult.Error(
                        message = "Authentication failed. Please check your API key.",
                        code = ErrorCode.AUTH_ERROR
                    )
                }
                else -> {
                    AppResult.Error(
                        message = "API error: ${response.code} ${response.message}",
                        code = ErrorCode.PROVIDER_ERROR
                    )
                }
            }
        } catch (e: java.net.UnknownHostException) {
            AppResult.Error(
                message = "Cannot reach the server. Please check the URL and your network.",
                code = ErrorCode.NETWORK_ERROR,
                exception = e
            )
        } catch (e: java.net.SocketTimeoutException) {
            AppResult.Error(
                message = "Connection timed out.",
                code = ErrorCode.TIMEOUT_ERROR,
                exception = e
            )
        } catch (e: Exception) {
            AppResult.Error(
                message = "Unexpected error: ${e.message}",
                code = ErrorCode.UNKNOWN,
                exception = e
            )
        }
    }

    override suspend fun testConnection(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<ConnectionTestResult> {
        return when (val result = listModels(apiBaseUrl, apiKey)) {
            is AppResult.Success -> AppResult.Success(
                ConnectionTestResult(
                    success = true,
                    modelCount = result.data.size,
                    errorType = null,
                    errorMessage = null
                )
            )
            is AppResult.Error -> {
                val errorType = when (result.code) {
                    ErrorCode.AUTH_ERROR -> ConnectionErrorType.AUTH_FAILURE
                    ErrorCode.NETWORK_ERROR -> ConnectionErrorType.NETWORK_FAILURE
                    ErrorCode.TIMEOUT_ERROR -> ConnectionErrorType.TIMEOUT
                    else -> ConnectionErrorType.UNKNOWN
                }
                AppResult.Success(
                    ConnectionTestResult(
                        success = false,
                        modelCount = null,
                        errorType = errorType,
                        errorMessage = result.message
                    )
                )
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
    ): Flow<StreamEvent> {
        throw NotImplementedError("sendMessageStream is implemented in RFC-001")
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
    ): AppResult<String> {
        // TODO: Implement in RFC-005
        return AppResult.Error(message = "Not implemented yet", code = ErrorCode.PROVIDER_ERROR)
    }

    private fun isRelevantOpenAiModel(modelId: String): Boolean {
        val chatPrefixes = listOf("gpt-", "o1", "o3", "o4", "chatgpt-")
        return chatPrefixes.any { modelId.startsWith(it) }
    }

    private fun formatOpenAiModelName(modelId: String): String {
        return modelId
            .replace("gpt-", "GPT-")
            .replace("-mini", " Mini")
    }
}
