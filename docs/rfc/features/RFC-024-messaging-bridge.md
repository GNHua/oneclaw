# RFC-024: Messaging Bridge

## Document Information
- **RFC ID**: RFC-024
- **Related PRD**: [FEAT-024 (Messaging Bridge)](../../prd/features/FEAT-024-messaging-bridge.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
OneClaw currently requires users to open the Android app to interact with AI agents. Many users spend their day in messaging platforms like Telegram, Discord, or Slack. A messaging bridge would allow them to chat with their agents from any platform, on any device, without switching apps.

The reference implementation (`oneclaw-1/lib-messaging-bridge`) provides a proven architecture supporting 6 channels with a channel abstraction layer, foreground service, and comprehensive state management. This RFC adapts that architecture to OneClaw as an independent `:bridge` Gradle module.

### Goals
1. Implement an independent `:bridge` Gradle module containing all messaging bridge logic
2. Support 6 messaging channels: Telegram, Discord, Slack, Matrix, LINE, WebChat
3. Run as a foreground service with reliable background execution
4. Provide a full Bridge settings screen for channel configuration and status monitoring
5. Support inbound image messages from Telegram and Discord
6. Integrate with the existing agent execution pipeline via well-defined interfaces

### Non-Goals
- Rich media outbound responses (images, files, audio sent from agent to platform)
- Voice message support
- End-to-end encryption for WebChat
- Multi-agent routing (all messages go to a single bridge conversation)
- Remote bridge management or web admin panel
- Rate limiting or throttling

## Technical Design

### Architecture Overview

The bridge is structured as an independent Gradle module (`:bridge`) that depends only on Kotlin stdlib, coroutines, OkHttp, NanoHTTPD, Commonmark, AndroidX Core, AndroidX Security Crypto, and WorkManager. It defines interfaces for agent execution, message observation, and conversation management that the `:app` module implements.

```
┌──────────────────────────────────────────────────────────────────┐
│                        :app module                                │
│                                                                   │
│  ┌─────────────────┐  ┌──────────────────┐  ┌─────────────────┐ │
│  │ BridgeAgent      │  │ BridgeMessage     │  │ BridgeConv      │ │
│  │ ExecutorImpl      │  │ ObserverImpl      │  │ ManagerImpl     │ │
│  └────────┬─────────┘  └────────┬──────────┘  └────────┬────────┘ │
│           │implements           │implements            │implements │
├───────────┼─────────────────────┼──────────────────────┼──────────┤
│           ▼                     ▼                      ▼          │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │                      :bridge module                          │ │
│  │                                                              │ │
│  │  ┌────────────────────────────────────────────────────────┐  │ │
│  │  │              MessagingBridgeService                     │  │ │
│  │  │              (Foreground Service)                       │  │ │
│  │  └────────────────────┬───────────────────────────────────┘  │ │
│  │                       │ manages                              │ │
│  │  ┌────────┬───────────┼───────────┬──────────┬────────────┐  │ │
│  │  ▼        ▼           ▼           ▼          ▼            ▼  │ │
│  │ Telegram Discord    Slack      Matrix      LINE       WebChat│ │
│  │ Channel  Channel   Channel    Channel    Channel     Channel │ │
│  │  │        │          │          │          │            │     │ │
│  │  └────────┴──────────┴──────────┴──────────┴────────────┘    │ │
│  │                       │ extends                              │ │
│  │              MessagingChannel (abstract)                      │ │
│  │                                                              │ │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐  │ │
│  │  │ BridgeState  │ │ BridgePref   │ │ BridgeCredential     │  │ │
│  │  │ Tracker      │ │ erences      │ │ Provider             │  │ │
│  │  └──────────────┘ └──────────────┘ └──────────────────────┘  │ │
│  │                                                              │ │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐  │ │
│  │  │ BridgeAgent  │ │ BridgeMessage│ │ BridgeConversation   │  │ │
│  │  │ Executor     │ │ Observer     │ │ Manager              │  │ │
│  │  │ (interface)  │ │ (interface)  │ │ (interface)          │  │ │
│  │  └──────────────┘ └──────────────┘ └──────────────────────┘  │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │                    Bridge Settings UI                         │ │
│  │  BridgeSettingsScreen + BridgeSettingsViewModel               │ │
│  └──────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### Core Components

#### 1. MessagingChannel (Abstract Base Class)

The channel abstraction provides a common message processing pipeline with deduplication, access control, typing indicators, and error handling. Each platform implements only the transport layer.

```kotlin
package com.oneclaw.shadow.bridge.channel

abstract class MessagingChannel(
    val channelType: ChannelType,
    protected val preferences: BridgePreferences,
    protected val conversationMapper: ConversationMapper,
    protected val agentExecutor: BridgeAgentExecutor,
    protected val messageObserver: BridgeMessageObserver,
    protected val conversationManager: BridgeConversationManager,
    protected val scope: CoroutineScope
) {
    // --- Abstract: transport layer ---
    abstract suspend fun start()
    abstract suspend fun stop()
    abstract fun isRunning(): Boolean
    protected abstract suspend fun sendResponse(externalChatId: String, message: BridgeMessage)

    // --- Open: optional platform features ---
    protected open suspend fun sendTypingIndicator(externalChatId: String) {}
    open suspend fun broadcast(message: BridgeMessage) { /* send to last known chat ID */ }

    // --- Concrete: shared message pipeline ---
    protected suspend fun processInboundMessage(msg: ChannelMessage) {
        // 1. Deduplication (500-entry LRU cache)
        // 2. Access control (whitelist check)
        // 3. Persist last chat ID for broadcast
        // 4. Handle /clear command
        // 5. Resolve conversation ID
        // 6. Insert user message
        // 7. Execute agent
        // 8. Launch typing indicator coroutine (every 4s)
        // 9. Await assistant response (300s timeout)
        // 10. Cancel typing, send response
    }

    companion object {
        private const val TYPING_INTERVAL_MS = 4000L
        private const val MAX_DEDUP_SIZE = 500
    }
}
```

#### 2. MessagingBridgeService (Foreground Service)

```kotlin
package com.oneclaw.shadow.bridge.service

class MessagingBridgeService : Service(), KoinComponent {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channelMutex = Mutex()
    private val channels = mutableListOf<MessagingChannel>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBridge()
            ACTION_STOP -> stopBridge()
        }
        return START_STICKY
    }

    private fun startBridge() {
        // 1. Create foreground notification
        // 2. Acquire wake lock if enabled
        // 3. Start enabled channels (mutex-protected)
        // 4. If no channels started, stop self
    }

    private fun stopBridge() {
        // 1. Stop all channels (mutex-protected)
        // 2. Release wake lock
        // 3. Reset state tracker
        // 4. Stop self
    }

    companion object {
        const val ACTION_START = "com.oneclaw.shadow.bridge.START"
        const val ACTION_STOP = "com.oneclaw.shadow.bridge.STOP"

        fun start(context: Context) { /* startForegroundService */ }
        fun stop(context: Context) { /* startService with ACTION_STOP */ }
    }
}
```

#### 3. BridgeStateTracker (Singleton State)

```kotlin
package com.oneclaw.shadow.bridge

