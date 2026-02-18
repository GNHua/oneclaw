package com.tomandy.oneclaw.scheduler

import android.app.AlarmManager
import android.content.Context
import com.tomandy.oneclaw.scheduler.data.CronjobDao
import com.tomandy.oneclaw.scheduler.data.CronjobDatabase
import com.tomandy.oneclaw.scheduler.data.CronjobEntity
import com.tomandy.oneclaw.scheduler.data.ExecutionLogDao
import com.tomandy.oneclaw.scheduler.data.ScheduleType
import androidx.work.WorkManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CronjobManagerRescheduleTest {

    private lateinit var manager: CronjobManager
    private lateinit var mockContext: Context
    private lateinit var mockDatabase: CronjobDatabase
    private lateinit var mockDao: CronjobDao
    private lateinit var mockAlarmManager: AlarmManager

    @Before
    fun setup() {
        mockDao = mockk(relaxed = true)
        mockAlarmManager = mockk(relaxed = true)
        mockDatabase = mockk {
            every { cronjobDao() } returns mockDao
            every { executionLogDao() } returns mockk<ExecutionLogDao>(relaxed = true)
        }
        mockContext = mockk {
            every { getSystemService(Context.ALARM_SERVICE) } returns mockAlarmManager
            every { packageName } returns "com.tomandy.oneclaw"
        }

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockk(relaxed = true)

        manager = CronjobManager(mockContext, mockDatabase)
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
    }

    @Test
    fun `rescheduleAlarms returns 0 when no one-time tasks exist`() = runTest {
        coEvery { mockDao.getByType(ScheduleType.ONE_TIME) } returns emptyList()

        val count = manager.rescheduleAlarms()

        assertEquals(0, count)
    }

    @Test
    fun `rescheduleAlarms re-schedules future one-time tasks`() = runTest {
        val futureTask = CronjobEntity(
            id = "task-1",
            instruction = "send report",
            scheduleType = ScheduleType.ONE_TIME,
            executeAt = System.currentTimeMillis() + 3_600_000 // 1 hour from now
        )
        coEvery { mockDao.getByType(ScheduleType.ONE_TIME) } returns listOf(futureTask)

        val count = manager.rescheduleAlarms()

        assertEquals(1, count)
        // Verify alarm was set (setExactAndAllowWhileIdle or setAndAllowWhileIdle)
        verify { mockAlarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `rescheduleAlarms disables expired tasks`() = runTest {
        val expiredTask = CronjobEntity(
            id = "task-expired",
            instruction = "missed task",
            scheduleType = ScheduleType.ONE_TIME,
            executeAt = System.currentTimeMillis() - 60_000 // 1 minute ago
        )
        coEvery { mockDao.getByType(ScheduleType.ONE_TIME) } returns listOf(expiredTask)

        val count = manager.rescheduleAlarms()

        assertEquals(0, count)
        coVerify { mockDao.updateEnabled("task-expired", false) }
    }

    @Test
    fun `rescheduleAlarms skips tasks with null executeAt`() = runTest {
        val badTask = CronjobEntity(
            id = "task-null",
            instruction = "no time set",
            scheduleType = ScheduleType.ONE_TIME,
            executeAt = null
        )
        coEvery { mockDao.getByType(ScheduleType.ONE_TIME) } returns listOf(badTask)

        val count = manager.rescheduleAlarms()

        assertEquals(0, count)
        coVerify(exactly = 0) { mockDao.updateEnabled(any(), any()) }
    }

}
