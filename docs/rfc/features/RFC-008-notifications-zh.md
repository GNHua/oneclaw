# RFC-008: 通知

## 文档信息
- **RFC ID**: RFC-008
- **关联 PRD**: [FEAT-008 (Notifications)](../../prd/features/FEAT-008-notifications.md)
- **关联架构**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **依赖**: [RFC-001 (Chat Interaction)](RFC-001-chat-interaction.md)
- **被依赖**: 无
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: Draft
- **作者**: TBD

## 概述

### 背景

当用户向 AI agent 发送消息后切换到其他应用，目前没有任何方式通知用户任务已完成或失败。用户必须手动切回 OneClaw 来查看结果。这在涉及多次工具调用的长时间任务中尤其不便，因为 agent 循环可能持续数分钟。

聊天基础设施已经在每次 agent 循环结束时发出 `ChatEvent.ResponseComplete` 和 `ChatEvent.Error` 事件。目前缺少的是一个检测用户不在查看应用的机制，以及将结果以 Android 系统通知的形式呈现的功能。

### 目标

1. 当 AI 任务完成（`ChatEvent.ResponseComplete`）且应用在后台时，发送 Android 通知。
2. 当 AI 任务失败（`ChatEvent.Error`）且应用在后台时，发送 Android 通知。
3. 通知正文包含 AI 回复或错误信息的预览（前 ~100 个字符）。
4. 点击通知导航用户到相关的聊天会话。
5. 当应用在前台时，不显示任何通知。

### 非目标

- 应用内的通知浮层、toast 或 snackbar。
- 用户可配置的通知偏好或开关（V1：后台时始终开启）。
- 多个通知渠道（如将成功和失败分开）。
- 自定义通知声音或振动模式。
- 非聊天事件的通知（同步、设置变更等）。
- Firebase Cloud Messaging 或服务端推送。

## 技术设计

### 架构概览

```
应用生命周期检测 + 通知分发，均由 ChatEvent 驱动：

1. 前台检测
   ProcessLifecycleOwner -> AppLifecycleObserver.isInForeground: Boolean

2. 通知触发 (在 ChatViewModel 中)
   ChatEvent.ResponseComplete 或 ChatEvent.Error
     -> 检查 AppLifecycleObserver.isInForeground
     -> 若 false: NotificationHelper.sendNotification(...)
     -> 若 true: 不做任何处理

3. 通知构建
   NotificationHelper -> NotificationCompat.Builder -> NotificationManager

4. 点击动作 (深度链接)
   PendingIntent -> MainActivity (携带 sessionId extra) -> NavGraph -> ChatSession(sessionId)
```

### 变更 1：添加 `lifecycle-process` 依赖

`ProcessLifecycleOwner` 类位于 `androidx.lifecycle:lifecycle-process`。该依赖当前未包含在项目中。

在 `gradle/libs.versions.toml` 中添加：

```toml
[libraries]
# ... 现有条目 ...
androidx-lifecycle-process = { group = "androidx.lifecycle", name = "lifecycle-process", version.ref = "lifecycle" }
```

在 `app/build.gradle.kts` 中添加：

```kotlin
implementation(libs.androidx.lifecycle.process)
```

`lifecycle` 版本（`2.8.7`）已定义并与其他 lifecycle 依赖共享。

### 变更 2：`AppLifecycleObserver`

新文件：`core/lifecycle/AppLifecycleObserver.kt`

该类通过 `ProcessLifecycleOwner` 观察应用级生命周期，暴露一个简单的布尔值来指示应用是否在前台。

```kotlin
package com.oneclaw.shadow.core.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class AppLifecycleObserver : DefaultLifecycleObserver {

    @Volatile
    var isInForeground: Boolean = false
        private set

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        isInForeground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        isInForeground = false
    }
}
```

`ProcessLifecycleOwner` 在任何 Activity 进入前台时分发 `ON_START`，在最后一个 Activity 离开前台时分发 `ON_STOP`。`@Volatile` 注解确保从协程调度器读取时的线程安全。

### 变更 3：`NotificationHelper`

新文件：`core/notification/NotificationHelper.kt`

该类封装通知渠道创建和通知分发。

