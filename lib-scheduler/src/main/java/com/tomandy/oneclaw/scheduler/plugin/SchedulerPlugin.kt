package com.tomandy.oneclaw.scheduler.plugin

import android.content.Intent
import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.ToolResult
import com.tomandy.oneclaw.scheduler.service.AgentExecutionService
import com.tomandy.oneclaw.scheduler.CronjobManager
import com.tomandy.oneclaw.scheduler.util.formatCronExpression
import com.tomandy.oneclaw.scheduler.data.CronjobEntity
import com.tomandy.oneclaw.scheduler.data.ScheduleType
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
            "run_scheduled_task" -> runScheduledTask(arguments)
            "cancel_scheduled_task" -> cancelScheduledTask(arguments)
            "update_scheduled_task" -> updateScheduledTask(arguments)
            "delete_scheduled_task" -> deleteScheduledTask(arguments)
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

            val title = arguments["title"]?.jsonPrimitive?.content ?: ""

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
                        title = title,
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

                    // Normalize interval_minutes to a cron expression so all
                    // recurring tasks use the same AlarmManager scheduling path.
                    val effectiveCron = cronExpression
                        ?: "*/$intervalMinutes * * * *"

                    CronjobEntity(
                        title = title,
                        instruction = instruction,
                        scheduleType = ScheduleType.RECURRING,
                        cronExpression = effectiveCron
                    )
                }

                else -> return ToolResult.Failure("Unsupported schedule type")
            }

            // Extract optional fields
            val maxExecutions = arguments["max_executions"]?.jsonPrimitive?.intOrNull
            val requireNetwork = arguments["require_network"]?.jsonPrimitive?.booleanOrNull ?: false
            val requireCharging = arguments["require_charging"]?.jsonPrimitive?.booleanOrNull ?: false
            val conversationId = arguments["_conversation_id"]?.jsonPrimitive?.content
            val agentName = arguments["agent"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

            // Build constraints JSON
            val constraints = buildJsonObject {
                put("requiresNetwork", requireNetwork)
                put("requiresCharging", requireCharging)
            }.toString()

            // Update cronjob with optional fields
            val finalCronjob = cronjob.copy(
                maxExecutions = maxExecutions,
                constraints = constraints,
                conversationId = conversationId,
                agentName = agentName
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
                    if (finalCronjob.cronExpression != null)
                        "on schedule: ${finalCronjob.cronExpression}"
                    else "on a recurring schedule"
                }
                else -> "as configured"
            }

            val titleLine = if (title.isNotBlank()) "Title: $title\n" else ""
            val agentLine = if (agentName != null) "Agent: $agentName\n" else ""

            return ToolResult.Success(
                output = """Task scheduled successfully!
                    |
                    |ID: $jobId
                    |${titleLine}${agentLine}Instruction: "$instruction"
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

            val tasks = if (includeDisabled) {
                cronjobManager.getAllSnapshot()
            } else {
                cronjobManager.getAllEnabledSnapshot()
            }

            if (tasks.isEmpty()) {
                return ToolResult.Success(
                    output = "No scheduled tasks found."
                )
            }

            val taskList = tasks.joinToString("\n\n") { task ->
                val schedule = when (task.scheduleType) {
                    ScheduleType.ONE_TIME -> {
                        val time = task.executeAt?.let {
                            LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(it),
                                ZoneId.systemDefault()
                            ).toString()
                        } ?: "unknown"
                        "One-time at $time"
                    }
                    ScheduleType.RECURRING ->
                        if (task.cronExpression != null) formatCronExpression(task.cronExpression!!)
                        else "Recurring"
                    ScheduleType.CONDITIONAL -> "Conditional"
                }

                val status = if (task.enabled) "Enabled" else "Disabled"

                val titleLine = if (task.title.isNotBlank()) "  Title: ${task.title}\n" else ""
                val agentLine = if (task.agentName != null) "  Agent: ${task.agentName}\n" else ""

                """- ID: ${task.id}
                    |${titleLine}${agentLine}  Instruction: "${task.instruction}"
                    |  Schedule: $schedule
                    |  Status: $status
                    |  Executions: ${task.executionCount}""".trimMargin()
            }

            ToolResult.Success(
                output = "Scheduled tasks (${tasks.size} total):\n\n$taskList"
            )

        } catch (e: Exception) {
            ToolResult.Failure(
                error = "Failed to list tasks: ${e.message}",
                exception = e
            )
        }
    }

    /**
     * Run a scheduled task immediately
     */
    private suspend fun runScheduledTask(arguments: JsonObject): ToolResult {
        return try {
            val taskId = arguments["task_id"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: task_id")

            val cronjob = cronjobManager.getById(taskId)
                ?: return ToolResult.Failure("Task not found: $taskId")

            if (!cronjob.enabled) {
                return ToolResult.Failure(
                    "Task '$taskId' is disabled. Enable it first with update_scheduled_task."
                )
            }

            val appContext = context.getApplicationContext()
            val serviceIntent = Intent(appContext, AgentExecutionService::class.java).apply {
                action = AgentExecutionService.ACTION_EXECUTE_TASK
                putExtra(AgentExecutionService.EXTRA_CRONJOB_ID, taskId)
            }
            appContext.startForegroundService(serviceIntent)

            val titleInfo = if (cronjob.title.isNotBlank()) " ('${cronjob.title}')" else ""
            ToolResult.Success(
                output = "Task '$taskId'$titleInfo has been triggered to run immediately. " +
                    "It will execute in the background and you'll see a notification when it completes."
            )
        } catch (e: Exception) {
            ToolResult.Failure(
                error = "Failed to trigger task: ${e.message}",
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

    /**
     * Update an existing scheduled task
     */
    private suspend fun updateScheduledTask(arguments: JsonObject): ToolResult {
        return try {
            val taskId = arguments["task_id"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: task_id")

            val existing = cronjobManager.getById(taskId)
                ?: return ToolResult.Failure("Task not found: $taskId")

            val newTitle = arguments["title"]?.jsonPrimitive?.content ?: existing.title
            val newInstruction = arguments["instruction"]?.jsonPrimitive?.content ?: existing.instruction

            val newScheduleType = arguments["schedule_type"]?.jsonPrimitive?.content?.let {
                when (it) {
                    "one_time" -> ScheduleType.ONE_TIME
                    "recurring" -> ScheduleType.RECURRING
                    else -> return ToolResult.Failure("Invalid schedule_type. Must be 'one_time' or 'recurring'")
                }
            } ?: existing.scheduleType

            val newExecuteAt = arguments["execute_at"]?.jsonPrimitive?.content?.let { str ->
                try {
                    LocalDateTime.parse(str)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                } catch (e: DateTimeParseException) {
                    return ToolResult.Failure("Invalid execute_at format. Expected ISO 8601 local datetime (e.g., '2026-02-12T18:00:00')")
                }
            } ?: existing.executeAt

            val newIntervalMinutes = if (arguments.containsKey("interval_minutes")) {
                arguments["interval_minutes"]?.jsonPrimitive?.intOrNull
            } else null

            val newCronExpression = if (arguments.containsKey("cron_expression")) {
                arguments["cron_expression"]?.jsonPrimitive?.content
            } else if (newIntervalMinutes != null) {
                // Convert interval to cron
                null // will be set below
            } else {
                existing.cronExpression
            }

            val newMaxExecutions = if (arguments.containsKey("max_executions")) {
                arguments["max_executions"]?.jsonPrimitive?.intOrNull
            } else {
                existing.maxExecutions
            }

            val newEnabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull ?: existing.enabled

            val newAgentName = if (arguments.containsKey("agent")) {
                arguments["agent"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            } else {
                existing.agentName
            }

            if (newIntervalMinutes != null && newIntervalMinutes < 15) {
                return ToolResult.Failure("Minimum interval is 15 minutes for battery optimization")
            }

            // Normalize: interval_minutes -> cron expression
            val effectiveCron = newCronExpression
                ?: if (newIntervalMinutes != null) "*/$newIntervalMinutes * * * *"
                else existing.cronExpression

            val updated = existing.copy(
                title = newTitle,
                instruction = newInstruction,
                scheduleType = newScheduleType,
                executeAt = newExecuteAt,
                cronExpression = effectiveCron,
                maxExecutions = newMaxExecutions,
                enabled = newEnabled,
                agentName = newAgentName
            )

            cronjobManager.update(updated)

            ToolResult.Success(
                output = "Task '$taskId' has been updated successfully."
            )

        } catch (e: Exception) {
            ToolResult.Failure(
                error = "Failed to update task: ${e.message}",
                exception = e
            )
        }
    }

    /**
     * Permanently delete a scheduled task
     */
    private suspend fun deleteScheduledTask(arguments: JsonObject): ToolResult {
        return try {
            val taskId = arguments["task_id"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: task_id")

            val existing = cronjobManager.getById(taskId)
                ?: return ToolResult.Failure("Task not found: $taskId")

            cronjobManager.delete(taskId)

            val titleInfo = if (existing.title.isNotBlank()) " ('${existing.title}')" else ""
            ToolResult.Success(
                output = "Task '$taskId'$titleInfo has been permanently deleted along with its execution history."
            )

        } catch (e: Exception) {
            ToolResult.Failure(
                error = "Failed to delete task: ${e.message}",
                exception = e
            )
        }
    }
}