object BridgeStateTracker {
    val serviceRunning: StateFlow<Boolean>
    val channelStates: StateFlow<Map<ChannelType, ChannelState>>

    fun updateServiceRunning(running: Boolean)
    fun updateChannelState(type: ChannelType, state: ChannelState)
    fun removeChannelState(type: ChannelType)
    fun reset()

    data class ChannelState(
        val isRunning: Boolean,
        val connectedSince: Long? = null,
        val lastMessageAt: Long? = null,
        val error: String? = null,
        val messageCount: Int = 0
    )
}
```

#### 4. BridgePreferences (Configuration Storage)

```kotlin
package com.oneclaw.shadow.bridge

class BridgePreferences(context: Context) {
    // SharedPreferences "messaging_bridge"

    // Master toggle
    fun isBridgeEnabled(): Boolean
    fun setBridgeEnabled(enabled: Boolean)

    // Per-channel enable/disable (Telegram, Discord, WebChat, Slack, Matrix, LINE)
    fun isTelegramEnabled(): Boolean
    fun setTelegramEnabled(enabled: Boolean)
    // ... same pattern for all 6 channels

    // Per-channel allowed user IDs (whitelist)
    fun getAllowedTelegramUserIds(): Set<String>
    fun setAllowedTelegramUserIds(ids: Set<String>)
    // ... same pattern for 5 channels (no whitelist for WebChat)

    // Channel-specific config
    fun getWebChatPort(): Int           // default: 8080
    fun getLineWebhookPort(): Int       // default: 8081
    fun getMatrixHomeserver(): String   // default: ""

    // Wake lock
    fun isWakeLockEnabled(): Boolean

    // Conversation mapping
    fun getBridgeConversationId(): String?
    fun setBridgeConversationId(conversationId: String)
    fun getMappedConversationId(externalKey: String): String?
    fun setMappedConversationId(externalKey: String, conversationId: String)

    // Last chat ID per channel (for broadcast)
    fun getLastChatId(channelType: ChannelType): String?
    fun setLastChatId(channelType: ChannelType, chatId: String)

    // Telegram polling offset
    fun getTelegramUpdateOffset(): Long
    fun setTelegramUpdateOffset(offset: Long)

    // Utility
    fun hasAnyChannelEnabled(): Boolean
}
```

#### 5. BridgeCredentialProvider (Encrypted Credential Storage)

```kotlin
package com.oneclaw.shadow.bridge.service

class BridgeCredentialProvider(context: Context) {
    // EncryptedSharedPreferences "bridge_credentials"
    // Fallback to plain SharedPreferences if encryption fails

    // Telegram
    fun getTelegramBotToken(): String?
    fun saveTelegramBotToken(token: String)

    // Discord
    fun getDiscordBotToken(): String?
    fun saveDiscordBotToken(token: String)

    // Slack (two tokens)
    fun getSlackBotToken(): String?
    fun saveSlackBotToken(token: String)
    fun getSlackAppToken(): String?
    fun saveSlackAppToken(token: String)

    // Matrix
    fun getMatrixAccessToken(): String?
    fun saveMatrixAccessToken(token: String)

    // LINE (two credentials)
    fun getLineChannelAccessToken(): String?
    fun saveLineChannelAccessToken(token: String)
    fun getLineChannelSecret(): String?
    fun saveLineChannelSecret(token: String)

    // WebChat
    fun getWebChatAccessToken(): String?
    fun saveWebChatAccessToken(token: String)
}
```

#### 6. Bridge Interfaces (implemented by :app module)

```kotlin
package com.oneclaw.shadow.bridge

// Triggers agent execution for a given conversation
interface BridgeAgentExecutor {
    suspend fun executeMessage(
        conversationId: String,
        userMessage: String,
        imagePaths: List<String> = emptyList()
    )
}

// Observes agent responses with timeout
interface BridgeMessageObserver {
    suspend fun awaitNextAssistantMessage(
        conversationId: String,
        afterTimestamp: Long = System.currentTimeMillis(),
        timeoutMs: Long = 300_000
    ): BridgeMessage
}