```kotlin
package com.oneclaw.shadow.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.oneclaw.shadow.MainActivity
import com.oneclaw.shadow.R

class NotificationHelper(
    private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "agent_tasks"
        const val CHANNEL_NAME = "Agent Tasks"
        const val EXTRA_SESSION_ID = "notification_session_id"
        private var notificationIdCounter = 1000
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for completed or failed AI agent tasks"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun sendTaskCompletedNotification(sessionId: String, responsePreview: String) {
        sendNotification(
            title = "Task completed",
            body = truncatePreview(responsePreview),
            sessionId = sessionId
        )
    }

    fun sendTaskFailedNotification(sessionId: String, errorMessage: String) {
        sendNotification(
            title = "Task failed",
            body = truncatePreview(errorMessage),
            sessionId = sessionId
        )
    }

    private fun sendNotification(title: String, body: String, sessionId: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationIdCounter++, notification)
        } catch (_: SecurityException) {
            // Android 13+ 未授予 POST_NOTIFICATIONS 权限；静默忽略
        }
    }

    private fun truncatePreview(text: String): String {
        val cleaned = text.trim()
        return if (cleaned.length > 100) {
            cleaned.take(100) + "..."
        } else {
            cleaned
        }
    }
}
```

关键设计决策：

- `notificationIdCounter` 递增，确保多个通知（如来自并行会话的通知）不会互相覆盖。
- `PendingIntent` 使用 `sessionId.hashCode()` 作为 request code，使得会话 A 的通知不会干扰会话 B 的 pending intent。
- Android 12+ 要求使用 `FLAG_IMMUTABLE`。
- `SecurityException` 捕获处理了 Android 13+ 上 `POST_NOTIFICATIONS` 权限被拒绝的情况。应用继续正常运行。
- `setAutoCancel(true)` 在用户点击时自动关闭通知。

### 变更 4：在 `OneclawApplication` 中创建通知渠道

在 `OneclawApplication.onCreate()` 中注册 `AppLifecycleObserver` 并创建通知渠道：

```kotlin
package com.oneclaw.shadow

import android.app.Application
import com.oneclaw.shadow.core.lifecycle.AppLifecycleObserver
import com.oneclaw.shadow.core.notification.NotificationHelper
import com.oneclaw.shadow.di.appModule
import com.oneclaw.shadow.di.databaseModule
import com.oneclaw.shadow.di.featureModule
import com.oneclaw.shadow.di.networkModule
import com.oneclaw.shadow.di.repositoryModule
import com.oneclaw.shadow.di.toolModule
import com.oneclaw.shadow.feature.session.usecase.CleanupSoftDeletedUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.java.KoinJavaComponent.get

class OneclawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@OneclawApplication)
            modules(
                appModule,
                databaseModule,
                networkModule,
                repositoryModule,
                toolModule,
                featureModule
            )
        }

        // RFC-008: 注册应用生命周期观察者用于前台检测
        get<AppLifecycleObserver>(AppLifecycleObserver::class.java).register()

        // RFC-008: 创建通知渠道（Android 8+ 要求）
        get<NotificationHelper>(NotificationHelper::class.java).createNotificationChannel()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            get<CleanupSoftDeletedUseCase>(CleanupSoftDeletedUseCase::class.java)()
        }
    }
}
```

### 变更 5：在 `ChatViewModel` 中添加通知触发

将 `AppLifecycleObserver` 和 `NotificationHelper` 注入 `ChatViewModel`。在 `handleChatEvent()` 的 `ResponseComplete` 和 `Error` 分支中添加通知分发。

#### 5a. 更新构造函数

```kotlin
class ChatViewModel(
    private val sendMessageUseCase: SendMessageUseCase,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val agentRepository: AgentRepository,
    private val providerRepository: ProviderRepository,
    private val createSessionUseCase: CreateSessionUseCase,
    private val generateTitleUseCase: GenerateTitleUseCase,
    private val appLifecycleObserver: AppLifecycleObserver,      // NEW
    private val notificationHelper: NotificationHelper           // NEW
) : ViewModel() {
```

#### 5b. 更新 `handleChatEvent()`

在 `ResponseComplete` 分支中，`finishStreaming()` 之后检查应用是否在后台并发送通知：

```kotlin
is ChatEvent.ResponseComplete -> {
    finishStreaming(sessionId)
    // RFC-008: 若应用在后台则发送通知
    if (!appLifecycleObserver.isInForeground) {
        val preview = event.message.content
        notificationHelper.sendTaskCompletedNotification(sessionId, preview)
    }
}
```

