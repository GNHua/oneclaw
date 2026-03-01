package com.oneclaw.shadow.feature.file

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.repository.FileRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.file.usecase.DeleteFileUseCase
import com.oneclaw.shadow.feature.file.usecase.ReadFileContentUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class FilePreviewViewModel(
    savedStateHandle: SavedStateHandle,
    private val readFileContentUseCase: ReadFileContentUseCase,
    private val deleteFileUseCase: DeleteFileUseCase,
    private val fileRepository: FileRepository
) : ViewModel() {

    private val encodedPath: String = savedStateHandle["path"] ?: ""
    val relativePath: String = Uri.decode(encodedPath)

    private val _uiState = MutableStateFlow(FilePreviewUiState())
    val uiState: StateFlow<FilePreviewUiState> = _uiState.asStateFlow()

    init {
        loadFileContent()
    }

    fun deleteFile() {
        viewModelScope.launch {
            when (deleteFileUseCase(relativePath)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isDeleted = true) }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = "Failed to delete file") }
                }
            }
        }
    }

    fun getShareFile(): File = fileRepository.getFileForSharing(relativePath)

    fun getAbsolutePath(): String = fileRepository.getFileForSharing(relativePath).absolutePath

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun loadFileContent() {
        viewModelScope.launch {
            when (val result = readFileContentUseCase(relativePath)) {
                is AppResult.Success -> {
                    val parentPath = File(relativePath).parent ?: ""
                    val fileInfoList = fileRepository.listFiles(parentPath)
                    val fileInfo = fileInfoList.find { it.relativePath == relativePath }
                    _uiState.update {
                        it.copy(
                            fileName = File(relativePath).name,
                            fileContent = result.data,
                            fileInfo = fileInfo,
                            isLoading = false
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
