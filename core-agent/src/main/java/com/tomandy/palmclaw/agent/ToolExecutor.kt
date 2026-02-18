package com.tomandy.palmclaw.agent

import android.util.Log
import com.tomandy.palmclaw.engine.ToolResult
import com.tomandy.palmclaw.llm.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * Executes tools from registered plugins.
 *
 * The ToolExecutor bridges the agent layer and the plugin engine:
 * 1. Receives ToolCall from LLM
 * 2. Looks up tool in ToolRegistry
 * 3. Executes plugin with timeout and error handling
 * 4. Saves result to database
 * 5. Returns ToolExecutionResult to ReActLoop
 *
 * Key features:
 * - 30-second timeout per tool execution
 * - Automatic persistence of tool results
 * - Comprehensive error handling
 * - Batch execution support
 *
 * Usage:
 * ```kotlin
 * val executor = ToolExecutor(toolRegistry, messageStore)
 *
 * // Execute single tool
 * val result = executor.execute(conversationId, toolCall)
 *
 * // Execute multiple tools
 * val results = executor.executeBatch(conversationId, toolCalls)
 * ```
 */
class ToolExecutor(
    private val toolRegistry: ToolRegistry,
    private val messageStore: MessageStore
) {
    companion object {
        private const val TOOL_EXECUTION_TIMEOUT_MS = 120_000L
        private const val MAX_STORED_RESULT_LENGTH = 16_384
    }

    /**
     * Execute a single tool.
     *
     * Steps:
     * 1. Look up tool in registry
     * 2. Parse arguments from JSON string
     * 3. Execute with timeout
     * 4. Save result to database
     * 5. Return ToolExecutionResult
     *
     * @param conversationId The conversation this tool execution belongs to
     * @param toolCall The tool call from the LLM
     * @return ToolExecutionResult (Success or Failure)
     */
    suspend fun execute(
        conversationId: String,
        toolCall: ToolCall
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        Log.d("ToolExecutor", "Executing tool: ${toolCall.function.name}")
        try {
            // 1. Find registered tool
            val registeredTool = toolRegistry.getTool(toolCall.function.name)
            if (registeredTool == null) {
                Log.e("ToolExecutor", "Tool '${toolCall.function.name}' not found in registry")
                return@withContext ToolExecutionResult.Failure(
                    toolCall = toolCall,
                    error = "Tool '${toolCall.function.name}' not found"
                )
            }
            Log.d("ToolExecutor", "Found registered tool: ${toolCall.function.name}")

            // 2. Parse arguments from JSON string to JsonObject
            val arguments = try {
                Json.parseToJsonElement(toolCall.function.arguments).jsonObject
            } catch (e: Exception) {
                return@withContext ToolExecutionResult.Failure(
                    toolCall = toolCall,
                    error = "Invalid JSON arguments: ${e.message}",
                    exception = e
                )
            }

            // 3. Execute with timeout (inject conversation context for built-in plugins)
            val enrichedArguments = buildJsonObject {
                arguments.forEach { (key, value) -> put(key, value) }
                put("_conversation_id", JsonPrimitive(conversationId))
            }
            Log.d("ToolExecutor", "Calling plugin.execute with arguments: $enrichedArguments")
            val timeout = registeredTool.definition.timeoutMs.takeIf { it > 0 }
                ?: TOOL_EXECUTION_TIMEOUT_MS
            val result = try {
                withTimeout(timeout) {
                    registeredTool.plugin.execute(toolCall.function.name, enrichedArguments)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e("ToolExecutor", "Tool execution timed out", e)
                return@withContext ToolExecutionResult.Failure(
                    toolCall = toolCall,
                    error = "Tool execution timed out (${timeout / 1000}s)",
                    exception = e
                )
            } catch (e: Exception) {
                Log.e("ToolExecutor", "Unexpected error during tool execution: ${e.message}", e)
                return@withContext ToolExecutionResult.Failure(
                    toolCall = toolCall,
                    error = "Unexpected error: ${e.message}",
                    exception = e
                )
            }

            Log.d("ToolExecutor", "Tool execution completed, result type: ${result::class.simpleName}")

            // 4. Save tool result to database
            val resultContent = when (result) {
                is ToolResult.Success -> result.output
                is ToolResult.Failure -> "Error: ${result.error}"
            }
            val storedContent = if (resultContent.length > MAX_STORED_RESULT_LENGTH) {
                resultContent.take(MAX_STORED_RESULT_LENGTH) +
                    "\n\n[Truncated: ${resultContent.length} chars total]"
            } else {
                resultContent
            }
            Log.d("ToolExecutor", "Saving tool result to database")

            val resultImagePaths = when (result) {
                is ToolResult.Success -> result.imagePaths.takeIf { it.isNotEmpty() }?.let { paths ->
                    buildJsonArray {
                        paths.forEach { add(JsonPrimitive(it)) }
                    }.toString()
                }
                else -> null
            }
            val resultMessage = MessageRecord(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "tool",
                content = storedContent,
                toolCallId = toolCall.id,
                toolName = toolCall.function.name,
                timestamp = System.currentTimeMillis(),
                imagePaths = resultImagePaths
            )
            messageStore.insert(resultMessage)

            // 5. Return execution result
            when (result) {
                is ToolResult.Success -> ToolExecutionResult.Success(
                    toolCall = toolCall,
                    output = result.output,
                    metadata = result.metadata
                )
                is ToolResult.Failure -> ToolExecutionResult.Failure(
                    toolCall = toolCall,
                    error = result.error,
                    exception = result.exception
                )
            }

        } catch (e: Exception) {
            // Catch-all for any unexpected errors
            ToolExecutionResult.Failure(
                toolCall = toolCall,
                error = "Unexpected error: ${e.message}",
                exception = e
            )
        }
    }

    /**
     * Execute multiple tools sequentially.
     *
     * Note: Tools are executed sequentially, not in parallel.
     * This ensures database consistency and prevents race conditions.
     *
     * @param conversationId The conversation this tool execution belongs to
     * @param toolCalls List of tool calls from the LLM
     * @return List of ToolExecutionResult in the same order as toolCalls
     */
    suspend fun executeBatch(
        conversationId: String,
        toolCalls: List<ToolCall>
    ): List<ToolExecutionResult> {
        Log.d("ToolExecutor", "executeBatch called with ${toolCalls.size} tool calls")
        return toolCalls.map { execute(conversationId, it) }
    }
}