在 `Error` 分支中，`handleError()` 之后检查应用是否在后台并发送通知：

```kotlin
is ChatEvent.Error -> {
    handleError(sessionId, event)
    // RFC-008: 若应用在后台则发送通知
    if (!appLifecycleObserver.isInForeground) {
        notificationHelper.sendTaskFailedNotification(sessionId, event.message)
    }
}
```

`ChatEvent.ResponseComplete` 已携带 `message: Message` 域对象，其 `content` 字段包含完整的 AI 回复文本。`ChatEvent.Error` 已携带 `message: String` 字段，包含错误描述。`NotificationHelper.truncatePreview()` 负责 ~100 字符的截断处理。

### 变更 6：通过 Intent Extra 实现深度链接导航

#### 6a. 在 `MainActivity` 中读取 sessionId

当用户点击通知时，`PendingIntent` 启动 `MainActivity` 并携带 `EXTRA_SESSION_ID`。`MainActivity` 必须读取此 extra 并传递给 NavGraph。

```kotlin
class MainActivity : ComponentActivity() {

    private val permissionChecker: PermissionChecker by inject()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionChecker.onPermissionResult(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionChecker.bindToActivity(permissionLauncher)
        enableEdgeToEdge()

        // RFC-008: 从通知点击 intent 中读取 sessionId
        val notificationSessionId = intent?.getStringExtra(
            com.oneclaw.shadow.core.notification.NotificationHelper.EXTRA_SESSION_ID
        )

        setContent {
            OneClawTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    AppNavGraph(
                        navController = navController,
                        notificationSessionId = notificationSessionId  // NEW
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        permissionChecker.unbind()
        super.onDestroy()
    }
}
```

#### 6b. 在 `AppNavGraph` 中处理 `notificationSessionId`

为 `AppNavGraph` 添加 `notificationSessionId` 参数。当该值非空时，在初始组合后导航到对应会话：

```kotlin
@Composable
fun AppNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    notificationSessionId: String? = null  // NEW
) {
    val settingsRepository: SettingsRepository = koinInject()

    LaunchedEffect(Unit) {
        val hasCompletedSetup = settingsRepository.getBoolean("has_completed_setup", false)
        if (!hasCompletedSetup) {
            navController.navigate(Route.Setup.path) {
                popUpTo(Route.Chat.path) { inclusive = true }
            }
        } else if (notificationSessionId != null) {
            // RFC-008: 从通知点击导航到会话
            navController.navigate(Route.ChatSession.create(notificationSessionId)) {
                popUpTo(Route.Chat.path) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Route.Chat.path,
        modifier = modifier
    ) {
        // ... 现有 composable 代码块不变 ...
    }
}
```

`popUpTo(Route.Chat.path) { inclusive = true }` 确保从通知打开的会话按返回键时回到默认聊天页面，而不会创建重复的返回栈条目。

### 变更 7：Android 13+ 通知权限

在 Android 13（API 33）及以上版本，必须在 manifest 中声明 `POST_NOTIFICATIONS` 权限并在运行时请求。

#### 7a. 在 `AndroidManifest.xml` 中添加权限声明

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

#### 7b. 在应用启动时请求权限

在 `MainActivity.onCreate()` 中请求权限。这是一个非阻塞请求：如果用户拒绝，通知会静默失败，应用继续正常工作。

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ... 现有代码 ...

    // RFC-008: 在 Android 13+ 上请求通知权限
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        requestNotificationPermissionIfNeeded()
    }

    // ... setContent { ... } ...
}

