package com.oneclaw.shadow.tool.js

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool

/**
 * A Tool implementation that executes a JavaScript file via QuickJS.
 * From the ToolRegistry's perspective, this is indistinguishable from a built-in Kotlin tool.
 */
class JsTool(
    override val definition: ToolDefinition,
    val jsFilePath: String,
    private val jsExecutionEngine: JsExecutionEngine,
    private val envVarStore: EnvironmentVariableStore
) : Tool {

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return jsExecutionEngine.execute(
            jsFilePath = jsFilePath,
            toolName = definition.name,
            params = parameters,
            env = envVarStore.getAll(),
            timeoutSeconds = definition.timeoutSeconds
        )
    }
}
