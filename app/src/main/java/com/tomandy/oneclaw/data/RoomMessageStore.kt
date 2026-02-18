package com.tomandy.oneclaw.data

import com.tomandy.oneclaw.agent.MessageRecord
import com.tomandy.oneclaw.agent.MessageStore
import com.tomandy.oneclaw.data.dao.MessageDao
import com.tomandy.oneclaw.data.entity.MessageEntity

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
