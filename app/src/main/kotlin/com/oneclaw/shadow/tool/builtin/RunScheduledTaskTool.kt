package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.schedule.usecase.RunScheduledTaskUseCase
import com.oneclaw.shadow.tool.engine.Tool

/**
 * Built-in tool that enqueues a scheduled task for immediate asynchronous execution via WorkManager.
 */
class RunScheduledTaskTool(
    private val runScheduledTaskUseCase: RunScheduledTaskUseCase
) : Tool {

    override val definition = ToolDefinition(
        name = "run_scheduled_task",
        description = "Trigger a scheduled task to run immediately in the background, regardless of its scheduled time or enabled status.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "task_id" to ToolParameter(
                    type = "string",
                    description = "ID of the task to run"
                )
            ),
            required = listOf("task_id")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val taskId = parameters["task_id"] as? String
            ?: return ToolResult.error(
                "validation_error",
                "Parameter 'task_id' is required and must be a string"
            )

        return when (val result = runScheduledTaskUseCase(taskId)) {
            is AppResult.Success -> {
                val taskName = result.data
                ToolResult.success(
                    "Task '$taskName' has been queued for execution. You will receive a notification when it completes."
                )
            }
            is AppResult.Error -> {
                ToolResult.error("run_failed", result.message)
            }
        }
    }
}
