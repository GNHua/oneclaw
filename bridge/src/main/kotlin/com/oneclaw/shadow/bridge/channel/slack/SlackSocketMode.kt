package com.oneclaw.shadow.bridge.channel.slack

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class SlackSocketMode(
    private val appToken: String,
    private val botToken: String,
    private val okHttpClient: OkHttpClient,
    private val onMessage: suspend (envelope: JsonObject, ack: () -> Unit) -> Unit,
    private val scope: CoroutineScope
) {
    private var webSocket: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var connected = false

    suspend fun connect() {
        val wsUrl = getSocketModeUrl() ?: throw IllegalStateException("Failed to get Socket Mode URL")
        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Slack Socket Mode WebSocket opened")
                connected = true
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val envelope = json.parseToJsonElement(text).jsonObject
                    val envelopeId = envelope["envelope_id"]?.jsonPrimitive?.content
                    val ack: () -> Unit = {
                        if (envelopeId != null) {
                            val ackMsg = buildJsonObject { put("envelope_id", envelopeId) }
                            ws.send(ackMsg.toString())
                        }
                    }
                    scope.launch { onMessage(envelope, ack) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling Slack message: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Slack WebSocket failure: ${t.message}")
                connected = false
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Slack WebSocket closed: $code $reason")
                connected = false
            }
        })
    }

    private suspend fun getSocketModeUrl(): String? {
        return try {
            val request = Request.Builder()
                .url("https://slack.com/api/apps.connections.open")
                .addHeader("Authorization", "Bearer $appToken")
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val parsed = json.parseToJsonElement(body).jsonObject
            if (parsed["ok"]?.jsonPrimitive?.content != "true") return null
            parsed["url"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.e(TAG, "getSocketModeUrl error: ${e.message}")
            null
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Stopping")
        connected = false
    }

    fun isConnected(): Boolean = connected

    companion object {
        private const val TAG = "SlackSocketMode"
    }
}
