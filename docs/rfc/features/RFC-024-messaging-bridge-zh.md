# RFC-024: 消息桥接

## 文档信息
- **RFC ID**: RFC-024
- **关联 PRD**: [FEAT-024 (消息桥接)](../../prd/features/FEAT-024-messaging-bridge.md)
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景
OneClaw 目前需要用户打开 Android 应用才能与 AI Agent 交互。许多用户每天在 Telegram、Discord 或 Slack 等消息平台上度过大部分时间。消息桥接功能可以让他们在任何平台、任何设备上直接与 Agent 对话，无需切换应用。

参考实现（`oneclaw-1/lib-messaging-bridge`）提供了一套经过验证的架构，支持 6 个频道，包含频道抽象层、前台服务和完善的状态管理。本 RFC 将该架构适配至 OneClaw，作为独立的 `:bridge` Gradle 模块实现。

### 目标
1. 实现独立的 `:bridge` Gradle 模块，包含所有消息桥接逻辑
2. 支持 6 个消息频道：Telegram、Discord、Slack、Matrix、LINE、WebChat
3. 以前台服务方式运行，确保可靠的后台执行
4. 提供完整的桥接设置页面，用于频道配置和状态监控
5. 支持来自 Telegram 和 Discord 的入站图片消息
6. 通过明确定义的接口与现有 Agent 执行管道集成

### 非目标
- 富媒体出站响应（Agent 向平台发送图片、文件、音频）
- 语音消息支持
- WebChat 端对端加密
- 多 Agent 路由（所有消息发往同一个桥接会话）
- 远程桥接管理或 Web 管理面板
- 速率限制或流量控制

## 技术方案

### 架构概述

桥接功能以独立 Gradle 模块（`:bridge`）的形式构建，仅依赖 Kotlin stdlib、协程、OkHttp、NanoHTTPD、Commonmark、AndroidX Core、AndroidX Security Crypto 和 WorkManager。它定义了 Agent 执行、消息观察和会话管理的接口，由 `:app` 模块负责实现。

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

### 核心组件

#### 1. MessagingChannel（抽象基类）

频道抽象层提供了一套通用的消息处理流水线，包含去重、访问控制、打字指示器和错误处理。每个平台只需实现传输层。

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
    // --- 抽象：传输层 ---
    abstract suspend fun start()
    abstract suspend fun stop()
    abstract fun isRunning(): Boolean
    protected abstract suspend fun sendResponse(externalChatId: String, message: BridgeMessage)

    // --- 开放：可选的平台特性 ---
    protected open suspend fun sendTypingIndicator(externalChatId: String) {}
    open suspend fun broadcast(message: BridgeMessage) { /* 发送到最后已知的 chat ID */ }

    // --- 具体实现：共享消息流水线 ---
    protected suspend fun processInboundMessage(msg: ChannelMessage) {
        // 1. 去重（500 条目 LRU 缓存）
        // 2. 访问控制（白名单检查）
        // 3. 持久化最后 chat ID 以供广播使用
        // 4. 处理 /clear 命令
        // 5. 解析会话 ID
        // 6. 插入用户消息
        // 7. 执行 Agent
        // 8. 启动打字指示器协程（每 4 秒发送一次）
        // 9. 等待助手响应（300 秒超时）
        // 10. 取消打字指示器，发送响应
    }

    companion object {
        private const val TYPING_INTERVAL_MS = 4000L
        private const val MAX_DEDUP_SIZE = 500
    }
}
```

#### 2. MessagingBridgeService（前台服务）

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
        // 1. 创建前台通知
        // 2. 如已启用则获取唤醒锁
        // 3. 启动已启用的频道（互斥锁保护）
        // 4. 如果没有频道启动，则停止自身
    }

    private fun stopBridge() {
        // 1. 停止所有频道（互斥锁保护）
        // 2. 释放唤醒锁
        // 3. 重置状态追踪器
        // 4. 停止自身
    }

    companion object {
        const val ACTION_START = "com.oneclaw.shadow.bridge.START"
        const val ACTION_STOP = "com.oneclaw.shadow.bridge.STOP"

        fun start(context: Context) { /* startForegroundService */ }
        fun stop(context: Context) { /* startService with ACTION_STOP */ }
    }
}
```

#### 3. BridgeStateTracker（单例状态）

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

#### 4. BridgePreferences（配置存储）

