package com.oneclaw.shadow.bridge.image

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

class BridgeImageStorage(private val context: Context) {

    private val client = OkHttpClient()

    fun getImageDir(): File {
        val dir = File(context.filesDir, "bridge_images")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun downloadAndStore(url: String, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder().url(url)
                headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
                val response = client.newCall(requestBuilder.build()).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to download image from $url: ${response.code}")
                    return@withContext null
                }

                val bytes = response.body?.bytes() ?: return@withContext null
                val extension = detectExtension(bytes, response.body?.contentType()?.subtype)
                val fileName = "${UUID.randomUUID()}.$extension"
                val file = File(getImageDir(), fileName)
                file.writeBytes(bytes)
                file.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading image from $url", e)
                null
            }
        }

    private fun detectExtension(bytes: ByteArray, contentTypeSubtype: String?): String {
        // Detect from magic bytes
        if (bytes.size >= 4) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "jpg"
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            ) return "png"
            if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte()
            ) return "gif"
        }
        // Fallback to content type
        return when (contentTypeSubtype?.lowercase()) {
            "jpeg", "jpg" -> "jpg"
            "png" -> "png"
            "gif" -> "gif"
            "webp" -> "webp"
            else -> "bin"
        }
    }

    companion object {
        private const val TAG = "BridgeImageStorage"
    }
}
