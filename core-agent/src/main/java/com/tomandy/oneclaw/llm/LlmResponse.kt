package com.tomandy.oneclaw.llm

import kotlinx.serialization.Serializable

@Serializable
data class LlmResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: MessageResponse,
    val finish_reason: String?
)

@Serializable
data class MessageResponse(
    val role: String,
    val content: String?,
    val tool_calls: List<ToolCall>? = null
)

@Serializable
data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)
