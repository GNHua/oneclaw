package com.tomandy.oneclaw.scheduler.plugin

import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import kotlinx.serialization.json.*

/**
 * Metadata for the built-in Scheduler plugin
 */
object SchedulerPluginMetadata {

    /**
     * Get the plugin metadata for registration
     */
    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "scheduler",
            name = "Task Scheduler",
            version = "1.0.0",
            description = "Schedule agent tasks to run at specific times or recurring intervals",
            author = "OneClaw Team",
            entryPoint = "SchedulerPlugin",
            tools = listOf(
                scheduleTaskTool(),
                listScheduledTasksTool(),
                cancelScheduledTaskTool()
            ),
            permissions = listOf(
                "SCHEDULE_EXACT_ALARM",
                "FOREGROUND_SERVICE",
                "RECEIVE_BOOT_COMPLETED",
                "WAKE_LOCK"
            )
        )
    }

    /**
     * Tool definition for schedule_task
     */
    private fun scheduleTaskTool() = ToolDefinition(
        name = "schedule_task",
        description = """Schedule a task to be executed at a specific time or on a recurring interval.
            |
            |The Agent will autonomously execute the instruction at the scheduled time, even if the user is not actively using the app.
            |
            |For one-time tasks:
            |- Use schedule_type: "one_time"
            |- Provide execute_at as a local datetime without timezone suffix (e.g., '2026-02-12T18:00:00')
            |- One-time tasks can be scheduled at any time in the future, with no minimum delay
            |
            |For recurring tasks:
            |- Use schedule_type: "recurring"
            |- Provide either interval_minutes (minimum 15) OR cron_expression
            |- Examples: "Check email every 30 minutes", "Every Monday at 9 AM"
            |- Note: The minimum interval for recurring tasks is 15 minutes due to Android battery optimization.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Short title for the task (e.g., 'Daily Weather', 'Morning Briefing')"))
                }
                putJsonObject("instruction") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The task instruction in natural language. This will be sent to the Agent at execution time."))
                }
                putJsonObject("schedule_type") {
                    put("type", JsonPrimitive("string"))
                    putJsonArray("enum") {
                        add(JsonPrimitive("one_time"))
                        add(JsonPrimitive("recurring"))
                    }
                    put("description", JsonPrimitive("Whether the task should run once or repeatedly"))
                }
                putJsonObject("execute_at") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("For one_time tasks: local datetime without timezone suffix (e.g., '2026-02-12T18:00:00')"))
                }
                putJsonObject("interval_minutes") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("For recurring tasks: Interval in minutes (minimum 15)"))
                    put("minimum", JsonPrimitive(15))
                }
                putJsonObject("cron_expression") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("For recurring tasks: Unix cron expression (e.g., '0 9 * * MON' for every Monday at 9 AM)"))
                }
                putJsonObject("max_executions") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Optional: Auto-disable after N executions"))
                }
                putJsonObject("require_network") {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("Whether the task requires internet connectivity (default: false)"))
                }
                putJsonObject("require_charging") {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("Whether the task should only run while device is charging (default: false)"))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("instruction"))
                add(JsonPrimitive("schedule_type"))
            }
        }
    )

    /**
     * Tool definition for list_scheduled_tasks
     */
    private fun listScheduledTasksTool() = ToolDefinition(
        name = "list_scheduled_tasks",
        description = """List all scheduled tasks.
            |
            |Returns information about active and disabled scheduled tasks, including:
            |- Task ID
            |- Title
            |- Instruction
            |- Schedule type and timing
            |- Execution count
            |- Last execution time
            |- Enabled/disabled status
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("include_disabled") {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("Whether to include disabled tasks in the list (default: false)"))
                }
            }
        }
    )

    /**
     * Tool definition for cancel_scheduled_task
     */
    private fun cancelScheduledTaskTool() = ToolDefinition(
        name = "cancel_scheduled_task",
        description = """Cancel a scheduled task.
            |
            |The task will be disabled and will no longer execute.
            |Use list_scheduled_tasks to get the task_id of tasks you want to cancel.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("task_id") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The ID of the task to cancel"))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("task_id"))
            }
        }
    )
}
