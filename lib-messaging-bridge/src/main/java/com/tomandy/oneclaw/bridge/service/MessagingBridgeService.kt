package com.tomandy.oneclaw.bridge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tomandy.oneclaw.bridge.BridgeAgentExecutor
import com.tomandy.oneclaw.bridge.BridgeConversationManager
import com.tomandy.oneclaw.bridge.BridgeMessageObserver
import com.tomandy.oneclaw.bridge.BridgePreferences
import com.tomandy.oneclaw.bridge.BridgeStateTracker
import com.tomandy.oneclaw.bridge.ConversationMapper
import com.tomandy.oneclaw.bridge.R
import com.tomandy.oneclaw.bridge.channel.MessagingChannel
import com.tomandy.oneclaw.bridge.channel.discord.DiscordChannel
import com.tomandy.oneclaw.bridge.channel.telegram.TelegramChannel
import com.tomandy.oneclaw.bridge.channel.webchat.WebChatChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MessagingBridgeService : Service(), KoinComponent {

    private val agentExecutor: BridgeAgentExecutor by inject()
    private val messageObserver: BridgeMessageObserver by inject()
    private val conversationManager: BridgeConversationManager by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var preferences: BridgePreferences
    private val channels = mutableListOf<MessagingChannel>()

    override fun onCreate() {
        super.onCreate()
        preferences = BridgePreferences(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                startEnabledChannels()
                BridgeStateTracker.updateServiceRunning(true)
                Log.i(TAG, "Messaging bridge service started")
            }
            ACTION_STOP -> {
                stopAllChannels()
                BridgeStateTracker.reset()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "Messaging bridge service stopped")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        BridgeStateTracker.reset()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startEnabledChannels() {
        stopAllChannels()

        val mapper = ConversationMapper(preferences, conversationManager)
        val credentialProvider = BridgeCredentialProvider(applicationContext)

        if (preferences.isTelegramEnabled()) {
            val botToken = credentialProvider.getTelegramBotToken()
            if (!botToken.isNullOrBlank()) {
                val telegram = TelegramChannel(
                    preferences = preferences,
                    conversationMapper = mapper,
                    agentExecutor = agentExecutor,
                    messageObserver = messageObserver,
                    conversationManager = conversationManager,
                    scope = serviceScope,
                    botToken = botToken
                )
                channels.add(telegram)
                serviceScope.launch { telegram.start() }
                Log.i(TAG, "Telegram channel started")
            }
        }

        if (preferences.isDiscordEnabled()) {
            val discordToken = credentialProvider.getDiscordBotToken()
            if (!discordToken.isNullOrBlank()) {
                val discord = DiscordChannel(
                    preferences = preferences,
                    conversationMapper = mapper,
                    agentExecutor = agentExecutor,
                    messageObserver = messageObserver,
                    conversationManager = conversationManager,
                    scope = serviceScope,
                    botToken = discordToken
                )
                channels.add(discord)
                serviceScope.launch { discord.start() }
                Log.i(TAG, "Discord channel started")
            }
        }

        if (preferences.isWebChatEnabled()) {
            val port = preferences.getWebChatPort()
            val webChatToken = credentialProvider.getWebChatAccessToken()
            val webchat = WebChatChannel(
                preferences = preferences,
                conversationMapper = mapper,
                agentExecutor = agentExecutor,
                messageObserver = messageObserver,
                conversationManager = conversationManager,
                scope = serviceScope,
                port = port,
                accessToken = webChatToken
            )
            channels.add(webchat)
            serviceScope.launch { webchat.start() }
            Log.i(TAG, "WebChat channel started on port $port")
        }
    }

    private fun stopAllChannels() {
        serviceScope.launch {
            channels.forEach { channel ->
                try {
                    channel.stop()
                    BridgeStateTracker.removeChannelState(channel.channelType)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping ${channel.channelType}", e)
                }
            }
            channels.clear()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Messaging Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the messaging bridge active"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Messaging Bridge")
            .setContentText("Listening for messages")
            .setSmallIcon(R.drawable.ic_bridge_notification)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "BridgeService"
        const val ACTION_START = "com.tomandy.oneclaw.bridge.START"
        const val ACTION_STOP = "com.tomandy.oneclaw.bridge.STOP"
        private const val CHANNEL_ID = "messaging_bridge_channel"
        private const val NOTIFICATION_ID = 1003

        fun start(context: Context) {
            val intent = Intent(context, MessagingBridgeService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MessagingBridgeService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
