package com.oneclaw.shadow.data.remote.adapter

import com.oneclaw.shadow.core.model.AttachmentType

sealed class ApiMessage {
    data class User(
        val content: String,
        val attachments: List<ApiAttachment> = emptyList()
    ) : ApiMessage()

    data class Assistant(
        val content: String?,
        val toolCalls: List<ApiToolCall>? = null
    ) : ApiMessage()

    data class ToolResult(
        val toolCallId: String,
        val content: String
    ) : ApiMessage()
}

data class ApiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val thoughtSignature: String? = null
)

data class ApiAttachment(
    val type: AttachmentType,
    val mimeType: String,
    val base64Data: String,
    val fileName: String
)
