# RFC-041: 消息桥接改进

## 文档信息
- **RFC ID**: RFC-041
- **关联 PRD**: [FEAT-041（桥接改进）](../../prd/features/FEAT-041-bridge-improvements.md)
- **创建时间**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 已完成
- **作者**: TBD

## 概述

### 背景

在初始消息桥接实现（RFC-024）完成后，用户测试发现了三类问题：（1）Telegram 消息格式中存在过多空行，（2）输入状态指示器在 Agent 处理完成后才显示，而非处理前，（3）桥接消息被发送到专用的仅桥接会话，而非应用的活跃会话。本 RFC 记录了解决上述问题的技术变更，以及额外的可靠性改进。

### 目标

1. 将 TelegramHtmlRenderer 从基于正则表达式的方式重写为 AST 访问者模式，以实现正确的格式化
2. 修复输入状态指示器的时序，使其在 Agent 执行前显示
3. 将桥接消息路由到应用最近使用的会话
4. 为 HTML 渲染失败添加纯文本回退机制
5. 将所有桥接改进整合到文档中

### 非目标

- 新增渠道实现
- Agent 向平台发送富媒体响应
- 数据库 Schema 迁移

## 技术设计

### 变更文件概览

```
bridge/src/main/kotlin/com/oneclaw/shadow/bridge/
├── BridgeConversationManager.kt                    # 已修改（suspend fun）
├── channel/
│   ├── ConversationMapper.kt                       # 已修改（移除 preferences）
│   ├── MessagingChannel.kt                         # 已修改（输入顺序）
│   └── telegram/
│       ├── TelegramApi.kt                          # 已修改（可空 parseMode）
│       ├── TelegramChannel.kt                      # 已修改（object + 回退）
│       └── TelegramHtmlRenderer.kt                 # 已重写（AST 访问者）
├── service/
│   └── MessagingBridgeService.kt                   # 已修改（mapper 构建）
app/src/main/kotlin/com/oneclaw/shadow/
├── core/repository/
│   └── SessionRepository.kt                        # 已修改（新方法）
├── data/
│   ├── local/dao/
│   │   └── SessionDao.kt                           # 已修改（新查询）
│   └── repository/
│       └── SessionRepositoryImpl.kt                # 已修改（新方法）
└── feature/bridge/
    └── BridgeConversationManagerImpl.kt            # 已修改（活跃会话）
bridge/src/test/kotlin/com/oneclaw/shadow/bridge/
├── channel/
│   ├── ConversationMapperTest.kt                   # 已重写
│   ├── MessagingChannelTest.kt                     # 已修改
│   └── telegram/
│       └── TelegramHtmlRendererTest.kt             # 已重写
```

## 详细设计

### 修复 1：TelegramHtmlRenderer 重写

**问题**：原有渲染器采用两步流程：Markdown -> HTML（通过 commonmark HtmlRenderer）-> Telegram HTML（通过正则替换）。正则方式在每个 `<p>` 和 `<h>` 标签后无差别地追加 `\n\n`，导致过多空行。

**解决方案**：改用直接的 AST 访问者模式。将 Markdown 解析为 commonmark AST，然后用自定义的 `AbstractVisitor` 子类遍历语法树，直接输出 Telegram 兼容的 HTML。

关键设计决策：
- 从 `class` 改为 `object`（无状态单例，线程安全）
- `TelegramHtmlVisitor` 继承 `AbstractVisitor`，并重写所有相关节点类型的处理方法
- `appendBlockSeparator(node)`：仅在 `node.next != null` 时添加 `\n`；仅对顶层块（父节点为 `Document` 或 `BlockQuote`）添加 `\n\n`
- 列表项：展开内部的 `Paragraph` 节点，避免列表项内出现多余换行
- 块引用：使用原生 `<blockquote>` 标签（而非 `<i>` 变通方案）；在关闭标签前去除尾部换行
- 有序列表：跟踪计数器，渲染为 `1. `、`2. ` 等
- 水平分割线：渲染为 8 个水平线框绘制字符（U+2500）
- 通过 `escapeHtml()` 对 `&`、`<`、`>` 进行 HTML 转义
- `splitForTelegram()` 移至伴生对象

**之前**（正则方式）：
```kotlin
class TelegramHtmlRenderer {
    fun render(markdown: String): String {
        val html = HtmlRenderer.builder().build().render(parser.parse(markdown))
        return convertToTelegramHtml(html)  // 正则替换
    }
}
```

**之后**（AST 访问者）：
```kotlin
object TelegramHtmlRenderer {
    fun render(markdown: String): String {
        val document = parser.parse(markdown)
        val visitor = TelegramHtmlVisitor()
        document.accept(visitor)
        return visitor.result().trimEnd()
    }
}
```