// Manages conversations for the bridge
interface BridgeConversationManager {
    fun getActiveConversationId(): String?
    suspend fun createNewConversation(): String
    suspend fun createConversation(conversationId: String, title: String)
    suspend fun conversationExists(conversationId: String): Boolean
    suspend fun insertUserMessage(
        conversationId: String,
        content: String,
        imagePaths: List<String> = emptyList()
    )
    suspend fun updateConversationTimestamp(conversationId: String)
}
```

### Data Model

#### ChannelType Enum

```kotlin
package com.oneclaw.shadow.bridge.channel

enum class ChannelType {
    TELEGRAM, DISCORD, WEBCHAT, SLACK, MATRIX, LINE
}
```

#### ChannelMessage (Inbound)

```kotlin
package com.oneclaw.shadow.bridge.channel

data class ChannelMessage(
    val externalChatId: String,
    val senderName: String?,
    val senderId: String?,
    val text: String,
    val imagePaths: List<String> = emptyList(),
    val messageId: String? = null
)
```

#### BridgeMessage (Outbound)

```kotlin
package com.oneclaw.shadow.bridge

data class BridgeMessage(
    val content: String,
    val timestamp: Long,
    val imagePaths: List<String> = emptyList()
)
```

### Channel Implementations

#### TelegramChannel

- **Connection**: Long-polling via `getUpdates()` with 30-second timeout
- **API**: OkHttp HTTP calls to `api.telegram.org/bot<token>/`
- **Message reception**: Polls for updates, extracts text and photo messages
- **Response sending**: `sendMessage()` with HTML parse mode
- **Image handling**: `getFile()` -> download to `bridge_images/`
- **Message splitting**: 4096 char limit, splits at paragraph > sentence > word boundaries
- **Typing indicator**: `sendChatAction(action=typing)`
- **HTML rendering**: Commonmark markdown-to-HTML conversion via `TelegramHtmlRenderer`
- **Backoff**: Exponential backoff on errors (3s initial, doubles to 60s max)

```kotlin
class TelegramChannel(...) : MessagingChannel(...) {
    private val api = TelegramApi(botToken, okHttpClient)
    private val imageStorage = BridgeImageStorage(context)

    override suspend fun start() { /* polling loop */ }
    override suspend fun stop() { /* cancel polling */ }
    override fun isRunning(): Boolean
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage)
    override suspend fun sendTypingIndicator(externalChatId: String)
}
```

Supporting classes:
- `TelegramApi` -- HTTP client wrapper for Bot API endpoints
- `TelegramHtmlRenderer` -- Commonmark markdown -> Telegram HTML conversion
- `BridgeImageStorage` -- Downloads and stores images locally

#### DiscordChannel

- **Connection**: WebSocket gateway (`wss://gateway.discord.gg`)
- **Protocol**: Discord Gateway v10 with heartbeat, sequence tracking, intent filtering
- **Intents**: `GUILD_MESSAGES | DIRECT_MESSAGES | MESSAGE_CONTENT` (= 33281)
- **Message reception**: `MESSAGE_CREATE` event, filtered to non-bot messages
- **Response sending**: REST API `POST /channels/{channelId}/messages`
- **Image handling**: Downloads from attachment URLs
- **Backoff**: Reconnection with exponential backoff

```kotlin
class DiscordChannel(...) : MessagingChannel(...) {
    private val gateway = DiscordGateway(botToken, okHttpClient, ::onMessage)

    override suspend fun start() { /* connect gateway */ }
    override suspend fun stop() { /* disconnect */ }
    override fun isRunning(): Boolean
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage)
}
```

Supporting class:
- `DiscordGateway` -- WebSocket client handling HELLO, HEARTBEAT, IDENTIFY, DISPATCH events

#### SlackChannel

- **Connection**: Socket Mode WebSocket via `apps.connections.open` API
- **Authentication**: Requires both `appToken` (xapp-) and `botToken` (xoxb-)
- **Message reception**: `events_api` envelopes with `message` event type
- **Filtering**: Ignores bot messages (non-null `botId`), ignores subtypes (edits, deletes)
- **Envelope acknowledgment**: Required for every received envelope
- **Response sending**: `chat.postMessage` REST API

```kotlin
class SlackChannel(...) : MessagingChannel(...) {
    private val socketMode = SlackSocketMode(appToken, botToken, okHttpClient, ::onMessage)

    override suspend fun start() { /* connect socket mode */ }
    override suspend fun stop() { /* disconnect */ }
    override fun isRunning(): Boolean
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage)
}
```

Supporting class:
- `SlackSocketMode` -- WebSocket client for Slack Socket Mode API

#### MatrixChannel

- **Connection**: Long-polling `/sync` endpoint with 30-second timeout
- **Configuration**: Requires homeserver URL and access token
- **Message reception**: `m.room.message` events from sync response timeline
- **Response sending**: `PUT /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}`
- **Typing indicator**: `PUT /_matrix/client/v3/rooms/{roomId}/typing/{userId}`
- **State tracking**: Uses `nextBatch` token for incremental sync

```kotlin
class MatrixChannel(...) : MessagingChannel(...) {
    private val api = MatrixApi(homeserverUrl, accessToken, okHttpClient)

    override suspend fun start() { /* sync loop */ }
    override suspend fun stop() { /* cancel sync */ }
    override fun isRunning(): Boolean
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage)
    override suspend fun sendTypingIndicator(externalChatId: String)
}
```

Supporting class:
- `MatrixApi` -- HTTP client wrapper for Matrix Client-Server API

#### LineChannel

