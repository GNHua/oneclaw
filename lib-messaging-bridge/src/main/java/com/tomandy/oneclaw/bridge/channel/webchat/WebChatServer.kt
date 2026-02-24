package com.tomandy.oneclaw.bridge.channel.webchat

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WebChatServer(
    port: Int,
    private val accessToken: String?,
    private val scope: CoroutineScope,
    private val onMessage: suspend (sessionId: String, text: String) -> Unit
) : NanoWSD(port) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val activeSessions = ConcurrentHashMap<String, ChatWebSocket>()

    override fun serveHttp(session: IHTTPSession): NanoHTTPD.Response {
        return when (session.uri) {
            "/", "/index.html" -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "text/html",
                WebChatHtml.HTML
            )
            "/health" -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                """{"status":"ok"}"""
            )
            else -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "text/plain",
                "404 Not Found"
            )
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return ChatWebSocket(handshake)
    }

    fun sendToSession(sessionId: String, text: String) {
        val session = activeSessions[sessionId]
        if (session != null) {
            try {
                session.send(json.encodeToString(
                    WsOutMessage.serializer(),
                    WsOutMessage(type = "response", text = text)
                ))
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send to session $sessionId", e)
            }
        }
    }

    fun broadcastToAll(text: String) {
        val payload = json.encodeToString(
            WsOutMessage.serializer(),
            WsOutMessage(type = "response", text = text)
        )
        for ((id, session) in activeSessions) {
            try {
                session.send(payload)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to broadcast to session $id", e)
            }
        }
    }

    inner class ChatWebSocket(handshake: IHTTPSession) : NanoWSD.WebSocket(handshake) {
        private val sessionId = UUID.randomUUID().toString()
        private var authenticated = false

        override fun onOpen() {
            Log.d(TAG, "WebSocket opened: $sessionId")
        }

        override fun onClose(
            code: NanoWSD.WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            activeSessions.remove(sessionId)
            Log.d(TAG, "WebSocket closed: $sessionId")
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            try {
                val text = message.textPayload ?: return
                val msg = json.decodeFromString<WsInMessage>(text)

                when (msg.type) {
                    "auth" -> {
                        if (accessToken.isNullOrBlank() || msg.token == accessToken) {
                            authenticated = true
                            activeSessions[sessionId] = this
                            send(json.encodeToString(
                                WsOutMessage.serializer(),
                                WsOutMessage(type = "auth_ok")
                            ))
                        } else {
                            send(json.encodeToString(
                                WsOutMessage.serializer(),
                                WsOutMessage(type = "auth_fail")
                            ))
                        }
                    }
                    "message" -> {
                        if (!authenticated) return
                        val userText = msg.text ?: return
                        scope.launch {
                            onMessage(sessionId, userText)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing WebSocket message", e)
            }
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            activeSessions.remove(sessionId)
            Log.e(TAG, "WebSocket exception: $sessionId", exception)
        }
    }

    @Serializable
    data class WsInMessage(
        val type: String,
        val text: String? = null,
        val token: String? = null
    )

    @Serializable
    data class WsOutMessage(
        val type: String,
        val text: String? = null
    )

    companion object {
        private const val TAG = "WebChatServer"
    }
}
