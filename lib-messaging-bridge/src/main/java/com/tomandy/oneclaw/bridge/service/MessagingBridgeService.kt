package com.tomandy.oneclaw.bridge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tomandy.oneclaw.bridge.BridgeAgentExecutor
import com.tomandy.oneclaw.bridge.BridgeBroadcaster
import com.tomandy.oneclaw.bridge.BridgeConversationManager
import com.tomandy.oneclaw.bridge.BridgeMessageObserver
import com.tomandy.oneclaw.bridge.BridgePreferences
import com.tomandy.oneclaw.bridge.BridgeStateTracker
import com.tomandy.oneclaw.bridge.ConversationMapper
import com.tomandy.oneclaw.bridge.R
import com.tomandy.oneclaw.bridge.channel.MessagingChannel
import com.tomandy.oneclaw.bridge.channel.discord.DiscordChannel
import com.tomandy.oneclaw.bridge.channel.line.LineChannel
import com.tomandy.oneclaw.bridge.channel.matrix.MatrixChannel
import com.tomandy.oneclaw.bridge.channel.slack.SlackChannel
import com.tomandy.oneclaw.bridge.channel.telegram.TelegramChannel
import com.tomandy.oneclaw.bridge.channel.webchat.WebChatChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MessagingBridgeService : Service(), KoinComponent {

    private val agentExecutor: BridgeAgentExecutor by inject()
    private val messageObserver: BridgeMessageObserver by inject()
    private val conversationManager: BridgeConversationManager by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channelMutex = Mutex()
    private lateinit var preferences: BridgePreferences
    private val channels = mutableListOf<MessagingChannel>()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        preferences = BridgePreferences(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                serviceScope.launch {
                    startEnabledChannels()
                    if (channels.isEmpty()) {
                        releaseWakeLock()
                        BridgeStateTracker.reset()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        Log.i(TAG, "No channels started, stopping service")
                        return@launch
                    }
                    if (preferences.isWakeLockEnabled()) {
                        acquireWakeLock()
                    }
                    BridgeStateTracker.updateServiceRunning(true)
                    Log.i(TAG, "Messaging bridge service started")
                }
            }
            ACTION_STOP -> {
                serviceScope.launch {
                    stopAllChannels()
                    releaseWakeLock()
                    BridgeStateTracker.reset()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    Log.i(TAG, "Messaging bridge service stopped")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
        BridgeStateTracker.reset()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private suspend fun startEnabledChannels() = channelMutex.withLock {
        stopAllChannelsLocked()

        val mapper = ConversationMapper(conversationManager)
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
                BridgeBroadcaster.register(telegram)
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
                BridgeBroadcaster.register(discord)
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
            BridgeBroadcaster.register(webchat)
            serviceScope.launch { webchat.start() }
            Log.i(TAG, "WebChat channel started on port $port")
        }

        if (preferences.isSlackEnabled()) {
            val botToken = credentialProvider.getSlackBotToken()
            val appToken = credentialProvider.getSlackAppToken()
            if (!botToken.isNullOrBlank() && !appToken.isNullOrBlank()) {
                val slack = SlackChannel(
                    preferences = preferences,
                    conversationMapper = mapper,
                    agentExecutor = agentExecutor,
                    messageObserver = messageObserver,
                    conversationManager = conversationManager,
                    scope = serviceScope,
                    botToken = botToken,
                    appToken = appToken
                )
                channels.add(slack)
                BridgeBroadcaster.register(slack)
                serviceScope.launch { slack.start() }
                Log.i(TAG, "Slack channel started")
            }
        }

        if (preferences.isMatrixEnabled()) {
            val accessToken = credentialProvider.getMatrixAccessToken()
            val homeserver = preferences.getMatrixHomeserver()
            if (!accessToken.isNullOrBlank() && homeserver.isNotBlank()) {
                val matrix = MatrixChannel(
                    preferences = preferences,
                    conversationMapper = mapper,
                    agentExecutor = agentExecutor,
                    messageObserver = messageObserver,
                    conversationManager = conversationManager,
                    scope = serviceScope,
                    homeserverUrl = homeserver,
                    accessToken = accessToken
                )
                channels.add(matrix)
                BridgeBroadcaster.register(matrix)
                serviceScope.launch { matrix.start() }
                Log.i(TAG, "Matrix channel started")
            }
        }

        if (preferences.isLineEnabled()) {
            val lineAccessToken = credentialProvider.getLineChannelAccessToken()
            val lineSecret = credentialProvider.getLineChannelSecret()
            if (!lineAccessToken.isNullOrBlank() && !lineSecret.isNullOrBlank()) {
                val line = LineChannel(
                    preferences = preferences,
                    conversationMapper = mapper,
                    agentExecutor = agentExecutor,
                    messageObserver = messageObserver,
                    conversationManager = conversationManager,
                    scope = serviceScope,
                    channelAccessToken = lineAccessToken,
                    channelSecret = lineSecret,
                    webhookPort = preferences.getLineWebhookPort()
                )
                channels.add(line)
                BridgeBroadcaster.register(line)
                serviceScope.launch { line.start() }
                Log.i(TAG, "LINE channel started on port ${preferences.getLineWebhookPort()}")
            }
        }
    }

    private suspend fun stopAllChannels() = channelMutex.withLock {
        stopAllChannelsLocked()
    }

    private suspend fun stopAllChannelsLocked() {
        BridgeBroadcaster.clear()
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
        private const val WAKE_LOCK_TAG = "oneclaw:bridge_service"

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
