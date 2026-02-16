package com.tomandy.palmclaw.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

object VideoStorageHelper {

    private const val VIDEO_DIR = "chat_video"

    fun copyVideoToStorage(
        context: Context,
        uri: Uri,
        conversationId: String
    ): String? {
        val dir = File(context.filesDir, "$VIDEO_DIR/$conversationId")
        dir.mkdirs()

        val file = File(dir, "${UUID.randomUUID()}.mp4")

        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        return file.absolutePath
    }

    /**
     * Create a temp video file for camera capture and return (File, Uri via FileProvider).
     */
    fun createTempVideoFile(context: Context, conversationId: String): Pair<File, Uri> {
        val dir = File(context.filesDir, "$VIDEO_DIR/$conversationId")
        dir.mkdirs()
        val file = File(dir, "${UUID.randomUUID()}.mp4")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return file to uri
    }

    fun readAsBase64(filePath: String): Pair<String, String>? {
        val file = File(filePath)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return base64 to "video/mp4"
    }

    fun getVideoDuration(filePath: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            retriever.release()
            duration
        } catch (_: Exception) {
            0L
        }
    }
}
