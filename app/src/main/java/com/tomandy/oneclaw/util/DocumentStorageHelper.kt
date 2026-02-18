package com.tomandy.oneclaw.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

object DocumentStorageHelper {

    private const val DOCUMENTS_DIR = "chat_documents"

    /**
     * Copy document from Uri to app-private storage.
     * Returns (filePath, mimeType) or null on failure.
     */
    fun copyDocumentToStorage(
        context: Context,
        uri: Uri,
        conversationId: String
    ): Pair<String, String>? {
        val mimeType = context.contentResolver.getType(uri) ?: return null

        val dir = File(context.filesDir, "$DOCUMENTS_DIR/$conversationId")
        dir.mkdirs()

        val extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType) ?: "bin"
        val file = File(dir, "${UUID.randomUUID()}.$extension")

        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        return file.absolutePath to mimeType
    }

    /**
     * Get the display name of a document from its Uri.
     */
    fun getFileName(context: Context, uri: Uri): String {
        var name = "document"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: name
            }
        }
        return name
    }

    /**
     * Whether this MIME type represents a plain-text file that can be inlined.
     */
    fun isTextFile(mimeType: String): Boolean {
        return mimeType.startsWith("text/")
    }

    /**
     * Read a file as UTF-8 text.
     */
    fun readAsText(filePath: String): String? {
        val file = File(filePath)
        if (!file.exists()) return null
        return file.readText(Charsets.UTF_8)
    }

    /**
     * Read a file and return (base64, mimeType) pair.
     */
    fun readAsBase64(filePath: String, mimeType: String): Pair<String, String>? {
        val file = File(filePath)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return base64 to mimeType
    }
}