```kotlin
package com.oneclaw.shadow.bridge

class BridgePreferences(context: Context) {
    // SharedPreferences "messaging_bridge"

    // 主开关
    fun isBridgeEnabled(): Boolean
    fun setBridgeEnabled(enabled: Boolean)

    // 各频道启用/禁用（Telegram、Discord、WebChat、Slack、Matrix、LINE）
    fun isTelegramEnabled(): Boolean
    fun setTelegramEnabled(enabled: Boolean)
    // ... 其余 6 个频道遵循相同模式

    // 各频道允许的用户 ID（白名单）
    fun getAllowedTelegramUserIds(): Set<String>
    fun setAllowedTelegramUserIds(ids: Set<String>)
    // ... 其余 5 个频道遵循相同模式（WebChat 无白名单）

    // 频道特定配置
    fun getWebChatPort(): Int           // 默认：8080
    fun getLineWebhookPort(): Int       // 默认：8081
    fun getMatrixHomeserver(): String   // 默认：""

    // 唤醒锁
    fun isWakeLockEnabled(): Boolean

    // 会话映射
    fun getBridgeConversationId(): String?
    fun setBridgeConversationId(conversationId: String)
    fun getMappedConversationId(externalKey: String): String?
    fun setMappedConversationId(externalKey: String, conversationId: String)

    // 各频道最后 chat ID（用于广播）
    fun getLastChatId(channelType: ChannelType): String?
    fun setLastChatId(channelType: ChannelType, chatId: String)

    // Telegram 轮询偏移量
    fun getTelegramUpdateOffset(): Long
    fun setTelegramUpdateOffset(offset: Long)

    // 工具方法
    fun hasAnyChannelEnabled(): Boolean
}
```

#### 5. BridgeCredentialProvider（加密凭据存储）

```kotlin
package com.oneclaw.shadow.bridge.service

class BridgeCredentialProvider(context: Context) {
    // EncryptedSharedPreferences "bridge_credentials"
    // 加密失败时回退到普通 SharedPreferences

    // Telegram
    fun getTelegramBotToken(): String?
    fun saveTelegramBotToken(token: String)

    // Discord
    fun getDiscordBotToken(): String?
    fun saveDiscordBotToken(token: String)

    // Slack（两个 token）
    fun getSlackBotToken(): String?
    fun saveSlackBotToken(token: String)
    fun getSlackAppToken(): String?
    fun saveSlackAppToken(token: String)

    // Matrix
    fun getMatrixAccessToken(): String?
    fun saveMatrixAccessToken(token: String)

    // LINE（两个凭据）
    fun getLineChannelAccessToken(): String?
    fun saveLineChannelAccessToken(token: String)
    fun getLineChannelSecret(): String?
    fun saveLineChannelSecret(token: String)

    // WebChat
    fun getWebChatAccessToken(): String?
    fun saveWebChatAccessToken(token: String)
}
```

#### 6. 桥接接口（由 :app 模块实现）

```kotlin
package com.oneclaw.shadow.bridge

// 触发指定会话的 Agent 执行
interface BridgeAgentExecutor {
    suspend fun executeMessage(
        conversationId: String,
        userMessage: String,
        imagePaths: List<String> = emptyList()
    )
}

// 带超时地观察 Agent 响应
interface BridgeMessageObserver {
    suspend fun awaitNextAssistantMessage(
        conversationId: String,
        afterTimestamp: Long = System.currentTimeMillis(),
        timeoutMs: Long = 300_000
    ): BridgeMessage
}

// 管理桥接会话
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

### 数据模型

#### ChannelType 枚举

```kotlin
package com.oneclaw.shadow.bridge.channel

enum class ChannelType {
    TELEGRAM, DISCORD, WEBCHAT, SLACK, MATRIX, LINE
}
```

#### ChannelMessage（入站消息）

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

#### BridgeMessage（出站消息）

```kotlin
package com.oneclaw.shadow.bridge

data class BridgeMessage(
    val content: String,
    val timestamp: Long,
    val imagePaths: List<String> = emptyList()
)
```

### 频道实现

#### TelegramChannel

- **连接方式**: 通过 `getUpdates()` 进行长轮询，超时 30 秒
- **API**: 使用 OkHttp 调用 `api.telegram.org/bot<token>/`
- **消息接收**: 轮询更新，提取文本和图片消息
- **响应发送**: `sendMessage()`，使用 HTML 解析模式
- **图片处理**: `getFile()` -> 下载到 `bridge_images/`
- **消息分割**: 4096 字符限制，按段落 > 句子 > 单词边界分割
- **打字指示器**: `sendChatAction(action=typing)`
- **HTML 渲染**: 通过 `TelegramHtmlRenderer` 将 Commonmark Markdown 转换为 HTML
- **退避策略**: 指数退避处理错误（初始 3 秒，最大翻倍至 60 秒）

```kotlin
class TelegramChannel(...) : MessagingChannel(...) {
    private val api = TelegramApi(botToken, okHttpClient)
    private val imageStorage = BridgeImageStorage(context)

