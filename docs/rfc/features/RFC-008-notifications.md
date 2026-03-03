# RFC-008: Notifications

## Document Information
- **RFC ID**: RFC-008
- **Related PRD**: [FEAT-008 (Notifications)](../../prd/features/FEAT-008-notifications.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Depends On**: [RFC-001 (Chat Interaction)](RFC-001-chat-interaction.md)
- **Depended On By**: None
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

When a user sends a message to the AI agent and switches to another app, there is currently no way to know when the task completes or fails. The user must manually switch back to OneClaw to check. This is especially problematic for long-running tasks that involve multiple tool calls, where the agent loop may take several minutes.

The chat infrastructure already emits `ChatEvent.ResponseComplete` and `ChatEvent.Error` events at the end of every agent loop. What is missing is a mechanism to detect that the user is not looking at the app and to surface the result as an Android system notification.

### Goals

1. Send an Android notification when an AI task completes (`ChatEvent.ResponseComplete`) while the app is in the background.
2. Send an Android notification when an AI task fails (`ChatEvent.Error`) while the app is in the background.
3. Include a preview of the AI response or error message in the notification body (first ~100 characters).
4. Tapping the notification navigates the user to the relevant chat session.
5. No notification is shown when the app is in the foreground.

### Non-Goals

- In-app notification overlays, toasts, or snackbars.
- User-configurable notification preferences or toggle (V1: always on if in background).
- Multiple notification channels (e.g., separate for success and error).
- Custom notification sounds or vibration patterns.
- Notifications for non-chat events (sync, settings changes, etc.).
- Firebase Cloud Messaging or server-side push.

## Technical Design

### Architecture Overview

```
App lifecycle detection + notification dispatch, all driven by ChatEvent:

1. Foreground Detection
   ProcessLifecycleOwner -> AppLifecycleObserver.isInForeground: Boolean

2. Notification Trigger (in ChatViewModel)
   ChatEvent.ResponseComplete or ChatEvent.Error
     -> check AppLifecycleObserver.isInForeground
     -> if false: NotificationHelper.sendNotification(...)
     -> if true: do nothing

3. Notification Construction
   NotificationHelper -> NotificationCompat.Builder -> NotificationManager

4. Tap Action (Deep Link)
   PendingIntent -> MainActivity (with sessionId extra) -> NavGraph -> ChatSession(sessionId)
```

### Change 1: Add `lifecycle-process` Dependency

The `ProcessLifecycleOwner` class lives in `androidx.lifecycle:lifecycle-process`. This artifact is not currently in the project.

Add to `gradle/libs.versions.toml`:

```toml
[libraries]
# ... existing entries ...
androidx-lifecycle-process = { group = "androidx.lifecycle", name = "lifecycle-process", version.ref = "lifecycle" }
```

Add to `app/build.gradle.kts`:

```kotlin
implementation(libs.androidx.lifecycle.process)
```

The `lifecycle` version (`2.8.7`) is already defined and shared with other lifecycle artifacts.

### Change 2: `AppLifecycleObserver`

New file: `core/lifecycle/AppLifecycleObserver.kt`

This class observes the app-level lifecycle via `ProcessLifecycleOwner` and exposes a simple boolean indicating whether the app is in the foreground.

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

`ProcessLifecycleOwner` dispatches `ON_START` when any Activity enters the foreground and `ON_STOP` when the last Activity leaves the foreground. The `@Volatile` annotation ensures thread-safe reads from coroutine dispatchers.

### Change 3: `NotificationHelper`

New file: `core/notification/NotificationHelper.kt`

This class encapsulates notification channel creation and notification dispatch.

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
            // POST_NOTIFICATIONS permission not granted on Android 13+; silently ignore
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

Key design decisions:

- `notificationIdCounter` increments so that multiple notifications (e.g., from parallel sessions) do not overwrite each other.
- `PendingIntent` uses `sessionId.hashCode()` as the request code so that tapping a notification for session A does not interfere with session B's pending intent.
- `FLAG_IMMUTABLE` is required on Android 12+.
- The `SecurityException` catch handles the case where `POST_NOTIFICATIONS` permission is denied on Android 13+. The app continues to function normally.
- `setAutoCancel(true)` dismisses the notification when the user taps it.

### Change 4: Notification Channel Creation in `OneclawApplication`

Register `AppLifecycleObserver` and create the notification channel in `OneclawApplication.onCreate()`:

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

        // RFC-008: Register app lifecycle observer for foreground detection
        get<AppLifecycleObserver>(AppLifecycleObserver::class.java).register()

        // RFC-008: Create notification channel (required for Android 8+)
        get<NotificationHelper>(NotificationHelper::class.java).createNotificationChannel()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            get<CleanupSoftDeletedUseCase>(CleanupSoftDeletedUseCase::class.java)()
        }
    }
}
```

### Change 5: Notification Trigger in `ChatViewModel`

Inject `AppLifecycleObserver` and `NotificationHelper` into `ChatViewModel`. Add notification dispatch in the `ResponseComplete` and `Error` branches of `handleChatEvent()`.

#### 5a. Update constructor

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

#### 5b. Update `handleChatEvent()`

In the `ResponseComplete` branch, after `finishStreaming()`, check if the app is in the background and send a notification:

```kotlin
is ChatEvent.ResponseComplete -> {
    finishStreaming(sessionId)
    // RFC-008: Notify if app is in background
    if (!appLifecycleObserver.isInForeground) {
        val preview = event.message.content
        notificationHelper.sendTaskCompletedNotification(sessionId, preview)
    }
}
```

In the `Error` branch, after `handleError()`, check if the app is in the background and send a notification:

```kotlin
is ChatEvent.Error -> {
    handleError(sessionId, event)
    // RFC-008: Notify if app is in background
    if (!appLifecycleObserver.isInForeground) {
        notificationHelper.sendTaskFailedNotification(sessionId, event.message)
    }
}
```

The `ChatEvent.ResponseComplete` already carries the `message: Message` domain object, which has a `content` field containing the full AI response text. `ChatEvent.Error` already carries the `message: String` field with the error description. `NotificationHelper.truncatePreview()` handles the ~100 character truncation.

### Change 6: Deep Link Navigation via Intent Extra

#### 6a. Read sessionId from Intent in `MainActivity`

When the user taps a notification, the `PendingIntent` launches `MainActivity` with `EXTRA_SESSION_ID`. `MainActivity` must read this extra and pass it to the NavGraph.

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

        // RFC-008: Read sessionId from notification tap intent
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

#### 6b. Handle `notificationSessionId` in `AppNavGraph`

Add a `notificationSessionId` parameter to `AppNavGraph`. When non-null, navigate to the session after the initial composition:

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
            // RFC-008: Navigate to session from notification tap
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
        // ... existing composable blocks unchanged ...
    }
}
```

