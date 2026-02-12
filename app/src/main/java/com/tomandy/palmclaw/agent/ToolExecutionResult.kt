package com.tomandy.palmclaw.agent

import com.tomandy.palmclaw.llm.ToolCall

/**
 * Result of a tool execution in the agent layer.
 *
 * This wraps the engine's ToolResult with additional context needed by the agent,
 * including the original ToolCall that triggered the execution.
 *
 * The difference between ToolExecutionResult and ToolResult:
 * - ToolResult (from engine): Low-level plugin execution result
 * - ToolExecutionResult: Agent-level result with full context for the ReAct loop
 */
sealed class ToolExecutionResult {
    /**
     * Tool executed successfully.
     *
     * @param toolCall The original tool call from the LLM
     * @param output The tool's output (will be sent to the LLM as an observation)
     * @param metadata Optional metadata for debugging or analytics
     */
    data class Success(
        val toolCall: ToolCall,
        val output: String,
        val metadata: Map<String, String> = emptyMap()
    ) : ToolExecutionResult()

    /**
     * Tool execution failed.
     *
     * @param toolCall The original tool call from the LLM
     * @param error User-friendly error message (will be sent to the LLM)
     * @param exception Optional exception for debugging
     */
    data class Failure(
        val toolCall: ToolCall,
        val error: String,
        val exception: Throwable? = null
    ) : ToolExecutionResult()
}
