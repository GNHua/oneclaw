package com.oneclaw.shadow.feature.schedule

import com.oneclaw.shadow.core.model.Agent
import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.ScheduledTask
import com.oneclaw.shadow.core.model.TaskExecutionRecord

data class ScheduledTaskListUiState(
    val tasks: List<ScheduledTask> = emptyList(),
    val isLoading: Boolean = true
)

data class ScheduledTaskEditUiState(
    val name: String = "",
    val agentId: String = "",
    val prompt: String = "",
    val scheduleType: ScheduleType = ScheduleType.DAILY,
    val hour: Int = 8,
    val minute: Int = 0,
    val dayOfWeek: Int = 1,
    val dateMillis: Long? = null,
    val agents: List<Agent> = emptyList(),
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val savedSuccessfully: Boolean = false
)

data class ScheduledTaskDetailUiState(
    val task: ScheduledTask? = null,
    val agentName: String = "",
    val executionHistory: List<TaskExecutionRecord> = emptyList(),
    val isLoading: Boolean = true,
    val isRunningNow: Boolean = false,
    val isDeleted: Boolean = false,
    val errorMessage: String? = null
)
