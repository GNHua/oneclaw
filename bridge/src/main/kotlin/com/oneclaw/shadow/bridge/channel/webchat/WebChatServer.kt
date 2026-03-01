package com.oneclaw.shadow.bridge.channel.webchat

import android.util.Log
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class WebChatServer(
    port: Int,
    private val accessToken: String?,
    private val onMessage: suspend (sessionId: String, text: String) -> Unit,
    private val scope: CoroutineScope
) : NanoWSD(port) {

    private val sessions = ConcurrentHashMap<String, WebSocket>()
    private val json = Json { ignoreUnknownKeys = true }
    private var sessionCounter = 0

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val sessionId = "webchat-${sessionCounter++}"
        return WebChatSession(handshake, sessionId)
    }

    fun sendToSession(sessionId: String, message: String) {
        sessions[sessionId]?.send(message)
    }

    fun sendToAll(message: String) {
        sessions.values.forEach { session ->
            runCatching { session.send(message) }
        }
    }

    inner class WebChatSession(handshake: IHTTPSession, private val sessionId: String) : WebSocket(handshake) {
        private var authenticated = accessToken == null

        override fun onOpen() {
            sessions[sessionId] = this
            Log.d(TAG, "WebChat session opened: $sessionId")
            if (accessToken == null) {
                runCatching { send(buildJsonObject { put("type", "auth_ok") }.toString()) }
            }
        }

        override fun onClose(
            code: NanoWSD.WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            sessions.remove(sessionId)
            Log.d(TAG, "WebChat session closed: $sessionId")
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            try {
                val text = message.textPayload ?: return
                val parsed = json.parseToJsonElement(text).jsonObject
                val type = parsed["type"]?.jsonPrimitive?.content ?: return

                when (type) {
                    "auth" -> {
                        val token = parsed["token"]?.jsonPrimitive?.content
                        if (accessToken == null || token == accessToken) {
                            authenticated = true
                            runCatching { send(buildJsonObject { put("type", "auth_ok") }.toString()) }
                        } else {
                            runCatching { send(buildJsonObject { put("type", "auth_fail") }.toString()) }
                        }
                    }
                    "message" -> {
                        if (!authenticated) {
                            runCatching { send(buildJsonObject { put("type", "auth_fail") }.toString()) }
                            return
                        }
                        val msgText = parsed["text"]?.jsonPrimitive?.content ?: return
                        scope.launch { onMessage(sessionId, msgText) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling WebChat message: ${e.message}")
            }
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

        override fun onException(exception: IOException) {
            Log.e(TAG, "WebChat session exception: ${exception.message}")
        }
    }

    companion object {
        private const val TAG = "WebChatServer"
    }
}
