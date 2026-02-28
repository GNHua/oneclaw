package com.oneclaw.shadow.feature.chat

import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.util.ErrorCode

sealed class ChatEvent {
    data class StreamingText(val text: String) : ChatEvent()
    data class ThinkingText(val text: String) : ChatEvent()
    data class ToolCallStarted(val toolCallId: String, val toolName: String) : ChatEvent()
    data class ToolCallArgumentsDelta(val toolCallId: String, val delta: String) : ChatEvent()
    data class ToolCallCompleted(val toolCallId: String, val toolName: String, val result: ToolResult) : ChatEvent()
    data class ToolRoundStarting(val round: Int) : ChatEvent()
    data class ResponseComplete(val message: com.oneclaw.shadow.core.model.Message, val usage: TokenUsage?) : ChatEvent()
    data class TokenUsage(val inputTokens: Int, val outputTokens: Int)
    data class Error(val message: String, val errorCode: ErrorCode, val isRetryable: Boolean) : ChatEvent()
    data object CompactStarted : ChatEvent()
    data class CompactCompleted(val didCompact: Boolean) : ChatEvent()
}