- **Connection**: Embedded HTTP server (NanoHTTPD) on configurable port
- **Webhook**: Receives POST requests to `/webhook` endpoint
- **Security**: HMAC-SHA256 signature verification using `channelSecret`
- **Message reception**: Webhook events of type `message` with `text` message type
- **Response sending**: LINE Push API via `channelAccessToken`

```kotlin
class LineChannel(...) : MessagingChannel(...) {
    private val webhookServer = LineWebhookServer(port, channelSecret, ::onWebhookEvent)
    private val api = LineApi(channelAccessToken, okHttpClient)

    override suspend fun start() { /* start webhook server */ }
    override suspend fun stop() { /* stop server */ }
    override fun isRunning(): Boolean
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage)
}
```

Supporting classes:
- `LineWebhookServer` -- NanoHTTPD server with signature verification
- `LineApi` -- HTTP client for LINE Push API

#### WebChatChannel

- **Connection**: WebSocket server (NanoWSD) on configurable port
- **Authentication**: Optional token-based JWT auth
- **Protocol**: JSON messages `{type: "auth"|"message", text?, token?}`
- **Response format**: JSON `{type: "auth_ok"|"auth_fail"|"response"|"typing", text?}`
- **Typing indicator**: Sends `{type: "typing"}` WebSocket frame
- **Multi-session**: Tracks per-connection sessions for routing

```kotlin
class WebChatChannel(...) : MessagingChannel(...) {
    private val server = WebChatServer(port, accessToken, ::onMessage)

    override suspend fun start() { /* start WebSocket server */ }
    override suspend fun stop() { /* stop server */ }
    override fun isRunning(): Boolean
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage)
    override suspend fun sendTypingIndicator(externalChatId: String)
}
```

Supporting class:
- `WebChatServer` -- NanoWSD WebSocket server with JSON protocol

### Supporting Components

#### ConversationMapper

Resolves or creates conversation IDs for the bridge. Uses `BridgeConversationManager` to check if the stored conversation still exists and creates a new one if needed.

```kotlin
class ConversationMapper(private val conversationManager: BridgeConversationManager) {
    suspend fun resolveConversationId(): String
    suspend fun createNewConversation(): String
}
```

#### BridgeBroadcaster

Singleton that sends a message to all registered channels' last known chat IDs.

```kotlin
object BridgeBroadcaster {
    fun register(channel: MessagingChannel)
    fun unregister(channel: MessagingChannel)
    fun clear()
    suspend fun broadcast(content: String)
}
```

#### BridgeImageStorage

Downloads images from external URLs and stores them in the app's internal `bridge_images/` directory with UUID filenames. Detects image format from content.

```kotlin
class BridgeImageStorage(private val context: Context) {
    suspend fun downloadAndStore(url: String, headers: Map<String, String> = emptyMap()): String?
    fun getImageDir(): File
}
```

#### BridgeWatchdogWorker

WorkManager periodic worker that checks if the bridge service is running and restarts it if needed.

```kotlin
class BridgeWatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // Check BridgeStateTracker.serviceRunning
        // If should be running but isn't, restart via MessagingBridgeService.start()
        return Result.success()
    }
}
```

### UI Layer Design

#### Bridge Settings Screen

**Route**: `Route.BridgeSettings` (`"bridge-settings"`)

**ViewModel**: `BridgeSettingsViewModel`

```kotlin
data class BridgeSettingsUiState(
    val bridgeEnabled: Boolean = false,
    val serviceRunning: Boolean = false,
    val wakeLockEnabled: Boolean = false,

    // Per-channel config
    val telegramEnabled: Boolean = false,
    val telegramBotToken: String = "",
    val telegramAllowedUserIds: String = "",

    val discordEnabled: Boolean = false,
    val discordBotToken: String = "",
    val discordAllowedUserIds: String = "",

    val slackEnabled: Boolean = false,
    val slackBotToken: String = "",
    val slackAppToken: String = "",
    val slackAllowedUserIds: String = "",

    val matrixEnabled: Boolean = false,
    val matrixAccessToken: String = "",
    val matrixHomeserver: String = "",
    val matrixAllowedUserIds: String = "",

    val lineEnabled: Boolean = false,
    val lineChannelAccessToken: String = "",
    val lineChannelSecret: String = "",
    val lineWebhookPort: Int = 8081,
    val lineAllowedUserIds: String = "",

    val webChatEnabled: Boolean = false,
    val webChatAccessToken: String = "",
    val webChatPort: Int = 8080,

    // Real-time channel states
    val channelStates: Map<ChannelType, BridgeStateTracker.ChannelState> = emptyMap()
)

class BridgeSettingsViewModel(
    private val preferences: BridgePreferences,
    private val credentialProvider: BridgeCredentialProvider
) : ViewModel() {
    val uiState: StateFlow<BridgeSettingsUiState>

    fun toggleBridge(enabled: Boolean)
    fun startService()
    fun stopService()
    fun toggleWakeLock(enabled: Boolean)

    // Per-channel toggle and credential update methods
    fun toggleTelegram(enabled: Boolean)
    fun updateTelegramBotToken(token: String)
    fun updateTelegramAllowedUserIds(ids: String)
    // ... same pattern for all 6 channels
}
```

**Screen layout**:

