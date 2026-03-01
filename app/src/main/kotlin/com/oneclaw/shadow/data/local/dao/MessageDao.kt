package com.oneclaw.shadow.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.oneclaw.shadow.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

data class ModelUsageRow(
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "input_tokens") val inputTokens: Long,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    suspend fun getMessagesSnapshot(sessionId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")
    suspend fun getMessageCount(sessionId: String): Int

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        """
        SELECT COALESCE(SUM(token_count_input), 0) + COALESCE(SUM(token_count_output), 0)
        FROM messages
        WHERE session_id = :sessionId AND token_count_input IS NOT NULL
        """
    )
    suspend fun getTotalTokensForSession(sessionId: String): Long

    @Query(
        """
        SELECT model_id,
               COALESCE(SUM(token_count_input), 0) AS input_tokens,
               COALESCE(SUM(token_count_output), 0) AS output_tokens,
               COUNT(*) AS message_count
        FROM messages
        WHERE type = 'AI_RESPONSE'
          AND token_count_input IS NOT NULL
          AND created_at >= :since
        GROUP BY model_id
        ORDER BY (COALESCE(SUM(token_count_input), 0) + COALESCE(SUM(token_count_output), 0)) DESC
        """
    )
    suspend fun getUsageStatsByModel(since: Long): List<ModelUsageRow>
}
