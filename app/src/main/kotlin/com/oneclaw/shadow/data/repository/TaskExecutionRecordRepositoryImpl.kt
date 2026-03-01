package com.oneclaw.shadow.data.repository

import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.TaskExecutionRecord
import com.oneclaw.shadow.core.repository.TaskExecutionRecordRepository
import com.oneclaw.shadow.data.local.dao.TaskExecutionRecordDao
import com.oneclaw.shadow.data.local.mapper.toDomain
import com.oneclaw.shadow.data.local.mapper.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TaskExecutionRecordRepositoryImpl(
    private val dao: TaskExecutionRecordDao
) : TaskExecutionRecordRepository {

    override fun getRecordsByTaskId(taskId: String, limit: Int): Flow<List<TaskExecutionRecord>> =
        dao.getRecordsByTaskId(taskId, limit).map { entities -> entities.map { it.toDomain() } }

    override suspend fun createRecord(record: TaskExecutionRecord) {
        dao.insert(record.toEntity())
    }

    override suspend fun updateResult(
        id: String,
        status: ExecutionStatus,
        completedAt: Long,
        sessionId: String?,
        errorMessage: String?
    ) {
        dao.updateResult(
            id = id,
            status = status.name,
            completedAt = completedAt,
            sessionId = sessionId,
            errorMessage = errorMessage
        )
    }

    override suspend fun deleteByTaskId(taskId: String) {
        dao.deleteByTaskId(taskId)
    }

    override suspend fun cleanupOlderThan(days: Int) {
        val cutoffMillis = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000)
        dao.deleteOlderThan(cutoffMillis)
    }
}
