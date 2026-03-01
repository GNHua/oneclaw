package com.oneclaw.shadow.core.model

data class TaskExecutionRecord(
    val id: String,
    val taskId: String,
    val status: ExecutionStatus,
    val sessionId: String?,
    val startedAt: Long,
    val completedAt: Long?,
    val errorMessage: String?
)
