package com.oneclaw.shadow.data.repository

import com.oneclaw.shadow.core.model.Attachment
import com.oneclaw.shadow.core.model.AttachmentType
import com.oneclaw.shadow.data.local.dao.AttachmentDao
import com.oneclaw.shadow.data.local.entity.AttachmentEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AttachmentRepositoryImplTest {

    private lateinit var dao: AttachmentDao
    private lateinit var repository: AttachmentRepositoryImpl

    private fun makeEntity(
        id: String = "att-1",
        messageId: String = "msg-1",
        type: String = "IMAGE"
    ) = AttachmentEntity(
        id = id,
        messageId = messageId,
        type = type,
        fileName = "file.jpg",
        mimeType = "image/jpeg",
        fileSize = 1024L,
        filePath = "/data/file.jpg",
        thumbnailPath = null,
        width = 800,
        height = 600,
        durationMs = null,
        createdAt = 1000L
    )

    private fun makeDomain(
        id: String = "att-1",
        messageId: String = "msg-1",
        type: AttachmentType = AttachmentType.IMAGE
    ) = Attachment(
        id = id,
        messageId = messageId,
        type = type,
        fileName = "file.jpg",
        mimeType = "image/jpeg",
        fileSize = 1024L,
        filePath = "/data/file.jpg",
        thumbnailPath = null,
        width = 800,
        height = 600,
        durationMs = null,
        createdAt = 1000L
    )

    @BeforeEach
    fun setup() {
        dao = mockk()
        repository = AttachmentRepositoryImpl(dao)
    }

    @Test
    fun `getAttachmentsForMessage returns mapped domain objects`() = runTest {
        val entities = listOf(makeEntity("att-1"), makeEntity("att-2"))
        every { dao.getAttachmentsForMessage("msg-1") } returns flowOf(entities)

        val result = repository.getAttachmentsForMessage("msg-1").first()

        assertEquals(2, result.size)
        assertEquals("att-1", result[0].id)
        assertEquals("att-2", result[1].id)
        assertEquals(AttachmentType.IMAGE, result[0].type)
    }

    @Test
    fun `getAttachmentsForMessage returns empty list when no attachments`() = runTest {
        every { dao.getAttachmentsForMessage("msg-empty") } returns flowOf(emptyList())

        val result = repository.getAttachmentsForMessage("msg-empty").first()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getAttachmentsForMessages returns mapped domain objects`() = runTest {
        val entities = listOf(makeEntity("att-1", "msg-1"), makeEntity("att-2", "msg-2"))
        coEvery { dao.getAttachmentsForMessages(listOf("msg-1", "msg-2")) } returns entities

        val result = repository.getAttachmentsForMessages(listOf("msg-1", "msg-2"))

        assertEquals(2, result.size)
        assertEquals("att-1", result[0].id)
        assertEquals("att-2", result[1].id)
    }

    @Test
    fun `getAttachmentsForMessages returns empty list for empty input`() = runTest {
        val result = repository.getAttachmentsForMessages(emptyList())

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { dao.getAttachmentsForMessages(any()) }
    }

    @Test
    fun `addAttachment inserts entity via dao`() = runTest {
        val entitySlot = slot<AttachmentEntity>()
        coEvery { dao.insertAttachment(capture(entitySlot)) } returns Unit

        repository.addAttachment(makeDomain("att-new"))

        coVerify { dao.insertAttachment(any()) }
        assertEquals("att-new", entitySlot.captured.id)
        assertEquals("IMAGE", entitySlot.captured.type)
    }

    @Test
    fun `addAttachments inserts all entities`() = runTest {
        val slotList = slot<List<AttachmentEntity>>()
        coEvery { dao.insertAttachments(capture(slotList)) } returns Unit

        val attachments = listOf(
            makeDomain("att-1"),
            makeDomain("att-2", type = AttachmentType.VIDEO),
            makeDomain("att-3", type = AttachmentType.FILE)
        )
        repository.addAttachments(attachments)

        coVerify { dao.insertAttachments(any()) }
        assertEquals(3, slotList.captured.size)
        assertEquals("att-1", slotList.captured[0].id)
        assertEquals("att-2", slotList.captured[1].id)
        assertEquals("att-3", slotList.captured[2].id)
        assertEquals("VIDEO", slotList.captured[1].type)
        assertEquals("FILE", slotList.captured[2].type)
    }

    @Test
    fun `deleteAttachment calls deleteAttachmentById`() = runTest {
        coEvery { dao.deleteAttachmentById("att-delete") } returns Unit

        repository.deleteAttachment("att-delete")

        coVerify { dao.deleteAttachmentById("att-delete") }
    }

    @Test
    fun `deleteAttachmentsForMessage calls dao correctly`() = runTest {
        coEvery { dao.deleteAttachmentsForMessage("msg-1") } returns Unit

        repository.deleteAttachmentsForMessage("msg-1")

        coVerify { dao.deleteAttachmentsForMessage("msg-1") }
    }

    @Test
    fun `getAttachmentsForMessages handles multiple message ids and groups correctly`() = runTest {
        val entities = listOf(
            makeEntity("att-1", "msg-1"),
            makeEntity("att-2", "msg-1"),
            makeEntity("att-3", "msg-2")
        )
        coEvery { dao.getAttachmentsForMessages(any()) } returns entities

        val result = repository.getAttachmentsForMessages(listOf("msg-1", "msg-2"))

        assertEquals(3, result.size)
        val byMessage = result.groupBy { it.messageId }
        assertEquals(2, byMessage["msg-1"]?.size)
        assertEquals(1, byMessage["msg-2"]?.size)
    }

    @Test
    fun `addAttachments with empty list calls dao with empty list`() = runTest {
        val slotList = slot<List<AttachmentEntity>>()
        coEvery { dao.insertAttachments(capture(slotList)) } returns Unit

        repository.addAttachments(emptyList())

        coVerify { dao.insertAttachments(any()) }
        assertTrue(slotList.captured.isEmpty())
    }
}
