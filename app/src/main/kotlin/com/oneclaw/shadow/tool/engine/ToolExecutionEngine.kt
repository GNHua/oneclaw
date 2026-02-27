package com.oneclaw.shadow.tool.engine

import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Orchestrates tool execution: lookup, availability check, parameter validation,
 * permission check, timeout, and error handling.
 */
class ToolExecutionEngine(
    private val registry: ToolRegistry,
    private val permissionChecker: PermissionChecker
) {

    /**
     * Execute a single tool call.
     *
     * @param toolName Name of the tool to execute
     * @param parameters Parameters to pass to the tool
     * @param availableToolNames Tool names available to the current agent
     * @return ToolResult (never throws — all errors are wrapped in ToolResult.error)
     */
    suspend fun executeTool(
        toolName: String,
        parameters: Map<String, Any?>,
        availableToolNames: List<String>
    ): ToolResult {
        // 1. Look up tool
        val tool = registry.getTool(toolName)
            ?: return ToolResult.error("tool_not_found", "Tool '$toolName' not found")

        // 2. Check availability (is this tool in the agent's tool set?)
        if (toolName !in availableToolNames) {
            return ToolResult.error(
                "tool_not_available",
                "Tool '$toolName' is not available for this agent"
            )
        }

        // 3. Validate parameters
        val validationError = validateParameters(parameters, tool.definition.parametersSchema)
        if (validationError != null) {
            return ToolResult.error("validation_error", validationError)
        }

        // 4. Check Android permissions
        val missing = permissionChecker.getMissingPermissions(tool.definition.requiredPermissions)
        if (missing.isNotEmpty()) {
            // Special handling for MANAGE_EXTERNAL_STORAGE
            if (android.Manifest.permission.MANAGE_EXTERNAL_STORAGE in missing) {
                permissionChecker.requestManageExternalStorage()
                return ToolResult.error(
                    "permission_denied",
                    "File access requires 'All files access' permission. " +
                        "Please enable it in Settings > Apps > OneClawShadow > Permissions."
                )
            }
            val granted = permissionChecker.requestPermissions(missing)
            if (!granted) {
                return ToolResult.error(
                    "permission_denied",
                    "Required permissions were denied: ${missing.joinToString(", ")}"
                )
            }
        }

        // 5. Execute with timeout on IO dispatcher
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(tool.definition.timeoutSeconds * 1000L) {
                    tool.execute(parameters)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ToolResult.error(
                "timeout",
                "Tool execution timed out after ${tool.definition.timeoutSeconds}s"
            )
        } catch (e: CancellationException) {
            throw e  // Never swallow coroutine cancellation
        } catch (e: Exception) {
            ToolResult.error(
                "execution_error",
                "Tool execution failed: ${e.message ?: "Unknown error"}"
            )
        }
    }

    /**
     * Execute a single tool call and measure wall-clock duration.
     */
    suspend fun executeToolTimed(
        toolName: String,
        parameters: Map<String, Any?>,
        availableToolNames: List<String>
    ): Pair<ToolResult, Long> {
        val start = System.currentTimeMillis()
        val result = executeTool(toolName, parameters, availableToolNames)
        return result to (System.currentTimeMillis() - start)
    }

    /**
     * Execute multiple tool calls in parallel.
     * Returns results in the same order as the input list.
     */
    suspend fun executeToolsParallel(
        toolCalls: List<ToolCallRequest>,
        availableToolNames: List<String>
    ): List<ToolCallResponse> = coroutineScope {
        toolCalls.map { call ->
            async {
                val start = System.currentTimeMillis()
                val result = executeTool(call.toolName, call.parameters, availableToolNames)
                val duration = System.currentTimeMillis() - start
                ToolCallResponse(
                    toolCallId = call.toolCallId,
                    toolName = call.toolName,
                    result = result,
                    durationMs = duration
                )
            }
        }.map { it.await() }
    }

    // --- Parameter validation ---

    private fun validateParameters(
        parameters: Map<String, Any?>,
        schema: ToolParametersSchema
    ): String? {
        // Check required parameters are present and non-null
        for (required in schema.required) {
            if (!parameters.containsKey(required) || parameters[required] == null) {
                return "Missing required parameter: '$required'"
            }
        }

        // Check parameter types
        for ((name, value) in parameters) {
            if (value == null) continue
            val paramDef = schema.properties[name] ?: continue  // Unknown param — ignore

            val typeError = validateType(name, value, paramDef.type)
            if (typeError != null) return typeError

            // Check enum constraint
            if (paramDef.enum != null && value is String && value !in paramDef.enum) {
                return "Parameter '$name' must be one of: ${paramDef.enum.joinToString(", ")}"
            }
        }

        return null  // Valid
    }

    private fun validateType(name: String, value: Any, expectedType: String): String? {
        val valid = when (expectedType) {
            "string" -> value is String
            "integer" -> value is Int || value is Long
            "number" -> value is Number
            "boolean" -> value is Boolean
            "object" -> value is Map<*, *>
            "array" -> value is List<*>
            else -> true  // Unknown type, allow
        }
        return if (!valid) {
            "Parameter '$name' expected type '$expectedType' but got ${value::class.simpleName}"
        } else null
    }
}
