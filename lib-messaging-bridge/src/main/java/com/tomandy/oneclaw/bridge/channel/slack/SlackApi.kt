package com.tomandy.oneclaw.bridge.channel.slack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SlackApi(private val botToken: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun sendMessage(channel: String, text: String): Unit =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(
                ChatPostMessageRequest.serializer(),
                ChatPostMessageRequest(channel = channel, text = text)
            )

            val request = Request.Builder()
                .url("$BASE_URL/chat.postMessage")
                .header("Authorization", "Bearer $botToken")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                throw SlackApiException("chat.postMessage HTTP failed: ${response.code} $body")
            }

            val parsed = json.decodeFromString<SlackApiResponse>(body)
            if (!parsed.ok) {
                throw SlackApiException("chat.postMessage failed: ${parsed.error}")
            }
        }

    suspend fun sendLongMessage(channel: String, text: String) {
        if (text.length <= MAX_MESSAGE_LENGTH) {
            sendMessage(channel, text)
            return
        }

        val chunks = splitMessage(text, MAX_MESSAGE_LENGTH)
        for (chunk in chunks) {
            sendMessage(channel, chunk)
        }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val BASE_URL = "https://slack.com/api"
        private const val MAX_MESSAGE_LENGTH = 4000

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

class SlackApiException(message: String) : Exception(message)
