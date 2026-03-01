package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.ScheduledTask
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class ListScheduledTasksToolTest {

    private lateinit var repository: ScheduledTaskRepository
    private lateinit var tool: ListScheduledTasksTool

    @BeforeEach
    fun setup() {
        repository = mockk()
        tool = ListScheduledTasksTool(repository)
    }

    private fun createTask(
        id: String = "task-1",
        name: String = "Morning Briefing",
        scheduleType: ScheduleType = ScheduleType.DAILY,
        hour: Int = 7,
        minute: Int = 0,
        dayOfWeek: Int? = null,
        dateMillis: Long? = null,
        isEnabled: Boolean = true,
        lastExecutionAt: Long? = null,
        lastExecutionStatus: ExecutionStatus? = null,
        nextTriggerAt: Long? = null
    ) = ScheduledTask(
        id = id,
        name = name,
        agentId = "agent-1",
        prompt = "Do something",
        scheduleType = scheduleType,
        hour = hour,
        minute = minute,
        dayOfWeek = dayOfWeek,
        dateMillis = dateMillis,
        isEnabled = isEnabled,
        lastExecutionAt = lastExecutionAt,
        lastExecutionStatus = lastExecutionStatus,
        lastExecutionSessionId = null,
        nextTriggerAt = nextTriggerAt,
        createdAt = 100L,
        updatedAt = 100L
    )

    @Test
    fun `empty list returns no scheduled tasks message`() = runTest {
        every { repository.getAllTasks() } returns flowOf(emptyList())

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("No scheduled tasks configured.", result.result)
    }

    @Test
    fun `single task returns formatted output with id and name`() = runTest {
        val task = createTask()
        every { repository.getAllTasks() } returns flowOf(listOf(task))

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("task-1"))
        assertTrue(result.result!!.contains("Morning Briefing"))
    }

    @Test
    fun `task list shows correct count`() = runTest {
        val tasks = listOf(createTask(id = "t1", name = "Task A"), createTask(id = "t2", name = "Task B"))
        every { repository.getAllTasks() } returns flowOf(tasks)

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Found 2 scheduled tasks"))
    }

    @Test
    fun `singular count for one task`() = runTest {
        every { repository.getAllTasks() } returns flowOf(listOf(createTask()))

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Found 1 scheduled task"))
    }

    @Test
    fun `daily task shows correct schedule description`() = runTest {
        val task = createTask(scheduleType = ScheduleType.DAILY, hour = 7, minute = 30)
        every { repository.getAllTasks() } returns flowOf(listOf(task))

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Daily at 07:30"))
    }

    @Test
    fun `weekly task shows correct schedule description`() = runTest {
        // DayOfWeek.MONDAY = 1
        val task = createTask(scheduleType = ScheduleType.WEEKLY, hour = 9, minute = 0, dayOfWeek = 1)
        every { repository.getAllTasks() } returns flowOf(listOf(task))

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Every Monday at 09:00"))
    }

    @Test
    fun `one_time task shows correct schedule description`() = runTest {
        val dateMillis = LocalDate.of(2026, 3, 15)
            .atTime(10, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val task = createTask(
            scheduleType = ScheduleType.ONE_TIME,
            hour = 10,
            minute = 30,
            dateMillis = dateMillis
        )
        every { repository.getAllTasks() } returns flowOf(listOf(task))

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("2026-03-15"))
    }

    @Test
    fun `task with no execution shows Never for last execution`() = runTest {
        val task = createTask(lastExecutionAt = null, lastExecutionStatus = null)
        every { repository.getAllTasks() } returns flowOf(listOf(task))

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Never"))
    }

    @Test
    fun `task with last execution shows timestamp and status`() = runTest {
        val execTime = 1740787200000L // some timestamp
        val task = createTask(lastExecutionAt = execTime, lastExecutionStatus = ExecutionStatus.SUCCESS)
        every { repository.getAllTasks() } returns flowOf(listOf(task))

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("SUCCESS"))
    }

    @Test
    fun `task with no next trigger shows None`() = runTest {
        val task = createTask(nextTriggerAt = null)
        every { repository.getAllTasks() } returns flowOf(listOf(task))

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("None"))
    }

    @Test
    fun `disabled task shows enabled false`() = runTest {
        val task = createTask(isEnabled = false)
        every { repository.getAllTasks() } returns flowOf(listOf(task))

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Enabled: false"))
    }
}
