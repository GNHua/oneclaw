package com.oneclaw.shadow.feature.schedule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.core.repository.TaskExecutionRecordRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.schedule.usecase.DeleteScheduledTaskUseCase
import com.oneclaw.shadow.feature.schedule.usecase.RunScheduledTaskNowUseCase
import com.oneclaw.shadow.feature.schedule.usecase.ToggleScheduledTaskUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScheduledTaskDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val executionRecordRepository: TaskExecutionRecordRepository,
    private val agentRepository: AgentRepository,
    private val toggleUseCase: ToggleScheduledTaskUseCase,
    private val deleteUseCase: DeleteScheduledTaskUseCase,
    private val runNowUseCase: RunScheduledTaskNowUseCase
) : ViewModel() {

    private val taskId: String = savedStateHandle["taskId"] ?: ""

    private val _uiState = MutableStateFlow(ScheduledTaskDetailUiState())
    val uiState: StateFlow<ScheduledTaskDetailUiState> = _uiState.asStateFlow()

    init {
        loadTask()
        loadExecutionHistory()
    }

    private fun loadTask() {
        viewModelScope.launch {
            val task = scheduledTaskRepository.getTaskById(taskId)
            val agentName = if (task != null) {
                agentRepository.getAgentById(task.agentId)?.name ?: ""
            } else {
                ""
            }
            _uiState.value = _uiState.value.copy(
                task = task,
                agentName = agentName,
                isLoading = false
            )
        }
    }

    private fun loadExecutionHistory() {
        viewModelScope.launch {
            executionRecordRepository.getRecordsByTaskId(taskId).collect { records ->
                _uiState.value = _uiState.value.copy(executionHistory = records)
            }
        }
    }

    fun toggleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            toggleUseCase(taskId, enabled)
            val updatedTask = scheduledTaskRepository.getTaskById(taskId)
            _uiState.value = _uiState.value.copy(task = updatedTask)
        }
    }

    fun deleteTask() {
        viewModelScope.launch {
            deleteUseCase(taskId)
            _uiState.value = _uiState.value.copy(isDeleted = true)
        }
    }

    fun runNow() {
        if (_uiState.value.isRunningNow) return
        _uiState.value = _uiState.value.copy(isRunningNow = true, errorMessage = null)
        viewModelScope.launch {
            val result = runNowUseCase(taskId)
            when (result) {
                is AppResult.Success -> {
                    val updatedTask = scheduledTaskRepository.getTaskById(taskId)
                    _uiState.value = _uiState.value.copy(
                        isRunningNow = false,
                        task = updatedTask
                    )
                }
                is AppResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isRunningNow = false,
                        errorMessage = result.message
                    )
                }
            }
        }
    }
}
