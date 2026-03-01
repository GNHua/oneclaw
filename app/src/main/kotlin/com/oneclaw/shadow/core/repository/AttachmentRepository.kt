package com.oneclaw.shadow.core.repository

import com.oneclaw.shadow.core.model.Attachment
import kotlinx.coroutines.flow.Flow

interface AttachmentRepository {
    fun getAttachmentsForMessage(messageId: String): Flow<List<Attachment>>
    suspend fun getAttachmentsForMessages(messageIds: List<String>): List<Attachment>
    suspend fun addAttachment(attachment: Attachment)
    suspend fun addAttachments(attachments: List<Attachment>)
    suspend fun deleteAttachment(id: String)
    suspend fun deleteAttachmentsForMessage(messageId: String)
}
