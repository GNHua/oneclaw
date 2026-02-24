package com.tomandy.oneclaw.bridge

import android.util.Log
import com.tomandy.oneclaw.bridge.channel.MessagingChannel

object BridgeBroadcaster {

    private const val TAG = "BridgeBroadcaster"

    private val channels = mutableListOf<MessagingChannel>()

    fun register(channel: MessagingChannel) {
        synchronized(channels) {
            channels.add(channel)
        }
    }

    fun unregister(channel: MessagingChannel) {
        synchronized(channels) {
            channels.remove(channel)
        }
    }

    fun clear() {
        synchronized(channels) {
            channels.clear()
        }
    }

    suspend fun broadcast(content: String) {
        val snapshot = synchronized(channels) { channels.toList() }
        val message = BridgeMessage(
            content = content,
            timestamp = System.currentTimeMillis()
        )
        for (channel in snapshot) {
            if (!channel.isRunning()) continue
            try {
                channel.broadcast(message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast to ${channel.channelType}", e)
            }
        }
    }
}