### 修复 2：输入状态指示器时序

**问题**：在 `processInboundMessage()` 中，`agentExecutor.executeMessage()` 通过 `.collect()` 同步（阻塞）调用，在启动输入状态指示器协程之前就已执行完毕。用户永远看不到"正在输入..."，因为它在 Agent 已经完成后才启动。

**解决方案**：调整操作顺序：

```
之前（有问题）：                              之后（已修复）：
  agentExecutor.executeMessage() [阻塞]        启动输入状态指示器协程
  启动输入状态 [为时已晚！]                    scope.launch { agentExecutor.executeMessage() }
  等待响应 [立即返回]                          通过 messageObserver 等待响应
  取消输入状态                                 取消输入状态
```

输入状态协程现在立即启动。`agentExecutor.executeMessage()` 被包装在 `scope.launch { }` 中，以便并发运行。`messageObserver.awaitNextAssistantMessage()` 调用仍以 300 秒超时等待实际响应。

### 修复 3：活跃会话集成

**问题**：桥接消息被发送到存储在 `BridgePreferences.getBridgeConversationId()` 中的专用桥接会话。该会话在应用 UI 中不可见，与用户的工作流程脱节。

**解决方案**：使用最近更新的会话作为桥接目标。

**接口变更** -- `BridgeConversationManager`：
```kotlin
// 之前
fun getActiveConversationId(): String?

// 之后
suspend fun getActiveConversationId(): String?
```

**新 DAO 查询** -- `SessionDao`：
```kotlin
@Query("SELECT id FROM sessions WHERE deleted_at IS NULL ORDER BY updated_at DESC LIMIT 1")
suspend fun getMostRecentSessionId(): String?
```

**新仓库方法** -- `SessionRepository` + `SessionRepositoryImpl`：
```kotlin
suspend fun getMostRecentSessionId(): String?
```

**实现** -- `BridgeConversationManagerImpl`：
```kotlin
override suspend fun getActiveConversationId(): String? {
    return sessionRepository.getMostRecentSessionId()
}
```

**简化后的 ConversationMapper**（移除了 `BridgePreferences` 依赖）：
```kotlin
class ConversationMapper(
    private val conversationManager: BridgeConversationManager
) {
    suspend fun resolveConversationId(): String {
        val activeId = conversationManager.getActiveConversationId()
        if (activeId != null && conversationManager.conversationExists(activeId)) {
            return activeId
        }
        return createNewConversation()
    }
    suspend fun createNewConversation(): String {
        return conversationManager.createNewConversation()
    }
}
```

### 修复 4：纯文本回退

**TelegramChannel.sendResponse()** 现在将 HTML 渲染包裹在 try/catch 中：
```kotlin
override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
    val htmlText = try {
        TelegramHtmlRenderer.render(message.content)
    } catch (e: Exception) {
        null
    }
    if (htmlText != null) {
        val parts = TelegramHtmlRenderer.splitForTelegram(htmlText)
        parts.forEach { api.sendMessage(chatId = externalChatId, text = it, parseMode = "HTML") }
    } else {
        val parts = TelegramHtmlRenderer.splitForTelegram(message.content)
        parts.forEach { api.sendMessage(chatId = externalChatId, text = it, parseMode = null) }
    }
}
```

**TelegramApi.sendMessage()** 更新为接受可空的 `parseMode`：
```kotlin
suspend fun sendMessage(chatId: String, text: String, parseMode: String? = "HTML")
```

## 测试

### 单元测试

- **TelegramHtmlRendererTest**：已重写，使用精确的 `assertEquals` 断言，覆盖段落、标题、列表（有序和无序）、块引用、代码块、水平分割线、链接、HTML 转义、混合内容以及消息拆分。
- **ConversationMapperTest**：已重写，改为针对 `getActiveConversationId()` 进行测试，而非 `preferences.getBridgeConversationId()`。移除了所有 `BridgePreferences` 的 mock 交互。
- **MessagingChannelTest**：更新了 Agent 执行验证的测试。

### 手动验证

1. 通过 Telegram 发送消息，验证响应格式紧凑
2. 验证 Agent 处理期间 Telegram 中显示输入状态指示器
3. 验证桥接消息出现在应用最近使用的会话中
4. 通过 Telegram 发送 `/clear`，验证新会话被创建
5. 重启设备，验证桥接自动启动

## 迁移说明

- 不需要数据库 Schema 变更
- `ConversationMapper` 构造函数签名已变更：移除了 `BridgePreferences` 参数
- `BridgeConversationManager.getActiveConversationId()` 从 `fun` 改为 `suspend fun`
- `TelegramHtmlRenderer` 从 `class` 改为 `object` -- 调用方不再需要实例化它
