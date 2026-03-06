package com.oneclaw.shadow.feature.chat.usecase

import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.data.remote.adapter.ApiMessage
import com.oneclaw.shadow.data.remote.adapter.ApiToolCall

/**
 * Convert a list of domain Messages to ApiMessages for the model API.
 * ERROR and SYSTEM messages are excluded (UI-only markers).
 *
 * Groups TOOL_CALL messages after an AI_RESPONSE into the assistant's toolCalls list,
 * then pairs them with following TOOL_RESULT messages.
 */
fun List<Message>.toApiMessages(): List<ApiMessage> {
    val result = mutableListOf<ApiMessage>()
    var i = 0
    while (i < size) {
        val msg = this[i]
        when (msg.type) {
            MessageType.USER -> {
                result.add(ApiMessage.User(content = msg.content))
                i++
            }
            MessageType.AI_RESPONSE -> {
                // Collect any following TOOL_CALL messages
                val toolCalls = mutableListOf<ApiToolCall>()
                var j = i + 1
                while (j < size && this[j].type == MessageType.TOOL_CALL) {
                    val tc = this[j]
                    toolCalls.add(ApiToolCall(
                        id = tc.toolCallId ?: "",
                        name = tc.toolName ?: "",
                        arguments = tc.toolInput ?: "{}",
                        thoughtSignature = tc.thinkingContent
                    ))
                    j++
                }
                result.add(ApiMessage.Assistant(
                    content = msg.content.ifEmpty { null },
                    toolCalls = toolCalls.ifEmpty { null }
                ))
                i = j
            }
            MessageType.TOOL_CALL -> {
                // Standalone tool call (shouldn't happen in well-formed history, skip)
                i++
            }
            MessageType.TOOL_RESULT -> {
                result.add(ApiMessage.ToolResult(
                    toolCallId = msg.toolCallId ?: "",
                    content = msg.toolOutput ?: ""
                ))
                i++
            }
            MessageType.ERROR, MessageType.SYSTEM -> {
                // Skip UI-only messages
                i++
            }
        }
    }
    return result
}
