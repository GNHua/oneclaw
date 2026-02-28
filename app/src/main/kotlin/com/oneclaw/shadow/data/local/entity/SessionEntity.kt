package com.oneclaw.shadow.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sessions",
    indices = [
        Index(value = ["updated_at"]),
        Index(value = ["deleted_at"])
    ]
)
data class SessionEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    @ColumnInfo(name = "current_agent_id")
    val currentAgentId: String,
    @ColumnInfo(name = "message_count")
    val messageCount: Int,
    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String?,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "compacted_summary")
    val compactedSummary: String? = null,
    @ColumnInfo(name = "compact_boundary_timestamp")
    val compactBoundaryTimestamp: Long? = null
)
