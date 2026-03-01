package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import com.oneclaw.shadow.tool.js.EnvironmentVariableStore
import com.oneclaw.shadow.tool.js.JsExecutionEngine

/**
 * Kotlin built-in tool that executes arbitrary JavaScript code
 * in a sandboxed QuickJS environment via JsExecutionEngine.
 * Useful for computation, data processing, and algorithmic tasks.
 *
 * RFC-034
 */
class JsEvalTool(
    private val jsExecutionEngine: JsExecutionEngine,
    private val envVarStore: EnvironmentVariableStore
) : Tool {

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 30
        private const val MAX_TIMEOUT_SECONDS = 120
    }

    override val definition = ToolDefinition(
        name = "js_eval",
        description = "Execute JavaScript code in a sandboxed environment and return the result. " +
            "Useful for computation, data processing, math, string manipulation, and algorithmic tasks. " +
            "If the code defines a main() function, it will be called and its return value used as the result. " +
            "Otherwise, the value of the last expression is returned. " +
            "Objects and arrays are JSON-serialized in the result.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "code" to ToolParameter(
                    type = "string",
                    description = "The JavaScript source code to execute"
                ),
                "timeout_seconds" to ToolParameter(
                    type = "integer",
                    description = "Maximum execution time in seconds. Default: 30, Max: 120"
                )
            ),
            required = listOf("code")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = MAX_TIMEOUT_SECONDS + 5
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val code = parameters["code"]?.toString()
        if (code.isNullOrBlank()) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'code' is required and cannot be empty"
            )
        }

        val timeoutSeconds = parseIntParam(parameters["timeout_seconds"])
            ?.coerceIn(1, MAX_TIMEOUT_SECONDS)
            ?: DEFAULT_TIMEOUT_SECONDS

        val wrappedCode = buildExecuteWrapper(code)

        return jsExecutionEngine.executeFromSource(
            jsSource = wrappedCode,
            toolName = "js_eval",
            functionName = null,
            params = emptyMap(),
            env = envVarStore.getAll(),
            timeoutSeconds = timeoutSeconds
        )
    }

    /**
     * Build a wrapper that defines execute() for JsExecutionEngine compatibility.
     *
     * Strategy:
     * 1. Define execute() as the entry point (required by JsExecutionEngine).
     * 2. Inside execute(), eval() the user code to get the last expression value.
     * 3. If the user code defines a main() function, call it instead.
     * 4. This supports both "2 + 2" expressions and multi-function scripts.
     *
     * The code string is escaped for safe embedding as a JS template literal.
     */
    internal fun buildExecuteWrapper(code: String): String {
        val escaped = code
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\$", "\\$")
        return """
            async function execute(params) {
                const __result__ = eval(`$escaped`);
                if (typeof main === 'function') {
                    return await main();
                }
                return __result__;
            }
        """.trimIndent()
    }

    private fun parseIntParam(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}
