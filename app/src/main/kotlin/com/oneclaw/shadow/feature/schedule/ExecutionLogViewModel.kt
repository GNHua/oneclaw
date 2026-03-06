package com.oneclaw.shadow.feature.schedule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.TaskExecutionRecord
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.core.repository.TaskExecutionRecordRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExecutionLogUiState(
    val record: TaskExecutionRecord? = null,
    val taskName: String = "",
    val isLoading: Boolean = true
)

class ExecutionLogViewModel(
    savedStateHandle: SavedStateHandle,
    private val executionRecordRepository: TaskExecutionRecordRepository,
    private val scheduledTaskRepository: ScheduledTaskRepository
) : ViewModel() {

    private val recordId: String = savedStateHandle["recordId"] ?: ""

    private val _uiState = MutableStateFlow(ExecutionLogUiState())
    val uiState: StateFlow<ExecutionLogUiState> = _uiState.asStateFlow()

    init {
        loadRecord()
    }

    private fun loadRecord() {
        viewModelScope.launch {
            val record = executionRecordRepository.getRecordById(recordId)
            val taskName = if (record != null) {
                scheduledTaskRepository.getTaskById(record.taskId)?.name ?: ""
            } else {
                ""
            }
            _uiState.value = ExecutionLogUiState(
                record = record,
                taskName = taskName,
                isLoading = false
            )
        }
    }
}
