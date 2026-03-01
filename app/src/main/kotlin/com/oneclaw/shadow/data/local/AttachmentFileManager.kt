package com.oneclaw.shadow.data.local

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import com.oneclaw.shadow.core.model.AttachmentType
import com.oneclaw.shadow.core.util.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class AttachmentFileManager(
    private val context: Context
) {
    companion object {
        private const val ATTACHMENTS_DIR = "attachments"
        private const val THUMBS_DIR = "thumbs"
        private const val THUMB_SIZE = 256
        const val MAX_FILE_SIZE = 20L * 1024 * 1024
    }

    data class PendingAttachment(
        val id: String,
        val type: AttachmentType,
        val fileName: String,
        val mimeType: String,
        val fileSize: Long,
        val filePath: String,
        val thumbnailPath: String?,
        val width: Int?,
        val height: Int?,
        val durationMs: Long?
    )

    suspend fun copyFromUri(
        uri: Uri,
        sessionId: String
    ): AppResult<PendingAttachment> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver

            val fileName = getFileName(contentResolver, uri)
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val fileSize = getFileSize(contentResolver, uri)

            if (fileSize > MAX_FILE_SIZE) {
                return@withContext AppResult.Error(
                    message = "File is too large (${fileSize / 1024 / 1024}MB). Maximum is 20MB."
                )
            }

            val type = when {
                mimeType.startsWith("image/") -> AttachmentType.IMAGE
                mimeType.startsWith("video/") -> AttachmentType.VIDEO
                else -> AttachmentType.FILE
            }

            val sessionDir = getSessionDir(sessionId)
            sessionDir.mkdirs()

            val id = UUID.randomUUID().toString()
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType) ?: fileName.substringAfterLast('.', "bin")
            val destFile = File(sessionDir, "$id.$extension")

            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext AppResult.Error(message = "Cannot open file")

            val thumbnailPath = when (type) {
                AttachmentType.IMAGE -> generateImageThumbnail(destFile, sessionId, id)
                AttachmentType.VIDEO -> generateVideoThumbnail(uri, sessionId, id)
                AttachmentType.FILE -> null
            }

            val dimensions = when (type) {
                AttachmentType.IMAGE -> getImageDimensions(destFile)
                AttachmentType.VIDEO -> getVideoDimensions(uri)
                AttachmentType.FILE -> null
            }

            val durationMs = if (type == AttachmentType.VIDEO) {
                getVideoDuration(uri)
            } else null

            AppResult.Success(
                PendingAttachment(
                    id = id,
                    type = type,
                    fileName = fileName,
                    mimeType = mimeType,
                    fileSize = fileSize,
                    filePath = destFile.absolutePath,
                    thumbnailPath = thumbnailPath,
                    width = dimensions?.first,
                    height = dimensions?.second,
                    durationMs = durationMs
                )
            )
        } catch (e: Exception) {
            AppResult.Error(message = "Failed to save attachment: ${e.message}")
        }
    }

    suspend fun copyFromCameraFile(
        file: File,
        sessionId: String
    ): AppResult<PendingAttachment> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext AppResult.Error(message = "Camera file not found")
            }

            val fileSize = file.length()
            if (fileSize > MAX_FILE_SIZE) {
                return@withContext AppResult.Error(
                    message = "File is too large (${fileSize / 1024 / 1024}MB). Maximum is 20MB."
                )
            }

            val mimeType = "image/jpeg"
            val type = AttachmentType.IMAGE

            val sessionDir = getSessionDir(sessionId)
            sessionDir.mkdirs()

            val id = UUID.randomUUID().toString()
            val destFile = File(sessionDir, "$id.jpg")

            file.copyTo(destFile, overwrite = true)

            val thumbnailPath = generateImageThumbnail(destFile, sessionId, id)
            val dimensions = getImageDimensions(destFile)

            AppResult.Success(
                PendingAttachment(
                    id = id,
                    type = type,
                    fileName = file.name.ifBlank { "$id.jpg" },
                    mimeType = mimeType,
                    fileSize = fileSize,
                    filePath = destFile.absolutePath,
                    thumbnailPath = thumbnailPath,
                    width = dimensions?.first,
                    height = dimensions?.second,
                    durationMs = null
                )
            )
        } catch (e: Exception) {
            AppResult.Error(message = "Failed to save camera photo: ${e.message}")
        }
    }

    suspend fun deleteSessionAttachments(sessionId: String) = withContext(Dispatchers.IO) {
        val sessionDir = getSessionDir(sessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
    }

    suspend fun readAsBase64(filePath: String): String = withContext(Dispatchers.IO) {
        val file = File(filePath)
        val bytes = file.readBytes()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun getSessionDir(sessionId: String): File {
        return File(context.filesDir, "$ATTACHMENTS_DIR/$sessionId")
    }

    private fun getThumbsDir(sessionId: String): File {
        return File(getSessionDir(sessionId), THUMBS_DIR).also { it.mkdirs() }
    }

    private fun generateImageThumbnail(
        sourceFile: File,
        sessionId: String,
        id: String
    ): String? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourceFile.absolutePath, options)

            val sampleSize = calculateInSampleSize(options, THUMB_SIZE, THUMB_SIZE)
            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize

            val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, options)
                ?: return null

            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                minOf(THUMB_SIZE, bitmap.width),
                minOf(THUMB_SIZE, bitmap.height),
                true
            )
            if (scaled != bitmap) bitmap.recycle()

            val thumbFile = File(getThumbsDir(sessionId), "${id}_thumb.jpg")
            thumbFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            scaled.recycle()

            thumbFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun generateVideoThumbnail(
        uri: Uri,
        sessionId: String,
        id: String
    ): String? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val bitmap = retriever.getFrameAtTime(0) ?: return null
            retriever.release()

            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                minOf(THUMB_SIZE, bitmap.width),
                minOf(THUMB_SIZE, bitmap.height),
                true
            )
            if (scaled != bitmap) bitmap.recycle()

            val thumbFile = File(getThumbsDir(sessionId), "${id}_thumb.jpg")
            thumbFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            scaled.recycle()

            thumbFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment ?: "unknown"
    }

    private fun getFileSize(contentResolver: ContentResolver, uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0) return cursor.getLong(sizeIndex)
            }
        }
        return 0L
    }

    private fun getImageDimensions(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            Pair(options.outWidth, options.outHeight)
        } else null
    }

    private fun getVideoDimensions(uri: Uri): Pair<Int, Int>? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()
            retriever.release()
            if (width != null && height != null) Pair(width, height) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun getVideoDuration(uri: Uri): Long? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
            retriever.release()
            duration
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
