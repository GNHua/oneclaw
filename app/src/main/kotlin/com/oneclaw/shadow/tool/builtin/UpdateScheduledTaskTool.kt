package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.schedule.usecase.UpdateScheduledTaskUseCase
import com.oneclaw.shadow.feature.schedule.util.ScheduleDescriptionFormatter
import com.oneclaw.shadow.tool.engine.Tool
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Built-in tool that performs a partial update on an existing scheduled task.
 */
class UpdateScheduledTaskTool(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val updateScheduledTaskUseCase: UpdateScheduledTaskUseCase
) : Tool {

    override val definition = ToolDefinition(
        name = "update_scheduled_task",
        description = "Update one or more fields of an existing scheduled task. Only provided fields are changed; omitted fields retain their current values.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "task_id" to ToolParameter(
                    type = "string",
                    description = "ID of the task to update"
                ),
                "name" to ToolParameter(
                    type = "string",
                    description = "New task name"
                ),
                "prompt" to ToolParameter(
                    type = "string",
                    description = "New prompt message"
                ),
                "schedule_type" to ToolParameter(
                    type = "string",
                    description = "New schedule type",
                    enum = listOf("one_time", "daily", "weekly")
                ),
                "hour" to ToolParameter(
                    type = "integer",
                    description = "New hour (0-23)"
                ),
                "minute" to ToolParameter(
                    type = "integer",
                    description = "New minute (0-59)"
                ),
                "day_of_week" to ToolParameter(
                    type = "string",
                    description = "Day name for weekly tasks (e.g., \"monday\")"
                ),
                "date" to ToolParameter(
                    type = "string",
                    description = "Date for one-time tasks in YYYY-MM-DD format"
                ),
                "enabled" to ToolParameter(
                    type = "boolean",
                    description = "Enable or disable the task"
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

        val existingTask = scheduledTaskRepository.getTaskById(taskId)
            ?: return ToolResult.error(
                "not_found",
                "Task not found with ID '$taskId'."
            )

        // Parse optional schedule_type
        val parsedScheduleType: ScheduleType? = (parameters["schedule_type"] as? String)?.let { typeStr ->
            when (typeStr) {
                "one_time" -> ScheduleType.ONE_TIME
                "daily" -> ScheduleType.DAILY
                "weekly" -> ScheduleType.WEEKLY
                else -> return ToolResult.error(
                    "validation_error",
                    "Invalid schedule_type '$typeStr'. Must be one of: one_time, daily, weekly"
                )
            }
        }

        val effectiveScheduleType = parsedScheduleType ?: existingTask.scheduleType

        // Parse optional hour
        val parsedHour: Int? = parameters["hour"]?.let { parseIntParam(it) }
        // Parse optional minute
        val parsedMinute: Int? = parameters["minute"]?.let { parseIntParam(it) }

        // Parse optional day_of_week (required if new schedule_type is weekly)
        val parsedDayOfWeek: Int? = (parameters["day_of_week"] as? String)?.let { dayStr ->
            try {
                DayOfWeek.valueOf(dayStr.uppercase()).value
            } catch (e: IllegalArgumentException) {
                return ToolResult.error(
                    "validation_error",
                    "Invalid day_of_week '$dayStr'. Must be a day name such as monday, tuesday, wednesday, thursday, friday, saturday, sunday"
                )
            }
        }

        // If switching to weekly and no day_of_week provided nor existing, error
        if (effectiveScheduleType == ScheduleType.WEEKLY &&
            parsedDayOfWeek == null &&
            existingTask.dayOfWeek == null &&
            parsedScheduleType != null
        ) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'day_of_week' is required when schedule_type is 'weekly'"
            )
        }

        // Parse optional date (required if new schedule_type is one_time and changing from non-one-time)
        val effectiveHour = parsedHour ?: existingTask.hour
        val effectiveMinute = parsedMinute ?: existingTask.minute
        val parsedDateMillis: Long? = (parameters["date"] as? String)?.let { dateStr ->
            try {
                LocalDate.parse(dateStr)
                    .atTime(effectiveHour, effectiveMinute)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (e: Exception) {
                return ToolResult.error(
                    "validation_error",
                    "Invalid date format '$dateStr'. Expected YYYY-MM-DD (e.g., 2026-03-15)"
                )
            }
        }

        // Parse optional enabled
        val parsedEnabled: Boolean? = parameters["enabled"]?.let {
            when (it) {
                is Boolean -> it
                is String -> it.toBooleanStrictOrNull()
                else -> null
            }
        }

        // Build changed-fields summary
        val changedFields = mutableListOf<String>()
        (parameters["name"] as? String)?.let { if (it != existingTask.name) changedFields.add("name") }
        (parameters["prompt"] as? String)?.let { if (it != existingTask.prompt) changedFields.add("prompt") }
        parsedScheduleType?.let { if (it != existingTask.scheduleType) changedFields.add("schedule_type") }
        parsedHour?.let { if (it != existingTask.hour) changedFields.add("hour (${existingTask.hour} -> $it)") }
        parsedMinute?.let { if (it != existingTask.minute) changedFields.add("minute (${existingTask.minute} -> $it)") }
        parsedDayOfWeek?.let { if (it != existingTask.dayOfWeek) changedFields.add("day_of_week") }
        parsedDateMillis?.let { if (it != existingTask.dateMillis) changedFields.add("date") }
        parsedEnabled?.let { if (it != existingTask.isEnabled) changedFields.add("enabled (${existingTask.isEnabled} -> $it)") }

        val updatedTask = existingTask.copy(
            name = (parameters["name"] as? String) ?: existingTask.name,
            prompt = (parameters["prompt"] as? String) ?: existingTask.prompt,
            scheduleType = parsedScheduleType ?: existingTask.scheduleType,
            hour = parsedHour ?: existingTask.hour,
            minute = parsedMinute ?: existingTask.minute,
            dayOfWeek = parsedDayOfWeek ?: existingTask.dayOfWeek,
            dateMillis = parsedDateMillis ?: existingTask.dateMillis,
            isEnabled = parsedEnabled ?: existingTask.isEnabled
        )

        return when (val result = updateScheduledTaskUseCase(updatedTask)) {
            is AppResult.Success -> {
                val schedule = ScheduleDescriptionFormatter.format(updatedTask)
                val changedSummary = if (changedFields.isEmpty()) "no fields" else changedFields.joinToString(", ")
                ToolResult.success(
                    "Task '${updatedTask.name}' updated successfully. Changed fields: $changedSummary. Schedule: $schedule."
                )
            }
            is AppResult.Error -> {
                ToolResult.error("update_failed", result.message)
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
}
