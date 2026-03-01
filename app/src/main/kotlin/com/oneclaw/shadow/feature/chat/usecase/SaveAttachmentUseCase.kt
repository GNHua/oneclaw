package com.oneclaw.shadow.feature.chat.usecase

import android.net.Uri
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.data.local.AttachmentFileManager

class SaveAttachmentUseCase(
    private val attachmentFileManager: AttachmentFileManager
) {
    suspend fun fromUri(uri: Uri, sessionId: String): AppResult<AttachmentFileManager.PendingAttachment> {
        return attachmentFileManager.copyFromUri(uri, sessionId)
    }

    suspend fun fromCameraFile(file: java.io.File, sessionId: String): AppResult<AttachmentFileManager.PendingAttachment> {
        return attachmentFileManager.copyFromCameraFile(file, sessionId)
    }
}
