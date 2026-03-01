package com.oneclaw.shadow.core.model

data class Message(
    val id: String,
    val sessionId: String,
    val type: MessageType,
    val content: String,
    val thinkingContent: String?,
    val toolCallId: String?,
    val toolName: String?,
    val toolInput: String?,
    val toolOutput: String?,
    val toolStatus: ToolCallStatus?,
    val toolDurationMs: Long?,
    val tokenCountInput: Int?,
    val tokenCountOutput: Int?,
    val modelId: String?,
    val providerId: String?,
    val createdAt: Long,
    val citations: List<Citation>? = null
)

enum class MessageType {
    USER,
    AI_RESPONSE,
    TOOL_CALL,
    TOOL_RESULT,
    ERROR,
    SYSTEM
}

enum class ToolCallStatus {
    PENDING,
    EXECUTING,
    SUCCESS,
    ERROR,
    TIMEOUT
}