The `popUpTo(Route.Chat.path) { inclusive = true }` ensures that tapping Back from the notification-opened session returns to the default chat screen rather than creating a duplicate back stack entry.

### Change 7: Android 13+ Notification Permission

On Android 13 (API 33) and above, the `POST_NOTIFICATIONS` permission must be declared in the manifest and requested at runtime.

#### 7a. Add permission to `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

#### 7b. Request permission at app launch

Request the permission in `MainActivity.onCreate()` after setup. This is a non-blocking request: if the user denies it, notifications silently fail and the app continues to work normally.

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ... existing code ...

    // RFC-008: Request notification permission on Android 13+
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

The existing `permissionLauncher` (already used for tool permissions) handles the result. No additional callback logic is needed because `NotificationHelper.sendNotification()` already catches `SecurityException` if the permission is denied.

### Change 8: Koin DI Registration

Register `AppLifecycleObserver` and `NotificationHelper` as singletons in the DI module.

In `FeatureModule.kt`:

```kotlin
val featureModule = module {
    // ... existing registrations ...

    // RFC-008: Notification dependencies
    single { AppLifecycleObserver() }
    single { NotificationHelper(get()) }

    // RFC-001: Chat feature view model (updated with new dependencies)
    viewModelOf(::ChatViewModel)
}
```

`AppLifecycleObserver` must be a singleton because there is exactly one app lifecycle. `NotificationHelper` is a singleton because it holds no mutable state beyond the atomic counter.

