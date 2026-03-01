package com.oneclaw.shadow.feature.usage

import com.oneclaw.shadow.data.local.dao.MessageDao
import com.oneclaw.shadow.data.local.dao.ModelUsageRow
import com.oneclaw.shadow.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.Calendar

@ExtendWith(MainDispatcherRule::class)
class UsageStatisticsViewModelTest {

    private lateinit var messageDao: MessageDao
    private lateinit var viewModel: UsageStatisticsViewModel

    @BeforeEach
    fun setup() {
        messageDao = mockk()
        coEvery { messageDao.getUsageStatsByModel(any()) } returns emptyList()
    }

    private fun createViewModel(): UsageStatisticsViewModel {
        return UsageStatisticsViewModel(messageDao)
    }

    // --- computeSinceTimestamp tests ---

    @Test
    fun `computeSinceTimestamp returns 0 for ALL_TIME`() {
        viewModel = createViewModel()
        assertEquals(0L, viewModel.computeSinceTimestamp(TimePeriod.ALL_TIME))
    }

    @Test
    fun `computeSinceTimestamp returns today midnight for TODAY`() {
        viewModel = createViewModel()
        val result = viewModel.computeSinceTimestamp(TimePeriod.TODAY)

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val expected = cal.timeInMillis

        assertEquals(expected, result)
    }

    @Test
    fun `computeSinceTimestamp result for TODAY is before current time`() {
        viewModel = createViewModel()
        val result = viewModel.computeSinceTimestamp(TimePeriod.TODAY)
        assertTrue(result <= System.currentTimeMillis())
    }

    @Test
    fun `computeSinceTimestamp returns Monday midnight for THIS_WEEK`() {
        viewModel = createViewModel()
        val result = viewModel.computeSinceTimestamp(TimePeriod.THIS_WEEK)

        val cal = Calendar.getInstance()
        cal.apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val expected = cal.timeInMillis

        assertEquals(expected, result)
    }

    @Test
    fun `computeSinceTimestamp returns first of month midnight for THIS_MONTH`() {
        viewModel = createViewModel()
        val result = viewModel.computeSinceTimestamp(TimePeriod.THIS_MONTH)

        val cal = Calendar.getInstance()
        cal.apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val expected = cal.timeInMillis

        assertEquals(expected, result)
    }

    // --- init and loadStats tests ---

    @Test
    fun `initial load calls getUsageStatsByModel with 0 for ALL_TIME`() = runTest {
        viewModel = createViewModel()
        coVerify { messageDao.getUsageStatsByModel(0L) }
    }

    @Test
    fun `empty query result produces empty modelStats`() = runTest {
        coEvery { messageDao.getUsageStatsByModel(any()) } returns emptyList()
        viewModel = createViewModel()

        assertTrue(viewModel.uiState.value.modelStats.isEmpty())
    }

    @Test
    fun `empty query result produces zero totals`() = runTest {
        coEvery { messageDao.getUsageStatsByModel(any()) } returns emptyList()
        viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertEquals(0L, state.totalInputTokens)
        assertEquals(0L, state.totalOutputTokens)
        assertEquals(0L, state.totalTokens)
        assertEquals(0, state.totalMessageCount)
    }

    @Test
    fun `multiple model rows are correctly mapped to ModelUsageStats`() = runTest {
        val rows = listOf(
            ModelUsageRow("gpt-4", 1000L, 500L, 3),
            ModelUsageRow("claude-3", 2000L, 800L, 5)
        )
        coEvery { messageDao.getUsageStatsByModel(0L) } returns rows
        viewModel = createViewModel()

        val stats = viewModel.uiState.value.modelStats
        assertEquals(2, stats.size)
        assertEquals("gpt-4", stats[0].modelId)
        assertEquals(1000L, stats[0].inputTokens)
        assertEquals(500L, stats[0].outputTokens)
        assertEquals(3, stats[0].messageCount)
        assertEquals("claude-3", stats[1].modelId)
    }

    @Test
    fun `totals are correctly computed from model stats`() = runTest {
        val rows = listOf(
            ModelUsageRow("gpt-4", 1000L, 500L, 3),
            ModelUsageRow("claude-3", 2000L, 800L, 5)
        )
        coEvery { messageDao.getUsageStatsByModel(0L) } returns rows
        viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertEquals(3000L, state.totalInputTokens)
        assertEquals(1300L, state.totalOutputTokens)
        assertEquals(4300L, state.totalTokens)
        assertEquals(8, state.totalMessageCount)
    }

    @Test
    fun `selectTimePeriod updates selectedPeriod`() = runTest {
        viewModel = createViewModel()
        viewModel.selectTimePeriod(TimePeriod.TODAY)

        assertEquals(TimePeriod.TODAY, viewModel.uiState.value.selectedPeriod)
    }

    @Test
    fun `selectTimePeriod triggers reload with correct timestamp`() = runTest {
        viewModel = createViewModel()
        viewModel.selectTimePeriod(TimePeriod.ALL_TIME)

        coVerify(atLeast = 1) { messageDao.getUsageStatsByModel(0L) }
    }

    @Test
    fun `isLoading is false after data is loaded`() = runTest {
        coEvery { messageDao.getUsageStatsByModel(any()) } returns emptyList()
        viewModel = createViewModel()

        assertFalse(viewModel.uiState.value.isLoading)
    }
}
