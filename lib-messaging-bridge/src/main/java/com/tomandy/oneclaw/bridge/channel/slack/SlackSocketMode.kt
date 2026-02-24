package com.tomandy.oneclaw.bridge.channel.slack

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SlackSocketMode(
    private val appToken: String,
    private val scope: CoroutineScope,
    private val onMessage: suspend (SlackEvent) -> Unit
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private val connected = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)

    val isConnected: Boolean get() = connected.get()

    suspend fun connect() {
        val wsUrl = obtainWebSocketUrl()
        if (wsUrl == null) {
            Log.e(TAG, "Failed to obtain Socket Mode URL")
            return
        }

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, SocketModeListener())
    }

    fun disconnect() {
        pingJob?.cancel()
        pingJob = null
        webSocket?.close(1000, "Shutting down")
        webSocket = null
        connected.set(false)
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private suspend fun obtainWebSocketUrl(): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://slack.com/api/apps.connections.open")
            .header("Authorization", "Bearer $appToken")
            .post(ByteArray(0).toRequestBody(null))
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext null

        if (!response.isSuccessful) {
            Log.e(TAG, "connections.open HTTP failed: ${response.code} $body")
            return@withContext null
        }

        val parsed = json.decodeFromString<ConnectionsOpenResponse>(body)
        if (!parsed.ok || parsed.url == null) {
            Log.e(TAG, "connections.open failed: ${parsed.error}")
            return@withContext null
        }

        parsed.url
    }

    private fun acknowledgeEnvelope(envelopeId: String) {
        val ack = json.encodeToString(SocketModeAck(envelopeId = envelopeId))
        webSocket?.send(ack)
    }

    private fun scheduleReconnect() {
        val attempt = reconnectAttempt.getAndIncrement()
        val backoffMs = (INITIAL_BACKOFF_MS * (1L shl attempt.coerceAtMost(5)))
            .coerceAtMost(MAX_BACKOFF_MS)

        scope.launch {
            delay(backoffMs)
            if (!connected.get()) {
                try {
                    connect()
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect failed", e)
                    scheduleReconnect()
                }
            }
        }
    }

    private inner class SocketModeListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected.set(true)
            reconnectAttempt.set(0)
            Log.i(TAG, "Socket Mode connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val envelope = json.decodeFromString<SocketModeEnvelope>(text)

                // Acknowledge the envelope immediately
                envelope.envelopeId?.let { acknowledgeEnvelope(it) }

                when (envelope.type) {
                    "hello" -> {
                        Log.i(TAG, "Received hello from Slack")
                    }
                    "disconnect" -> {
                        Log.i(TAG, "Slack requested disconnect, reconnecting")
                        connected.set(false)
                        scheduleReconnect()
                    }
                    "events_api" -> {
                        val payload = envelope.payload ?: return
                        try {
                            val eventPayload = json.decodeFromJsonElement(
                                EventPayload.serializer(),
                                payload
                            )
                            val event = eventPayload.event ?: return

                            // Only process actual user messages (not bot messages, not subtypes)
                            if (event.type == "message" && event.subtype == null && event.botId == null) {
                                scope.launch { onMessage(event) }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse event payload", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing Socket Mode message", e)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected.set(false)
            pingJob?.cancel()
            Log.i(TAG, "Socket Mode closed: $code $reason")
            if (code != 1000) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Socket Mode failure", t)
            connected.set(false)
            pingJob?.cancel()
            scheduleReconnect()
        }
    }

    companion object {
        private const val TAG = "SlackSocketMode"
        private const val INITIAL_BACKOFF_MS = 3000L
        private const val MAX_BACKOFF_MS = 60000L
    }
}
