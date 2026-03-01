package com.oneclaw.shadow.feature.memory.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.feature.memory.MemoryManager
import com.oneclaw.shadow.feature.memory.longterm.LongTermMemoryManager
import com.oneclaw.shadow.feature.memory.model.MemoryStats
import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoryUiState(
    val isLoading: Boolean = false,
    val memoryContent: String = "",
    val dailyLogDates: List<String> = emptyList(),
    val selectedDailyLogDate: String? = null,
    val selectedDailyLogContent: String? = null,
    val stats: MemoryStats? = null,
    val isEditingMemory: Boolean = false,
    val editingContent: String = "",
    val isRebuildingIndex: Boolean = false,
    val rebuildSuccess: Boolean? = null,
    val errorMessage: String? = null
)

class MemoryViewModel(
    private val memoryManager: MemoryManager,
    private val longTermMemoryManager: LongTermMemoryManager,
    private val memoryFileStorage: MemoryFileStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val content = longTermMemoryManager.readMemory()
                val dates = memoryFileStorage.listDailyLogDates()
                val stats = memoryManager.getStats()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        memoryContent = content,
                        dailyLogDates = dates,
                        stats = stats,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message)
                }
            }
        }
    }

    fun startEditingMemory() {
        _uiState.update {
            it.copy(isEditingMemory = true, editingContent = it.memoryContent)
        }
    }

    fun onEditContentChanged(content: String) {
        _uiState.update { it.copy(editingContent = content) }
    }

    fun saveMemory() {
        viewModelScope.launch {
            try {
                val content = _uiState.value.editingContent
                longTermMemoryManager.writeMemory(content)
                _uiState.update {
                    it.copy(
                        isEditingMemory = false,
                        memoryContent = content,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(isEditingMemory = false) }
    }

    fun selectDailyLog(date: String) {
        viewModelScope.launch {
            val content = memoryFileStorage.readDailyLog(date)
            _uiState.update {
                it.copy(selectedDailyLogDate = date, selectedDailyLogContent = content)
            }
        }
    }

    fun clearSelectedDailyLog() {
        _uiState.update { it.copy(selectedDailyLogDate = null, selectedDailyLogContent = null) }
    }

    fun rebuildIndex() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRebuildingIndex = true, rebuildSuccess = null) }
            try {
                memoryManager.rebuildIndex()
                val stats = memoryManager.getStats()
                _uiState.update {
                    it.copy(
                        isRebuildingIndex = false,
                        rebuildSuccess = true,
                        stats = stats
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRebuildingIndex = false,
                        rebuildSuccess = false,
                        errorMessage = e.message
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
