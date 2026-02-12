package com.tomandy.palmclaw.agent

import com.tomandy.palmclaw.engine.ToolDefinition
import com.tomandy.palmclaw.llm.LlmClient
import com.tomandy.palmclaw.llm.Message
import com.tomandy.palmclaw.llm.Tool
import com.tomandy.palmclaw.llm.ToolFunction

/**
 * Implements the ReAct (Reasoning + Acting) cycle for the agent.
 *
 * The ReAct pattern involves iterative cycles of:
 * 1. **Reasoning**: LLM analyzes the task and decides what to do
 * 2. **Acting**: LLM calls tools to perform actions or gathers final answer
 * 3. **Observing**: Tool results are fed back to the LLM as observations
 *
 * This loop continues until the LLM provides a final answer or maxIterations is reached.
 *
 * Example flow:
 * ```
 * User: "What's the weather in San Francisco?"
 *
 * Iteration 1:
 *   LLM → [Reasoning] I need to get weather data
 *   LLM → [Acting] Call get_weather("San Francisco")
 *   Tool → [Observing] "Sunny, 72°F"
 *
 * Iteration 2:
 *   LLM → [Reasoning] I have the weather data
 *   LLM → [Final Answer] "The weather in San Francisco is sunny and 72°F"
 * ```
 *
 * Key features:
 * - Multi-turn reasoning with tool execution
 * - Automatic loop termination on final answer
 * - Max iteration safety limit (default: 5)
 * - Full conversation context preservation
 */
class ReActLoop(
    private val llmClient: LlmClient,
    private val toolExecutor: ToolExecutor
) {

    /**
     * Execute the ReAct loop with tool execution.
     *
     * Steps:
     * 1. Call LLM with available tools
     * 2. Check finish_reason:
     *    - "stop" → Return final answer
     *    - "tool_calls" → Execute tools and continue loop
     * 3. Add tool results to conversation
     * 4. Repeat until final answer or max iterations
     *
     * @param messages The conversation history
     * @param tools Available tool definitions for the LLM
     * @param conversationId The conversation ID for persisting tool results
     * @param model The LLM model to use
     * @param maxIterations Maximum ReAct iterations (default: 5)
     * @return Result containing the final response or error
     */
    suspend fun step(
        messages: List<Message>,
        tools: List<ToolDefinition>,
        conversationId: String,
        model: String = "gpt-4o-mini",
        maxIterations: Int = 5
    ): Result<String> {
        val workingMessages = messages.toMutableList()
        var iterations = 0

        // Convert ToolDefinition to LLM Tool format
        val llmTools = tools.map { toolDefToLlmTool(it) }

        while (iterations < maxIterations) {
            iterations++

            // 1. Call LLM with tools
            val result = llmClient.complete(
                messages = workingMessages,
                model = model,
                tools = if (llmTools.isNotEmpty()) llmTools else null
            )

            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull()!!)
            }

            val response = result.getOrNull()!!
            val choice = response.choices.firstOrNull()
                ?: return Result.failure(Exception("No choices in LLM response"))

            val message = choice.message

            // 2. Check finish reason
            when (choice.finish_reason) {
                "stop" -> {
                    // Final answer - LLM has completed the task
                    val content = message.content
                    if (content.isNullOrBlank()) {
                        return Result.failure(Exception("Empty final response from LLM"))
                    }
                    return Result.success(content)
                }

                "tool_calls" -> {
                    // LLM wants to call tools
                    val toolCalls = message.tool_calls
                        ?: return Result.failure(
                            Exception("finish_reason=tool_calls but no tool_calls in message")
                        )

                    // Add assistant message with tool calls to history
                    workingMessages.add(
                        Message(
                            role = "assistant",
                            content = message.content,
                            tool_calls = toolCalls
                        )
                    )

                    // Execute all tool calls
                    val toolResults = toolExecutor.executeBatch(conversationId, toolCalls)

                    // Add tool results to history as observations
                    toolResults.forEach { result ->
                        when (result) {
                            is ToolExecutionResult.Success -> {
                                workingMessages.add(
                                    Message(
                                        role = "tool",
                                        content = result.output,
                                        tool_call_id = result.toolCall.id,
                                        name = result.toolCall.function.name
                                    )
                                )
                            }
                            is ToolExecutionResult.Failure -> {
                                workingMessages.add(
                                    Message(
                                        role = "tool",
                                        content = "Error: ${result.error}",
                                        tool_call_id = result.toolCall.id,
                                        name = result.toolCall.function.name
                                    )
                                )
                            }
                        }
                    }

                    // Continue loop with updated context
                    continue
                }

                else -> {
                    // Unknown finish reason - return content if available
                    val content = message.content
                    return if (!content.isNullOrBlank()) {
                        Result.success(content)
                    } else {
                        Result.failure(
                            Exception("Unknown finish_reason: ${choice.finish_reason}")
                        )
                    }
                }
            }
        }

        // Max iterations reached without completion
        return Result.failure(
            Exception("Max iterations ($maxIterations) reached without final answer")
        )
    }

    /**
     * Convert engine ToolDefinition to LLM Tool format.
     *
     * ToolDefinition (from plugin metadata) → Tool (for LLM API)
     *
     * @param toolDef The tool definition from plugin
     * @return Tool object for LLM request
     */
    private fun toolDefToLlmTool(toolDef: ToolDefinition): Tool {
        return Tool(
            type = "function",
            function = ToolFunction(
                name = toolDef.name,
                description = toolDef.description,
                parameters = toolDef.parameters
            )
        )
    }
}
