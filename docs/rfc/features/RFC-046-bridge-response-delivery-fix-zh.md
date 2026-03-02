# RFC-046: Bridge 响应投递修复

## 文档信息
- **RFC ID**: RFC-046
- **关联 PRD**: [FEAT-046（Bridge 响应投递修复）](../../prd/features/FEAT-046-bridge-response-delivery-fix.md)
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景

FEAT-041 为消息 Bridge 引入了输入指示器、会话路由和 HTML 格式化功能之后，发现了一个关键的投递缺陷：当 AI 代理处理涉及工具调用的请求时（例如加载 Google Drive 工具组、列出文件），Telegram 只收到第一条中间响应（"我来加载工具组……"），而不是最终答案（实际的文件列表）。

根本原因在于 Bridge 响应投递管道中存在一个架构上的间接层。`BridgeAgentExecutorImpl` 调用 `.collect()` 而不处理事件，直接丢弃了 `SendMessageUseCase` 发出的所有 `ChatEvent`。执行完成后，`MessagingChannel` 必须通过 `BridgeMessageObserverImpl` 轮询数据库来重新获取响应——该实现使用 `maxByOrNull { it.createdAt }` 找到最新的 `AI_RESPONSE`。这种间接方式无法可靠地返回正确的最终响应。

### 目标

1. 直接从执行流中投递代理的最终响应，消除数据库轮询间接层
2. 保留 `BridgeMessageObserver` 作为边缘情况的兜底方案
3. 通过移除 `scope.launch`/`join` 模式来简化 `processInboundMessage()`
4. 确保执行后操作（如 AI 标题生成）不会导致响应投递管道崩溃
5. 添加诊断日志以便未来调试

### 非目标

- 向 Telegram 发送中间规划消息（这些消息增加噪音，没有实际价值）
- 修改 `SendMessageUseCase` 或工具调用流式循环
- 数据库 Schema 变更
- 新增 Channel 实现

## 技术设计

### 变更文件概览

```
bridge/src/main/kotlin/com/oneclaw/shadow/bridge/
├── BridgeAgentExecutor.kt                          # 已修改（返回类型）
└── channel/
    └── MessagingChannel.kt                         # 已修改（使用直接响应）
app/src/main/kotlin/com/oneclaw/shadow/feature/bridge/
└── BridgeAgentExecutorImpl.kt                      # 已修改（捕获响应）
bridge/src/test/kotlin/com/oneclaw/shadow/bridge/
└── channel/
    └── MessagingChannelTest.kt                     # 已修改（更新 Mock）
```

## 详细设计

### 变更 1：BridgeAgentExecutor 返回类型

**文件**: `bridge/src/main/kotlin/com/oneclaw/shadow/bridge/BridgeAgentExecutor.kt`

**当前代码**:
```kotlin
interface BridgeAgentExecutor {
    suspend fun executeMessage(
        conversationId: String,
        userMessage: String,
        imagePaths: List<String> = emptyList()
    )
}
```

**新代码**:
```kotlin
interface BridgeAgentExecutor {
    suspend fun executeMessage(
        conversationId: String,
        userMessage: String,
        imagePaths: List<String> = emptyList()
    ): BridgeMessage?
}
```

**设计理由**: 返回类型从 `Unit` 更改为 `BridgeMessage?`。返回 `null` 表示执行器无法确定最终响应（例如，Flow 仅发出了错误事件），调用方可据此回退到数据库观察者。

---

### 变更 2：BridgeAgentExecutorImpl 捕获最终响应

**文件**: `app/src/main/kotlin/com/oneclaw/shadow/feature/bridge/BridgeAgentExecutorImpl.kt`

**当前代码**:
```kotlin
override suspend fun executeMessage(
    conversationId: String,
    userMessage: String,
    imagePaths: List<String>
) {
    val agentId = resolveAgentId()
    sendMessageUseCase.execute(
        sessionId = conversationId,
        userText = userMessage,
        agentId = agentId
    ).collect()
}
```