private fun requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}
```

现有的 `permissionLauncher`（已用于工具权限）处理结果回调。不需要额外的回调逻辑，因为 `NotificationHelper.sendNotification()` 已经捕获了权限被拒绝时的 `SecurityException`。

### 变更 8：Koin DI 注册

将 `AppLifecycleObserver` 和 `NotificationHelper` 作为单例注册到 DI 模块中。

在 `FeatureModule.kt` 中：

```kotlin
val featureModule = module {
    // ... 现有注册 ...

    // RFC-008: 通知相关依赖
    single { AppLifecycleObserver() }
    single { NotificationHelper(get()) }

    // RFC-001: Chat feature view model（已更新新依赖）
    viewModelOf(::ChatViewModel)
}
```

`AppLifecycleObserver` 必须是单例，因为只有一个应用生命周期。`NotificationHelper` 也是单例，因为除了原子计数器外不持有可变状态。

Koin 的 `viewModelOf(::ChatViewModel)` 会自动解析所有构造函数参数，因此 `viewModelOf` 调用本身无需修改 -- Koin 会从新的 `single` 注册中自动注入 `AppLifecycleObserver` 和 `NotificationHelper`。

## 实现步骤

### 步骤 1：添加 `lifecycle-process` 依赖
- 文件：`gradle/libs.versions.toml`
  - 使用现有 `lifecycle` 版本引用添加 `androidx-lifecycle-process` library 条目
- 文件：`app/build.gradle.kts`
  - 添加 `implementation(libs.androidx.lifecycle.process)`

### 步骤 2：创建 `AppLifecycleObserver`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/core/lifecycle/AppLifecycleObserver.kt`（新建）
  - `DefaultLifecycleObserver` 实现
  - `isInForeground: Boolean` volatile 属性
  - `register()` 方法，用于附加到 `ProcessLifecycleOwner`

### 步骤 3：创建 `NotificationHelper`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/core/notification/NotificationHelper.kt`（新建）
  - `createNotificationChannel()` 用于 Android 8+
  - `sendTaskCompletedNotification(sessionId, responsePreview)`
  - `sendTaskFailedNotification(sessionId, errorMessage)`
  - `truncatePreview()` 工具方法（100 字符限制）
  - 携带 `EXTRA_SESSION_ID` 的 `PendingIntent` 用于深度链接

### 步骤 4：在 `OneclawApplication` 中注册生命周期观察者和通知渠道
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/OneclawApplication.kt`
  - 在 Koin 启动后调用 `AppLifecycleObserver.register()`
  - 在 Koin 启动后调用 `NotificationHelper.createNotificationChannel()`

### 步骤 5：在 `ChatViewModel` 中添加通知触发
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/chat/ChatViewModel.kt`
  - 添加 `AppLifecycleObserver` 和 `NotificationHelper` 构造函数参数
  - 在 `handleChatEvent()` 的 `ResponseComplete` 分支中：检查 `isInForeground`，若为 false 则发送完成通知
  - 在 `handleChatEvent()` 的 `Error` 分支中：检查 `isInForeground`，若为 false 则发送失败通知

### 步骤 6：在 `MainActivity` 中添加深度链接导航
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/MainActivity.kt`
  - 从 intent 中读取 `EXTRA_SESSION_ID`
  - 将 `notificationSessionId` 传递给 `AppNavGraph`

### 步骤 7：在 `NavGraph` 中处理通知导航
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/navigation/NavGraph.kt`
  - 为 `AppNavGraph` 添加 `notificationSessionId: String?` 参数
  - 在 `LaunchedEffect` 中，若非空则导航到 `ChatSession(notificationSessionId)`

### 步骤 8：添加 `POST_NOTIFICATIONS` 权限
- 文件：`app/src/main/AndroidManifest.xml`
  - 添加 `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/MainActivity.kt`
  - 在 Android 13+ 上运行时请求 `POST_NOTIFICATIONS`

