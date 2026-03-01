package com.oneclaw.shadow.core.repository

import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.TaskExecutionRecord
import kotlinx.coroutines.flow.Flow

interface TaskExecutionRecordRepository {
    fun getRecordsByTaskId(taskId: String, limit: Int = 50): Flow<List<TaskExecutionRecord>>
    suspend fun createRecord(record: TaskExecutionRecord)
    suspend fun updateResult(
        id: String,
        status: ExecutionStatus,
        completedAt: Long,
        sessionId: String?,
        errorMessage: String?
    )
    suspend fun deleteByTaskId(taskId: String)
    suspend fun cleanupOlderThan(days: Int)
}