Koin's `viewModelOf(::ChatViewModel)` auto-resolves all constructor parameters, so no changes to the `viewModelOf` call itself are needed -- Koin will inject `AppLifecycleObserver` and `NotificationHelper` automatically from the new `single` registrations.

## Implementation Steps

### Step 1: Add `lifecycle-process` dependency
- File: `gradle/libs.versions.toml`
  - Add `androidx-lifecycle-process` library entry using existing `lifecycle` version ref
- File: `app/build.gradle.kts`
  - Add `implementation(libs.androidx.lifecycle.process)`

### Step 2: Create `AppLifecycleObserver`
- File: `app/src/main/kotlin/com/oneclaw/shadow/core/lifecycle/AppLifecycleObserver.kt` (new)
  - `DefaultLifecycleObserver` implementation
  - `isInForeground: Boolean` volatile property
  - `register()` method to attach to `ProcessLifecycleOwner`

### Step 3: Create `NotificationHelper`
- File: `app/src/main/kotlin/com/oneclaw/shadow/core/notification/NotificationHelper.kt` (new)
  - `createNotificationChannel()` for Android 8+
  - `sendTaskCompletedNotification(sessionId, responsePreview)`
  - `sendTaskFailedNotification(sessionId, errorMessage)`
  - `truncatePreview()` utility (100 char limit)
  - `PendingIntent` with `EXTRA_SESSION_ID` for deep linking

### Step 4: Register lifecycle observer and notification channel in `OneclawApplication`
- File: `app/src/main/kotlin/com/oneclaw/shadow/OneclawApplication.kt`
  - Call `AppLifecycleObserver.register()` after Koin startup
  - Call `NotificationHelper.createNotificationChannel()` after Koin startup

