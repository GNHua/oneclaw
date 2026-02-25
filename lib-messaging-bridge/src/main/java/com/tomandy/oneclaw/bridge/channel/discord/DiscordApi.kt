package com.tomandy.oneclaw.bridge.channel.discord

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class DiscordApi(private val botToken: String) {

    private val baseUrl = "https://discord.com/api/v10"

    internal val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun sendMessage(channelId: String, content: String): Unit =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(
                SendMessagePayload.serializer(),
                SendMessagePayload(content = content)
            )

            val request = Request.Builder()
                .url("$baseUrl/channels/$channelId/messages")
                .header("Authorization", "Bot $botToken")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                throw DiscordApiException("sendMessage failed: ${response.code} $body")
            }
            response.close()
        }

    suspend fun sendLongMessage(channelId: String, text: String) {
        if (text.length <= MAX_MESSAGE_LENGTH) {
            sendMessage(channelId, text)
            return
        }

        val chunks = splitMessage(text, MAX_MESSAGE_LENGTH)
        for (chunk in chunks) {
            sendMessage(channelId, chunk)
        }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val MAX_MESSAGE_LENGTH = 2000

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

class DiscordApiException(message: String) : Exception(message)
