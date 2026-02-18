package com.tomandy.oneclaw.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tomandy.oneclaw.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteAllInConversation(conversationId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: String): Int

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages")
    suspend fun getAllOnce(): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE (role = 'user' OR role = 'assistant')
          AND content LIKE '%' || :query || '%'
          AND (:timeFrom IS NULL OR timestamp >= :timeFrom)
          AND (:timeTo IS NULL OR timestamp <= :timeTo)
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchMessages(
        query: String,
        timeFrom: Long?,
        timeTo: Long?,
        limit: Int
    ): List<MessageEntity>
}