### Step 5: Add notification trigger to `ChatViewModel`
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/chat/ChatViewModel.kt`
  - Add `AppLifecycleObserver` and `NotificationHelper` constructor parameters
  - In `handleChatEvent()`, `ResponseComplete` branch: check `isInForeground`, send completed notification if false
  - In `handleChatEvent()`, `Error` branch: check `isInForeground`, send failed notification if false

### Step 6: Add deep link navigation in `MainActivity`
- File: `app/src/main/kotlin/com/oneclaw/shadow/MainActivity.kt`
  - Read `EXTRA_SESSION_ID` from intent
  - Pass `notificationSessionId` to `AppNavGraph`

### Step 7: Handle notification navigation in `NavGraph`
- File: `app/src/main/kotlin/com/oneclaw/shadow/navigation/NavGraph.kt`
  - Add `notificationSessionId: String?` parameter to `AppNavGraph`
  - In `LaunchedEffect`, navigate to `ChatSession(notificationSessionId)` if non-null

### Step 8: Add `POST_NOTIFICATIONS` permission
- File: `app/src/main/AndroidManifest.xml`
  - Add `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`
- File: `app/src/main/kotlin/com/oneclaw/shadow/MainActivity.kt`
  - Request `POST_NOTIFICATIONS` at runtime on Android 13+

### Step 9: Register DI dependencies
- File: `app/src/main/kotlin/com/oneclaw/shadow/di/FeatureModule.kt`
  - Add `single { AppLifecycleObserver() }`
  - Add `single { NotificationHelper(get()) }`
  - No change needed to `viewModelOf(::ChatViewModel)` -- Koin auto-resolves new parameters

## Test Strategy

### Layer 1A -- Unit Tests

**`AppLifecycleObserverTest`** (`app/src/test/kotlin/.../core/lifecycle/`):
- Test: initial `isInForeground` is `false`
- Test: after `onStart()` is called, `isInForeground` is `true`
- Test: after `onStop()` is called, `isInForeground` is `false`
- Test: `onStart()` then `onStop()` then `onStart()` correctly toggles state

**`NotificationHelperTest`** (`app/src/test/kotlin/.../core/notification/`):
- Test: `truncatePreview("short text")` returns `"short text"` unchanged
- Test: `truncatePreview` of a 150-character string returns first 100 chars + `"..."`
- Test: `truncatePreview` of exactly 100 characters returns the string unchanged
- Test: `truncatePreview` of whitespace-padded text trims before truncating

**`ChatViewModelNotificationTest`** (`app/src/test/kotlin/.../feature/chat/`):
- Test: `ResponseComplete` when `isInForeground = true` does NOT call `sendTaskCompletedNotification`
- Test: `ResponseComplete` when `isInForeground = false` calls `sendTaskCompletedNotification` with correct sessionId and response preview
- Test: `Error` when `isInForeground = true` does NOT call `sendTaskFailedNotification`
- Test: `Error` when `isInForeground = false` calls `sendTaskFailedNotification` with correct sessionId and error message

### Layer 1B -- Integration Tests

**`NotificationChannelTest`** (`app/src/androidTest/kotlin/.../core/notification/`):
- Test: after `createNotificationChannel()`, the system has a channel with ID `"agent_tasks"` and importance DEFAULT
- Test: `sendTaskCompletedNotification` produces a notification with the correct title and truncated body (requires Robolectric or instrumented test)

### Layer 2 -- adb Visual Verification

**Flow 8-1: Notification on task completion (background)**
1. Open the app, send a message to the AI
2. Press the Home button to go to background
3. Wait for the AI response to complete
4. Verify a notification appears: title "Task completed", body showing first ~100 chars of response
5. Tap the notification
6. Verify the app opens to the correct session with the completed response visible

**Flow 8-2: Notification on task failure (background)**
1. Configure a provider with an invalid API key
2. Send a message
3. Press the Home button
4. Wait for the API call to fail
5. Verify a notification appears: title "Task failed", body showing the error message
6. Tap the notification
7. Verify the app opens to the correct session with the error visible

**Flow 8-3: No notification when app is in foreground**
1. Send a message while staying in the app
2. Wait for the response to complete
3. Check the notification shade -- verify no notification was posted

**Flow 8-4: Notification navigates to correct session**
1. Open Session A, send a message
2. Switch to Session B via the drawer
3. Press the Home button
4. Wait for Session A's task to complete
5. Tap the notification
6. Verify the app opens to Session A (not Session B)

**Flow 8-5: Notification permission denied (Android 13+)**
1. On an Android 13+ device, deny the POST_NOTIFICATIONS permission when prompted
2. Send a message and go to background
3. Wait for completion
4. Verify no notification appears (no crash, app still works)
5. Verify the completed response is visible when returning to the app

## Data Flow

### Foreground detection

```
OneclawApplication.onCreate()
  -> AppLifecycleObserver.register()
  -> ProcessLifecycleOwner.get().lifecycle.addObserver(observer)

User switches to another app:
  -> ProcessLifecycleOwner dispatches ON_STOP
  -> AppLifecycleObserver.onStop() sets isInForeground = false

User returns to app:
  -> ProcessLifecycleOwner dispatches ON_START
  -> AppLifecycleObserver.onStart() sets isInForeground = true
```

### Notification dispatch

```
ChatViewModel.handleChatEvent()
  -> ChatEvent.ResponseComplete received
  -> finishStreaming(sessionId)  [updates UI state, reloads messages]
  -> check appLifecycleObserver.isInForeground
  -> if false:
       -> notificationHelper.sendTaskCompletedNotification(sessionId, event.message.content)
       -> truncatePreview(content) -> first 100 chars + "..."
       -> build PendingIntent with sessionId extra
       -> build NotificationCompat with channel "agent_tasks"
       -> NotificationManagerCompat.notify(id, notification)
```

### Deep link on notification tap

```
User taps notification
  -> PendingIntent fires Intent(MainActivity) with EXTRA_SESSION_ID = "abc123"
  -> MainActivity.onCreate() reads intent.getStringExtra(EXTRA_SESSION_ID)
  -> passes notificationSessionId = "abc123" to AppNavGraph()
  -> LaunchedEffect checks notificationSessionId != null
  -> navController.navigate(Route.ChatSession.create("abc123"))
  -> ChatScreen loads session "abc123" and displays the completed response
```

## Change History

| Date | Version | Change | Author |
|------|---------|--------|--------|
| 2026-02-28 | 0.1 | Initial draft | TBD |
