package com.tomandy.palmclaw.agent

import android.util.Log
import com.tomandy.palmclaw.engine.ToolDefinition
import com.tomandy.palmclaw.llm.LlmClient
import com.tomandy.palmclaw.llm.Message
import com.tomandy.palmclaw.llm.NetworkConfig
import com.tomandy.palmclaw.llm.Tool
import com.tomandy.palmclaw.llm.ToolFunction
import com.tomandy.palmclaw.llm.Usage
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

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
 * Key features:
 * - Multi-turn reasoning with tool execution
 * - Automatic loop termination on final answer
 * - Max iteration safety limit (default: 5)
 * - Full conversation context preservation
 * - Persists intermediate tool-call messages to DB for UI display
 */
class ReActLoop(
    private val llmClient: LlmClient,
    private val toolExecutor: ToolExecutor,
    private val messageStore: MessageStore
) {
    private val pendingUserMessages = ConcurrentLinkedQueue<String>()

    /** Usage from the most recent LLM call, updated after each step(). */
    var lastUsage: Usage? = null
        private set

    fun injectMessage(text: String) {
        pendingUserMessages.add(text)
    }

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
     * @param toolsProvider Provider function that returns current tool definitions.
     *   Called each iteration to support dynamic tool activation mid-loop.
     * @param conversationId The conversation ID for persisting tool results
     * @param model The LLM model to use
     * @param maxIterations Maximum ReAct iterations (default: 5)
     * @return Result containing the final response or error
     */
    suspend fun step(
        messages: List<Message>,
        toolsProvider: () -> List<ToolDefinition>,
        conversationId: String,
        model: String = "gpt-4o-mini",
        maxIterations: Int = 200,
        temperature: Float = 0.7f
    ): Result<String> {
        Log.d("ReActLoop", "step called with ${messages.size} messages, model: $model")
        val workingMessages = messages.toMutableList()
        var iterations = 0

        while (iterations < maxIterations) {
            iterations++
            Log.d("ReActLoop", "Starting iteration $iterations/$maxIterations")

            // Refresh tools each iteration to support dynamic tool activation
            val currentTools = toolsProvider()
            val llmTools = currentTools.map { toolDefToLlmTool(it) }
            Log.d("ReActLoop", "Iteration $iterations: ${llmTools.size} tools available")

            // Drain any user messages injected mid-loop
            var injected: String? = pendingUserMessages.poll()
            while (injected != null) {
                Log.d("ReActLoop", "Injecting user message into loop: $injected")
                workingMessages.add(Message(role = "user", content = injected))
                injected = pendingUserMessages.poll()
            }

            // 1. Call LLM with tools
            Log.d("ReActLoop", "Calling llmClient.complete with ${workingMessages.size} messages")
            val result = llmClient.complete(
                messages = workingMessages,
                model = model,
                temperature = temperature,
                tools = if (llmTools.isNotEmpty()) llmTools else null
            )
            Log.d("ReActLoop", "llmClient.complete returned")

            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull()!!)
            }

            val response = result.getOrNull()!!
            lastUsage = response.usage
            val choice = response.choices.firstOrNull()
                ?: return Result.failure(Exception("No choices in LLM response"))

            val message = choice.message

            // 2. Check finish reason
            Log.d("ReActLoop", "Finish reason: ${choice.finish_reason}")
            when (choice.finish_reason) {
                "stop" -> {
                    // Final answer - LLM has completed the task
                    Log.d("ReActLoop", "LLM returned final answer")
                    val content = message.content
                    if (content.isNullOrBlank()) {
                        return Result.failure(Exception("Empty final response from LLM"))
                    }

                    // If user messages were injected while we were working,
                    // add the assistant answer to history and continue the loop
                    if (pendingUserMessages.isNotEmpty()) {
                        Log.d("ReActLoop", "Pending user messages found, continuing loop")
                        workingMessages.add(Message(role = "assistant", content = content))
                        // Persist intermediate final answer to DB
                        messageStore.insert(
                            MessageRecord(
                                id = UUID.randomUUID().toString(),
                                conversationId = conversationId,
                                role = "assistant",
                                content = content,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        continue
                    }

                    return Result.success(content)
                }

                "tool_calls" -> {
                    // LLM wants to call tools
                    Log.d("ReActLoop", "LLM requested tool calls")
                    val toolCalls = message.tool_calls
                        ?: return Result.failure(
                            Exception("finish_reason=tool_calls but no tool_calls in message")
                        )
                    Log.d("ReActLoop", "Executing ${toolCalls.size} tool calls")

                    // Save intermediate assistant message with tool calls to DB
                    val toolCallsJson = NetworkConfig.json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(com.tomandy.palmclaw.llm.ToolCall.serializer()),
                        toolCalls
                    )
                    messageStore.insert(
                        MessageRecord(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = "assistant",
                            content = message.content ?: "",
                            toolCalls = toolCallsJson,
                            timestamp = System.currentTimeMillis()
                        )
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
                    Log.d("ReActLoop", "Tool execution completed, got ${toolResults.size} results")

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
