package com.oneclaw.shadow.data.local.mapper

import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.TaskExecutionRecord
import com.oneclaw.shadow.data.local.entity.TaskExecutionRecordEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TaskExecutionRecordMapperTest {

    private fun createEntity(
        id: String = "record-1",
        taskId: String = "task-1",
        status: String = "SUCCESS",
        sessionId: String? = "session-1",
        startedAt: Long = 1000L,
        completedAt: Long? = 2000L,
        errorMessage: String? = null
    ) = TaskExecutionRecordEntity(
        id = id,
        taskId = taskId,
        status = status,
        sessionId = sessionId,
        startedAt = startedAt,
        completedAt = completedAt,
        errorMessage = errorMessage
    )

    private fun createDomain(
        id: String = "record-1",
        taskId: String = "task-1",
        status: ExecutionStatus = ExecutionStatus.SUCCESS,
        sessionId: String? = "session-1",
        startedAt: Long = 1000L,
        completedAt: Long? = 2000L,
        errorMessage: String? = null
    ) = TaskExecutionRecord(
        id = id,
        taskId = taskId,
        status = status,
        sessionId = sessionId,
        startedAt = startedAt,
        completedAt = completedAt,
        errorMessage = errorMessage
    )

    @Test
    fun `entity to domain maps all fields correctly`() {
        val entity = createEntity(
            id = "record-99",
            taskId = "task-42",
            status = "FAILED",
            sessionId = "session-abc",
            startedAt = 5000L,
            completedAt = 6000L,
            errorMessage = "Something went wrong"
        )

        val domain = entity.toDomain()

        assertEquals("record-99", domain.id)
        assertEquals("task-42", domain.taskId)
        assertEquals(ExecutionStatus.FAILED, domain.status)
        assertEquals("session-abc", domain.sessionId)
        assertEquals(5000L, domain.startedAt)
        assertEquals(6000L, domain.completedAt)
        assertEquals("Something went wrong", domain.errorMessage)
    }

    @Test
    fun `domain to entity maps all fields correctly`() {
        val domain = createDomain(
            id = "record-55",
            taskId = "task-7",
            status = ExecutionStatus.RUNNING,
            sessionId = null,
            startedAt = 3000L,
            completedAt = null,
            errorMessage = null
        )

        val entity = domain.toEntity()

        assertEquals("record-55", entity.id)
        assertEquals("task-7", entity.taskId)
        assertEquals("RUNNING", entity.status)
        assertNull(entity.sessionId)
        assertEquals(3000L, entity.startedAt)
        assertNull(entity.completedAt)
        assertNull(entity.errorMessage)
    }

    @Test
    fun `handles null optional fields correctly`() {
        val entity = createEntity(
            sessionId = null,
            completedAt = null,
            errorMessage = null
        )

        val domain = entity.toDomain()

        assertNull(domain.sessionId)
        assertNull(domain.completedAt)
        assertNull(domain.errorMessage)
    }

    @Test
    fun `maps all ExecutionStatus values`() {
        val runningEntity = createEntity(status = "RUNNING")
        val successEntity = createEntity(status = "SUCCESS")
        val failedEntity = createEntity(status = "FAILED")

        assertEquals(ExecutionStatus.RUNNING, runningEntity.toDomain().status)
        assertEquals(ExecutionStatus.SUCCESS, successEntity.toDomain().status)
        assertEquals(ExecutionStatus.FAILED, failedEntity.toDomain().status)
    }

    @Test
    fun `roundtrip domain to entity and back preserves data`() {
        val original = createDomain(
            id = "roundtrip-record",
            taskId = "roundtrip-task",
            status = ExecutionStatus.SUCCESS,
            sessionId = "session-round",
            startedAt = 7000L,
            completedAt = 8000L,
            errorMessage = null
        )

        val roundtripped = original.toEntity().toDomain()

        assertEquals(original, roundtripped)
    }
}
