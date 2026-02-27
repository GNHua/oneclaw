package com.oneclaw.shadow.tool.engine

import com.oneclaw.shadow.core.model.ToolResult

/**
 * Represents a tool call request from the model.
 */
data class ToolCallRequest(
    val toolCallId: String,
    val toolName: String,
    val parameters: Map<String, Any?>
)

/**
 * Represents the result of a tool call execution.
 */
data class ToolCallResponse(
    val toolCallId: String,
    val toolName: String,
    val result: ToolResult,
    val durationMs: Long
)
