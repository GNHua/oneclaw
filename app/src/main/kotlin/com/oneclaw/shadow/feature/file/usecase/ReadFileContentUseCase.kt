package com.oneclaw.shadow.feature.file.usecase

import com.oneclaw.shadow.core.model.FileContent
import com.oneclaw.shadow.core.repository.FileRepository
import com.oneclaw.shadow.core.util.AppResult

class ReadFileContentUseCase(private val fileRepository: FileRepository) {
    operator fun invoke(relativePath: String): AppResult<FileContent> {
        return try {
            AppResult.Success(fileRepository.readFileContent(relativePath))
        } catch (e: Exception) {
            AppResult.Error(e, e.message ?: "Failed to read file")
        }
    }
}
