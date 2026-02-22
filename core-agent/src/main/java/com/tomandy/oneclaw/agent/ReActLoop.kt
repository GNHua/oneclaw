package com.tomandy.oneclaw.agent

import android.util.Log
import com.tomandy.oneclaw.engine.ToolDefinition
import com.tomandy.oneclaw.llm.ContextOverflowException
import com.tomandy.oneclaw.llm.LlmClient
import com.tomandy.oneclaw.llm.Message
import com.tomandy.oneclaw.llm.NetworkConfig
import com.tomandy.oneclaw.llm.Tool
import com.tomandy.oneclaw.llm.ToolFunction
import com.tomandy.oneclaw.llm.Usage
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
 * - Tool result truncation to prevent context overflow
 * - Mid-loop token estimation with automatic message trimming
 * - Recovery from context overflow API errors
 */
class ReActLoop(
    private val llmClient: LlmClient,
    private val toolExecutor: ToolExecutor,
    private val messageStore: MessageStore,
    private val contextWindow: Int = 200_000
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
     *    - "stop" -> Return final answer
     *    - "tool_calls" -> Execute tools and continue loop
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
        temperature: Float = 0.2f
    ): Result<String> {
        Log.d("ReActLoop", "step called with ${messages.size} messages, model: $model")
        val workingMessages = messages.toMutableList()
        var iterations = 0
        var lastKnownPromptTokens = 0
        var overflowRetries = 0

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

            // Layer 2: Mid-loop token check -- trim if approaching context limit
            val estimatedTokens = if (lastKnownPromptTokens > 0) {
                lastKnownPromptTokens
            } else {
                workingMessages.sumOf { (it.content?.length ?: 0) } / 4
            }
            val trimThreshold = (contextWindow * 0.85).toInt()
            if (estimatedTokens > trimThreshold && workingMessages.size > 6) {
                Log.d(
                    "ReActLoop",
                    "Token estimate $estimatedTokens exceeds 85% of $contextWindow, trimming"
                )
                trimWorkingMessages(workingMessages, contextWindow)
                lastKnownPromptTokens = 0
            }

            // 1. Call LLM with tools
            val totalChars = workingMessages.sumOf { (it.content?.length ?: 0) }
            Log.d(
                "ReActLoop",
                "Calling llmClient.complete with ${workingMessages.size} messages, " +
                    "~${totalChars} chars (~${totalChars / 4} tokens est)"
            )
            val result = llmClient.complete(
                messages = workingMessages,
                model = model,
                temperature = temperature,
                tools = if (llmTools.isNotEmpty()) llmTools else null
            )
            Log.d("ReActLoop", "llmClient.complete returned")

            // Layer 3: Handle context overflow with recovery
            if (result.isFailure) {
                val error = result.exceptionOrNull()!!
                if (error is ContextOverflowException &&
                    overflowRetries < MAX_OVERFLOW_RETRIES &&
                    iterations > 1
                ) {
                    overflowRetries++
                    Log.w(
                        "ReActLoop",
                        "Context overflow on iteration $iterations, " +
                            "retry $overflowRetries/$MAX_OVERFLOW_RETRIES"
                    )
                    trimWorkingMessages(workingMessages, (contextWindow * 0.5).toInt())
                    lastKnownPromptTokens = 0
                    iterations-- // don't count the failed attempt
                    continue
                }
                // Graceful degradation: return a friendly message instead of
                // a hard error so the user can follow up in the same conversation.
                Log.w(
                    "ReActLoop",
                    "LLM call failed on iteration $iterations: ${error.message}"
                )
                val msg = "Sorry, I ran into an issue while processing your request" +
                    " (${error.message}). You can try again or ask me to continue."
                return Result.success(msg)
            }

            val response = result.getOrNull()!!
            lastUsage = response.usage
            // Layer 4: Track actual token usage for next iteration's check.
            // Use prompt + completion tokens because the completion becomes
            // part of the next iteration's prompt (conversation history).
            response.usage?.let {
                lastKnownPromptTokens = it.prompt_tokens + it.completion_tokens
            }

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
                        kotlinx.serialization.builtins.ListSerializer(
                            com.tomandy.oneclaw.llm.ToolCall.serializer()
                        ),
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
                    toolResults.forEach { toolResult ->
                        when (toolResult) {
                            is ToolExecutionResult.Success -> {
                                // Layer 1: Cap tool result size for LLM context
                                val output = truncateToolOutput(toolResult.output)
                                workingMessages.add(
                                    Message(
                                        role = "tool",
                                        content = output,
                                        tool_call_id = toolResult.toolCall.id,
                                        name = toolResult.toolCall.function.name
                                    )
                                )
                            }
                            is ToolExecutionResult.Failure -> {
                                workingMessages.add(
                                    Message(
                                        role = "tool",
                                        content = "Error: ${toolResult.error}",
                                        tool_call_id = toolResult.toolCall.id,
                                        name = toolResult.toolCall.function.name
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

        // Max iterations reached. Return a friendly message -- tool call
        // history is preserved in the DB so the user can continue seamlessly.
        Log.w("ReActLoop", "Max iterations ($maxIterations) reached")
        return Result.success(
            "Reached the maximum number of steps ($maxIterations). " +
                "You can send a follow-up message to continue, " +
                "or increase max iterations in Agent Profiles."
        )
    }

    /**
     * Convert engine ToolDefinition to LLM Tool format.
     *
     * ToolDefinition (from plugin metadata) -> Tool (for LLM API)
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

    companion object {
        /** Max characters for a single tool result sent to the LLM. */
        internal const val MAX_LLM_TOOL_RESULT_CHARS = 32_768

        /** Max overflow recovery retries to prevent infinite loops. */
        private const val MAX_OVERFLOW_RETRIES = 2

        /** Number of recent messages to always preserve when trimming. */
        private const val PRESERVE_TAIL_COUNT = 6

        /**
         * Truncate a tool output string if it exceeds [MAX_LLM_TOOL_RESULT_CHARS].
         */
        internal fun truncateToolOutput(output: String): String {
            if (output.length <= MAX_LLM_TOOL_RESULT_CHARS) return output
            Log.d(
                "ReActLoop",
                "Truncating tool output: ${output.length} -> $MAX_LLM_TOOL_RESULT_CHARS chars"
            )
            return output.take(MAX_LLM_TOOL_RESULT_CHARS) +
                "\n\n[Output truncated: ${output.length} chars total, " +
                "showing first $MAX_LLM_TOOL_RESULT_CHARS]"
        }

        /**
         * Trim older tool-call/tool-result message pairs from the middle of
         * [workingMessages] to bring the estimated token count under [targetTokens].
         *
         * Preserves: system messages, the first user message, and the last
         * [PRESERVE_TAIL_COUNT] messages.
         */
        internal fun trimWorkingMessages(
            workingMessages: MutableList<Message>,
            targetTokens: Int
        ) {
            if (workingMessages.size <= PRESERVE_TAIL_COUNT + 2) return

            // Find the boundary: preserve head (system + first user) and tail
            val headEnd = workingMessages.indexOfFirst { it.role == "user" } + 1
            if (headEnd <= 0) return
            val tailStart = (workingMessages.size - PRESERVE_TAIL_COUNT).coerceAtLeast(headEnd)
            if (tailStart <= headEnd) return

            // Estimate current tokens
            val currentTokens = workingMessages.sumOf { (it.content?.length ?: 0) } / 4
            if (currentTokens <= targetTokens) return

            // Remove messages from the middle, oldest first, until under budget
            val removable = workingMessages.subList(headEnd, tailStart)
            val toRemove = mutableListOf<Int>()
            var freed = 0
            val needed = currentTokens - targetTokens

            for (i in removable.indices) {
                val msg = removable[i]
                freed += (msg.content?.length ?: 0) / 4
                toRemove.add(headEnd + i)
                if (freed >= needed) break
            }

            if (toRemove.isEmpty()) return

            // Remove in reverse order to preserve indices
            for (idx in toRemove.reversed()) {
                workingMessages.removeAt(idx)
            }

            // Insert a placeholder at the trim point
            workingMessages.add(
                headEnd,
                Message(
                    role = "user",
                    content = "[System: ${toRemove.size} earlier tool interactions were " +
                        "trimmed to fit context window]"
                )
            )

            Log.d(
                "ReActLoop",
                "Trimmed ${toRemove.size} messages, freed ~$freed tokens"
            )
        }
    }
}
