package com.oneclaw.shadow.data.local.mapper

import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.TaskExecutionRecord
import com.oneclaw.shadow.data.local.entity.TaskExecutionRecordEntity

fun TaskExecutionRecordEntity.toDomain(): TaskExecutionRecord = TaskExecutionRecord(
    id = id,
    taskId = taskId,
    status = ExecutionStatus.valueOf(status),
    sessionId = sessionId,
    startedAt = startedAt,
    completedAt = completedAt,
    errorMessage = errorMessage
)

fun TaskExecutionRecord.toEntity(): TaskExecutionRecordEntity = TaskExecutionRecordEntity(
    id = id,
    taskId = taskId,
    status = status.name,
    sessionId = sessionId,
    startedAt = startedAt,
    completedAt = completedAt,
    errorMessage = errorMessage
)