    override suspend fun start() { /* 轮询循环 */ }
    override suspend fun stop() { /* 取消轮询 */ }
    override fun isRunning(): Boolean
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage)
    override suspend fun sendTypingIndicator(externalChatId: String)
}
```

辅助类：
- `TelegramApi` -- Bot API 端点的 HTTP 客户端封装
- `TelegramHtmlRenderer` -- Commonmark Markdown 转 Telegram HTML 转换器
- `BridgeImageStorage` -- 下载并本地存储图片

#### DiscordChannel

- **连接方式**: WebSocket 网关（`wss://gateway.discord.gg`）
- **协议**: Discord Gateway v10，包含心跳、序列号追踪和意图过滤
- **意图**: `GUILD_MESSAGES | DIRECT_MESSAGES | MESSAGE_CONTENT`（= 33281）
- **消息接收**: `MESSAGE_CREATE` 事件，过滤非 bot 消息
- **响应发送**: REST API `POST /channels/{channelId}/messages`
- **图片处理**: 从附件 URL 下载图片
- **退避策略**: 指数退避重连

```kotlin
class DiscordChannel(...) : MessagingChannel(...) {
    private val gateway = DiscordGateway(botToken, okHttpClient, ::onMessage)

    override suspend fun start() { /* 连接网关 */ }
    override suspend fun stop() { /* 断开连接 */ }
    override fun isRunning(): Boolean
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage)
}
```

辅助类：
- `DiscordGateway` -- 处理 HELLO、HEARTBEAT、IDENTIFY、DISPATCH 事件的 WebSocket 客户端

#### SlackChannel

- **连接方式**: 通过 `apps.connections.open` API 建立 Socket Mode WebSocket
- **认证**: 需要 `appToken`（xapp-）和 `botToken`（xoxb-）两个 token
- **消息接收**: 带 `message` 事件类型的 `events_api` 信封
- **过滤规则**: 忽略 bot 消息（`botId` 非空），忽略子类型（编辑、删除）
- **信封确认**: 每个收到的信封都需要确认
- **响应发送**: `chat.postMessage` REST API

```kotlin
class SlackChannel(...) : MessagingChannel(...) {
    private val socketMode = SlackSocketMode(appToken, botToken, okHttpClient, ::onMessage)

    override suspend fun start() { /* 连接 Socket Mode */ }
    override suspend fun stop() { /* 断开连接 */ }
    override fun isRunning(): Boolean
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage)
}
```

辅助类：
- `SlackSocketMode` -- 用于 Slack Socket Mode API 的 WebSocket 客户端

#### MatrixChannel

- **连接方式**: 长轮询 `/sync` 端点，超时 30 秒
- **配置**: 需要 homeserver URL 和访问 token
- **消息接收**: 从同步响应时间线中提取 `m.room.message` 事件
- **响应发送**: `PUT /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}`
- **打字指示器**: `PUT /_matrix/client/v3/rooms/{roomId}/typing/{userId}`
- **状态追踪**: 使用 `nextBatch` token 进行增量同步

```kotlin
class MatrixChannel(...) : MessagingChannel(...) {
    private val api = MatrixApi(homeserverUrl, accessToken, okHttpClient)

    override suspend fun start() { /* 同步循环 */ }
    override suspend fun stop() { /* 取消同步 */ }
    override fun isRunning(): Boolean
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage)
    override suspend fun sendTypingIndicator(externalChatId: String)
}
```

辅助类：
- `MatrixApi` -- Matrix 客户端-服务器 API 的 HTTP 客户端封装

#### LineChannel

- **连接方式**: 嵌入式 HTTP 服务器（NanoHTTPD），监听可配置端口
- **Webhook**: 接收发送到 `/webhook` 端点的 POST 请求
- **安全**: 使用 `channelSecret` 进行 HMAC-SHA256 签名验证
- **消息接收**: 类型为 `message` 且消息类型为 `text` 的 Webhook 事件
- **响应发送**: 通过 `channelAccessToken` 调用 LINE Push API

```kotlin
class LineChannel(...) : MessagingChannel(...) {
    private val webhookServer = LineWebhookServer(port, channelSecret, ::onWebhookEvent)
    private val api = LineApi(channelAccessToken, okHttpClient)

    override suspend fun start() { /* 启动 Webhook 服务器 */ }
    override suspend fun stop() { /* 停止服务器 */ }
    override fun isRunning(): Boolean
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage)
}
```

