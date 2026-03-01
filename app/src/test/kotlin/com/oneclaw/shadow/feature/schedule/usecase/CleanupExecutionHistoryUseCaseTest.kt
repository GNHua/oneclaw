package com.oneclaw.shadow.feature.schedule.usecase

import com.oneclaw.shadow.core.repository.TaskExecutionRecordRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CleanupExecutionHistoryUseCaseTest {

    private lateinit var repository: TaskExecutionRecordRepository
    private lateinit var useCase: CleanupExecutionHistoryUseCase

    @BeforeEach
    fun setup() {
        repository = mockk(relaxed = true)
        useCase = CleanupExecutionHistoryUseCase(repository)
    }

    @Test
    fun `invokes repository cleanupOlderThan with default 90 days`() = runTest {
        useCase()

        coVerify { repository.cleanupOlderThan(90) }
    }

    @Test
    fun `invokes repository cleanupOlderThan with custom retention days`() = runTest {
        useCase(30)

        coVerify { repository.cleanupOlderThan(30) }
    }
}