```
┌──────────────────────────────────────┐
│ <- Bridge Settings                    │
├──────────────────────────────────────┤
│                                      │
│ [==] Messaging Bridge         [ON]   │
│ [Start/Stop Bridge Button]           │
│ [==] Wake Lock               [OFF]  │
│                                      │
├──────────────────────────────────────┤
│ Telegram                    [v][ON]  │
│ ┌──────────────────────────────────┐ │
│ │ Bot Token: [**************]      │ │
│ │ Allowed Users: [123456, 789012]  │ │
│ │ Status: Connected (2h 15m)       │ │
│ │ Messages: 47                     │ │
│ └──────────────────────────────────┘ │
│                                      │
│ Discord                    [v][OFF]  │
│ ┌──────────────────────────────────┐ │
│ │ Bot Token: [                   ] │ │
│ │ Allowed Users: [               ] │ │
│ │ Status: Disabled                 │ │
│ └──────────────────────────────────┘ │
│                                      │
│ Slack                      [>][OFF]  │
│                                      │
│ Matrix                     [>][OFF]  │
│                                      │
│ LINE                       [>][OFF]  │
│                                      │
│ WebChat                    [>][OFF]  │
│                                      │
└──────────────────────────────────────┘
```

### Module Structure

#### `:bridge` module (new)

```
bridge/
├── build.gradle.kts
└── src/main/
    ├── kotlin/com/oneclaw/shadow/bridge/
    │   ├── BridgeMessage.kt
    │   ├── BridgeAgentExecutor.kt
    │   ├── BridgeMessageObserver.kt
    │   ├── BridgeConversationManager.kt
    │   ├── BridgePreferences.kt
    │   ├── BridgeStateTracker.kt
    │   ├── BridgeBroadcaster.kt
    │   │
    │   ├── channel/
    │   │   ├── ChannelType.kt
    │   │   ├── ChannelMessage.kt
    │   │   ├── MessagingChannel.kt
    │   │   ├── ConversationMapper.kt
    │   │   │
    │   │   ├── telegram/
    │   │   │   ├── TelegramChannel.kt
    │   │   │   ├── TelegramApi.kt
    │   │   │   └── TelegramHtmlRenderer.kt
    │   │   │
    │   │   ├── discord/
    │   │   │   ├── DiscordChannel.kt
    │   │   │   └── DiscordGateway.kt
    │   │   │
    │   │   ├── slack/
    │   │   │   ├── SlackChannel.kt
    │   │   │   └── SlackSocketMode.kt
    │   │   │
    │   │   ├── matrix/
    │   │   │   ├── MatrixChannel.kt
    │   │   │   └── MatrixApi.kt
    │   │   │
    │   │   ├── line/
    │   │   │   ├── LineChannel.kt
    │   │   │   ├── LineWebhookServer.kt
    │   │   │   └── LineApi.kt
    │   │   │
    │   │   └── webchat/
    │   │       ├── WebChatChannel.kt
    │   │       └── WebChatServer.kt
    │   │
    │   ├── image/
    │   │   └── BridgeImageStorage.kt
    │   │
    │   └── service/
    │       ├── MessagingBridgeService.kt
    │       ├── BridgeCredentialProvider.kt
    │       └── BridgeWatchdogWorker.kt
    │
    └── AndroidManifest.xml
```

#### `:app` module additions

```
app/src/main/kotlin/com/oneclaw/shadow/
├── feature/bridge/
│   ├── BridgeSettingsScreen.kt
│   ├── BridgeSettingsViewModel.kt
│   ├── BridgeSettingsUiState.kt
│   ├── BridgeAgentExecutorImpl.kt
│   ├── BridgeMessageObserverImpl.kt
│   └── BridgeConversationManagerImpl.kt
│
├── di/
│   └── BridgeModule.kt              (new Koin module)
│
└── navigation/
    └── Routes.kt                     (add BridgeSettings route)
```

### Dependency Injection

#### Bridge Koin Module (in :app)

```kotlin
// di/BridgeModule.kt
val bridgeModule = module {
    // Bridge infrastructure
    single { BridgePreferences(androidContext()) }
    single { BridgeCredentialProvider(androidContext()) }

    // Bridge interfaces -> implementations
    single<BridgeAgentExecutor> { BridgeAgentExecutorImpl(get(), get(), get()) }
    single<BridgeMessageObserver> { BridgeMessageObserverImpl(get()) }
    single<BridgeConversationManager> { BridgeConversationManagerImpl(get(), get()) }

    // ViewModel
    viewModel { BridgeSettingsViewModel(get(), get()) }
}
```

Register in `OneclawApplication.onCreate()`:

```kotlin
startKoin {
    androidContext(this@OneclawApplication)
    modules(
        appModule, databaseModule, networkModule, repositoryModule,
        toolModule, featureModule, memoryModule,
        bridgeModule  // RFC-024
    )
}
```

### Gradle Configuration

#### `bridge/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.oneclaw.shadow.bridge"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Koin
    implementation(libs.koin.android)

    // OkHttp (HTTP + WebSocket)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // JSON Serialization
    implementation(libs.kotlinx.serialization.json)

    // Security (encrypted credentials)
    implementation(libs.security.crypto)

    // WorkManager (watchdog)
    implementation(libs.work.runtime.ktx)

    // NanoHTTPD (webhook + WebSocket servers)
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.websocket)

    // Commonmark (Markdown -> HTML for Telegram)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.strikethrough)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
```

#### New entries in `gradle/libs.versions.toml`

```toml
[versions]
nanohttpd = "2.3.1"
commonmark = "0.24.0"

[libraries]
# NanoHTTPD
nanohttpd = { group = "org.nanohttpd", name = "nanohttpd", version.ref = "nanohttpd" }
nanohttpd-websocket = { group = "org.nanohttpd", name = "nanohttpd-websocket", version.ref = "nanohttpd" }