**新代码**:
```kotlin
override suspend fun executeMessage(
    conversationId: String,
    userMessage: String,
    imagePaths: List<String>
): BridgeMessage? {
    // 阶段 1 标题：基于用户消息的截断标题（FEAT-045）
    val isFirstMessage = (sessionRepository.getSessionById(conversationId)?.messageCount ?: 0) == 0
    if (isFirstMessage) {
        val truncatedTitle = generateTitleUseCase.generateTruncatedTitle(userMessage)
        sessionRepository.updateTitle(conversationId, truncatedTitle)
    }

    val agentId = resolveAgentId()
    val pendingAttachments = imagePaths.mapNotNull { path -> /* ... 图片处理 ... */ }
    var lastResponseContent: String? = null
    var lastResponseTimestamp: Long = 0L
    var lastModelId: String? = null
    var lastProviderId: String? = null

    try {
        sendMessageUseCase.execute(
            sessionId = conversationId,
            userText = userMessage,
            agentId = agentId,
            pendingAttachments = pendingAttachments
        ).collect { event ->
            when (event) {
                is ChatEvent.ResponseComplete -> {
                    lastResponseContent = event.message.content
                    lastResponseTimestamp = event.message.createdAt
                    lastModelId = event.message.modelId
                    lastProviderId = event.message.providerId
                }
                else -> { /* bridge 不需要处理其他事件 */ }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 代理执行失败；返回 null 以便调用方回退
        return null
    }

    val content = lastResponseContent

    // 阶段 2 标题：首次响应后的 AI 生成标题（非致命）
    if (isFirstMessage && content != null && lastModelId != null && lastProviderId != null) {
        try {
            generateTitleUseCase.generateAiTitle(
                sessionId = conversationId,
                firstUserMessage = userMessage,
                firstAiResponse = content,
                currentModelId = lastModelId!!,
                currentProviderId = lastProviderId!!
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AI title generation failed (non-fatal)", e)
        }
    }

    return if (content != null && content.isNotBlank()) {
        BridgeMessage(content = content, timestamp = lastResponseTimestamp)
    } else {
        null
    }
}
```

**设计理由**:

- `ChatEvent.ResponseComplete` 由 `SendMessageUseCase` 在工具调用循环结束时恰好发出一次，此时不再有更多工具调用。它包含**最终**的 AI 响应消息——也就是用户真正想要的那条。
- 如果 Flow 在未发出 `ResponseComplete` 的情况下完成（例如，所有轮次均产生了工具调用且达到最大轮次限制，或发生了错误），`lastResponseContent` 保持为 null，方法返回 `null`。
- 按照 Kotlin 协程规范，`CancellationException` 会被重新抛出。其他所有异常返回 `null`，以便调用方可以优雅地回退。
- 阶段 2 标题生成（`generateAiTitle()`）被包裹在独立的 try/catch 块中。这一点至关重要：如果不加此包裹，标题生成 API 失败会作为未处理异常向上传播，导致整个 `processInboundMessage()` 管道崩溃，阻止响应投递到 Telegram。详见变更 4。

**修复后的行为**:

| 场景 | `lastResponseContent` | 返回值 |
|------|----------------------|--------|
| 单轮（无工具调用） | 最终 AI 文本 | `BridgeMessage(content, timestamp)` |
| 多轮（有工具调用） | 所有工具完成后的最终 AI 文本 | `BridgeMessage(content, timestamp)` |
| 执行期间代理出错 | null | `null` |
| 超出最大轮次 | null（未发出 `ResponseComplete`） | `null` |

---

### 变更 3：MessagingChannel 使用直接响应

**文件**: `bridge/src/main/kotlin/com/oneclaw/shadow/bridge/channel/MessagingChannel.kt`

**当前代码**（第 84-118 行）:
```kotlin
// 7. 并发执行代理（SendMessageUseCase 在内部插入用户消息）
val beforeTimestamp = System.currentTimeMillis()
val agentJob = scope.launch {
    agentExecutor.executeMessage(
        conversationId = conversationId,
        userMessage = msg.text,
        imagePaths = msg.imagePaths
    )
}

// 8. 等待代理完成，然后获取最终响应
val response = try {
    withTimeout(AGENT_RESPONSE_TIMEOUT_MS) {
        agentJob.join()
        messageObserver.awaitNextAssistantMessage(
            conversationId = conversationId,
            afterTimestamp = beforeTimestamp,
            timeoutMs = 10_000
        )
    }
} catch (e: kotlinx.coroutines.TimeoutCancellationException) {
    BridgeMessage(
        content = "Sorry, the agent did not respond in time. Please try again.",
        timestamp = System.currentTimeMillis()
    )
} finally {
    // 9. 取消输入指示器
    typingJob.cancel()
}

// 发送响应
runCatching { sendResponse(msg.externalChatId, response) }
```

