package com.oneclaw.shadow.data.remote.adapter

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.ConnectionTestResult
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.util.AppResult
import kotlinx.coroutines.flow.Flow

interface ModelApiAdapter {
    fun sendMessageStream(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?,
        webSearchEnabled: Boolean = false
    ): Flow<StreamEvent>

    suspend fun listModels(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<List<AiModel>>

    suspend fun testConnection(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<ConnectionTestResult>

    fun formatToolDefinitions(tools: List<ToolDefinition>): Any

    suspend fun generateSimpleCompletion(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        prompt: String,
        maxTokens: Int = 100
    ): AppResult<String>
}
