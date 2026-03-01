package com.oneclaw.shadow.feature.file.usecase

import com.oneclaw.shadow.core.model.FileInfo
import com.oneclaw.shadow.core.repository.FileRepository
import com.oneclaw.shadow.core.util.AppResult

class ListFilesUseCase(private val fileRepository: FileRepository) {
    operator fun invoke(relativePath: String = ""): AppResult<List<FileInfo>> {
        return try {
            AppResult.Success(fileRepository.listFiles(relativePath))
        } catch (e: Exception) {
            AppResult.Error(e, e.message ?: "Failed to list files")
        }
    }
}
