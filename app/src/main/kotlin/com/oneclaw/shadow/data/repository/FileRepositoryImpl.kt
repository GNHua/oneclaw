package com.oneclaw.shadow.data.repository

import com.oneclaw.shadow.core.model.FileContent
import com.oneclaw.shadow.core.model.FileInfo
import com.oneclaw.shadow.core.repository.FileRepository
import com.oneclaw.shadow.data.storage.UserFileStorage
import java.io.File

class FileRepositoryImpl(
    private val userFileStorage: UserFileStorage
) : FileRepository {

    override fun listFiles(relativePath: String): List<FileInfo> =
        userFileStorage.listFiles(relativePath)

    override fun readFileContent(relativePath: String): FileContent =
        userFileStorage.readFileContent(relativePath)

    override fun deleteFile(relativePath: String): Boolean =
        userFileStorage.delete(relativePath)

    override fun getFileForSharing(relativePath: String): File =
        userFileStorage.resolveFile(relativePath)

    override fun getTotalSize(): Long =
        userFileStorage.getTotalSize()

    override fun getRootPath(): String =
        userFileStorage.rootDir.absolutePath
}
