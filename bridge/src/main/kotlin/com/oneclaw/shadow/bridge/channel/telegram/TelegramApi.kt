package com.oneclaw.shadow.bridge.channel.telegram

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class TelegramApi(
    private val botToken: String,
    private val okHttpClient: OkHttpClient
) {
    private val baseUrl = "https://api.telegram.org/bot$botToken"
    private val json = Json { ignoreUnknownKeys = true }
    private val longPollClient = okHttpClient.newBuilder()
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    suspend fun getUpdates(offset: Long, timeout: Int = 30): List<JsonObject> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/getUpdates?offset=$offset&timeout=$timeout&allowed_updates=[\"message\"]"
                val request = Request.Builder().url(url).build()
                val response = longPollClient.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext emptyList()
                val parsed = json.parseToJsonElement(body).jsonObject
                if (parsed["ok"]?.jsonPrimitive?.content != "true") return@withContext emptyList()
                parsed["result"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "getUpdates error: ${e.message}")
                emptyList()
            }
        }

    suspend fun sendMessage(chatId: String, text: String, parseMode: String? = "HTML"): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("chat_id", chatId)
                    put("text", text)
                    if (parseMode != null) put("parse_mode", parseMode)
                }
                val request = Request.Builder()
                    .url("$baseUrl/sendMessage")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val response = okHttpClient.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage error: ${e.message}")
                false
            }
        }

    suspend fun sendChatAction(chatId: String, action: String = "typing"): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("chat_id", chatId)
                    put("action", action)
                }
                val request = Request.Builder()
                    .url("$baseUrl/sendChatAction")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val response = okHttpClient.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                Log.e(TAG, "sendChatAction error: ${e.message}")
                false
            }
        }

    suspend fun getFile(fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/getFile?file_id=$fileId")
                .build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            val parsed = json.parseToJsonElement(body).jsonObject
            if (parsed["ok"]?.jsonPrimitive?.content != "true") return@withContext null
            parsed["result"]?.jsonObject?.get("file_path")?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.e(TAG, "getFile error: ${e.message}")
            null
        }
    }

    fun getFileDownloadUrl(filePath: String): String =
        "https://api.telegram.org/file/bot$botToken/$filePath"

    companion object {
        private const val TAG = "TelegramApi"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
