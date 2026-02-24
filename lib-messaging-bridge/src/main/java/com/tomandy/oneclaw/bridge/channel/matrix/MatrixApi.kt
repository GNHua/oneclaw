package com.tomandy.oneclaw.bridge.channel.matrix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MatrixApi(
    private val homeserverUrl: String,
    private val accessToken: String
) {
    private val baseUrl = homeserverUrl.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // Long-poll needs longer read timeout
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val txnCounter = AtomicLong(System.currentTimeMillis())

    suspend fun sync(since: String?, timeout: Int = 30000): SyncResponse =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append("$baseUrl/_matrix/client/v3/sync?timeout=$timeout")
                if (since != null) {
                    append("&since=$since")
                }
                // Only get message events to reduce payload
                append("&filter={\"room\":{\"timeline\":{\"types\":[\"m.room.message\"]}}}")
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
                ?: throw MatrixApiException("sync returned empty body")

            if (!response.isSuccessful) {
                throw MatrixApiException("sync failed: ${response.code} $body")
            }

            json.decodeFromString<SyncResponse>(body)
        }

    suspend fun sendMessage(roomId: String, text: String): Unit =
        withContext(Dispatchers.IO) {
            val txnId = "m${txnCounter.incrementAndGet()}"
            val encodedRoomId = roomId.replace("!", "%21")
            val url = "$baseUrl/_matrix/client/v3/rooms/$encodedRoomId/send/m.room.message/$txnId"

            val payload = json.encodeToString(
                SendMessageBody.serializer(),
                SendMessageBody(body = text)
            )

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .put(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                throw MatrixApiException("sendMessage failed: ${response.code} $body")
            }
            response.close()
        }

    suspend fun sendLongMessage(roomId: String, text: String) {
        if (text.length <= MAX_MESSAGE_LENGTH) {
            sendMessage(roomId, text)
            return
        }

        val chunks = splitMessage(text, MAX_MESSAGE_LENGTH)
        for (chunk in chunks) {
            sendMessage(roomId, chunk)
        }
    }

    suspend fun whoAmI(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/_matrix/client/v3/account/whoami")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()
            ?: throw MatrixApiException("whoami returned empty body")

        if (!response.isSuccessful) {
            throw MatrixApiException("whoami failed: ${response.code} $body")
        }

        json.decodeFromString<WhoAmIResponse>(body).userId
    }

    suspend fun sendTyping(roomId: String, userId: String, typing: Boolean, timeout: Int = 30000): Unit =
        withContext(Dispatchers.IO) {
            val encodedRoomId = roomId.replace("!", "%21")
            val encodedUserId = userId.replace("@", "%40")
            val url = "$baseUrl/_matrix/client/v3/rooms/$encodedRoomId/typing/$encodedUserId"

            val payload = if (typing) {
                """{"typing":true,"timeout":$timeout}"""
            } else {
                """{"typing":false}"""
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .put(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            response.close()
        }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val MAX_MESSAGE_LENGTH = 40000

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

class MatrixApiException(message: String) : Exception(message)
