package com.oneclaw.shadow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.oneclaw.shadow.data.local.entity.MemoryIndexEntity

@Dao
interface MemoryIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MemoryIndexEntity>)

    @Query("SELECT * FROM memory_index WHERE source_type = :sourceType")
    suspend fun getBySourceType(sourceType: String): List<MemoryIndexEntity>

    @Query("SELECT * FROM memory_index WHERE source_date = :date")
    suspend fun getByDate(date: String): List<MemoryIndexEntity>

    @Query("SELECT * FROM memory_index WHERE embedding IS NOT NULL")
    suspend fun getAllWithEmbeddings(): List<MemoryIndexEntity>

    @Query("SELECT * FROM memory_index")
    suspend fun getAll(): List<MemoryIndexEntity>

    @Query("DELETE FROM memory_index WHERE source_type = :sourceType")
    suspend fun deleteBySourceType(sourceType: String)

    @Query("DELETE FROM memory_index WHERE source_date = :date")
    suspend fun deleteByDate(date: String)

    @Query("DELETE FROM memory_index")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM memory_index")
    suspend fun count(): Int
}
