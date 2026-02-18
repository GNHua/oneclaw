package com.tomandy.oneclaw.scheduler.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for execution logs
 */
@Dao
interface ExecutionLogDao {

    @Query("SELECT * FROM cronjob_executions WHERE id = :id")
    suspend fun getById(id: Long): ExecutionLog?

    @Query("SELECT * FROM cronjob_executions WHERE cronjobId = :cronjobId ORDER BY startedAt DESC")
    fun getLogsForCronjob(cronjobId: String): Flow<List<ExecutionLog>>

    @Query("SELECT * FROM cronjob_executions WHERE cronjobId = :cronjobId ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecentLogsForCronjob(cronjobId: String, limit: Int): List<ExecutionLog>

    @Query("SELECT * FROM cronjob_executions ORDER BY startedAt DESC LIMIT :limit")
    fun getRecentLogs(limit: Int): Flow<List<ExecutionLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ExecutionLog): Long

    @Update
    suspend fun update(log: ExecutionLog)

    @Delete
    suspend fun delete(log: ExecutionLog)

    @Query("DELETE FROM cronjob_executions WHERE cronjobId = :cronjobId")
    suspend fun deleteLogsForCronjob(cronjobId: String)

    @Query("DELETE FROM cronjob_executions WHERE startedAt < :timestamp")
    suspend fun deleteLogsOlderThan(timestamp: Long)

    @Query("SELECT * FROM cronjob_executions")
    suspend fun getAllOnce(): List<ExecutionLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<ExecutionLog>)
}
