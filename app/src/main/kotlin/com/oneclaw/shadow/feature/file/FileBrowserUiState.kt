package com.oneclaw.shadow.feature.file

import com.oneclaw.shadow.core.model.FileInfo

data class PathSegment(val name: String, val relativePath: String)

data class FileBrowserUiState(
    val currentPath: String = "",
    val files: List<FileInfo> = emptyList(),
    val isLoading: Boolean = true,
    val totalSize: Long = 0,
    val errorMessage: String? = null
) {
    val pathSegments: List<PathSegment>
        get() {
            if (currentPath.isEmpty()) return listOf(PathSegment("Files", ""))
            val parts = currentPath.split("/").filter { it.isNotEmpty() }
            val segments = mutableListOf(PathSegment("Files", ""))
            var accumulated = ""
            for (part in parts) {
                accumulated = if (accumulated.isEmpty()) part else "$accumulated/$part"
                segments.add(PathSegment(part, accumulated))
            }
            return segments
        }
}
