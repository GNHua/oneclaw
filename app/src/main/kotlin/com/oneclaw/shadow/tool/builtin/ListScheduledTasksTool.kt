package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.ScheduledTask
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.feature.schedule.util.ScheduleDescriptionFormatter
import com.oneclaw.shadow.tool.engine.Tool
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Built-in tool that lists all scheduled tasks with their current state.
 */
class ListScheduledTasksTool(
    private val scheduledTaskRepository: ScheduledTaskRepository
) : Tool {

    private val dateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    override val definition = ToolDefinition(
        name = "list_scheduled_tasks",
        description = "List all scheduled tasks with their current status, schedule, and next trigger time.",
        parametersSchema = ToolParametersSchema(
            properties = emptyMap(),
            required = emptyList()
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val tasks = scheduledTaskRepository.getAllTasks().first()

        if (tasks.isEmpty()) {
            return ToolResult.success("No scheduled tasks configured.")
        }

        val sb = StringBuilder()
        sb.appendLine("Found ${tasks.size} scheduled task${if (tasks.size == 1) "" else "s"}:")

        tasks.forEachIndexed { index, task ->
            sb.appendLine()
            sb.appendLine("${index + 1}. [id: ${task.id}] ${task.name}")
            sb.appendLine("   Schedule: ${ScheduleDescriptionFormatter.format(task)}")
            sb.appendLine("   Enabled: ${task.isEnabled}")
            sb.appendLine("   Last execution: ${formatLastExecution(task)}")
            sb.append("   Next trigger: ${formatNextTrigger(task.nextTriggerAt)}")
        }

        return ToolResult.success(sb.toString())
    }

    private fun formatLastExecution(task: ScheduledTask): String {
        val at = task.lastExecutionAt ?: return "Never"
        val status = task.lastExecutionStatus
        val timeStr = dateTimeFormatter.format(Instant.ofEpochMilli(at))
        val statusStr = when (status) {
            ExecutionStatus.SUCCESS -> "SUCCESS"
            ExecutionStatus.FAILED -> "FAILED"
            ExecutionStatus.RUNNING -> "RUNNING"
            null -> "UNKNOWN"
        }
        return "$timeStr - $statusStr"
    }

    private fun formatNextTrigger(nextTriggerAt: Long?): String {
        nextTriggerAt ?: return "None"
        return dateTimeFormatter.format(Instant.ofEpochMilli(nextTriggerAt))
    }
}