# Commonmark
commonmark = { group = "org.commonmark", name = "commonmark", version.ref = "commonmark" }
commonmark-ext-strikethrough = { group = "org.commonmark", name = "commonmark-ext-gfm-strikethrough", version.ref = "commonmark" }

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
```

#### `settings.gradle.kts` update

```kotlin
include(":app")
include(":bridge")
```

#### `:app` dependency on `:bridge`

Add to `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":bridge"))
}
```

### Android Manifest (`:bridge` module)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application>
        <service
            android:name=".service.MessagingBridgeService"
            android:foregroundServiceType="specialUse"
            android:exported="false" />
    </application>

</manifest>
```

### Navigation Integration

Add route in `Routes.kt`:

```kotlin
data object BridgeSettings : Route("bridge-settings")
```

Add navigation entry in `NavHost`:

```kotlin
composable(Route.BridgeSettings.path) {
    BridgeSettingsScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

Add entry point in Settings screen (alongside existing entries like Memory, Tools, etc.).

### Technology Stack

| Technology | Version | Purpose | Selection Rationale |
|-----------|---------|---------|-------------------|
| OkHttp | 4.12.0 | HTTP client + WebSocket | Already in project; proven for API calls and WebSocket gateway connections |
| NanoHTTPD | 2.3.1 | Embedded HTTP/WS server | Lightweight (< 100KB), no native dependencies, ideal for LINE webhooks and WebChat |
| Commonmark | 0.24.0 | Markdown -> HTML | Standard CommonMark parser; needed for Telegram HTML message formatting |
| Kotlinx Serialization | 1.7.3 | JSON parsing | Already in project; consistent with existing serialization |
| AndroidX Security Crypto | 1.1.0-alpha06 | Encrypted preferences | Already in project; consistent with existing key storage |
| WorkManager | 2.10.0 | Watchdog scheduling | Already in project; reliable periodic task execution |
| Koin | 3.5.6 | Dependency injection | Already in project; consistent DI framework |

## Implementation Steps

### Phase 1: Module Setup and Core Infrastructure
1. [ ] Create `:bridge` Gradle module with `build.gradle.kts`
2. [ ] Add NanoHTTPD and Commonmark to `libs.versions.toml`
3. [ ] Add `android-library` plugin to `libs.versions.toml`
4. [ ] Update `settings.gradle.kts` to include `:bridge`
5. [ ] Add `implementation(project(":bridge"))` to `:app` dependencies
6. [ ] Create `AndroidManifest.xml` for `:bridge` with service and permissions
7. [ ] Implement core data classes: `BridgeMessage`, `ChannelMessage`, `ChannelType`
8. [ ] Implement bridge interfaces: `BridgeAgentExecutor`, `BridgeMessageObserver`, `BridgeConversationManager`
9. [ ] Implement `BridgePreferences` (SharedPreferences wrapper)
10. [ ] Implement `BridgeCredentialProvider` (EncryptedSharedPreferences)
11. [ ] Implement `BridgeStateTracker` (singleton with StateFlow)
12. [ ] Implement `BridgeBroadcaster` (singleton)
13. [ ] Implement `ConversationMapper`
14. [ ] Implement `BridgeImageStorage`

### Phase 2: Channel Abstraction and MessagingChannel
1. [ ] Implement `MessagingChannel` abstract base class
   - Message deduplication (LRU cache)
   - Access control (whitelist)
   - `processInboundMessage()` pipeline
   - Typing indicator coroutine management
   - Error handling and state tracking updates

### Phase 3: Channel Implementations
1. [ ] Implement `TelegramApi` (HTTP client for Bot API)
2. [ ] Implement `TelegramHtmlRenderer` (Commonmark -> HTML)
3. [ ] Implement `TelegramChannel` (long-polling, image download)
4. [ ] Implement `DiscordGateway` (WebSocket with heartbeat)
5. [ ] Implement `DiscordChannel` (gateway events, REST responses)
6. [ ] Implement `SlackSocketMode` (WebSocket for Socket Mode)
7. [ ] Implement `SlackChannel` (envelope handling, event filtering)
8. [ ] Implement `MatrixApi` (HTTP client for Client-Server API)
9. [ ] Implement `MatrixChannel` (sync polling, room messaging)
10. [ ] Implement `LineWebhookServer` (NanoHTTPD with signature verification)
11. [ ] Implement `LineApi` (HTTP client for Push API)
12. [ ] Implement `LineChannel` (webhook -> push response)
13. [ ] Implement `WebChatServer` (NanoWSD with JSON protocol)
14. [ ] Implement `WebChatChannel` (WebSocket server, optional auth)

### Phase 4: Service and Lifecycle
1. [ ] Implement `MessagingBridgeService` (foreground service)
   - Channel lifecycle management (start/stop)
   - Notification channel and notification
   - Wake lock management
   - START_STICKY behavior
2. [ ] Implement `BridgeWatchdogWorker` (WorkManager)
3. [ ] Register watchdog in `OneclawApplication.onCreate()`

### Phase 5: App Integration (Interface Implementations)
1. [ ] Implement `BridgeAgentExecutorImpl` in `:app`
2. [ ] Implement `BridgeMessageObserverImpl` in `:app`
3. [ ] Implement `BridgeConversationManagerImpl` in `:app`
4. [ ] Create `BridgeModule.kt` Koin module
5. [ ] Register `bridgeModule` in `OneclawApplication.startKoin()`
6. [ ] Add `Route.BridgeSettings` to `Routes.kt`
7. [ ] Add navigation composable for BridgeSettingsScreen

### Phase 6: Settings UI
1. [ ] Create `BridgeSettingsUiState` data class
2. [ ] Implement `BridgeSettingsViewModel`
   - Load preferences and credentials on init
   - Observe `BridgeStateTracker` for real-time status
   - Service start/stop methods
   - Per-channel toggle and credential update methods
3. [ ] Implement `BridgeSettingsScreen` Composable
   - Master toggle and start/stop button
   - Per-channel expandable cards
   - Credential input fields (password-masked)
   - Real-time status display
   - Error display
4. [ ] Add "Messaging Bridge" entry to main Settings screen

### Phase 7: Testing
1. [ ] Unit tests for `MessagingChannel.processInboundMessage()` (dedup, access control, pipeline)
2. [ ] Unit tests for `ConversationMapper` (resolve, create)
3. [ ] Unit tests for `BridgeStateTracker` (state updates, reset)
4. [ ] Unit tests for `BridgePreferences` (read/write)
5. [ ] Unit tests for each channel's message parsing and response formatting
6. [ ] Unit tests for `TelegramHtmlRenderer`
7. [ ] Unit tests for `LineWebhookServer` signature verification
8. [ ] Unit tests for `BridgeSettingsViewModel` (toggle, credential update)
9. [ ] Integration test: service start/stop lifecycle
10. [ ] Integration test: end-to-end message flow (mock channel -> agent -> response)

## Data Flow

### Typical Message Flow: User sends Telegram message to agent

```
1. User sends "Hello" to Telegram bot
2. TelegramChannel.start() polling loop calls TelegramApi.getUpdates()
3. getUpdates() returns Update with Message containing "Hello"
4. TelegramChannel extracts ChannelMessage(externalChatId="12345", text="Hello", ...)
5. Calls MessagingChannel.processInboundMessage(channelMessage)
6. processInboundMessage() checks deduplication cache -> not seen
7. Checks access control -> user allowed (whitelist empty or user in list)
8. Saves lastChatId "12345" for TELEGRAM in BridgePreferences
9. ConversationMapper.resolveConversationId() -> returns existing bridge conversation ID
10. BridgeConversationManager.insertUserMessage(conversationId, "Hello")
11. BridgeAgentExecutor.executeMessage(conversationId, "Hello")
12. Launches typing indicator coroutine (sendChatAction every 4s)
13. BridgeMessageObserver.awaitNextAssistantMessage(conversationId, timeout=300s)
14. Agent processes message, generates response "Hi there! How can I help?"
15. Observer returns BridgeMessage(content="Hi there! How can I help?", ...)
16. Cancel typing indicator coroutine
17. TelegramChannel.sendResponse("12345", bridgeMessage)
18. TelegramApi.sendMessage(chatId="12345", text="Hi there! How can I help?", parseMode="HTML")
19. BridgeStateTracker.updateChannelState(TELEGRAM, state.copy(messageCount++, lastMessageAt=now))
```

### Service Start Flow

```
1. User taps "Start Bridge" in BridgeSettingsScreen
2. BridgeSettingsViewModel calls MessagingBridgeService.start(context)
3. Service receives ACTION_START intent
4. Creates foreground notification
5. BridgeStateTracker.updateServiceRunning(true)
6. For each enabled channel:
   a. Check credentials from BridgeCredentialProvider
   b. Construct channel instance with dependencies
   c. Register with BridgeBroadcaster
   d. Launch channel.start() in serviceScope
   e. BridgeStateTracker.updateChannelState(type, ChannelState(isRunning=true))
