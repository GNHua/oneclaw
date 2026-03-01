package com.oneclaw.shadow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.oneclaw.shadow.data.local.entity.TaskExecutionRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskExecutionRecordDao {

    @Query("""
        SELECT * FROM task_execution_records
        WHERE task_id = :taskId
        ORDER BY started_at DESC
        LIMIT :limit
    """)
    fun getRecordsByTaskId(taskId: String, limit: Int = 50): Flow<List<TaskExecutionRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TaskExecutionRecordEntity)

    @Query("""
        UPDATE task_execution_records
        SET status = :status, completed_at = :completedAt,
            session_id = :sessionId, error_message = :errorMessage
        WHERE id = :id
    """)
    suspend fun updateResult(
        id: String,
        status: String,
        completedAt: Long,
        sessionId: String?,
        errorMessage: String?
    )

    @Query("DELETE FROM task_execution_records WHERE task_id = :taskId")
    suspend fun deleteByTaskId(taskId: String)

    @Query("DELETE FROM task_execution_records WHERE started_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    @Query("SELECT COUNT(*) FROM task_execution_records WHERE task_id = :taskId")
    suspend fun countByTaskId(taskId: String): Int
}
