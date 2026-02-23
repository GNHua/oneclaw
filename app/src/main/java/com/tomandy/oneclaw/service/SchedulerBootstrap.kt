package com.tomandy.oneclaw.service

import com.tomandy.oneclaw.scheduler.CronjobManager
import com.tomandy.oneclaw.scheduler.data.CronjobEntity
import com.tomandy.oneclaw.scheduler.data.ScheduleType
import com.tomandy.oneclaw.scheduler.util.formatCronExpression
import com.tomandy.oneclaw.scheduler.util.formatIntervalMinutes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Loads a concise summary of active scheduled tasks for injection
 * into the system prompt. Parallels [MemoryBootstrap].
 */
object SchedulerBootstrap {

    /**
     * Load a concise summary of enabled scheduled tasks.
     *
     * Returns a formatted block to append to the system prompt,
     * or empty string if no enabled tasks exist.
     */
    suspend fun loadSchedulerContext(cronjobManager: CronjobManager): String {
        val tasks = cronjobManager.getAllEnabledSnapshot()
        if (tasks.isEmpty()) return ""

        val taskLines = tasks.joinToString("\n") { task ->
            val schedule = formatSchedule(task)
            val title = if (task.title.isNotBlank()) task.title else task.instruction.take(50)
            "- ${task.id}: \"$title\" ($schedule)"
        }

        return buildString {
            appendLine("--- Active Scheduled Tasks ---")
            appendLine()
            appendLine(taskLines)
            appendLine()
            appendLine("--- End of Scheduled Tasks ---")
            appendLine()
            append("Use run_scheduled_task with the task ID to run a task immediately. ")
            append("Use list_scheduled_tasks for full details including instructions.")
        }
    }

    private fun formatSchedule(task: CronjobEntity): String {
        return when (task.scheduleType) {
            ScheduleType.ONE_TIME -> {
                val time = task.executeAt?.let {
                    LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(it),
                        ZoneId.systemDefault()
                    ).toString()
                } ?: "unknown"
                "once at $time"
            }
            ScheduleType.RECURRING -> when {
                task.cronExpression != null -> formatCronExpression(task.cronExpression!!)
                task.intervalMinutes != null -> formatIntervalMinutes(task.intervalMinutes!!)
                else -> "recurring"
            }
            ScheduleType.CONDITIONAL -> "conditional"
        }
    }
}