**新代码**:
```kotlin
// 7. 执行代理并直接获取响应
val beforeTimestamp = System.currentTimeMillis()
Log.d(TAG, "Executing agent for conv=$conversationId, text=${msg.text.take(50)}")
val response = try {
    withTimeout(AGENT_RESPONSE_TIMEOUT_MS) {
        // executeMessage 现在直接返回最终响应
        val directResponse = agentExecutor.executeMessage(
            conversationId = conversationId,
            userMessage = msg.text,
            imagePaths = msg.imagePaths
        )
        Log.d(TAG, "executeMessage returned: ${if (directResponse != null) "content(${directResponse.content.length} chars)" else "null"}")
        // 使用直接响应；若为 null 则回退到数据库观察者
        if (directResponse != null) {
            directResponse
        } else {
            Log.d(TAG, "Falling back to DB observer")
            messageObserver.awaitNextAssistantMessage(
                conversationId = conversationId,
                afterTimestamp = beforeTimestamp,
                timeoutMs = 10_000
            )
        }
    }
} catch (e: kotlinx.coroutines.TimeoutCancellationException) {
    Log.w(TAG, "Agent timed out after ${AGENT_RESPONSE_TIMEOUT_MS}ms")
    BridgeMessage(
        content = "Sorry, the agent did not respond in time. Please try again.",
        timestamp = System.currentTimeMillis()
    )
} finally {
    // 8. 取消输入指示器
    typingJob.cancel()
}

// 发送响应
Log.d(TAG, "Sending response to ${msg.externalChatId}: ${response.content.length} chars")
val sendResult = runCatching { sendResponse(msg.externalChatId, response) }
if (sendResult.isFailure) {
    Log.e(TAG, "sendResponse FAILED", sendResult.exceptionOrNull())
}
```

**设计理由**:

1. **不再使用 `scope.launch`/`join` 模式**：`agentExecutor.executeMessage()` 直接作为 `suspend` 函数调用。由于 `processInboundMessage()` 本身运行在 `scope.launch {}` 内部（由轮询循环调用），输入指示器协程仍然并发运行——此处无需任何改动。

2. **优先使用直接响应**：`executeMessage()` 返回的 `BridgeMessage` 直接使用。这是 `ResponseComplete` 事件携带的消息内容——保证是最终响应。

3. **数据库观察者作为兜底**：若 `executeMessage()` 返回 `null`（代理出错、超出最大轮次），观察者照常轮询数据库。这保留了向后兼容性并处理了边缘情况。

4. **超时时间不变**：`withTimeout(AGENT_RESPONSE_TIMEOUT_MS)`（300 秒）同时包裹直接调用和兜底逻辑。若代理响应超时，则返回超时提示消息。

**并发模型对比**:

```
修复前：                                    修复后：
  typingJob = scope.launch { ... }           typingJob = scope.launch { ... }
  agentJob = scope.launch { execute() }      response = execute()  // 挂起，输入指示器并发运行
  agentJob.join()                            // 直接返回，无需 join
  response = observerPoll()                  // 仅在 null 时回退
  typingJob.cancel()                         typingJob.cancel()
```

两种模型实现了相同的并发性：输入指示器协程与代理执行并发运行。区别在于新模型直接捕获结果，而不是丢弃结果后再从数据库重新获取。

---

### 变更 4：执行后代码的异常安全

**文件**: `app/src/main/kotlin/com/oneclaw/shadow/feature/bridge/BridgeAgentExecutorImpl.kt`

**测试中发现的问题**: FEAT-045 在 `BridgeAgentExecutorImpl.executeMessage()` 的 Flow 收集之后添加了 `generateAiTitle()`。此调用位于包裹 `sendMessageUseCase.execute().collect()` 的 try/catch 块**之外**。当 AI 标题生成 API 调用失败时（如网络错误、API 速率限制），异常通过 `executeMessage()` -> `withTimeout` -> `processInboundMessage()` -> `scope.launch`（`SupervisorJob` 静默吞掉异常）向上传播。结果：`sendResponse()` 永远不会被执行，用户在 Telegram 上什么都收不到，尽管代理已经生成了有效的响应。

