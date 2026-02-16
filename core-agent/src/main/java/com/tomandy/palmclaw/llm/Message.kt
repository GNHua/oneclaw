package com.tomandy.palmclaw.llm

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val role: String, // "user", "assistant", "system", "tool"
    val content: String? = null,
    val tool_calls: List<ToolCall>? = null,  // For assistant messages with tool calls
    val tool_call_id: String? = null,        // For tool role messages
    val name: String? = null,                // Tool name for tool role messages
    @kotlinx.serialization.Transient
    val mediaData: List<MediaData>? = null   // Not serialized -- used in-memory for media attachments (images, audio)
)
