package com.tomandy.palmclaw.agent

import com.tomandy.palmclaw.llm.LlmClient
import com.tomandy.palmclaw.llm.Message

/**
 * Helper class for implementing the ReAct (Reasoning + Acting) cycle logic.
 *
 * The ReAct pattern involves iterative cycles of:
 * 1. Reasoning: LLM thinks about the task
 * 2. Acting: LLM decides to call tools or respond
 * 3. Observing: Results from tool calls are fed back to the LLM
 *
 * Phase 1: Detects tool calls but returns them as text (no execution).
 * Phase 2: Will implement full ReAct loop with tool execution.
 */
class ReActLoop(
    private val llmClient: LlmClient
) {

    /**
     * Executes a single step of the ReAct loop.
     *
     * Phase 1 Behavior:
     * - Calls LLM with the provided messages
     * - If tool calls are detected, returns their description as text
     * - If no tool calls, returns the LLM's content response
     * - No actual tool execution occurs
     *
     * Phase 2 Behavior (future):
     * - Will execute tools and iterate until completion
     * - Will maintain conversation context across iterations
     * - Will respect maxIterations to prevent infinite loops
     *
     * @param messages The conversation history to process
     * @param model The LLM model to use
     * @param maxIterations Maximum number of ReAct iterations (Phase 2)
     * @return Result containing the final response or error
     */
    suspend fun step(
        messages: List<Message>,
        model: String = "gpt-4o-mini",
        maxIterations: Int = 5
    ): Result<String> {
        val currentMessages = messages.toMutableList()
        var iterations = 0

        // Phase 1: Single iteration, no actual tool execution
        while (iterations < maxIterations) {
            val result = llmClient.complete(
                messages = currentMessages,
                model = model
            )

            result.fold(
                onSuccess = { response ->
                    val choice = response.choices.firstOrNull()
                        ?: return Result.failure(Exception("No choices in response"))

                    val message = choice.message

                    // Phase 1: If tool calls exist, return description (don't execute)
                    if (!message.tool_calls.isNullOrEmpty()) {
                        val toolDesc = message.tool_calls.joinToString("\n") {
                            "Tool: ${it.function.name}\nArguments: ${it.function.arguments}"
                        }
                        return Result.success("Agent wants to call tools:\n\n$toolDesc")
                    }

                    // No tool calls, return content
                    val content = message.content
                    if (content.isNullOrBlank()) {
                        return Result.failure(Exception("Empty response from LLM"))
                    }

                    return Result.success(content)
                },
                onFailure = { error ->
                    return Result.failure(error)
                }
            )

            iterations++
        }

        // This should not be reached in Phase 1 (single iteration)
        return Result.failure(Exception("Max iterations ($maxIterations) reached without completion"))
    }

    /**
     * Phase 2: Will implement multi-turn ReAct loop with tool execution.
     *
     * Future implementation will:
     * 1. Call LLM
     * 2. If tool calls detected, execute them via ToolExecutor
     * 3. Add tool results to conversation as observations
     * 4. Repeat until final answer or max iterations
     */
    @Suppress("unused")
    private suspend fun stepWithToolExecution(
        messages: List<Message>,
        toolExecutor: ToolExecutor,
        model: String = "gpt-4o-mini",
        maxIterations: Int = 5
    ): Result<String> {
        // Phase 2 implementation placeholder
        return Result.failure(Exception("Not implemented in Phase 1"))
    }
}
