package com.tomandy.palmclaw.llm

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val role: String, // "user", "assistant", "system"
    val content: String
)