辅助类：
- `LineWebhookServer` -- 带签名验证的 NanoHTTPD 服务器
- `LineApi` -- LINE Push API 的 HTTP 客户端

#### WebChatChannel

- **连接方式**: WebSocket 服务器（NanoWSD），监听可配置端口
- **认证**: 可选的基于 token 的 JWT 认证
- **协议**: JSON 消息 `{type: "auth"|"message", text?, token?}`
- **响应格式**: JSON `{type: "auth_ok"|"auth_fail"|"response"|"typing", text?}`
- **打字指示器**: 发送 `{type: "typing"}` WebSocket 帧
- **多会话**: 追踪每连接会话以进行路由

```kotlin
class WebChatChannel(...) : MessagingChannel(...) {
    private val server = WebChatServer(port, accessToken, ::onMessage)

    override suspend fun start() { /* 启动 WebSocket 服务器 */ }
    override suspend fun stop() { /* 停止服务器 */ }
    override fun isRunning(): Boolean
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage)
    override suspend fun sendTypingIndicator(externalChatId: String)
}
```

辅助类：
- `WebChatServer` -- 带 JSON 协议的 NanoWSD WebSocket 服务器

### 辅助组件

#### ConversationMapper

为桥接解析或创建会话 ID。使用 `BridgeConversationManager` 检查已存储的会话是否仍然存在，如不存在则创建新会话。

```kotlin
class ConversationMapper(private val conversationManager: BridgeConversationManager) {
    suspend fun resolveConversationId(): String
    suspend fun createNewConversation(): String
}
```

#### BridgeBroadcaster

单例，向所有已注册频道的最后已知 chat ID 发送消息。

```kotlin
object BridgeBroadcaster {
    fun register(channel: MessagingChannel)
    fun unregister(channel: MessagingChannel)
    fun clear()
    suspend fun broadcast(content: String)
}
```

#### BridgeImageStorage

从外部 URL 下载图片并以 UUID 文件名存储到应用内部的 `bridge_images/` 目录。自动检测图片格式。

```kotlin
class BridgeImageStorage(private val context: Context) {
    suspend fun downloadAndStore(url: String, headers: Map<String, String> = emptyMap()): String?
    fun getImageDir(): File
}
```

#### BridgeWatchdogWorker

WorkManager 周期性 Worker，检查桥接服务是否正在运行，如需要则重启。

```kotlin
class BridgeWatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // 检查 BridgeStateTracker.serviceRunning
        // 如果应该运行但未运行，则通过 MessagingBridgeService.start() 重启
        return Result.success()
    }
}
```

### UI 层设计

#### 桥接设置页面

**路由**: `Route.BridgeSettings`（`"bridge-settings"`）

**ViewModel**: `BridgeSettingsViewModel`

```kotlin
data class BridgeSettingsUiState(
    val bridgeEnabled: Boolean = false,
    val serviceRunning: Boolean = false,
    val wakeLockEnabled: Boolean = false,

    // 各频道配置
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

    // 实时频道状态
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

    // 各频道开关及凭据更新方法
    fun toggleTelegram(enabled: Boolean)
    fun updateTelegramBotToken(token: String)
    fun updateTelegramAllowedUserIds(ids: String)
    // ... 其余 6 个频道遵循相同模式
}
```

**页面布局**:

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

### 模块结构

#### `:bridge` 模块（新建）

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

#### `:app` 模块新增内容

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
│   └── BridgeModule.kt              （新增 Koin 模块）
│
└── navigation/
    └── Routes.kt                     （添加 BridgeSettings 路由）
```

### 依赖注入

#### 桥接 Koin 模块（位于 :app）

```kotlin
// di/BridgeModule.kt
val bridgeModule = module {
    // 桥接基础设施
    single { BridgePreferences(androidContext()) }
    single { BridgeCredentialProvider(androidContext()) }

    // 桥接接口 -> 实现
    single<BridgeAgentExecutor> { BridgeAgentExecutorImpl(get(), get(), get()) }
    single<BridgeMessageObserver> { BridgeMessageObserverImpl(get()) }
    single<BridgeConversationManager> { BridgeConversationManagerImpl(get(), get()) }

    // ViewModel
    viewModel { BridgeSettingsViewModel(get(), get()) }
}
```

在 `OneclawApplication.onCreate()` 中注册：

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

### Gradle 配置

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

    // 协程
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Koin
    implementation(libs.koin.android)

    // OkHttp（HTTP + WebSocket）
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // JSON 序列化
    implementation(libs.kotlinx.serialization.json)

    // 安全（加密凭据）
    implementation(libs.security.crypto)

    // WorkManager（看门狗）
    implementation(libs.work.runtime.ktx)

    // NanoHTTPD（Webhook + WebSocket 服务器）
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.websocket)

    // Commonmark（Markdown 转 HTML，用于 Telegram）
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.strikethrough)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
```

