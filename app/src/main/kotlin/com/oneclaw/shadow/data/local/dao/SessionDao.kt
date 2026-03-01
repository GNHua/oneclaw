package com.oneclaw.shadow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.oneclaw.shadow.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("UPDATE sessions SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("UPDATE sessions SET deleted_at = :deletedAt WHERE id IN (:ids)")
    suspend fun softDeleteBatch(ids: List<String>, deletedAt: Long)

    @Query("UPDATE sessions SET deleted_at = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("UPDATE sessions SET deleted_at = NULL WHERE id IN (:ids)")
    suspend fun restoreBatch(ids: List<String>)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("DELETE FROM sessions WHERE deleted_at IS NOT NULL")
    suspend fun hardDeleteAllSoftDeleted()

    @Query("UPDATE sessions SET current_agent_id = :newAgentId WHERE current_agent_id = :oldAgentId")
    suspend fun updateAgentForSessions(oldAgentId: String, newAgentId: String)

    @Query("UPDATE sessions SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE sessions SET message_count = :count, last_message_preview = :preview, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateMessageStats(id: String, count: Int, preview: String?, updatedAt: Long)

    @Query("UPDATE sessions SET is_active = :isActive WHERE id = :id")
    suspend fun setActive(id: String, isActive: Boolean)

    @Query("UPDATE sessions SET current_agent_id = :agentId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateCurrentAgent(id: String, agentId: String, updatedAt: Long)

    @Query("UPDATE sessions SET compacted_summary = :summary, compact_boundary_timestamp = :boundaryTimestamp, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateCompactedSummary(id: String, summary: String?, boundaryTimestamp: Long?, updatedAt: Long)

    @Query("UPDATE sessions SET last_logged_message_id = :messageId WHERE id = :sessionId")
    suspend fun updateLastLoggedMessageId(sessionId: String, messageId: String)

    @Query("SELECT * FROM sessions WHERE is_active = 1 LIMIT 1")
    suspend fun getActiveSession(): SessionEntity?
}
