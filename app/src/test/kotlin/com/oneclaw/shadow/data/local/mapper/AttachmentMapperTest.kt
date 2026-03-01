package com.oneclaw.shadow.data.local.mapper

import com.oneclaw.shadow.core.model.Attachment
import com.oneclaw.shadow.core.model.AttachmentType
import com.oneclaw.shadow.data.local.entity.AttachmentEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AttachmentMapperTest {

    @Test
    fun `entity toDomain maps IMAGE attachment correctly`() {
        val entity = AttachmentEntity(
            id = "att-1",
            messageId = "msg-1",
            type = "IMAGE",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            fileSize = 1024L,
            filePath = "/data/files/attachments/session-1/att-1.jpg",
            thumbnailPath = "/data/files/attachments/session-1/thumbs/att-1_thumb.jpg",
            width = 1920,
            height = 1080,
            durationMs = null,
            createdAt = 1000L
        )

        val domain = entity.toDomain()

        assertEquals("att-1", domain.id)
        assertEquals("msg-1", domain.messageId)
        assertEquals(AttachmentType.IMAGE, domain.type)
        assertEquals("photo.jpg", domain.fileName)
        assertEquals("image/jpeg", domain.mimeType)
        assertEquals(1024L, domain.fileSize)
        assertEquals("/data/files/attachments/session-1/att-1.jpg", domain.filePath)
        assertEquals("/data/files/attachments/session-1/thumbs/att-1_thumb.jpg", domain.thumbnailPath)
        assertEquals(1920, domain.width)
        assertEquals(1080, domain.height)
        assertNull(domain.durationMs)
        assertEquals(1000L, domain.createdAt)
    }

    @Test
    fun `entity toDomain maps VIDEO attachment correctly`() {
        val entity = AttachmentEntity(
            id = "att-2",
            messageId = "msg-2",
            type = "VIDEO",
            fileName = "video.mp4",
            mimeType = "video/mp4",
            fileSize = 5242880L,
            filePath = "/data/files/attachments/session-1/att-2.mp4",
            thumbnailPath = "/data/files/attachments/session-1/thumbs/att-2_thumb.jpg",
            width = 1280,
            height = 720,
            durationMs = 30000L,
            createdAt = 2000L
        )

        val domain = entity.toDomain()

        assertEquals(AttachmentType.VIDEO, domain.type)
        assertEquals("video.mp4", domain.fileName)
        assertEquals("video/mp4", domain.mimeType)
        assertEquals(30000L, domain.durationMs)
        assertEquals(1280, domain.width)
        assertEquals(720, domain.height)
    }

    @Test
    fun `entity toDomain maps FILE attachment correctly`() {
        val entity = AttachmentEntity(
            id = "att-3",
            messageId = "msg-3",
            type = "FILE",
            fileName = "document.pdf",
            mimeType = "application/pdf",
            fileSize = 2048L,
            filePath = "/data/files/attachments/session-1/att-3.pdf",
            thumbnailPath = null,
            width = null,
            height = null,
            durationMs = null,
            createdAt = 3000L
        )

        val domain = entity.toDomain()

        assertEquals(AttachmentType.FILE, domain.type)
        assertEquals("document.pdf", domain.fileName)
        assertEquals("application/pdf", domain.mimeType)
        assertNull(domain.thumbnailPath)
        assertNull(domain.width)
        assertNull(domain.height)
        assertNull(domain.durationMs)
    }

    @Test
    fun `domain toEntity maps all fields correctly`() {
        val domain = Attachment(
            id = "att-4",
            messageId = "msg-4",
            type = AttachmentType.IMAGE,
            fileName = "image.png",
            mimeType = "image/png",
            fileSize = 512L,
            filePath = "/data/files/attachments/session-2/att-4.png",
            thumbnailPath = "/data/files/attachments/session-2/thumbs/att-4_thumb.jpg",
            width = 800,
            height = 600,
            durationMs = null,
            createdAt = 4000L
        )

        val entity = domain.toEntity()

        assertEquals("att-4", entity.id)
        assertEquals("msg-4", entity.messageId)
        assertEquals("IMAGE", entity.type)
        assertEquals("image.png", entity.fileName)
        assertEquals("image/png", entity.mimeType)
        assertEquals(512L, entity.fileSize)
        assertEquals("/data/files/attachments/session-2/att-4.png", entity.filePath)
        assertEquals("/data/files/attachments/session-2/thumbs/att-4_thumb.jpg", entity.thumbnailPath)
        assertEquals(800, entity.width)
        assertEquals(600, entity.height)
        assertNull(entity.durationMs)
        assertEquals(4000L, entity.createdAt)
    }

    @Test
    fun `roundtrip preserves all attachment types`() {
        for (type in AttachmentType.entries) {
            val original = Attachment(
                id = "att-${type.name}",
                messageId = "msg-rt",
                type = type,
                fileName = "file.bin",
                mimeType = "application/octet-stream",
                fileSize = 100L,
                filePath = "/data/file.bin",
                thumbnailPath = null,
                width = null,
                height = null,
                durationMs = null,
                createdAt = 1000L
            )
            val roundtripped = original.toEntity().toDomain()
            assertEquals(original, roundtripped, "Roundtrip failed for $type")
        }
    }

    @Test
    fun `roundtrip preserves nullable fields`() {
        val original = Attachment(
            id = "att-nullable",
            messageId = "msg-1",
            type = AttachmentType.IMAGE,
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            fileSize = 1024L,
            filePath = "/path/file.jpg",
            thumbnailPath = "/path/thumb.jpg",
            width = 1920,
            height = 1080,
            durationMs = null,
            createdAt = 5000L
        )

        val roundtripped = original.toEntity().toDomain()
        assertEquals(original, roundtripped)
    }

    @Test
    fun `toEntity stores type as string name`() {
        val imageAttachment = Attachment(
            id = "id", messageId = "msg", type = AttachmentType.IMAGE,
            fileName = "f", mimeType = "image/jpeg", fileSize = 0L,
            filePath = "/f", thumbnailPath = null, width = null, height = null,
            durationMs = null, createdAt = 0L
        )
        val videoAttachment = imageAttachment.copy(type = AttachmentType.VIDEO)
        val fileAttachment = imageAttachment.copy(type = AttachmentType.FILE)

        assertEquals("IMAGE", imageAttachment.toEntity().type)
        assertEquals("VIDEO", videoAttachment.toEntity().type)
        assertEquals("FILE", fileAttachment.toEntity().type)
    }
}
