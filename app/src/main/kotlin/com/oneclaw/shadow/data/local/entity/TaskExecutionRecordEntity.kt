package com.oneclaw.shadow.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "task_execution_records",
    foreignKeys = [
        ForeignKey(
            entity = ScheduledTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["task_id"]),
        Index(value = ["started_at"])
    ]
)
data class TaskExecutionRecordEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    val status: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String?,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?
)
