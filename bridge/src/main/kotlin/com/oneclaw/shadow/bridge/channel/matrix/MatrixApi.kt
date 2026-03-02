package com.oneclaw.shadow.bridge.channel.matrix

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

class MatrixApi(
    private val homeserverUrl: String,
    private val accessToken: String,
    private val okHttpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val longPollClient = okHttpClient.newBuilder()
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    suspend fun sync(since: String? = null, timeout: Int = 30000): JsonObject? =
        withContext(Dispatchers.IO) {
            try {
                val url = buildString {
                    append("$homeserverUrl/_matrix/client/v3/sync?timeout=$timeout")
                    if (since != null) append("&since=$since")
                }
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
                val response = longPollClient.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext null
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                Log.e(TAG, "sync error: ${e.message}")
                null
            }
        }

    suspend fun sendMessage(roomId: String, text: String, htmlBody: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val txnId = UUID.randomUUID().toString()
                val body = buildJsonObject {
                    put("msgtype", "m.text")
                    put("body", text)
                    if (htmlBody != null) {
                        put("format", "org.matrix.custom.html")
                        put("formatted_body", htmlBody)
                    }
                }
                val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                val url = "$homeserverUrl/_matrix/client/v3/rooms/$encodedRoomId/send/m.room.message/$txnId"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .put(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val response = okHttpClient.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage error: ${e.message}")
                false
            }
        }

    suspend fun sendTyping(roomId: String, userId: String, typing: Boolean, timeout: Int = 5000): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("typing", typing)
                    if (typing) put("timeout", timeout)
                }
                val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                val encodedUserId = java.net.URLEncoder.encode(userId, "UTF-8")
                val url = "$homeserverUrl/_matrix/client/v3/rooms/$encodedRoomId/typing/$encodedUserId"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .put(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val response = okHttpClient.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                Log.e(TAG, "sendTyping error: ${e.message}")
                false
            }
        }

    suspend fun whoAmI(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$homeserverUrl/_matrix/client/v3/account/whoami")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            json.parseToJsonElement(body).jsonObject["user_id"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.e(TAG, "whoAmI error: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MatrixApi"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