7. If no channels started, stop self and reset state
8. Schedule BridgeWatchdogWorker via WorkManager
```

## Error Handling

### Error Classification

| Error Type | Examples | Strategy |
|-----------|---------|----------|
| **Connection Errors** | Network unreachable, DNS failure, TLS handshake | Exponential backoff (3s -> 6s -> 12s -> ... -> 60s max) |
| **Authentication Errors** | Invalid token, expired credentials | Report error in channel state, stop channel |
| **Protocol Errors** | Unexpected API response, gateway disconnect | Log and reconnect with backoff |
| **Rate Limiting** | 429 Too Many Requests | Respect Retry-After header, exponential backoff |
| **Agent Timeout** | No response within 300s | Send timeout error message to user, continue channel |
| **Port Conflicts** | Address already in use (WebChat/LINE) | Report error in channel state, channel fails to start |
| **Signature Verification** | LINE webhook HMAC mismatch | Reject request with 401, log security warning |

### Error Recovery Strategy

```kotlin
// Each channel's polling/connection loop follows this pattern:
while (isActive) {
    try {
        // Connection/polling logic
        backoffMs = INITIAL_BACKOFF_MS  // Reset on success
    } catch (e: CancellationException) {
        throw e  // Respect cancellation
    } catch (e: Exception) {
        Log.e(TAG, "Channel error: ${e.message}")
        BridgeStateTracker.updateChannelState(channelType,
            ChannelState(isRunning = true, error = e.message))
        delay(backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }
}
```

## Performance Considerations

### Performance Targets
- Bridge service memory overhead: < 30MB with all 6 channels active
- Message processing latency (bridge pipeline, excluding agent): < 500ms
- Channel connection time: < 10s for initial connection
- Typing indicator interval: exactly 4 seconds
- Agent response timeout: 300 seconds

### Optimization Strategies

1. **Coroutine Management**
   - `SupervisorJob` for channel isolation (one crash doesn't kill others)
   - `Mutex` for channel start/stop serialization
   - `withTimeout` for agent response awaiting

2. **Memory Efficiency**
   - LRU deduplication cache capped at 500 entries per channel
   - Image files stored on disk, not in memory
   - No message history buffering (messages flow through to agent)

3. **Network Efficiency**
   - OkHttp connection pooling shared across channels
   - Long-polling with 30-second timeouts (Telegram, Matrix)
   - WebSocket heartbeat for persistent connections (Discord, Slack)

4. **Battery Consideration**
   - Optional wake lock (off by default)
   - Polling intervals chosen to balance responsiveness and battery
   - Service stops itself if no channels are enabled

## Security Considerations

### Credential Security
- All API tokens stored in `EncryptedSharedPreferences` (AES256_SIV + AES256_GCM)
- Master key via `MasterKeys.AES256_GCM_SPEC`
- Credentials never logged, never included in error messages
- Fallback to plain SharedPreferences only when hardware security module unavailable

### Access Control
- Per-channel user ID whitelist
- Empty whitelist = open access (explicit opt-in to restrict)
- Unauthorized messages silently dropped (no information leakage)

### Network Security
- All external API calls use HTTPS
- LINE webhook verifies HMAC-SHA256 signatures using channel secret
- WebChat supports optional token authentication
- WebChat and LINE webhook servers listen on all interfaces (local network exposure)

### Service Security
- Service is not exported (`android:exported="false"`)
- Start/stop controlled only via explicit intents from within the app

## Testing Strategy

### Unit Tests (Layer 1A)
- `MessagingChannel`: deduplication, access control, processInboundMessage pipeline
- `ConversationMapper`: resolve existing, create new, handle missing conversation
- `BridgeStateTracker`: state updates, channel state management, reset
- `BridgePreferences`: all getters/setters, conversation mapping, default values
- `TelegramHtmlRenderer`: markdown to HTML conversion edge cases
- `LineWebhookServer`: HMAC-SHA256 signature verification
- `BridgeSettingsViewModel`: state loading, toggle operations, credential updates

### Integration Tests
- Service lifecycle: start -> channels running -> stop -> channels stopped -> state reset
- End-to-end flow: mock channel message -> agent execution -> response delivery
- Watchdog: service dies -> worker detects -> service restarted

### Manual Tests (Layer 2)
- Configure Telegram bot and verify bidirectional messaging
- Configure Discord bot and verify bidirectional messaging
- Configure Slack app and verify bidirectional messaging
- Configure Matrix account and verify bidirectional messaging
- Configure LINE channel and verify webhook + response
- Open WebChat via browser and verify messaging
- Send image in Telegram, verify agent receives it
- Send `/clear` and verify new conversation created
- Kill service via system, verify watchdog restarts it
- Test with multiple channels simultaneously
- Test access control: allowed user succeeds, blocked user silently dropped

## Dependencies

### Depends On
- **RFC-001 (Chat Interaction)**: Agent execution pipeline, message system
- **RFC-004 (Tool System)**: Tool execution during agent processing
- **RFC-008 (Session Management)**: Conversation/session lifecycle

### Depended On By
- None currently

## Risks and Mitigation

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Background service killed by Android battery optimization | High | High | Foreground service + watchdog worker + user guidance to disable battery optimization |
| Platform API changes break channel implementations | Medium | Medium | Each channel is isolated; can update/disable individually |
| NanoHTTPD limitations under load | Low | Low | WebChat/LINE are typically low-volume; can replace if needed |
| Port conflicts with other apps | Medium | Low | Configurable ports; clear error reporting |
| User misconfigures credentials | Medium | High | Input validation; clear error messages in channel state |

## Alternatives Considered

### Alternative A: All code in `:app` module (rejected)
- **Pro**: Simpler build configuration, no module boundary
- **Con**: Violates separation of concerns; bridge code mixed with app logic; harder to test independently
- **Why rejected**: Independent module provides cleaner architecture and reusability

### Alternative B: Firebase Cloud Messaging relay (rejected)
- **Pro**: No foreground service needed; better battery life
- **Con**: Requires server infrastructure; adds latency; not self-hosted
- **Why rejected**: Contradicts OneClaw's local-first, self-hosted philosophy

### Alternative C: Single unified WebSocket gateway (rejected)
- **Pro**: Simpler architecture; all platforms connect through one point
- **Con**: Requires server-side relay for each platform; defeats purpose of direct integration
- **Why rejected**: Direct integration with each platform's native API is more reliable and feature-complete

## Future Extensions

1. **Rich media outbound**: Send images, files, or formatted cards as agent responses
2. **Multi-conversation support**: Route different external chats to different agent conversations
3. **Voice message transcription**: Convert voice messages to text before sending to agent
4. **Bridge conversation UI**: View bridge conversation history in the app
5. **Remote management**: Web-based dashboard for bridge configuration
6. **Additional channels**: WhatsApp Business API, Signal, Microsoft Teams
7. **Message queuing**: Queue messages when agent is busy, process in order
8. **Per-channel agent selection**: Route different channels to different agents

## Open Questions

- [ ] Should the bridge support multiple simultaneous conversations (one per external chat ID) or a single shared conversation?
- [ ] Should the bridge conversation use a specific agent, or use the default General Assistant?
- [ ] Should there be a maximum message rate limit to prevent abuse?
- [ ] Should WebChat and LINE webhook servers require authentication by default?

## References

- Reference implementation: `oneclaw-1/lib-messaging-bridge`
- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Discord Gateway API](https://discord.com/developers/docs/topics/gateway)
- [Slack Socket Mode](https://api.slack.com/apis/connections/socket)
- [Matrix Client-Server API](https://spec.matrix.org/latest/client-server-api/)
- [LINE Messaging API](https://developers.line.biz/en/docs/messaging-api/)
- [NanoHTTPD GitHub](https://github.com/NanoHttpd/nanohttpd)
- [Android Foreground Services](https://developer.android.com/develop/background-work/services/foreground-services)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
