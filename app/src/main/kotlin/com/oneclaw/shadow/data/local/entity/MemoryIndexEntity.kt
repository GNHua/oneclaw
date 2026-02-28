package com.oneclaw.shadow.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_index")
data class MemoryIndexEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "source_type")
    val sourceType: String,             // "daily_log" or "long_term"

    @ColumnInfo(name = "source_date")
    val sourceDate: String?,            // "2026-02-28" for daily logs, null for MEMORY.md

    @ColumnInfo(name = "chunk_text")
    val chunkText: String,              // The actual text content of this chunk

    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray?,          // 384-dim float vector serialized as bytes (null if embedding failed)

    @ColumnInfo(name = "created_at")
    val createdAt: Long,                // Epoch millis when this chunk was indexed

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long                 // Epoch millis of last update
)