#### `gradle/libs.versions.toml` 新增条目

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

#### `settings.gradle.kts` 更新

```kotlin
include(":app")
include(":bridge")
```

#### `:app` 依赖 `:bridge`

在 `app/build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation(project(":bridge"))
}
```

### Android 清单文件（`:bridge` 模块）

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

### 导航集成

在 `Routes.kt` 中添加路由：

```kotlin
data object BridgeSettings : Route("bridge-settings")
```

在 `NavHost` 中添加导航入口：

```kotlin
composable(Route.BridgeSettings.path) {
    BridgeSettingsScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

在设置页面添加入口（与 Memory、Tools 等现有入口并列）。

### 技术栈

| 技术 | 版本 | 用途 | 选型理由 |
|-----------|---------|---------|-------------------|
| OkHttp | 4.12.0 | HTTP 客户端 + WebSocket | 项目已有；经过验证，适用于 API 调用和 WebSocket 网关连接 |
| NanoHTTPD | 2.3.1 | 嵌入式 HTTP/WS 服务器 | 轻量级（< 100KB），无原生依赖，非常适合 LINE Webhook 和 WebChat |
| Commonmark | 0.24.0 | Markdown 转 HTML | 标准 CommonMark 解析器；用于 Telegram HTML 消息格式化 |
| Kotlinx Serialization | 1.7.3 | JSON 解析 | 项目已有；与现有序列化保持一致 |
| AndroidX Security Crypto | 1.1.0-alpha06 | 加密首选项 | 项目已有；与现有密钥存储保持一致 |
| WorkManager | 2.10.0 | 看门狗调度 | 项目已有；可靠的周期性任务执行 |
| Koin | 3.5.6 | 依赖注入 | 项目已有；统一的 DI 框架 |

## 实施步骤

### 阶段一：模块搭建与核心基础设施
1. [ ] 创建 `:bridge` Gradle 模块及 `build.gradle.kts`
2. [ ] 将 NanoHTTPD 和 Commonmark 添加到 `libs.versions.toml`
3. [ ] 将 `android-library` 插件添加到 `libs.versions.toml`
4. [ ] 更新 `settings.gradle.kts`，包含 `:bridge`
5. [ ] 在 `:app` 依赖中添加 `implementation(project(":bridge"))`
6. [ ] 为 `:bridge` 创建包含服务和权限声明的 `AndroidManifest.xml`
7. [ ] 实现核心数据类：`BridgeMessage`、`ChannelMessage`、`ChannelType`
8. [ ] 实现桥接接口：`BridgeAgentExecutor`、`BridgeMessageObserver`、`BridgeConversationManager`
9. [ ] 实现 `BridgePreferences`（SharedPreferences 封装）
10. [ ] 实现 `BridgeCredentialProvider`（EncryptedSharedPreferences）
11. [ ] 实现 `BridgeStateTracker`（带 StateFlow 的单例）
12. [ ] 实现 `BridgeBroadcaster`（单例）
13. [ ] 实现 `ConversationMapper`
14. [ ] 实现 `BridgeImageStorage`

### 阶段二：频道抽象与 MessagingChannel
1. [ ] 实现 `MessagingChannel` 抽象基类
   - 消息去重（LRU 缓存）
   - 访问控制（白名单）
   - `processInboundMessage()` 流水线
   - 打字指示器协程管理
   - 错误处理与状态追踪更新

### 阶段三：频道实现
1. [ ] 实现 `TelegramApi`（Bot API 的 HTTP 客户端）
2. [ ] 实现 `TelegramHtmlRenderer`（Commonmark 转 HTML）
3. [ ] 实现 `TelegramChannel`（长轮询、图片下载）
4. [ ] 实现 `DiscordGateway`（带心跳的 WebSocket）
5. [ ] 实现 `DiscordChannel`（网关事件、REST 响应）
6. [ ] 实现 `SlackSocketMode`（Socket Mode 的 WebSocket）
7. [ ] 实现 `SlackChannel`（信封处理、事件过滤）
8. [ ] 实现 `MatrixApi`（客户端-服务器 API 的 HTTP 客户端）
9. [ ] 实现 `MatrixChannel`（同步轮询、房间消息）
10. [ ] 实现 `LineWebhookServer`（带签名验证的 NanoHTTPD）
11. [ ] 实现 `LineApi`（Push API 的 HTTP 客户端）
12. [ ] 实现 `LineChannel`（Webhook 接收 -> Push 响应）
13. [ ] 实现 `WebChatServer`（带 JSON 协议的 NanoWSD）
14. [ ] 实现 `WebChatChannel`（WebSocket 服务器，可选认证）

### 阶段四：服务与生命周期
1. [ ] 实现 `MessagingBridgeService`（前台服务）
   - 频道生命周期管理（启动/停止）
   - 通知渠道和通知
   - 唤醒锁管理
   - START_STICKY 行为
2. [ ] 实现 `BridgeWatchdogWorker`（WorkManager）
3. [ ] 在 `OneclawApplication.onCreate()` 中注册看门狗

### 阶段五：应用集成（接口实现）
1. [ ] 在 `:app` 中实现 `BridgeAgentExecutorImpl`
2. [ ] 在 `:app` 中实现 `BridgeMessageObserverImpl`
3. [ ] 在 `:app` 中实现 `BridgeConversationManagerImpl`
4. [ ] 创建 `BridgeModule.kt` Koin 模块
5. [ ] 在 `OneclawApplication.startKoin()` 中注册 `bridgeModule`
6. [ ] 将 `Route.BridgeSettings` 添加到 `Routes.kt`
7. [ ] 为 BridgeSettingsScreen 添加导航 Composable

### 阶段六：设置 UI
1. [ ] 创建 `BridgeSettingsUiState` 数据类
2. [ ] 实现 `BridgeSettingsViewModel`
   - 初始化时加载首选项和凭据
   - 观察 `BridgeStateTracker` 获取实时状态
   - 服务启动/停止方法
   - 各频道开关和凭据更新方法
3. [ ] 实现 `BridgeSettingsScreen` Composable
   - 主开关和启动/停止按钮
   - 各频道可展开卡片
   - 凭据输入字段（密码遮罩）
   - 实时状态显示
   - 错误信息展示
4. [ ] 在主设置页面添加"消息桥接"入口

### 阶段七：测试
1. [ ] `MessagingChannel.processInboundMessage()` 单元测试（去重、访问控制、流水线）
2. [ ] `ConversationMapper` 单元测试（解析、创建）
3. [ ] `BridgeStateTracker` 单元测试（状态更新、重置）
4. [ ] `BridgePreferences` 单元测试（读写）
5. [ ] 各频道消息解析和响应格式化单元测试
6. [ ] `TelegramHtmlRenderer` 单元测试
7. [ ] `LineWebhookServer` 签名验证单元测试
8. [ ] `BridgeSettingsViewModel` 单元测试（开关、凭据更新）
9. [ ] 集成测试：服务启动/停止生命周期
10. [ ] 集成测试：端到端消息流（模拟频道 -> Agent -> 响应）

## 数据流

### 典型消息流：用户通过 Telegram 发送消息给 Agent

```
1. 用户向 Telegram bot 发送"Hello"
2. TelegramChannel.start() 轮询循环调用 TelegramApi.getUpdates()
3. getUpdates() 返回包含"Hello"消息的 Update
4. TelegramChannel 提取 ChannelMessage(externalChatId="12345", text="Hello", ...)
5. 调用 MessagingChannel.processInboundMessage(channelMessage)
6. processInboundMessage() 检查去重缓存 -> 未见过
7. 检查访问控制 -> 用户允许（白名单为空或用户在列表中）
8. 在 BridgePreferences 中保存 TELEGRAM 的 lastChatId "12345"
9. ConversationMapper.resolveConversationId() -> 返回现有桥接会话 ID
10. BridgeConversationManager.insertUserMessage(conversationId, "Hello")
11. BridgeAgentExecutor.executeMessage(conversationId, "Hello")
12. 启动打字指示器协程（每 4 秒发送一次 sendChatAction）
13. BridgeMessageObserver.awaitNextAssistantMessage(conversationId, timeout=300s)
14. Agent 处理消息，生成响应"Hi there! How can I help?"
15. Observer 返回 BridgeMessage(content="Hi there! How can I help?", ...)
16. 取消打字指示器协程
17. TelegramChannel.sendResponse("12345", bridgeMessage)
18. TelegramApi.sendMessage(chatId="12345", text="Hi there! How can I help?", parseMode="HTML")
19. BridgeStateTracker.updateChannelState(TELEGRAM, state.copy(messageCount++, lastMessageAt=now))
```

### 服务启动流程

```
1. 用户在 BridgeSettingsScreen 点击"Start Bridge"
2. BridgeSettingsViewModel 调用 MessagingBridgeService.start(context)
3. 服务接收 ACTION_START intent
4. 创建前台通知
5. BridgeStateTracker.updateServiceRunning(true)
6. 对每个已启用的频道：
   a. 从 BridgeCredentialProvider 检查凭据
   b. 构建带依赖的频道实例
   c. 向 BridgeBroadcaster 注册
   d. 在 serviceScope 中启动 channel.start()
   e. BridgeStateTracker.updateChannelState(type, ChannelState(isRunning=true))
