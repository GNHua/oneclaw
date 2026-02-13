package com.tomandy.palmclaw.scheduler.plugin

import com.tomandy.palmclaw.engine.Plugin
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.ToolResult
import com.tomandy.palmclaw.scheduler.CronjobManager
import com.tomandy.palmclaw.scheduler.data.CronjobEntity
import com.tomandy.palmclaw.scheduler.data.ScheduleType
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Built-in plugin for scheduling agent tasks
 *
 * This plugin provides tools for creating, managing, and canceling scheduled tasks.
 * Unlike KTS plugins, this is a built-in plugin that's always available.
 */
class SchedulerPlugin : Plugin {

    private lateinit var context: PluginContext
    private lateinit var cronjobManager: CronjobManager

    override suspend fun onLoad(context: PluginContext) {
        this.context = context
        this.cronjobManager = CronjobManager(context.getApplicationContext())
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "schedule_task" -> scheduleTask(arguments)
            "list_scheduled_tasks" -> listScheduledTasks(arguments)
            "cancel_scheduled_task" -> cancelScheduledTask(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {
        // No cleanup needed
    }

    /**
     * Schedule a new task
     */
    private suspend fun scheduleTask(arguments: JsonObject): ToolResult {
        try {
            // Extract required fields
            val instruction = arguments["instruction"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: instruction")

            val scheduleTypeStr = arguments["schedule_type"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: schedule_type")

            val scheduleType = when (scheduleTypeStr) {
                "one_time" -> ScheduleType.ONE_TIME
                "recurring" -> ScheduleType.RECURRING
                else -> return ToolResult.Failure("Invalid schedule_type. Must be 'one_time' or 'recurring'")
            }

            // Build the cronjob entity
            val cronjob = when (scheduleType) {
                ScheduleType.ONE_TIME -> {
                    val executeAtStr = arguments["execute_at"]?.jsonPrimitive?.content
                        ?: return ToolResult.Failure("Missing required field 'execute_at' for one_time tasks")

                    val executeAt = try {
                        LocalDateTime.parse(executeAtStr)
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                    } catch (e: DateTimeParseException) {
                        return ToolResult.Failure("Invalid execute_at format. Expected ISO 8601 local datetime (e.g., '2026-02-12T18:00:00')")
                    }

                    if (executeAt <= System.currentTimeMillis()) {
                        return ToolResult.Failure("execute_at must be in the future")
                    }

                    CronjobEntity(
                        instruction = instruction,
                        scheduleType = ScheduleType.ONE_TIME,
                        executeAt = executeAt
                    )
                }

                ScheduleType.RECURRING -> {
                    val intervalMinutes = arguments["interval_minutes"]?.jsonPrimitive?.intOrNull
                    val cronExpression = arguments["cron_expression"]?.jsonPrimitive?.content

                    if (intervalMinutes == null && cronExpression == null) {
                        return ToolResult.Failure("Must provide either 'interval_minutes' or 'cron_expression' for recurring tasks")
                    }

                    if (intervalMinutes != null && intervalMinutes < 15) {
                        return ToolResult.Failure("Minimum interval is 15 minutes for battery optimization")
                    }

                    CronjobEntity(
                        instruction = instruction,
                        scheduleType = ScheduleType.RECURRING,
                        intervalMinutes = intervalMinutes,
                        cronExpression = cronExpression
                    )
                }

                else -> return ToolResult.Failure("Unsupported schedule type")
            }

            // Extract optional fields
            val maxExecutions = arguments["max_executions"]?.jsonPrimitive?.intOrNull
            val requireNetwork = arguments["require_network"]?.jsonPrimitive?.booleanOrNull ?: false
            val requireCharging = arguments["require_charging"]?.jsonPrimitive?.booleanOrNull ?: false
            val conversationId = arguments["_conversation_id"]?.jsonPrimitive?.content

            // Build constraints JSON
            val constraints = buildJsonObject {
                put("requiresNetwork", requireNetwork)
                put("requiresCharging", requireCharging)
            }.toString()

            // Update cronjob with optional fields
            val finalCronjob = cronjob.copy(
                maxExecutions = maxExecutions,
                constraints = constraints,
                conversationId = conversationId
            )

            // Schedule the cronjob
            val jobId = cronjobManager.schedule(finalCronjob)

            // Build success message
            val scheduleDescription = when (scheduleType) {
                ScheduleType.ONE_TIME -> {
                    val localTime = LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(finalCronjob.executeAt!!),
                        ZoneId.systemDefault()
                    )
                    "once at $localTime"
                }
                ScheduleType.RECURRING -> {
                    when {
                        finalCronjob.cronExpression != null ->
                            "on schedule: ${finalCronjob.cronExpression}"
                        finalCronjob.intervalMinutes != null ->
                            "every ${finalCronjob.intervalMinutes} minutes"
                        else -> "on a recurring schedule"
                    }
                }
                else -> "as configured"
            }

            return ToolResult.Success(
                output = """Task scheduled successfully!
                    |
                    |ID: $jobId
                    |Instruction: "$instruction"
                    |Schedule: $scheduleDescription
                    |
                    |The Agent will autonomously execute this task at the scheduled time.
                """.trimMargin()
            )

        } catch (e: Exception) {
            return ToolResult.Failure(
                error = "Failed to schedule task: ${e.message}",
                exception = e
            )
        }
    }

    /**
     * List all scheduled tasks
     */
    private suspend fun listScheduledTasks(arguments: JsonObject): ToolResult {
        return try {
            val includeDisabled = arguments["include_disabled"]?.jsonPrimitive?.booleanOrNull ?: false

            // Note: Since Flow doesn't work well with synchronous tool execution,
            // we'll need to collect once
            // TODO: Consider making this return a snapshot

            return ToolResult.Success(
                output = "Listing scheduled tasks (feature in progress - need to integrate with Flow collection)"
            )

        } catch (e: Exception) {
            ToolResult.Failure(
                error = "Failed to list tasks: ${e.message}",
                exception = e
            )
        }
    }

    /**
     * Cancel a scheduled task
     */
    private suspend fun cancelScheduledTask(arguments: JsonObject): ToolResult {
        return try {
            val taskId = arguments["task_id"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: task_id")

            cronjobManager.cancel(taskId)

            ToolResult.Success(
                output = "Task '$taskId' has been cancelled and will no longer execute."
            )

        } catch (e: Exception) {
            ToolResult.Failure(
                error = "Failed to cancel task: ${e.message}",
                exception = e
            )
        }
    }
}