**修复**: 将 `generateAiTitle()` 包裹在独立的 try/catch 块中，使用 `Log.w()` 记录非致命日志。参见上方变更 2 中的代码。

**设计原则**: 执行后操作（标题生成、数据分析等）绝不能阻断响应投递。它们应被视为尽力而为（best-effort），并包裹在独立的错误边界中。

---

### 变更 5：诊断日志

**文件**: `MessagingChannel.kt`、`BridgeAgentExecutorImpl.kt`

Bridge 代码此前没有任何日志，导致无法通过 logcat 诊断投递故障。添加了以下日志点：

| 位置 | 级别 | 内容 |
|------|------|------|
| `MessagingChannel` 代理执行前 | `Log.d` | 会话 ID、消息文本预览 |
| `MessagingChannel` `executeMessage()` 返回后 | `Log.d` | 响应是否非空、内容长度 |
| `MessagingChannel` 回退到观察者时 | `Log.d` | 触发回退 |
| `MessagingChannel` `sendResponse()` 前 | `Log.d` | 目标聊天 ID、响应长度 |
| `MessagingChannel` `sendResponse()` 失败时 | `Log.e` | 异常详情 |
| `MessagingChannel` 超时时 | `Log.w` | 超时时长 |
| `BridgeAgentExecutorImpl` `generateAiTitle()` 失败时 | `Log.w` | 异常详情（非致命） |

---

### 无变更：BridgeMessageObserver

`BridgeMessageObserverImpl` **不做修改**。当 `executeMessage()` 返回 `null` 时，它仍作为兜底方案可用。这是一个刻意保留的安全网——若未来的改动引入了不发出 `ResponseComplete` 的新代码路径，观察者可确保响应仍能被投递。

## 测试

### 单元测试

**MessagingChannelTest** -- 更新：
- `processInboundMessage sends agent response to user`：将 `agentExecutor.executeMessage()` 的 Mock 更新为返回 `BridgeMessage("Agent response", ...)` 而非 `Unit`。验证返回的响应被直接发送（而不是通过观察者）。
- `processInboundMessage falls back to observer when executor returns null`：新增测试。Mock `executeMessage()` 返回 `null`。验证 `messageObserver.awaitNextAssistantMessage()` 被调用且其结果被发送。
- `processInboundMessage handles agent exception gracefully`：新增测试。Mock `executeMessage()` 抛出异常。验证回退到观察者。

**BridgeAgentExecutorImplTest** -- 新文件（可选，可推迟）：
- `executeMessage returns final response from ResponseComplete event`
- `executeMessage returns null when flow emits no ResponseComplete`
- `executeMessage returns null when flow throws exception`

### 手动验证

1. 通过 Telegram 发送触发工具调用的消息（例如"列出我的 Google Drive 根目录"）。验证最终文件列表被投递，而不是中间规划消息。
2. 通过 Telegram 发送简单问题（无工具调用）。验证响应正常投递。
3. 发送导致代理出错的消息（例如无效的 API Key）。验证错误消息或兜底响应被投递。

## 迁移说明

- `BridgeAgentExecutor.executeMessage()` 返回类型从 `Unit` 更改为 `BridgeMessage?`。所有实现必须同步更新。
- `MessagingChannel.processInboundMessage()` 不再使用 `scope.launch`/`join` 执行代理。代理直接在调用协程中运行。
- 无数据库 Schema 变更。
- 无 DI 模块变更（无新增依赖）。

## 待定问题

- [ ] 是否应将中间规划消息（例如"我来加载工具……"）也作为独立消息发送到 Telegram？当前决策：否，这些消息增加噪音。只有最终响应才有价值。

## 性能考量

- 轻微性能提升：在正常路径下消除了一次数据库查询（观察者轮询）。观察者查询仅在直接响应为 null 时作为兜底执行。

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|----------|--------|
| 2026-03-01 | 1.0 | 初始草稿 | - |
| 2026-03-01 | 1.1 | 新增变更 4（generateAiTitle 异常安全）、变更 5（诊断日志）、更新代码片段以反映 FEAT-045 集成 | - |
