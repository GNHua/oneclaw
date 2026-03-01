package com.oneclaw.shadow.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["created_at"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    val type: String, // "USER", "AI_RESPONSE", "TOOL_CALL", "TOOL_RESULT", "ERROR", "SYSTEM"
    val content: String,
    @ColumnInfo(name = "thinking_content")
    val thinkingContent: String?,
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String?,
    @ColumnInfo(name = "tool_name")
    val toolName: String?,
    @ColumnInfo(name = "tool_input")
    val toolInput: String?,
    @ColumnInfo(name = "tool_output")
    val toolOutput: String?,
    @ColumnInfo(name = "tool_status")
    val toolStatus: String?,
    @ColumnInfo(name = "tool_duration_ms")
    val toolDurationMs: Long?,
    @ColumnInfo(name = "token_count_input")
    val tokenCountInput: Int?,
    @ColumnInfo(name = "token_count_output")
    val tokenCountOutput: Int?,
    @ColumnInfo(name = "model_id")
    val modelId: String?,
    @ColumnInfo(name = "provider_id")
    val providerId: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "citations")
    val citations: String? = null
)
