# RFC-045：Bridge-App 会话同步

## 文档信息
- **RFC ID**: RFC-045
- **Related PRD**: [FEAT-045（Bridge 会话同步）](../../prd/features/FEAT-045-bridge-session-sync.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: 草稿
- **Author**: TBD

## 概述

### 背景

在 FEAT-041 之后，bridge 将所有传入消息路由到应用中最近更新的会话。因此，在正常使用情况下，bridge 与应用共享同一个活跃会话。然而，当用户通过 Telegram 发送 `/clear` 时，`MessagingChannel.processInboundMessage()` 会调用 `conversationMapper.createNewConversation()` 并静默返回。新创建的会话成为数据库中最新的会话，但应用的 ChatScreen 并不会收到通知，仍然继续显示旧会话。

本 RFC 描述了一个最小化的修复方案：在 `BridgeStateTracker` 中添加基于 `SharedFlow` 的进程内事件总线，在 bridge 创建新会话时发出新会话 ID；同时在 ChatScreen 中添加一个 `LaunchedEffect` 进行订阅并重新初始化 ViewModel。

### 目标

1. 当 `/clear` 创建新会话时，从 bridge 层发出会话切换事件。
2. 在 `ChatScreen` 中订阅该事件，并在收到事件时调用 `viewModel.initialize(sessionId)`。
3. 保持变更最小化：不新增模块、不新增数据类、不更改数据库 schema。

### 非目标

- 任何新的持久化层或进程间通信。
- 应用不在前台时的通知处理（现有的 Room Flow 已负责列表刷新；会话切换将在下次进入前台时生效）。
- 对导航图或返回栈的更改。
- 对 `SessionListViewModel` 的更改（抽屉列表已通过 Room Flow 自动更新）。

## 技术设计

### 变更文件概览

```
bridge/src/main/kotlin/com/oneclaw/shadow/bridge/
└── BridgeStateTracker.kt                            # 修改（添加 SharedFlow）
    channel/
    └── MessagingChannel.kt                          # 修改（/clear 分支发出事件）
app/src/main/kotlin/com/oneclaw/shadow/
└── feature/chat/
    └── ChatScreen.kt                                # 修改（添加 LaunchedEffect 订阅者）
```

## 详细设计

### 变更 1：BridgeStateTracker -- 添加 `newSessionFromBridge` SharedFlow

`BridgeStateTracker` 是一个 `object`（单例），已持有在 bridge 服务与应用 UI 之间共享的可观察状态。在此处添加 `SharedFlow` 与其现有职责保持一致。

```kotlin
// BridgeStateTracker.kt -- 仅展示新增内容

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object BridgeStateTracker {

    // ... 现有字段和方法保持不变 ...

    private val _newSessionFromBridge = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val newSessionFromBridge: SharedFlow<String> = _newSessionFromBridge.asSharedFlow()

    fun emitNewSessionFromBridge(sessionId: String) {
        _newSessionFromBridge.tryEmit(sessionId)
    }
}
```

设计决策：
- `extraBufferCapacity = 1`：缓冲一个事件，避免在发出时没有收集者处于活跃状态（例如 ChatScreen 正在组合过程中）导致事件丢失。
- `tryEmit`：即发即弃；bridge 无需确认 UI 已收到事件。
- 使用 `SharedFlow`（而非 `StateFlow`）：会话切换是一次性事件，而非持久状态值。`StateFlow` 会将最后一个会话 ID 重新推送给每个新收集者，导致每次重组时触发不必要的导航。

### 变更 2：MessagingChannel -- 在 `/clear` 时发出事件

在 `processInboundMessage()` 中，`/clear` 分支已调用 `conversationMapper.createNewConversation()`。在该调用之后添加一行：

```kotlin
// MessagingChannel.kt -- /clear 分支（第 4 步）

if (msg.text.trim() == "/clear") {
    val newConversationId = conversationMapper.createNewConversation()
    BridgeStateTracker.emitNewSessionFromBridge(newConversationId)   // <-- 新增行
    val clearMessage = BridgeMessage(
        content = "Conversation cleared. Starting a new conversation.",
        timestamp = System.currentTimeMillis()
    )
    runCatching { sendResponse(msg.externalChatId, clearMessage) }
    updateChannelState(newMessage = true)
    return
}
```

`MessagingChannel` 无其他变更。

### 变更 3：ChatScreen -- 订阅并重新初始化

在 `ChatScreen` 可组合函数顶部附近，与现有 `LaunchedEffect` 块并列，添加一个新的 `LaunchedEffect` 来收集 `BridgeStateTracker.newSessionFromBridge`：

```kotlin
// ChatScreen.kt -- ChatScreen 可组合函数内部

LaunchedEffect(Unit) {
    BridgeStateTracker.newSessionFromBridge.collect { sessionId ->
        viewModel.initialize(sessionId)
    }
}
```

设计决策：
- `LaunchedEffect(Unit)`：在每次组合生命周期内只启动一次，这是持久订阅的正确作用域。
- `viewModel.initialize(sessionId)`：这与用户从抽屉手动选择会话时调用的方法相同。复用该方法可确保行为一致：ViewModel 加载新会话的消息并更新 `uiState.sessionId`。
- 无需更改导航，因为 `ChatScreen` 已是当前屏幕。

## 测试

### 单元测试

本次变更严格意义上不需要新增单元测试。三个修改文件的现有覆盖情况如下：

- `BridgeStateTracker`：无现有单元测试（它是一个简单的状态持有者）；新增的 `SharedFlow` 字段遵循与现有 `StateFlow` 字段相同的模式。
- `MessagingChannel`（`MessagingChannelTest`）：现有测试覆盖了 `/clear` 分支。更新测试以验证在收到 `/clear` 消息后，`BridgeStateTracker.newSessionFromBridge` 会发出新会话 ID。
- `ChatScreen`：由 UI/Roborazzi 测试覆盖；视觉布局无变更，因此无需新的截图基线。

### 手动验证

1. 打开应用至 `ChatScreen`，确认其显示当前活跃会话。
2. 通过 Telegram 发送 `/clear`。
3. 在 3 秒内，验证 `ChatScreen` 切换到一个新的空会话。
4. 通过 Telegram 发送一条普通消息，验证其出现在应用的同一新会话中。
5. 在应用抽屉中手动切换到旧会话，通过 Telegram 发送一条消息，验证其出现在旧会话中（FEAT-041 原有行为不变）。

## 迁移说明

- 无数据库 schema 变更。
- `BridgeConversationManager`、`SessionRepository` 或任何仓库接口均无 API 变更。
- `BridgeStateTracker` 对象新增两个公开成员（`newSessionFromBridge`、`emitNewSessionFromBridge`）。bridge 模块外部的调用方（目前仅 `ChatScreen`）通过现有的对 `BridgeStateTracker` 的共享依赖访问这些成员。
