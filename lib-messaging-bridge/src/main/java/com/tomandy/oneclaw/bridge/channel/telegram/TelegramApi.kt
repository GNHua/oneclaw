package com.tomandy.oneclaw.bridge.channel.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class TelegramApi(private val botToken: String) {

    private val baseUrl = "https://api.telegram.org/bot$botToken"

    internal val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // long-poll needs longer read timeout
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun getUpdates(offset: Long?, timeout: Int = 30): List<TelegramUpdate> =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append("$baseUrl/getUpdates?timeout=$timeout")
                if (offset != null && offset > 0) {
                    append("&offset=$offset")
                }
            }

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()

            if (!response.isSuccessful) {
                throw TelegramApiException("getUpdates failed: ${response.code} $body")
            }

            val parsed = json.decodeFromString<TelegramResponse<List<TelegramUpdate>>>(body)
            parsed.result ?: emptyList()
        }

    suspend fun sendMessage(chatId: String, text: String, parseMode: String? = null): Unit =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(
                SendMessageRequest.serializer(),
                SendMessageRequest(chatId = chatId, text = text, parseMode = parseMode)
            )

            val request = Request.Builder()
                .url("$baseUrl/sendMessage")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                throw TelegramApiException("sendMessage failed: ${response.code} $body")
            }
            response.close()
        }

    suspend fun sendLongMessage(chatId: String, text: String, parseMode: String? = null) {
        if (text.length <= MAX_MESSAGE_LENGTH) {
            sendMessage(chatId, text, parseMode)
            return
        }

        val chunks = splitMessage(text, MAX_MESSAGE_LENGTH)
        for (chunk in chunks) {
            sendMessage(chatId, chunk, parseMode)
        }
    }

    suspend fun sendChatAction(chatId: String, action: String = "typing"): Unit =
        withContext(Dispatchers.IO) {
            val payload = """{"chat_id":"$chatId","action":"$action"}"""

            val request = Request.Builder()
                .url("$baseUrl/sendChatAction")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            response.close()
        }

    suspend fun getFile(fileId: String): TelegramFile? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/getFile?file_id=$fileId"
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext null

        if (!response.isSuccessful) {
            throw TelegramApiException("getFile failed: ${response.code} $body")
        }

        val parsed = json.decodeFromString<TelegramResponse<TelegramFile>>(body)
        parsed.result
    }

    fun getFileDownloadUrl(filePath: String): String =
        "https://api.telegram.org/file/bot$botToken/$filePath"

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val MAX_MESSAGE_LENGTH = 4096

        fun splitMessage(text: String, maxLength: Int): List<String> {
            if (text.length <= maxLength) return listOf(text)

            val chunks = mutableListOf<String>()
            var remaining = text

            while (remaining.length > maxLength) {
                // Try to split at a paragraph boundary
                var splitIndex = remaining.lastIndexOf("\n\n", maxLength)
                if (splitIndex <= 0) {
                    // Try single newline
                    splitIndex = remaining.lastIndexOf('\n', maxLength)
                }
                if (splitIndex <= 0) {
                    // Try space
                    splitIndex = remaining.lastIndexOf(' ', maxLength)
                }
                if (splitIndex <= 0) {
                    // Hard split
                    splitIndex = maxLength
                }

                chunks.add(remaining.substring(0, splitIndex))
                remaining = remaining.substring(splitIndex).trimStart()
            }

            if (remaining.isNotEmpty()) {
                chunks.add(remaining)
            }

            return chunks
        }
    }
}

class TelegramApiException(message: String) : Exception(message)
