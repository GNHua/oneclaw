package com.tomandy.oneclaw.bridge.channel.line

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LineApi(private val channelAccessToken: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun pushMessage(userId: String, text: String): Unit =
        withContext(Dispatchers.IO) {
            val messages = splitMessage(text, MAX_MESSAGE_LENGTH).map { chunk ->
                LineTextMessage(text = chunk)
            }

            // LINE allows max 5 messages per push request
            messages.chunked(MAX_MESSAGES_PER_REQUEST).forEach { batch ->
                val payload = json.encodeToString(
                    LinePushRequest.serializer(),
                    LinePushRequest(to = userId, messages = batch)
                )

                val request = Request.Builder()
                    .url("$BASE_URL/message/push")
                    .header("Authorization", "Bearer $channelAccessToken")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    throw LineApiException("push message failed: ${response.code} $body")
                }
                response.close()
            }
        }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val BASE_URL = "https://api.line.me/v2/bot"
        private const val MAX_MESSAGE_LENGTH = 5000
        private const val MAX_MESSAGES_PER_REQUEST = 5

        fun splitMessage(text: String, maxLength: Int): List<String> {
            if (text.length <= maxLength) return listOf(text)

            val chunks = mutableListOf<String>()
            var remaining = text

            while (remaining.length > maxLength) {
                var splitIndex = remaining.lastIndexOf("\n\n", maxLength)
                if (splitIndex <= 0) {
                    splitIndex = remaining.lastIndexOf('\n', maxLength)
                }
                if (splitIndex <= 0) {
                    splitIndex = remaining.lastIndexOf(' ', maxLength)
                }
                if (splitIndex <= 0) {
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

class LineApiException(message: String) : Exception(message)
