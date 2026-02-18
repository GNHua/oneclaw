package com.tomandy.oneclaw.data.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tomandy.oneclaw.data.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversationsPaged(): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getConversation(id: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationOnce(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations")
    suspend fun getAllOnce(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(conversations: List<ConversationEntity>)

    @Query(
        """
        SELECT * FROM conversations
        WHERE title LIKE '%' || :query || '%'
          AND (:timeFrom IS NULL OR updatedAt >= :timeFrom)
          AND (:timeTo IS NULL OR updatedAt <= :timeTo)
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchConversations(
        query: String,
        timeFrom: Long?,
        timeTo: Long?,
        limit: Int
    ): List<ConversationEntity>
}
