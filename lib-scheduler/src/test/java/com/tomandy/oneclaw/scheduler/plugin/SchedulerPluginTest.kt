package com.tomandy.oneclaw.scheduler.plugin

import com.tomandy.oneclaw.engine.ToolResult
import com.tomandy.oneclaw.scheduler.CronjobManager
import com.tomandy.oneclaw.scheduler.data.CronjobEntity
import com.tomandy.oneclaw.scheduler.data.ScheduleType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SchedulerPluginTest {

    private lateinit var plugin: SchedulerPlugin
    private lateinit var mockManager: CronjobManager

    private fun args(vararg pairs: Pair<String, Any>): JsonObject {
        val map = pairs.associate { (key, value) ->
            key to when (value) {
                is String -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        }
        return JsonObject(map)
    }

    @Before
    fun setup() {
        mockManager = mockk(relaxed = true)
        plugin = SchedulerPlugin()
        // Inject mock CronjobManager via reflection (bypassing onLoad which needs Android Context)
        val field = SchedulerPlugin::class.java.getDeclaredField("cronjobManager")
        field.isAccessible = true
        field.set(plugin, mockManager)
    }

    @Test
    fun `unknown tool name returns Failure`() = runTest {
        val result = plugin.execute("nonexistent", args())
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Unknown tool"))
    }

    // --- schedule_task ---

    @Test
    fun `schedule_task missing instruction returns Failure`() = runTest {
        val result = plugin.execute("schedule_task", args("schedule_type" to "one_time"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("instruction"))
    }

    @Test
    fun `schedule_task missing schedule_type returns Failure`() = runTest {
        val result = plugin.execute("schedule_task", args("instruction" to "do something"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("schedule_type"))
    }

    @Test
    fun `schedule_task invalid schedule_type returns Failure`() = runTest {
        val result = plugin.execute("schedule_task", args(
            "instruction" to "do something",
            "schedule_type" to "invalid"
        ))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Invalid schedule_type"))
    }

    @Test
    fun `schedule_task one_time missing execute_at returns Failure`() = runTest {
        val result = plugin.execute("schedule_task", args(
            "instruction" to "do something",
            "schedule_type" to "one_time"
        ))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("execute_at"))
    }

    @Test
    fun `schedule_task one_time invalid datetime format returns Failure`() = runTest {
        val result = plugin.execute("schedule_task", args(
            "instruction" to "do something",
            "schedule_type" to "one_time",
            "execute_at" to "not-a-date"
        ))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Invalid execute_at format"))
    }

    @Test
    fun `schedule_task recurring without interval or cron returns Failure`() = runTest {
        val result = plugin.execute("schedule_task", args(
            "instruction" to "do something",
            "schedule_type" to "recurring"
        ))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("interval_minutes"))
    }

    @Test
    fun `schedule_task recurring interval below 15 returns Failure`() = runTest {
        val result = plugin.execute("schedule_task", args(
            "instruction" to "do something",
            "schedule_type" to "recurring",
            "interval_minutes" to 5
        ))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Minimum interval"))
    }

    @Test
    fun `schedule_task valid one_time schedules and returns Success`() = runTest {
        val futureTime = LocalDateTime.now().plusHours(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val entitySlot = slot<CronjobEntity>()
        coEvery { mockManager.schedule(capture(entitySlot)) } returns "job-123"

        val result = plugin.execute("schedule_task", args(
            "instruction" to "send report",
            "schedule_type" to "one_time",
            "execute_at" to futureTime,
            "title" to "Report Task"
        ))

        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("job-123"))
        assertTrue(output.contains("send report"))
        assertEquals(ScheduleType.ONE_TIME, entitySlot.captured.scheduleType)
        assertEquals("send report", entitySlot.captured.instruction)
    }

    @Test
    fun `schedule_task valid recurring with interval returns Success`() = runTest {
        val entitySlot = slot<CronjobEntity>()
        coEvery { mockManager.schedule(capture(entitySlot)) } returns "job-456"

        val result = plugin.execute("schedule_task", args(
            "instruction" to "check email",
            "schedule_type" to "recurring",
            "interval_minutes" to 30
        ))

        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("job-456"))
        assertEquals(ScheduleType.RECURRING, entitySlot.captured.scheduleType)
        assertEquals(30, entitySlot.captured.intervalMinutes)
    }

    @Test
    fun `schedule_task valid recurring with cron_expression returns Success`() = runTest {
        coEvery { mockManager.schedule(any()) } returns "job-789"

        val result = plugin.execute("schedule_task", args(
            "instruction" to "daily backup",
            "schedule_type" to "recurring",
            "cron_expression" to "0 9 * * *"
        ))

        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("job-789"))
    }

    // --- list_scheduled_tasks ---

    @Test
    fun `list_scheduled_tasks empty returns no tasks message`() = runTest {
        coEvery { mockManager.getAllEnabledSnapshot() } returns emptyList()

        val result = plugin.execute("list_scheduled_tasks", args())

        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("No scheduled tasks found"))
    }

    @Test
    fun `list_scheduled_tasks returns formatted task list`() = runTest {
        val task = CronjobEntity(
            id = "task-1",
            title = "My Task",
            instruction = "do something",
            scheduleType = ScheduleType.RECURRING,
            intervalMinutes = 60,
            enabled = true,
            executionCount = 3
        )
        coEvery { mockManager.getAllEnabledSnapshot() } returns listOf(task)

        val result = plugin.execute("list_scheduled_tasks", args())

        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("task-1"))
        assertTrue(output.contains("My Task"))
        assertTrue(output.contains("do something"))
    }

    // --- cancel_scheduled_task ---

    @Test
    fun `cancel_scheduled_task missing task_id returns Failure`() = runTest {
        val result = plugin.execute("cancel_scheduled_task", args())
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("task_id"))
    }

    @Test
    fun `cancel_scheduled_task valid ID cancels and returns Success`() = runTest {
        coEvery { mockManager.cancel("task-1") } returns Unit

        val result = plugin.execute("cancel_scheduled_task", args("task_id" to "task-1"))

        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("task-1"))
        coVerify { mockManager.cancel("task-1") }
    }
}
