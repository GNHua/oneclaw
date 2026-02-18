package com.tomandy.oneclaw.scheduler.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for cronjobs
 */
@Dao
interface CronjobDao {

    @Query("SELECT * FROM cronjobs WHERE id = :id")
    suspend fun getById(id: String): CronjobEntity?

    @Query("SELECT * FROM cronjobs WHERE enabled = 1 ORDER BY createdAt DESC")
    fun getAllEnabled(): Flow<List<CronjobEntity>>

    @Query("SELECT * FROM cronjobs ORDER BY createdAt DESC")
    fun getAll(): Flow<List<CronjobEntity>>

    @Query("SELECT * FROM cronjobs ORDER BY createdAt DESC")
    suspend fun getAllSnapshot(): List<CronjobEntity>

    @Query("SELECT * FROM cronjobs WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun getAllEnabledSnapshot(): List<CronjobEntity>

    @Query("SELECT * FROM cronjobs WHERE scheduleType = :scheduleType AND enabled = 1")
    suspend fun getByType(scheduleType: ScheduleType): List<CronjobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cronjob: CronjobEntity): Long

    @Update
    suspend fun update(cronjob: CronjobEntity)

    @Delete
    suspend fun delete(cronjob: CronjobEntity)

    @Query("DELETE FROM cronjobs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE cronjobs SET enabled = :enabled WHERE id = :id")
    suspend fun updateEnabled(id: String, enabled: Boolean)

    @Query("UPDATE cronjobs SET lastExecutedAt = :timestamp, executionCount = executionCount + 1 WHERE id = :id")
    suspend fun updateLastExecution(id: String, timestamp: Long)

    @Query("UPDATE cronjobs SET workManagerId = :workManagerId WHERE id = :id")
    suspend fun updateWorkManagerId(id: String, workManagerId: String)

    @Query("SELECT * FROM cronjobs WHERE enabled = 0 ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getDisabledPaged(limit: Int, offset: Int): List<CronjobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cronjobs: List<CronjobEntity>)
}
