package com.oneclaw.shadow.core.repository

import com.oneclaw.shadow.core.model.FileContent
import com.oneclaw.shadow.core.model.FileInfo
import java.io.File

interface FileRepository {
    fun listFiles(relativePath: String = ""): List<FileInfo>
    fun readFileContent(relativePath: String): FileContent
    fun deleteFile(relativePath: String): Boolean
    fun getFileForSharing(relativePath: String): File
    fun getTotalSize(): Long
    fun getRootPath(): String
}
