package com.oneclaw.shadow.feature.schedule.usecase

import com.oneclaw.shadow.core.repository.TaskExecutionRecordRepository

class CleanupExecutionHistoryUseCase(
    private val executionRecordRepository: TaskExecutionRecordRepository
) {
    suspend operator fun invoke(retentionDays: Int = 90) {
        executionRecordRepository.cleanupOlderThan(retentionDays)
    }
}
