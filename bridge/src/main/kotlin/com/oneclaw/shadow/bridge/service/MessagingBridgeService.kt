package com.oneclaw.shadow.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oneclaw.shadow.bridge.BridgeAgentExecutor
import com.oneclaw.shadow.bridge.BridgeConversationManager
import com.oneclaw.shadow.bridge.BridgeBroadcaster
import com.oneclaw.shadow.bridge.BridgeMessageObserver
import com.oneclaw.shadow.bridge.BridgePreferences
import com.oneclaw.shadow.bridge.BridgeStateTracker
import com.oneclaw.shadow.bridge.channel.ChannelType
import com.oneclaw.shadow.bridge.channel.ConversationMapper
import com.oneclaw.shadow.bridge.channel.MessagingChannel
import com.oneclaw.shadow.bridge.channel.discord.DiscordChannel
import com.oneclaw.shadow.bridge.channel.line.LineChannel
import com.oneclaw.shadow.bridge.channel.matrix.MatrixChannel
import com.oneclaw.shadow.bridge.channel.slack.SlackChannel
import com.oneclaw.shadow.bridge.channel.telegram.TelegramChannel
import com.oneclaw.shadow.bridge.channel.webchat.WebChatChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject

class MessagingBridgeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channelMutex = Mutex()
    private val channels = mutableListOf<MessagingChannel>()

    private val preferences: BridgePreferences by inject()
    private val credentialProvider: BridgeCredentialProvider by inject()
    private val agentExecutor: BridgeAgentExecutor by inject()
    private val messageObserver: BridgeMessageObserver by inject()
    private val conversationManager: BridgeConversationManager by inject()
    private val okHttpClient: OkHttpClient by inject()

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBridge()
            ACTION_STOP -> stopBridge()
            ACTION_RESTART -> restartBridge()
        }
        return START_STICKY
    }

    private fun startBridge() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        if (preferences.isWakeLockEnabled()) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            wakeLock?.acquire()
        }

        BridgeStateTracker.updateServiceRunning(true)

        serviceScope.launch {
            channelMutex.withLock {
                val conversationMapper = ConversationMapper(conversationManager)
                var startedCount = 0

                if (preferences.isTelegramEnabled()) {
                    val token = credentialProvider.getTelegramBotToken()
                    if (token != null) {
                        val channel = TelegramChannel(
                            botToken = token,
                            context = applicationContext,
                            okHttpClient = okHttpClient,
                            preferences = preferences,
                            conversationMapper = conversationMapper,
                            agentExecutor = agentExecutor,
                            messageObserver = messageObserver,
                            conversationManager = conversationManager,
                            scope = serviceScope
                        )
                        channel.start()
                        channels.add(channel)
                        BridgeBroadcaster.register(channel)
                        startedCount++
                    }
                }

                if (preferences.isDiscordEnabled()) {
                    val token = credentialProvider.getDiscordBotToken()
                    if (token != null) {
                        val channel = DiscordChannel(
                            botToken = token,
                            context = applicationContext,
                            okHttpClient = okHttpClient,
                            preferences = preferences,
                            conversationMapper = conversationMapper,
                            agentExecutor = agentExecutor,
                            messageObserver = messageObserver,
                            conversationManager = conversationManager,
                            scope = serviceScope
                        )
                        channel.start()
                        channels.add(channel)
                        BridgeBroadcaster.register(channel)
                        startedCount++
                    }
                }

                if (preferences.isSlackEnabled()) {
                    val appToken = credentialProvider.getSlackAppToken()
                    val botToken = credentialProvider.getSlackBotToken()
                    if (appToken != null && botToken != null) {
                        val channel = SlackChannel(
                            appToken = appToken,
                            botToken = botToken,
                            okHttpClient = okHttpClient,
                            preferences = preferences,
                            conversationMapper = conversationMapper,
                            agentExecutor = agentExecutor,
                            messageObserver = messageObserver,
                            conversationManager = conversationManager,
                            scope = serviceScope
                        )
                        channel.start()
                        channels.add(channel)
                        BridgeBroadcaster.register(channel)
                        startedCount++
                    }
                }

                if (preferences.isMatrixEnabled()) {
                    val token = credentialProvider.getMatrixAccessToken()
                    val homeserver = preferences.getMatrixHomeserver()
                    if (token != null && homeserver.isNotEmpty()) {
                        val channel = MatrixChannel(
                            homeserverUrl = homeserver,
                            accessToken = token,
                            okHttpClient = okHttpClient,
                            preferences = preferences,
                            conversationMapper = conversationMapper,
                            agentExecutor = agentExecutor,
                            messageObserver = messageObserver,
                            conversationManager = conversationManager,
                            scope = serviceScope
                        )
                        channel.start()
                        channels.add(channel)
                        BridgeBroadcaster.register(channel)
                        startedCount++
                    }
                }

                if (preferences.isLineEnabled()) {
                    val accessToken = credentialProvider.getLineChannelAccessToken()
                    val secret = credentialProvider.getLineChannelSecret()
                    if (accessToken != null && secret != null) {
                        val channel = LineChannel(
                            channelAccessToken = accessToken,
                            channelSecret = secret,
                            port = preferences.getLineWebhookPort(),
                            okHttpClient = okHttpClient,
                            preferences = preferences,
                            conversationMapper = conversationMapper,
                            agentExecutor = agentExecutor,
                            messageObserver = messageObserver,
                            conversationManager = conversationManager,
                            scope = serviceScope
                        )
                        channel.start()
                        channels.add(channel)
                        BridgeBroadcaster.register(channel)
                        startedCount++
                    }
                }

                if (preferences.isWebChatEnabled()) {
                    val accessToken = credentialProvider.getWebChatAccessToken()
                    val channel = WebChatChannel(
                        port = preferences.getWebChatPort(),
                        accessToken = accessToken,
                        preferences = preferences,
                        conversationMapper = conversationMapper,
                        agentExecutor = agentExecutor,
                        messageObserver = messageObserver,
                        conversationManager = conversationManager,
                        scope = serviceScope
                    )
                    channel.start()
                    channels.add(channel)
                    BridgeBroadcaster.register(channel)
                    startedCount++
                }

                if (startedCount == 0) {
                    Log.w(TAG, "No channels started -- stopping service")
                    stopBridge()
                } else {
                    Log.d(TAG, "Bridge started with $startedCount channel(s)")
                }
            }
        }
    }

    private fun restartBridge() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        serviceScope.launch {
            channelMutex.withLock {
                // Stop existing channels
                channels.forEach { channel ->
                    runCatching { channel.stop() }
                }
                channels.clear()
                BridgeBroadcaster.clear()
            }
            // Re-read preferences and start channels with updated config
            channelMutex.withLock {
                val conversationMapper = ConversationMapper(conversationManager)
                var startedCount = 0

                if (preferences.isTelegramEnabled()) {
                    val token = credentialProvider.getTelegramBotToken()
                    if (token != null) {
                        val channel = TelegramChannel(
                            botToken = token,
                            context = applicationContext,
                            okHttpClient = okHttpClient,
                            preferences = preferences,
                            conversationMapper = conversationMapper,
                            agentExecutor = agentExecutor,
                            messageObserver = messageObserver,
                            conversationManager = conversationManager,
                            scope = serviceScope
                        )
                        channel.start()
                        channels.add(channel)
                        BridgeBroadcaster.register(channel)
                        startedCount++
                    }
                }

                if (preferences.isDiscordEnabled()) {
                    val token = credentialProvider.getDiscordBotToken()
                    if (token != null) {
                        val channel = DiscordChannel(
                            botToken = token,
                            context = applicationContext,
                            okHttpClient = okHttpClient,
                            preferences = preferences,
                            conversationMapper = conversationMapper,
                            agentExecutor = agentExecutor,
                            messageObserver = messageObserver,
                            conversationManager = conversationManager,
                            scope = serviceScope
                        )
                        channel.start()
                        channels.add(channel)
                        BridgeBroadcaster.register(channel)
                        startedCount++
                    }
                }

                if (preferences.isSlackEnabled()) {
                    val appToken = credentialProvider.getSlackAppToken()
                    val botToken = credentialProvider.getSlackBotToken()
                    if (appToken != null && botToken != null) {
                        val channel = SlackChannel(
                            appToken = appToken,
                            botToken = botToken,
                            okHttpClient = okHttpClient,
                            preferences = preferences,
                            conversationMapper = conversationMapper,
                            agentExecutor = agentExecutor,
                            messageObserver = messageObserver,
                            conversationManager = conversationManager,
                            scope = serviceScope
                        )
                        channel.start()
                        channels.add(channel)
                        BridgeBroadcaster.register(channel)
                        startedCount++
                    }
                }

                if (preferences.isMatrixEnabled()) {
                    val token = credentialProvider.getMatrixAccessToken()
                    val homeserver = preferences.getMatrixHomeserver()
                    if (token != null && homeserver.isNotEmpty()) {
                        val channel = MatrixChannel(
                            homeserverUrl = homeserver,
                            accessToken = token,
                            okHttpClient = okHttpClient,
                            preferences = preferences,
                            conversationMapper = conversationMapper,
                            agentExecutor = agentExecutor,
                            messageObserver = messageObserver,
                            conversationManager = conversationManager,
                            scope = serviceScope
                        )
                        channel.start()
                        channels.add(channel)
                        BridgeBroadcaster.register(channel)
                        startedCount++
                    }
                }

                if (preferences.isLineEnabled()) {
                    val accessToken = credentialProvider.getLineChannelAccessToken()
                    val secret = credentialProvider.getLineChannelSecret()
                    if (accessToken != null && secret != null) {
                        val channel = LineChannel(
                            channelAccessToken = accessToken,
                            channelSecret = secret,
                            port = preferences.getLineWebhookPort(),
                            okHttpClient = okHttpClient,
                            preferences = preferences,
                            conversationMapper = conversationMapper,
                            agentExecutor = agentExecutor,
                            messageObserver = messageObserver,
                            conversationManager = conversationManager,
                            scope = serviceScope
                        )
                        channel.start()
                        channels.add(channel)
                        BridgeBroadcaster.register(channel)
                        startedCount++
                    }
                }

                if (preferences.isWebChatEnabled()) {
                    val accessToken = credentialProvider.getWebChatAccessToken()
                    val channel = WebChatChannel(
                        port = preferences.getWebChatPort(),
                        accessToken = accessToken,
                        preferences = preferences,
                        conversationMapper = conversationMapper,
                        agentExecutor = agentExecutor,
                        messageObserver = messageObserver,
                        conversationManager = conversationManager,
                        scope = serviceScope
                    )
                    channel.start()
                    channels.add(channel)
                    BridgeBroadcaster.register(channel)
                    startedCount++
                }

                if (startedCount == 0) {
                    Log.d(TAG, "No channels after restart -- stopping service")
                    stopBridge()
                } else {
                    Log.d(TAG, "Bridge restarted with $startedCount channel(s)")
                }
            }
        }
    }

    private fun stopBridge() {
        serviceScope.launch {
            channelMutex.withLock {
                channels.forEach { channel ->
                    runCatching { channel.stop() }
                }
                channels.clear()
                BridgeBroadcaster.clear()
            }
            wakeLock?.release()
            wakeLock = null
            BridgeStateTracker.reset()
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        wakeLock?.release()
        BridgeStateTracker.updateServiceRunning(false)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Messaging Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Messaging bridge foreground service"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Messaging Bridge")
            .setContentText("Listening for messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MessagingBridgeService"
        const val ACTION_START = "com.oneclaw.shadow.bridge.START"
        const val ACTION_STOP = "com.oneclaw.shadow.bridge.STOP"
        const val ACTION_RESTART = "com.oneclaw.shadow.bridge.RESTART"
        private const val NOTIFICATION_ID = 2024
        private const val CHANNEL_ID = "messaging_bridge"
        private const val WAKE_LOCK_TAG = "oneclaw:bridge_wake_lock"

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

        fun restart(context: Context) {
            val intent = Intent(context, MessagingBridgeService::class.java).apply {
                action = ACTION_RESTART
            }
            context.startForegroundService(intent)
        }
    }
}
