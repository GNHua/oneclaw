package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.AgentConstants
import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.ScheduledTask
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.schedule.usecase.CreateScheduledTaskUseCase
import com.oneclaw.shadow.tool.engine.Tool
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Built-in tool that creates a scheduled task from conversation.
 * Enables AI agents to create one-time, daily, or weekly scheduled tasks on behalf of the user.
 */
class CreateScheduledTaskTool(
    private val createScheduledTaskUseCase: CreateScheduledTaskUseCase
) : Tool {

    override val definition = ToolDefinition(
        name = "schedule_task",
        description = "Create a scheduled task that automatically runs an AI agent at a specified time. " +
            "Supports one-time, daily, and weekly schedules.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "name" to ToolParameter(
                    type = "string",
                    description = "Task name"
                ),
                "prompt" to ToolParameter(
                    type = "string",
                    description = "The prompt message to send to the agent when the task fires"
                ),
                "schedule_type" to ToolParameter(
                    type = "string",
                    description = "Schedule type",
                    enum = listOf("one_time", "daily", "weekly")
                ),
                "hour" to ToolParameter(
                    type = "integer",
                    description = "Hour (0-23)"
                ),
                "minute" to ToolParameter(
                    type = "integer",
                    description = "Minute (0-59)"
                ),
                "day_of_week" to ToolParameter(
                    type = "string",
                    description = "Day name for weekly tasks (e.g., \"monday\", \"tuesday\", etc.)"
                ),
                "date" to ToolParameter(
                    type = "string",
                    description = "Date for one-time tasks in YYYY-MM-DD format"
                )
            ),
            required = listOf("name", "prompt", "schedule_type", "hour", "minute")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val name = parameters["name"] as? String
            ?: return ToolResult.error(
                "validation_error",
                "Parameter 'name' is required and must be a string"
            )

        val prompt = parameters["prompt"] as? String
            ?: return ToolResult.error(
                "validation_error",
                "Parameter 'prompt' is required and must be a string"
            )

        val scheduleTypeStr = parameters["schedule_type"] as? String
            ?: return ToolResult.error(
                "validation_error",
                "Parameter 'schedule_type' is required and must be a string"
            )

        val scheduleType = when (scheduleTypeStr) {
            "one_time" -> ScheduleType.ONE_TIME
            "daily" -> ScheduleType.DAILY
            "weekly" -> ScheduleType.WEEKLY
            else -> return ToolResult.error(
                "validation_error",
                "Invalid schedule_type '$scheduleTypeStr'. Must be one of: one_time, daily, weekly"
            )
        }

        val hour = parseIntParam(parameters["hour"])
            ?: return ToolResult.error(
                "validation_error",
                "Parameter 'hour' is required and must be an integer (0-23)"
            )

        val minute = parseIntParam(parameters["minute"])
            ?: return ToolResult.error(
                "validation_error",
                "Parameter 'minute' is required and must be an integer (0-59)"
            )

        val dayOfWeek: Int? = if (scheduleType == ScheduleType.WEEKLY) {
            val dayStr = parameters["day_of_week"] as? String
                ?: return ToolResult.error(
                    "validation_error",
                    "Parameter 'day_of_week' is required for weekly tasks (e.g., \"monday\")"
                )
            try {
                DayOfWeek.valueOf(dayStr.uppercase()).value
            } catch (e: IllegalArgumentException) {
                return ToolResult.error(
                    "validation_error",
                    "Invalid day_of_week '$dayStr'. Must be a day name such as monday, tuesday, wednesday, thursday, friday, saturday, sunday"
                )
            }
        } else {
            null
        }

        val dateMillis: Long? = if (scheduleType == ScheduleType.ONE_TIME) {
            val dateStr = parameters["date"] as? String
                ?: return ToolResult.error(
                    "validation_error",
                    "Parameter 'date' is required for one-time tasks in YYYY-MM-DD format"
                )
            try {
                LocalDate.parse(dateStr)
                    .atTime(hour, minute)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (e: Exception) {
                return ToolResult.error(
                    "validation_error",
                    "Invalid date format '$dateStr'. Expected YYYY-MM-DD (e.g., 2026-03-15)"
                )
            }
        } else {
            null
        }

        val task = ScheduledTask(
            id = "",
            name = name,
            agentId = AgentConstants.GENERAL_ASSISTANT_ID,
            prompt = prompt,
            scheduleType = scheduleType,
            hour = hour,
            minute = minute,
            dayOfWeek = dayOfWeek,
            dateMillis = dateMillis,
            isEnabled = true,
            lastExecutionAt = null,
            lastExecutionStatus = null,
            lastExecutionSessionId = null,
            nextTriggerAt = null,
            createdAt = 0,
            updatedAt = 0
        )

        return when (val result = createScheduledTaskUseCase(task)) {
            is AppResult.Success -> {
                val created = result.data
                val description = buildScheduleDescription(created)
                val nextTrigger = created.nextTriggerAt?.toString() ?: "unknown"
                ToolResult.success(
                    "Scheduled task '${name}' created successfully. Schedule: ${description}. Next trigger: ${nextTrigger}"
                )
            }
            is AppResult.Error -> {
                ToolResult.error("creation_failed", result.message)
            }
        }
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

    private fun buildScheduleDescription(task: ScheduledTask): String {
        val time = String.format("%02d:%02d", task.hour, task.minute)
        return when (task.scheduleType) {
            ScheduleType.DAILY -> "Daily at $time"
            ScheduleType.WEEKLY -> {
                val dayName = task.dayOfWeek?.let {
                    DayOfWeek.of(it).name.lowercase().replaceFirstChar { c -> c.uppercase() }
                } ?: "unknown"
                "Every $dayName at $time"
            }
            ScheduleType.ONE_TIME -> {
                val dateStr = task.dateMillis?.let {
                    java.time.Instant.ofEpochMilli(it)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .toString()
                } ?: "unknown date"
                "One-time on $dateStr at $time"
            }
        }
    }
}
