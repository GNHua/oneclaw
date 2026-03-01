package com.oneclaw.shadow.bridge.channel.line

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LineApi(
    private val channelAccessToken: String,
    private val okHttpClient: OkHttpClient
) {
    suspend fun pushMessage(to: String, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("to", to)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                })
            }
            val request = Request.Builder()
                .url("https://api.line.me/v2/bot/message/push")
                .addHeader("Authorization", "Bearer $channelAccessToken")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "pushMessage error: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "LineApi"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
