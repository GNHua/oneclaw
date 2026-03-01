package com.oneclaw.shadow.feature.schedule.usecase

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.feature.schedule.worker.ScheduledTaskWorker

class RunScheduledTaskUseCase(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val workManager: WorkManager
) {
    suspend operator fun invoke(taskId: String): AppResult<String> {
        val task = scheduledTaskRepository.getTaskById(taskId)
            ?: return AppResult.Error(
                message = "Task not found with ID '$taskId'.",
                code = ErrorCode.NOT_FOUND
            )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    ScheduledTaskWorker.KEY_TASK_ID to taskId,
                    ScheduledTaskWorker.KEY_MANUAL_RUN to true
                )
            )
            .build()

        workManager.enqueueUniqueWork(
            "scheduled_task_manual_$taskId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        return AppResult.Success(task.name)
    }
}
