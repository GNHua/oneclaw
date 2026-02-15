package com.tomandy.palmclaw.scheduler.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Database entity representing a scheduled task (cronjob)
 */
@Entity(tableName = "cronjobs")
data class CronjobEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /**
     * Short user-facing title for this task (e.g., "Daily Weather")
     */
    val title: String = "",

    /**
     * Detailed user-facing description of what this task does
     */
    val description: String? = null,

    /**
     * The Agent's task instruction in natural language
     */
    val instruction: String,

    /**
     * Type of schedule (one-time, recurring, conditional)
     */
    val scheduleType: ScheduleType,

    /**
     * Unix cron expression for recurring tasks (e.g., "0 9 * * MON" for every Monday at 9 AM)
     * Only used when scheduleType is RECURRING and intervalMinutes is null
     */
    val cronExpression: String? = null,

    /**
     * Unix timestamp in milliseconds for one-time execution
     * Only used when scheduleType is ONE_TIME
     */
    val executeAt: Long? = null,

    /**
     * Interval in minutes for simple recurring tasks
     * Only used when scheduleType is RECURRING and cronExpression is null
     */
    val intervalMinutes: Int? = null,

    /**
     * JSON string containing execution constraints
     * Example: {"requiresNetwork": true, "requiresCharging": false}
     */
    val constraints: String = "{}",

    /**
     * Whether this cronjob is enabled
     */
    val enabled: Boolean = true,

    /**
     * When this cronjob was created (Unix timestamp in millis)
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Last execution timestamp (Unix timestamp in millis)
     */
    val lastExecutedAt: Long? = null,

    /**
     * Total number of times this cronjob has been executed
     */
    val executionCount: Int = 0,

    /**
     * Auto-disable after N executions (null = no limit)
     */
    val maxExecutions: Int? = null,

    /**
     * Whether to send a notification when execution completes
     */
    val notifyOnCompletion: Boolean = true,

    /**
     * Reference to WorkManager work request ID
     * Used for cancelling WorkManager jobs
     */
    val workManagerId: String? = null,

    /**
     * The conversation ID where this task was scheduled.
     * Used to post execution results back to the original conversation.
     */
    val conversationId: String? = null
)
