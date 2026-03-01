package com.oneclaw.shadow.data.repository

import com.oneclaw.shadow.core.model.Attachment
import com.oneclaw.shadow.core.repository.AttachmentRepository
import com.oneclaw.shadow.data.local.dao.AttachmentDao
import com.oneclaw.shadow.data.local.entity.AttachmentEntity
import com.oneclaw.shadow.data.local.mapper.toDomain
import com.oneclaw.shadow.data.local.mapper.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AttachmentRepositoryImpl(
    private val attachmentDao: AttachmentDao
) : AttachmentRepository {

    override fun getAttachmentsForMessage(messageId: String): Flow<List<Attachment>> {
        return attachmentDao.getAttachmentsForMessage(messageId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getAttachmentsForMessages(messageIds: List<String>): List<Attachment> {
        if (messageIds.isEmpty()) return emptyList()
        return attachmentDao.getAttachmentsForMessages(messageIds)
            .map { it.toDomain() }
    }

    override suspend fun addAttachment(attachment: Attachment) {
        attachmentDao.insertAttachment(attachment.toEntity())
    }

    override suspend fun addAttachments(attachments: List<Attachment>) {
        attachmentDao.insertAttachments(attachments.map { it.toEntity() })
    }

    override suspend fun deleteAttachment(id: String) {
        attachmentDao.deleteAttachmentById(id)
    }

    override suspend fun deleteAttachmentsForMessage(messageId: String) {
        attachmentDao.deleteAttachmentsForMessage(messageId)
    }
}
