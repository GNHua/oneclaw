package com.oneclaw.shadow.tool.js

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool

/**
 * A Tool implementation that executes a JavaScript file via QuickJS.
 * From the ToolRegistry's perspective, this is indistinguishable from a built-in Kotlin tool.
 *
 * Supports two source modes:
 * - File-based (jsFilePath set, jsSource null): user tools from file system
 * - Source-based (jsSource set, jsFilePath empty): built-in tools from assets
 */
class JsTool(
    override val definition: ToolDefinition,
    val jsFilePath: String = "",
    private val jsSource: String? = null,
    private val functionName: String? = null,
    private val jsExecutionEngine: JsExecutionEngine,
    private val envVarStore: EnvironmentVariableStore
) : Tool {

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return if (jsSource != null) {
            jsExecutionEngine.executeFromSource(
                jsSource = jsSource,
                toolName = definition.name,
                functionName = functionName,
                params = parameters,
                env = envVarStore.getAll(),
                timeoutSeconds = definition.timeoutSeconds
            )
        } else {
            jsExecutionEngine.execute(
                jsFilePath = jsFilePath,
                toolName = definition.name,
                functionName = functionName,
                params = parameters,
                env = envVarStore.getAll(),
                timeoutSeconds = definition.timeoutSeconds
            )
        }
    }
}