7. 如果没有频道启动，停止自身并重置状态
8. 通过 WorkManager 调度 BridgeWatchdogWorker
```

## 错误处理

### 错误分类

| 错误类型 | 示例 | 处理策略 |
|-----------|---------|----------|
| **连接错误** | 网络不可达、DNS 失败、TLS 握手失败 | 指数退避（初始 3 秒，最大翻倍至 60 秒） |
| **认证错误** | 无效 token、凭据过期 | 在频道状态中报告错误，停止频道 |
| **协议错误** | 意外的 API 响应、网关断开 | 记录日志并带退避重连 |
| **速率限制** | 429 Too Many Requests | 遵守 Retry-After 响应头，指数退避 |
| **Agent 超时** | 300 秒内无响应 | 向用户发送超时错误消息，频道继续运行 |
| **端口冲突** | 地址已被占用（WebChat/LINE） | 在频道状态中报告错误，频道启动失败 |
| **签名验证失败** | LINE Webhook HMAC 不匹配 | 以 401 拒绝请求，记录安全警告 |

### 错误恢复策略

```kotlin
// 每个频道的轮询/连接循环遵循以下模式：
while (isActive) {
    try {
        // 连接/轮询逻辑
        backoffMs = INITIAL_BACKOFF_MS  // 成功时重置
    } catch (e: CancellationException) {
        throw e  // 遵守取消信号
    } catch (e: Exception) {
        Log.e(TAG, "Channel error: ${e.message}")
        BridgeStateTracker.updateChannelState(channelType,
            ChannelState(isRunning = true, error = e.message))
        delay(backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }
}
```

## 性能考量

### 性能目标
- 6 个频道全部激活时，桥接服务内存开销：< 30MB
- 消息处理延迟（桥接流水线，不含 Agent）：< 500ms
- 频道连接时间：初次连接 < 10 秒
- 打字指示器间隔：精确 4 秒
- Agent 响应超时：300 秒

### 优化策略

1. **协程管理**
   - `SupervisorJob` 实现频道隔离（一个崩溃不影响其他频道）
   - `Mutex` 串行化频道启动/停止操作
   - `withTimeout` 用于等待 Agent 响应

2. **内存效率**
   - 每个频道的 LRU 去重缓存上限为 500 条
   - 图片文件存储在磁盘，不驻留内存
   - 不缓冲消息历史（消息直接流向 Agent）

3. **网络效率**
   - OkHttp 连接池在各频道间共享
   - 长轮询使用 30 秒超时（Telegram、Matrix）
   - 持久连接使用 WebSocket 心跳（Discord、Slack）

4. **电量考量**
   - 可选唤醒锁（默认关闭）
   - 轮询间隔在响应速度与电量之间取得平衡
   - 无频道启用时服务自动停止

## 安全考量

### 凭据安全
- 所有 API token 存储在 `EncryptedSharedPreferences`（AES256_SIV + AES256_GCM）
- 主密钥通过 `MasterKeys.AES256_GCM_SPEC` 生成
- 凭据不记录日志，不包含在错误消息中
- 仅在硬件安全模块不可用时回退到普通 SharedPreferences

### 访问控制
- 各频道独立的用户 ID 白名单
- 白名单为空表示开放访问（限制访问需显式配置）
- 未授权消息静默丢弃（不泄露任何信息）

### 网络安全
- 所有外部 API 调用使用 HTTPS
- LINE Webhook 使用频道密钥验证 HMAC-SHA256 签名
- WebChat 支持可选的 token 认证
- WebChat 和 LINE Webhook 服务器监听所有接口（存在局域网暴露风险）

### 服务安全
- 服务未导出（`android:exported="false"`）
- 启动/停止仅通过应用内部的显式 intent 控制

## 测试策略

### 单元测试（Layer 1A）
- `MessagingChannel`：去重、访问控制、processInboundMessage 流水线
- `ConversationMapper`：解析现有会话、创建新会话、处理缺失会话
- `BridgeStateTracker`：状态更新、频道状态管理、重置
- `BridgePreferences`：所有 getter/setter、会话映射、默认值
- `TelegramHtmlRenderer`：Markdown 转 HTML 的边界情况
- `LineWebhookServer`：HMAC-SHA256 签名验证
- `BridgeSettingsViewModel`：状态加载、开关操作、凭据更新

### 集成测试
- 服务生命周期：启动 -> 频道运行 -> 停止 -> 频道停止 -> 状态重置
- 端到端流程：模拟频道消息 -> Agent 执行 -> 响应送达
- 看门狗：服务终止 -> Worker 检测到 -> 服务重启

### 手动测试（Layer 2）
- 配置 Telegram bot，验证双向消息通信
- 配置 Discord bot，验证双向消息通信
- 配置 Slack 应用，验证双向消息通信
- 配置 Matrix 账号，验证双向消息通信
- 配置 LINE 频道，验证 Webhook + 响应
- 通过浏览器打开 WebChat，验证消息通信
- 在 Telegram 发送图片，验证 Agent 能接收
- 发送 `/clear`，验证是否创建新会话
- 通过系统终止服务，验证看门狗是否重启
- 同时测试多个频道
- 测试访问控制：允许的用户成功，被阻止的用户静默丢弃

## 依赖关系

### 依赖于
- **RFC-001（聊天交互）**: Agent 执行管道、消息系统
- **RFC-004（工具系统）**: Agent 处理过程中的工具执行
- **RFC-008（会话管理）**: 会话/会话生命周期

### 被依赖于
- 暂无

## 风险与缓解措施

| 风险 | 影响 | 概率 | 缓解措施 |
|------|--------|-------------|------------|
| Android 电量优化导致后台服务被杀 | 高 | 高 | 前台服务 + 看门狗 Worker + 引导用户关闭电量优化 |
| 平台 API 变更导致频道实现失效 | 中 | 中 | 各频道相互隔离，可单独更新或禁用 |
| NanoHTTPD 在高负载下的局限性 | 低 | 低 | WebChat/LINE 通常是低流量场景；如需要可替换 |
| 与其他应用的端口冲突 | 中 | 低 | 端口可配置；提供清晰的错误报告 |
| 用户凭据配置错误 | 中 | 高 | 输入验证；频道状态中提供清晰的错误消息 |

## 备选方案

### 备选方案 A：所有代码放在 `:app` 模块（已拒绝）
- **优点**: 构建配置更简单，无模块边界
- **缺点**: 违反关注点分离原则；桥接代码与应用逻辑混杂；难以独立测试
- **拒绝原因**: 独立模块提供更清晰的架构和更好的可复用性

### 备选方案 B：Firebase Cloud Messaging 中继（已拒绝）
- **优点**: 无需前台服务；电量消耗更低
- **缺点**: 需要服务器基础设施；增加延迟；非自托管
- **拒绝原因**: 与 OneClaw 本地优先、自托管的理念相悖

### 备选方案 C：统一的单一 WebSocket 网关（已拒绝）
- **优点**: 架构更简单；所有平台通过同一个接入点连接
- **缺点**: 需要为每个平台提供服务端中继；违背直接集成的初衷
- **拒绝原因**: 直接集成各平台原生 API 更可靠、功能更完整

## 未来扩展

1. **富媒体出站**：以图片、文件或格式化卡片作为 Agent 响应发送
2. **多会话支持**：将不同外部聊天路由到不同的 Agent 会话
3. **语音消息转录**：将语音消息转为文字再发送给 Agent
4. **桥接会话 UI**：在应用内查看桥接会话历史
5. **远程管理**：基于 Web 的桥接配置仪表盘
6. **更多频道**：WhatsApp Business API、Signal、Microsoft Teams
7. **消息队列**：Agent 繁忙时排队消息，按序处理
8. **各频道独立 Agent**：将不同频道路由到不同 Agent

## 待解决问题

- [ ] 桥接是否应支持多个并发会话（每个外部 chat ID 对应一个会话），还是使用单一共享会话？
- [ ] 桥接会话是否应使用特定 Agent，还是使用默认的通用助手？
- [ ] 是否应设置最大消息频率限制以防止滥用？
- [ ] WebChat 和 LINE Webhook 服务器是否应默认要求认证？

## 参考资料

- 参考实现：`oneclaw-1/lib-messaging-bridge`
- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Discord Gateway API](https://discord.com/developers/docs/topics/gateway)
- [Slack Socket Mode](https://api.slack.com/apis/connections/socket)
- [Matrix Client-Server API](https://spec.matrix.org/latest/client-server-api/)
- [LINE Messaging API](https://developers.line.biz/en/docs/messaging-api/)
- [NanoHTTPD GitHub](https://github.com/NanoHttpd/nanohttpd)
- [Android 前台服务](https://developer.android.com/develop/background-work/services/foreground-services)

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | 初始版本 | - |
