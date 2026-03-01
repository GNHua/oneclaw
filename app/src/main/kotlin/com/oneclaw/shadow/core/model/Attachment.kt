package com.oneclaw.shadow.core.model

data class Attachment(
    val id: String,
    val messageId: String,
    val type: AttachmentType,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val filePath: String,
    val thumbnailPath: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val createdAt: Long
)

enum class AttachmentType {
    IMAGE,
    VIDEO,
    FILE
}
