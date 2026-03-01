package com.oneclaw.shadow.bridge

import com.oneclaw.shadow.bridge.channel.MessagingChannel
import java.util.concurrent.CopyOnWriteArrayList

object BridgeBroadcaster {

    private val channels = CopyOnWriteArrayList<MessagingChannel>()

    fun register(channel: MessagingChannel) {
        channels.add(channel)
    }

    fun unregister(channel: MessagingChannel) {
        channels.remove(channel)
    }

    fun clear() {
        channels.clear()
    }

    suspend fun broadcast(content: String) {
        val message = BridgeMessage(
            content = content,
            timestamp = System.currentTimeMillis()
        )
        channels.forEach { channel ->
            runCatching { channel.broadcast(message) }
        }
    }
}
