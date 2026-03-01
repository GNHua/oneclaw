package com.oneclaw.shadow.data.repository

import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.TaskExecutionRecord
import com.oneclaw.shadow.data.local.dao.TaskExecutionRecordDao
import com.oneclaw.shadow.data.local.entity.TaskExecutionRecordEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TaskExecutionRecordRepositoryImplTest {

    private lateinit var dao: TaskExecutionRecordDao
    private lateinit var repository: TaskExecutionRecordRepositoryImpl

    private val entity = TaskExecutionRecordEntity(
        id = "record-1",
        taskId = "task-1",
        status = "SUCCESS",
        sessionId = "session-1",
        startedAt = 1000L,
        completedAt = 2000L,
        errorMessage = null
    )

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true)
        repository = TaskExecutionRecordRepositoryImpl(dao)
    }

    @Test
    fun `getRecordsByTaskId returns mapped domain models in descending order`() = runTest {
        val entity2 = entity.copy(id = "record-2", startedAt = 3000L)
        // DAO returns in descending order (as specified by the query)
        every { dao.getRecordsByTaskId("task-1", 50) } returns flowOf(listOf(entity2, entity))

        val records = repository.getRecordsByTaskId("task-1").first()

        assertEquals(2, records.size)
        assertEquals("record-2", records[0].id)
        assertEquals("record-1", records[1].id)
        assertEquals(3000L, records[0].startedAt)
        assertEquals(1000L, records[1].startedAt)
    }

    @Test
    fun `createRecord inserts entity into DAO`() = runTest {
        val entitySlot = slot<TaskExecutionRecordEntity>()
        coEvery { dao.insert(capture(entitySlot)) } returns Unit

        val record = TaskExecutionRecord(
            id = "record-new",
            taskId = "task-1",
            status = ExecutionStatus.RUNNING,
            sessionId = null,
            startedAt = 5000L,
            completedAt = null,
            errorMessage = null
        )

        repository.createRecord(record)

        coVerify { dao.insert(any()) }
        assertEquals("record-new", entitySlot.captured.id)
        assertEquals("task-1", entitySlot.captured.taskId)
        assertEquals("RUNNING", entitySlot.captured.status)
    }

    @Test
    fun `updateResult calls DAO with correct parameters`() = runTest {
        repository.updateResult(
            id = "record-1",
            status = ExecutionStatus.SUCCESS,
            completedAt = 9000L,
            sessionId = "session-new",
            errorMessage = null
        )

        coVerify {
            dao.updateResult(
                id = "record-1",
                status = "SUCCESS",
                completedAt = 9000L,
                sessionId = "session-new",
                errorMessage = null
            )
        }
    }

    @Test
    fun `updateResult with failure passes error message`() = runTest {
        repository.updateResult(
            id = "record-fail",
            status = ExecutionStatus.FAILED,
            completedAt = 7000L,
            sessionId = null,
            errorMessage = "Connection timed out"
        )

        coVerify {
            dao.updateResult(
                id = "record-fail",
                status = "FAILED",
                completedAt = 7000L,
                sessionId = null,
                errorMessage = "Connection timed out"
            )
        }
    }

    @Test
    fun `deleteByTaskId calls DAO deleteByTaskId`() = runTest {
        repository.deleteByTaskId("task-1")

        coVerify { dao.deleteByTaskId("task-1") }
    }

    @Test
    fun `cleanupOlderThan calculates cutoff millis correctly`() = runTest {
        val cutoffSlot = slot<Long>()
        coEvery { dao.deleteOlderThan(capture(cutoffSlot)) } returns Unit

        val beforeCall = System.currentTimeMillis()
        repository.cleanupOlderThan(90)
        val afterCall = System.currentTimeMillis()

        val expectedMinCutoff = beforeCall - (90 * 24L * 60 * 60 * 1000)
        val expectedMaxCutoff = afterCall - (90 * 24L * 60 * 60 * 1000)

        assert(cutoffSlot.captured >= expectedMinCutoff) {
            "Cutoff ${cutoffSlot.captured} should be >= $expectedMinCutoff"
        }
        assert(cutoffSlot.captured <= expectedMaxCutoff) {
            "Cutoff ${cutoffSlot.captured} should be <= $expectedMaxCutoff"
        }
    }

    @Test
    fun `getRecordsByTaskId respects custom limit`() = runTest {
        every { dao.getRecordsByTaskId("task-1", 10) } returns flowOf(emptyList())

        repository.getRecordsByTaskId("task-1", 10).first()

        coVerify { dao.getRecordsByTaskId("task-1", 10) }
    }
}
