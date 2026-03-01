package com.oneclaw.shadow.core.model

/**
 * Represents a file or directory entry in the user_files storage.
 */
data class FileInfo(
    val name: String,
    val absolutePath: String,
    val relativePath: String,    // relative to user_files root
    val isDirectory: Boolean,
    val size: Long,              // 0 for directories
    val lastModified: Long,      // epoch millis
    val mimeType: String?,       // null for directories
    val childCount: Int = 0      // number of items inside, for directories only
)
