package com.oneclaw.shadow.feature.file.usecase

import com.oneclaw.shadow.core.repository.FileRepository
import com.oneclaw.shadow.core.util.AppResult

class DeleteFileUseCase(private val fileRepository: FileRepository) {
    operator fun invoke(relativePath: String): AppResult<Unit> {
        return try {
            val success = fileRepository.deleteFile(relativePath)
            if (success) AppResult.Success(Unit)
            else AppResult.Error(message = "Failed to delete")
        } catch (e: Exception) {
            AppResult.Error(e, e.message ?: "Failed to delete")
        }
    }
}
