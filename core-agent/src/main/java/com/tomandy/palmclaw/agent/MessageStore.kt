package com.tomandy.palmclaw.agent

/**
 * Abstraction for persisting agent messages.
 *
 * This interface decouples the agent layer from the data/Room layer,
 * allowing `:core-agent` to persist messages without depending on `:app`.
 * The `:app` module provides the concrete implementation backed by Room.
 */
interface MessageStore {
    /**
     * Persist a message record.
     */
    suspend fun insert(record: MessageRecord)
}

/**
 * A lightweight message record used by the agent layer for persistence.
 * Maps 1:1 to the fields the agent needs to write.
 */
data class MessageRecord(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: String? = null
)
