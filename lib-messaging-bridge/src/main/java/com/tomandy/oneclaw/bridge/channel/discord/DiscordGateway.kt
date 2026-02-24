package com.tomandy.oneclaw.bridge.channel.discord

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DiscordGateway(
    private val botToken: String,
    private val scope: CoroutineScope,
    private val onMessage: suspend (MessageCreateEvent) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private val sequenceNumber = AtomicReference<Int?>(null)
    private val connected = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)

    val isConnected: Boolean get() = connected.get()

    fun connect() {
        val request = Request.Builder()
            .url(GATEWAY_URL)
            .build()
        webSocket = client.newWebSocket(request, GatewayListener())
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket?.close(1000, "Shutting down")
        webSocket = null
        connected.set(false)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun sendPayload(payload: String) {
        webSocket?.send(payload)
    }

    private fun sendIdentify() {
        val identify = IdentifyPayload(
            token = botToken,
            intents = INTENT_GUILD_MESSAGES or INTENT_DIRECT_MESSAGES or INTENT_MESSAGE_CONTENT
        )
        val payload = buildJsonObject {
            put("op", JsonPrimitive(OP_IDENTIFY))
            put("d", json.encodeToJsonElement(identify))
        }
        sendPayload(json.encodeToString(payload))
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay((intervalMs * Math.random()).toLong())
            while (isActive) {
                val seq = sequenceNumber.get()
                val payload = buildJsonObject {
                    put("op", JsonPrimitive(OP_HEARTBEAT))
                    put("d", if (seq != null) JsonPrimitive(seq) else JsonNull)
                }
                sendPayload(json.encodeToString(payload))
                delay(intervalMs)
            }
        }
    }

    private fun handleDispatch(payload: GatewayPayload) {
        payload.s?.let { sequenceNumber.set(it) }

        when (payload.t) {
            "READY" -> {
                val data = payload.d ?: return
                try {
                    val ready = json.decodeFromJsonElement(ReadyEvent.serializer(), data)
                    connected.set(true)
                    reconnectAttempt.set(0)
                    Log.i(TAG, "Gateway READY, sessionId=${ready.sessionId}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse READY event", e)
                }
            }
            "MESSAGE_CREATE" -> {
                val data = payload.d ?: return
                try {
                    val event = json.decodeFromJsonElement(MessageCreateEvent.serializer(), data)
                    scope.launch { onMessage(event) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse MESSAGE_CREATE", e)
                }
            }
        }
    }

    private fun scheduleReconnect() {
        val attempt = reconnectAttempt.getAndIncrement()
        val backoffMs = (INITIAL_BACKOFF_MS * (1L shl attempt.coerceAtMost(5)))
            .coerceAtMost(MAX_BACKOFF_MS)

        scope.launch {
            delay(backoffMs)
            if (!connected.get()) connect()
        }
    }

    private inner class GatewayListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val payload = json.decodeFromString<GatewayPayload>(text)
                when (payload.op) {
                    OP_DISPATCH -> handleDispatch(payload)
                    OP_HELLO -> {
                        val data = payload.d ?: return
                        val hello = json.decodeFromJsonElement(HelloEvent.serializer(), data)
                        startHeartbeat(hello.heartbeatInterval)
                        sendIdentify()
                    }
                    OP_HEARTBEAT_ACK -> { /* OK */ }
                    OP_HEARTBEAT -> {
                        val seq = sequenceNumber.get()
                        val resp = buildJsonObject {
                            put("op", JsonPrimitive(OP_HEARTBEAT))
                            put("d", if (seq != null) JsonPrimitive(seq) else JsonNull)
                        }
                        sendPayload(json.encodeToString(resp))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing gateway message", e)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected.set(false)
            heartbeatJob?.cancel()
            if (code != 1000 && code != 4004) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            connected.set(false)
            heartbeatJob?.cancel()
            scheduleReconnect()
        }
    }

    companion object {
        private const val TAG = "DiscordGateway"
        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
        private const val INTENT_GUILD_MESSAGES = 1 shl 9
        private const val INTENT_DIRECT_MESSAGES = 1 shl 12
        private const val INTENT_MESSAGE_CONTENT = 1 shl 15
        private const val INITIAL_BACKOFF_MS = 3000L
        private const val MAX_BACKOFF_MS = 60000L
    }
}
