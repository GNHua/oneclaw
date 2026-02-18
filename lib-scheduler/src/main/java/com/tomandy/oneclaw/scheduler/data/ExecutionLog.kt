package com.tomandy.oneclaw.scheduler.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Audit trail for cronjob executions
 */
@Entity(tableName = "cronjob_executions")
data class ExecutionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Reference to the cronjob that was executed
     */
    val cronjobId: String,

    /**
     * When execution started (Unix timestamp in millis)
     */
    val startedAt: Long,

    /**
     * When execution completed (Unix timestamp in millis)
     */
    val completedAt: Long? = null,

    /**
     * Result status of the execution
     */
    val status: ExecutionStatus,

    /**
     * Brief summary of the result from the Agent
     */
    val resultSummary: String? = null,

    /**
     * Error message if execution failed
     */
    val errorMessage: String? = null
)
