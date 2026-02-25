package com.tomandy.oneclaw.bridge.channel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

object BridgeImageStorage {

    private const val TAG = "BridgeImageStorage"

    suspend fun downloadImage(
        client: OkHttpClient,
        url: String,
        targetDir: File
    ): String? = withContext(Dispatchers.IO) {
        try {
            targetDir.mkdirs()

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Download failed: ${response.code} for $url")
                response.close()
                return@withContext null
            }

            val contentType = response.header("Content-Type") ?: "image/jpeg"
            val ext = when {
                contentType.contains("png") -> "png"
                contentType.contains("gif") -> "gif"
                contentType.contains("webp") -> "webp"
                contentType.contains("bmp") -> "bmp"
                else -> "jpg"
            }

            val file = File(targetDir, "${UUID.randomUUID()}.$ext")
            response.body?.byteStream()?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                response.close()
                return@withContext null
            }

            response.close()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download image from $url", e)
            null
        }
    }
}
