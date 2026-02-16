package com.tomandy.palmclaw.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: String, // "user", "assistant", "system", "tool", "meta"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),

    // Tool support fields
    val toolCallId: String? = null,    // Links tool result to assistant's tool call
    val toolName: String? = null,      // Name of tool that was executed
    val toolCalls: String? = null,      // JSON serialized List<ToolCall> for assistant messages
    val imagePaths: String? = null,       // JSON array of file paths for image attachments
    val audioPaths: String? = null,        // JSON array of file paths for audio attachments
    val videoPaths: String? = null,        // JSON array of file paths for video attachments
    val documentPaths: String? = null      // JSON array of {path, name, mimeType} for document attachments
)
