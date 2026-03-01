package com.oneclaw.shadow.data.local.mapper

import com.oneclaw.shadow.core.model.Attachment
import com.oneclaw.shadow.core.model.AttachmentType
import com.oneclaw.shadow.data.local.entity.AttachmentEntity

fun AttachmentEntity.toDomain(): Attachment = Attachment(
    id = id,
    messageId = messageId,
    type = AttachmentType.valueOf(type),
    fileName = fileName,
    mimeType = mimeType,
    fileSize = fileSize,
    filePath = filePath,
    thumbnailPath = thumbnailPath,
    width = width,
    height = height,
    durationMs = durationMs,
    createdAt = createdAt
)

fun Attachment.toEntity(): AttachmentEntity = AttachmentEntity(
    id = id,
    messageId = messageId,
    type = type.name,
    fileName = fileName,
    mimeType = mimeType,
    fileSize = fileSize,
    filePath = filePath,
    thumbnailPath = thumbnailPath,
    width = width,
    height = height,
    durationMs = durationMs,
    createdAt = createdAt
)
