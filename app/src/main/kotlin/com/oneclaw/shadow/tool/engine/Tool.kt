package com.oneclaw.shadow.tool.engine

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolResult

/**
 * Interface that all tools must implement.
 *
 * execute() is called on Dispatchers.IO — do not switch dispatchers internally.
 */
interface Tool {
    val definition: ToolDefinition
    suspend fun execute(parameters: Map<String, Any?>): ToolResult
}
