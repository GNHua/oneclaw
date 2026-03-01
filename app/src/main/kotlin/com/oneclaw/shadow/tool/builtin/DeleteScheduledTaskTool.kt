package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.feature.schedule.usecase.DeleteScheduledTaskUseCase
import com.oneclaw.shadow.tool.engine.Tool

/**
 * Built-in tool that permanently removes a scheduled task and cancels its alarm.
 */
class DeleteScheduledTaskTool(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val deleteScheduledTaskUseCase: DeleteScheduledTaskUseCase
) : Tool {

    override val definition = ToolDefinition(
        name = "delete_scheduled_task",
        description = "Permanently delete a scheduled task and cancel its alarm.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "task_id" to ToolParameter(
                    type = "string",
                    description = "ID of the task to delete"
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

        val task = scheduledTaskRepository.getTaskById(taskId)
            ?: return ToolResult.error(
                "not_found",
                "Task not found with ID '$taskId'."
            )

        val taskName = task.name
        deleteScheduledTaskUseCase(taskId)

        return ToolResult.success(
            "Task '$taskName' has been deleted. Its alarm has been cancelled."
        )
    }
}
