package com.oneclaw.shadow.feature.file

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.model.FileInfo
import com.oneclaw.shadow.core.repository.FileRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.file.usecase.DeleteFileUseCase
import com.oneclaw.shadow.feature.file.usecase.ListFilesUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class FileBrowserViewModel(
    private val listFilesUseCase: ListFilesUseCase,
    private val deleteFileUseCase: DeleteFileUseCase,
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    init {
        loadFiles()
    }

    fun navigateTo(relativePath: String) {
        _uiState.update { it.copy(currentPath = relativePath) }
        loadFiles()
    }

    fun navigateUp() {
        val parent = File(_uiState.value.currentPath).parent ?: ""
        navigateTo(parent)
    }

    fun deleteFile(fileInfo: FileInfo) {
        viewModelScope.launch {
            when (deleteFileUseCase(fileInfo.relativePath)) {
                is AppResult.Success -> loadFiles()
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = "Failed to delete") }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun getFileForSharing(relativePath: String): File =
        fileRepository.getFileForSharing(relativePath)

    fun refresh() {
        loadFiles()
    }

    private fun loadFiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = listFilesUseCase(_uiState.value.currentPath)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            files = result.data,
                            isLoading = false,
                            totalSize = fileRepository.getTotalSize(),
                            errorMessage = null
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = result.message)
                    }
                }
            }
        }
    }
}
