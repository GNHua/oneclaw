package com.oneclaw.shadow.feature.file

import com.oneclaw.shadow.core.model.FileContent
import com.oneclaw.shadow.core.model.FileInfo

data class FilePreviewUiState(
    val fileName: String = "",
    val fileInfo: FileInfo? = null,
    val fileContent: FileContent? = null,
    val isLoading: Boolean = true,
    val isDeleted: Boolean = false,
    val errorMessage: String? = null
)