### 步骤 9：注册 DI 依赖
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/di/FeatureModule.kt`
  - 添加 `single { AppLifecycleObserver() }`
  - 添加 `single { NotificationHelper(get()) }`
  - 无需修改 `viewModelOf(::ChatViewModel)` -- Koin 自动解析新参数

## 测试策略

### Layer 1A -- 单元测试

**`AppLifecycleObserverTest`** (`app/src/test/kotlin/.../core/lifecycle/`):
- 测试：初始 `isInForeground` 为 `false`
- 测试：调用 `onStart()` 后，`isInForeground` 为 `true`
- 测试：调用 `onStop()` 后，`isInForeground` 为 `false`
- 测试：`onStart()` -> `onStop()` -> `onStart()` 正确切换状态

**`NotificationHelperTest`** (`app/src/test/kotlin/.../core/notification/`):
- 测试：`truncatePreview("short text")` 返回 `"short text"` 不变
- 测试：150 字符字符串的 `truncatePreview` 返回前 100 个字符 + `"..."`
- 测试：恰好 100 个字符的 `truncatePreview` 返回原字符串不变
- 测试：带空白填充的文本的 `truncatePreview` 先 trim 再截断

**`ChatViewModelNotificationTest`** (`app/src/test/kotlin/.../feature/chat/`):
- 测试：`isInForeground = true` 时的 `ResponseComplete` 不调用 `sendTaskCompletedNotification`
- 测试：`isInForeground = false` 时的 `ResponseComplete` 使用正确的 sessionId 和回复预览调用 `sendTaskCompletedNotification`
- 测试：`isInForeground = true` 时的 `Error` 不调用 `sendTaskFailedNotification`
- 测试：`isInForeground = false` 时的 `Error` 使用正确的 sessionId 和错误信息调用 `sendTaskFailedNotification`

### Layer 1B -- 集成测试

**`NotificationChannelTest`** (`app/src/androidTest/kotlin/.../core/notification/`):
- 测试：`createNotificationChannel()` 后，系统拥有 ID 为 `"agent_tasks"` 且重要性为 DEFAULT 的渠道
- 测试：`sendTaskCompletedNotification` 生成带有正确标题和截断正文的通知（需要 Robolectric 或仪器化测试）

### Layer 2 -- adb 可视化验证

**Flow 8-1: 后台任务完成通知**
1. 打开应用，向 AI 发送消息
2. 按 Home 键进入后台
3. 等待 AI 回复完成
4. 验证通知出现：标题 "Task completed"，正文显示回复的前 ~100 个字符
5. 点击通知
6. 验证应用打开并导航到正确的会话，可见已完成的回复

**Flow 8-2: 后台任务失败通知**
1. 配置一个使用无效 API key 的 provider
2. 发送消息
3. 按 Home 键
4. 等待 API 调用失败
5. 验证通知出现：标题 "Task failed"，正文显示错误信息
6. 点击通知
7. 验证应用打开并导航到正确的会话，可见错误信息

**Flow 8-3: 前台无通知**
1. 停留在应用内发送消息
2. 等待回复完成
3. 检查通知栏 -- 验证没有发送通知

**Flow 8-4: 通知导航到正确会话**
1. 打开会话 A，发送消息
2. 通过抽屉切换到会话 B
3. 按 Home 键
4. 等待会话 A 的任务完成
5. 点击通知
6. 验证应用打开到会话 A（而非会话 B）

**Flow 8-5: 通知权限被拒绝（Android 13+）**
1. 在 Android 13+ 设备上，提示时拒绝 POST_NOTIFICATIONS 权限
2. 发送消息并进入后台
3. 等待完成
4. 验证无通知出现（无崩溃，应用正常工作）
5. 验证返回应用时可见已完成的回复

## 数据流

### 前台检测

```
OneclawApplication.onCreate()
  -> AppLifecycleObserver.register()
  -> ProcessLifecycleOwner.get().lifecycle.addObserver(observer)

用户切换到其他应用：
  -> ProcessLifecycleOwner 分发 ON_STOP
  -> AppLifecycleObserver.onStop() 设置 isInForeground = false

用户返回应用：
  -> ProcessLifecycleOwner 分发 ON_START
  -> AppLifecycleObserver.onStart() 设置 isInForeground = true
```

### 通知分发

```
ChatViewModel.handleChatEvent()
  -> 收到 ChatEvent.ResponseComplete
  -> finishStreaming(sessionId)  [更新 UI 状态，重新加载消息]
  -> 检查 appLifecycleObserver.isInForeground
  -> 若 false:
       -> notificationHelper.sendTaskCompletedNotification(sessionId, event.message.content)
       -> truncatePreview(content) -> 前 100 个字符 + "..."
       -> 构建携带 sessionId extra 的 PendingIntent
       -> 构建 NotificationCompat，使用渠道 "agent_tasks"
       -> NotificationManagerCompat.notify(id, notification)
```

### 点击通知的深度链接

```
用户点击通知
  -> PendingIntent 触发 Intent(MainActivity)，携带 EXTRA_SESSION_ID = "abc123"
  -> MainActivity.onCreate() 读取 intent.getStringExtra(EXTRA_SESSION_ID)
  -> 将 notificationSessionId = "abc123" 传递给 AppNavGraph()
  -> LaunchedEffect 检查 notificationSessionId != null
  -> navController.navigate(Route.ChatSession.create("abc123"))
  -> ChatScreen 加载会话 "abc123" 并显示已完成的回复
```

## 变更历史

| 日期 | 版本 | 变更内容 | 作者 |
|------|------|----------|------|
| 2026-02-28 | 0.1 | 初始草稿 | TBD |
