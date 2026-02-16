package com.tomandy.palmclaw.data

import com.tomandy.palmclaw.agent.MessageRecord
import com.tomandy.palmclaw.agent.MessageStore
import com.tomandy.palmclaw.data.dao.MessageDao
import com.tomandy.palmclaw.data.entity.MessageEntity

/**
 * Room-backed implementation of [MessageStore].
 *
 * Bridges the `:core-agent` module's persistence interface to the
 * `:app` module's Room database layer.
 */
class RoomMessageStore(private val messageDao: MessageDao) : MessageStore {
    override suspend fun insert(record: MessageRecord) {
        messageDao.insert(
            MessageEntity(
                id = record.id,
                conversationId = record.conversationId,
                role = record.role,
                content = record.content,
                timestamp = record.timestamp,
                toolCallId = record.toolCallId,
                toolName = record.toolName,
                toolCalls = record.toolCalls,
                imagePaths = record.imagePaths
            )
        )
    }
}
