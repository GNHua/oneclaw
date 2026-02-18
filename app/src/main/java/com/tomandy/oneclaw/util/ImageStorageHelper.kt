package com.tomandy.oneclaw.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

object ImageStorageHelper {

    private const val IMAGES_DIR = "chat_images"

    private val SUPPORTED_MIME_TYPES = setOf(
        "image/jpeg", "image/png", "image/webp", "image/gif",
        "image/heic", "image/heif"
    )

    /**
     * Copy image from Uri to app-private storage.
     * HEIC/HEIF images are converted to JPEG.
     * Returns the absolute file path, or null on failure.
     */
    fun copyImageToStorage(
        context: Context,
        uri: Uri,
        conversationId: String
    ): String? {
        val mimeType = context.contentResolver.getType(uri) ?: return null
        if (mimeType !in SUPPORTED_MIME_TYPES) return null

        val dir = File(context.filesDir, "$IMAGES_DIR/$conversationId")
        dir.mkdirs()

        // HEIC/HEIF: decode and re-encode as JPEG
        if (mimeType == "image/heic" || mimeType == "image/heif") {
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            bitmap.recycle()
            return file.absolutePath
        }

        val extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType) ?: "jpg"
        val file = File(dir, "${UUID.randomUUID()}.$extension")

        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        return file.absolutePath
    }

    /**
     * Create a temp image file for camera capture and return (File, Uri via FileProvider).
     */
    fun createTempImageFile(context: Context, conversationId: String): Pair<File, Uri> {
        val dir = File(context.filesDir, "$IMAGES_DIR/$conversationId")
        dir.mkdirs()
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return file to uri
    }

    /**
     * Read a file and return (base64, mimeType) pair.
     */
    fun readAsBase64(filePath: String): Pair<String, String>? {
        val file = File(filePath)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val mimeType = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        return base64 to mimeType
    }
}
