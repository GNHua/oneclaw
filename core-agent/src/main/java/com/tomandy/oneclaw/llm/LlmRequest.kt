package com.tomandy.oneclaw.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class LlmRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Float = 0.7f,
    val max_tokens: Int? = null,
    val tools: List<Tool>? = null,
    val stream: Boolean = false
)

@Serializable
data class Tool(
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject
)
