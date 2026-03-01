package com.oneclaw.shadow.bridge.channel.discord

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class DiscordGateway(
    private val botToken: String,
    private val okHttpClient: OkHttpClient,
    private val onMessage: suspend (JsonObject) -> Unit,
    private val scope: CoroutineScope
) {
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var sequenceNumber: Int? = null
    private var connected = false
    private val json = Json { ignoreUnknownKeys = true }

    fun connect() {
        val request = Request.Builder()
            .url("wss://gateway.discord.gg/?v=10&encoding=json")
            .build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Gateway WebSocket opened")
                connected = true
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val payload = json.parseToJsonElement(text).jsonObject
                    handlePayload(payload)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling gateway message: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Gateway WebSocket failure: ${t.message}")
                connected = false
                heartbeatJob?.cancel()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gateway WebSocket closed: $code $reason")
                connected = false
                heartbeatJob?.cancel()
            }
        })
    }

    private fun handlePayload(payload: JsonObject) {
        val op = payload["op"]?.jsonPrimitive?.int ?: return
        val s = payload["s"]?.jsonPrimitive?.content?.toIntOrNull()
        if (s != null) sequenceNumber = s

        when (op) {
            OP_HELLO -> {
                val heartbeatInterval = payload["d"]?.jsonObject
                    ?.get("heartbeat_interval")?.jsonPrimitive?.int?.toLong() ?: 41250L
                startHeartbeat(heartbeatInterval)
                identify()
            }
            OP_DISPATCH -> {
                val eventName = payload["t"]?.jsonPrimitive?.content
                val data = payload["d"]?.jsonObject ?: return
                if (eventName == "MESSAGE_CREATE") {
                    scope.launch { onMessage(data) }
                }
            }
            OP_HEARTBEAT_ACK -> {
                Log.v(TAG, "Heartbeat acknowledged")
            }
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(intervalMs)
                val heartbeat = buildJsonObject {
                    put("op", OP_HEARTBEAT)
                    val seq = sequenceNumber
                    if (seq != null) put("d", seq) else put("d", "null")
                }
                webSocket?.send(heartbeat.toString())
            }
        }
    }

    private fun identify() {
        val identify = buildJsonObject {
            put("op", OP_IDENTIFY)
            putJsonObject("d") {
                put("token", botToken)
                put("intents", INTENTS)
                putJsonObject("properties") {
                    put("os", "android")
                    put("browser", "oneclaw-shadow")
                    put("device", "oneclaw-shadow")
                }
            }
        }
        webSocket?.send(identify.toString())
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        webSocket?.close(1000, "Stopping")
        connected = false
    }

    fun isConnected(): Boolean = connected

    companion object {
        private const val TAG = "DiscordGateway"
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
        private const val INTENTS = 33281 // GUILD_MESSAGES | DIRECT_MESSAGES | MESSAGE_CONTENT
    }
}
