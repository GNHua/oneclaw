# RFC-011: Auto Compact 与 Tool Result 截断

## 文档信息
- **RFC 编号**: RFC-011
- **关联 PRD**: [FEAT-011 (Auto Compact 与 Tool Result 截断)](../../prd/features/FEAT-011-auto-compact-zh.md)
- **关联设计**: [UI 设计规范](../../design/ui-design-spec-zh.md)
- **关联架构**: [RFC-000 (总体架构)](../architecture/RFC-000-overall-architecture-zh.md)
- **依赖**: [RFC-001 (对话交互)](RFC-001-chat-interaction-zh.md)、[RFC-003 (Provider 管理)](RFC-003-provider-management-zh.md)、[RFC-005 (会话管理)](RFC-005-session-management-zh.md)
- **被依赖**: 无
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景
当前 `SendMessageUseCase` 在每次请求时将会话的全部消息历史发送给 API，没有任何上下文窗口管理。当对话变长（50+ 条消息、大量工具调用）时，累积的 token 数会接近或超出模型的上下文窗口限制，导致 API 错误。此外，工具结果（如通过 `HttpRequestTool` 获取网页）可能达数百 KB，膨胀数据库并占用过多上下文空间。

本 RFC 引入两个相关功能：
1. **Auto Compact**：当对话接近模型上下文窗口限制时，自动总结较早的消息。摘要在 API 请求中替换旧消息，同时在数据库中保留所有原始消息。
2. **Tool Result 截断**：在存储时截断过大的工具结果，防止其进入数据库。

### 目标
1. 为 AiModel 实体添加 `contextWindowSize` 并为所有预置模型填充数据
2. 为 Session 实体添加压缩摘要存储字段
3. 实现数据库从版本 1 到版本 2 的迁移
4. 实现 `ToolResultTruncator`，在 DB 存储前截断超过 30K 字符的工具结果
5. 实现 `TokenEstimator`，基于字符的 token 估算
6. 实现 `AutoCompactUseCase`，检测是否需要压缩并生成摘要
7. 实现 `CompactAwareMessageBuilder`，使用压缩摘要构建 API 请求
8. 将所有组件集成到 `SendMessageUseCase`
9. 添加压缩相关的 `ChatEvent` 类型以提供 UI 反馈
10. 为所有新组件提供全面的单元测试

### 非目标
- 客户端 tokenizer（使用基于字符的估算）
- 设置中用户可配置的压缩阈值
- UI 中的手动压缩触发按钮
- 从 Provider API 自动检测上下文窗口大小
- 单条消息内部压缩（仅整条消息粒度）
- 工具结果流式传输或分页
- 聊天 UI 中的 token 使用量指示器

## 技术方案

### 架构概览

```
+--------------------------------------------------------------------------+
|                              UI 层                                        |
|  ChatViewModel                                                            |
|    |-- 处理 ChatEvent.CompactStarted / CompactCompleted                   |
|    |-- 压缩期间显示简短提示                                                 |
|    |-- 回退时显示 Snackbar                                                 |
+--------------------------------------------------------------------------+
|                            领域层                                          |
|  SendMessageUseCase                                                       |
|    |-- CompactAwareMessageBuilder.build()  (准备 API 消息)                 |
|    |-- adapter.sendMessageStream()         (调用 AI API)                   |
|    |-- ToolResultTruncator.truncate()      (保存工具结果前)                  |
|    |-- AutoCompactUseCase.compactIfNeeded() (响应完成后)                    |
|                                                                           |
|  AutoCompactUseCase                                                       |
|    |-- TokenEstimator.estimateTotalTokens()                               |
|    |-- splitMessages()（保护窗口 vs. 较早消息）                              |
|    |-- adapter.generateSimpleCompletion()  (生成摘要)                       |
|    |-- sessionRepository.updateCompactedSummary()                         |
+--------------------------------------------------------------------------+
|                             数据层                                         |
|  SessionEntity  (+ compacted_summary, compact_boundary_timestamp)         |
|  ModelEntity    (+ context_window_size)                                   |
|  Migration(1,2) (ALTER TABLE + UPDATE 预置值)                              |
+--------------------------------------------------------------------------+
```

### 核心组件

#### 1. ToolResultTruncator

无状态工具类，截断超过字符限制的工具结果字符串。

**文件**: `app/src/main/kotlin/com/oneclaw/shadow/core/util/ToolResultTruncator.kt`

```kotlin
package com.oneclaw.shadow.core.util

object ToolResultTruncator {
    const val MAX_CHARS = 30_000

    fun truncate(result: String): String {
        if (result.length <= MAX_CHARS) return result
        val suffix = "\n\n[... content truncated, showing first ${MAX_CHARS} of ${result.length} characters ...]"
        return result.substring(0, MAX_CHARS) + suffix
    }
}
```

**集成点**：在 `SendMessageUseCase` 中创建工具结果消息时（`toolOutput` 字段），在构造 `Message` 之前应用 `ToolResultTruncator.truncate()`。

#### 2. TokenEstimator

使用基于字符的启发式方法估算消息的 token 数。仅用于阈值检测 -- 精度不是关键，因为我们触发的是尽力而为的优化，而非精确限制。

**文件**: `app/src/main/kotlin/com/oneclaw/shadow/core/util/TokenEstimator.kt`

```kotlin
package com.oneclaw.shadow.core.util

import com.oneclaw.shadow.core.model.Message

object TokenEstimator {
    const val CHARS_PER_TOKEN = 4

    fun estimateTotalTokens(messages: List<Message>): Int =
        messages.sumOf { estimateMessageTokens(it) }

    fun estimateMessageTokens(msg: Message): Int {
        val contentTokens = estimateFromText(msg.content)
        val thinkingTokens = msg.thinkingContent?.let { estimateFromText(it) } ?: 0
        val toolInputTokens = msg.toolInput?.let { estimateFromText(it) } ?: 0
        val toolOutputTokens = msg.toolOutput?.let { estimateFromText(it) } ?: 0
        return contentTokens + thinkingTokens + toolInputTokens + toolOutputTokens
    }

    fun estimateFromText(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)
    }
}
```

#### 3. AutoCompactUseCase

压缩功能的核心编排器。

**文件**: `app/src/main/kotlin/com/oneclaw/shadow/feature/chat/usecase/AutoCompactUseCase.kt`

**构造函数依赖**:
- `SessionRepository`
- `MessageRepository`
- `ApiKeyStorage`
- `ModelApiAdapterFactory`

**核心方法**: `suspend fun compactIfNeeded(sessionId, model, provider): CompactResult`

**算法**:

```
1. 检查 model.contextWindowSize -- 如果为 null，返回（无操作）
2. 获取会话的所有消息
3. 通过 TokenEstimator 估算总 token 数
4. 如果 totalTokens <= contextWindowSize * 0.85，返回（无操作）
5. 将消息分为 (olderMessages, protectedMessages)：
   - 从最新消息向前遍历，累加 token 数
   - 当累积 token 达到 contextWindowSize * 0.25 时停止
   - 分割点之前的所有内容 = olderMessages
6. 如果 olderMessages 为空，返回（无操作）
7. 构建总结 prompt：
   - 如果会话已有 compactedSummary，将其作为"先前摘要"包含
   - 附加所有 olderMessages 作为对话记录
   - 指示模型生成简洁的事实摘要（200-500 词）
8. 调用 adapter.generateSimpleCompletion(prompt, maxTokens=2048)
9. 如果成功：将摘要 + 边界时间戳存储到 Session，返回 CompactResult(true)
10. 如果失败：重试一次
11. 如果重试仍失败：返回 CompactResult(false) -- 调用方处理回退
```

**CompactResult 数据类**:

```kotlin
data class CompactResult(
    val didCompact: Boolean,
    val fallbackToTruncation: Boolean = false
)
```

**总结 prompt**:

```
You are summarizing a conversation for context continuity. Create a concise but
comprehensive summary that preserves:
- Key topics discussed
- Important decisions or conclusions
- Any pending questions or tasks
- Tool calls made and their results (briefly)

[如果存在已有摘要:]
Previous conversation summary:
{existingSummary}

Additional conversation to incorporate:

[带角色标签的对话记录]

Provide a summary in 200-500 words. Be factual and concise.
```

#### 4. CompactAwareMessageBuilder

替代 `SendMessageUseCase` 中直接使用的 `allMessages.toApiMessages()` 调用。处理将压缩摘要注入系统提示和过滤消息。

**文件**: `app/src/main/kotlin/com/oneclaw/shadow/feature/chat/usecase/CompactAwareMessageBuilder.kt`

```kotlin
package com.oneclaw.shadow.feature.chat.usecase

import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.Session
import com.oneclaw.shadow.data.remote.adapter.ApiMessage

object CompactAwareMessageBuilder {

    fun build(
        session: Session,
        allMessages: List<Message>,
        originalSystemPrompt: String?
    ): Pair<String?, List<ApiMessage>> {
        val summary = session.compactedSummary
        val boundary = session.compactBoundaryTimestamp

        if (summary == null || boundary == null) {
            return Pair(originalSystemPrompt, allMessages.toApiMessages())
        }

        val recentMessages = allMessages.filter { it.createdAt >= boundary }
        val apiMessages = recentMessages.toApiMessages()

        val summaryPrefix = "Previous conversation summary:\n$summary\n\n---\n\n"
        val enhancedPrompt = if (originalSystemPrompt != null) {
            summaryPrefix + originalSystemPrompt
        } else {
            summaryPrefix + "Continue the conversation based on the summary above."
        }

        return Pair(enhancedPrompt, apiMessages)
    }
}
```

**关键设计决策**：压缩摘要前置到系统提示中，而不是作为单独的 user/assistant 消息注入。这确保模型将其视为背景上下文而非对话轮次的一部分，避免混淆角色交替模式。

### 数据模型

#### 修改的实体

**AiModel** (`core/model/AiModel.kt`):

```kotlin
data class AiModel(
    val id: String,
    val displayName: String?,
    val providerId: String,
    val isDefault: Boolean,
    val source: ModelSource,
    val contextWindowSize: Int? = null  // 最大上下文窗口 token 数；null = 未知
)
```

**ModelEntity** (`data/local/entity/ModelEntity.kt`):

```kotlin
@Entity(tableName = "models", primaryKeys = ["id", "provider_id"], ...)
data class ModelEntity(
    val id: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    val source: String,
    @ColumnInfo(name = "context_window_size") val contextWindowSize: Int? = null  // 新增
)
```

**Session** (`core/model/Session.kt`):

```kotlin
data class Session(
    val id: String,
    val title: String,
    val currentAgentId: String,
    val messageCount: Int,
    val lastMessagePreview: String?,
    val isActive: Boolean,
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val compactedSummary: String? = null,           // 新增
    val compactBoundaryTimestamp: Long? = null       // 新增
)
```

**SessionEntity** (`data/local/entity/SessionEntity.kt`):

```kotlin
@Entity(tableName = "sessions", ...)
data class SessionEntity(
    ...现有字段...,
    @ColumnInfo(name = "compacted_summary") val compactedSummary: String? = null,           // 新增
    @ColumnInfo(name = "compact_boundary_timestamp") val compactBoundaryTimestamp: Long? = null  // 新增
)
```

#### 数据库迁移

**新文件**: `app/src/main/kotlin/com/oneclaw/shadow/data/local/db/Migrations.kt`

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 为 models 表添加 context_window_size
        db.execSQL("ALTER TABLE models ADD COLUMN context_window_size INTEGER DEFAULT NULL")

        // 填充预置模型的上下文窗口大小
        db.execSQL("UPDATE models SET context_window_size = 128000 WHERE id = 'gpt-4o'")
        db.execSQL("UPDATE models SET context_window_size = 128000 WHERE id = 'gpt-4o-mini'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'o1'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'o3-mini'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'claude-opus-4-5-20251101'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'claude-sonnet-4-5-20250929'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'claude-haiku-4-5-20251001'")
        db.execSQL("UPDATE models SET context_window_size = 1048576 WHERE id = 'gemini-2.0-flash'")
        db.execSQL("UPDATE models SET context_window_size = 1048576 WHERE id = 'gemini-2.5-pro'")

        // 为 sessions 表添加压缩字段
        db.execSQL("ALTER TABLE sessions ADD COLUMN compacted_summary TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE sessions ADD COLUMN compact_boundary_timestamp INTEGER DEFAULT NULL")
    }
}
```

**AppDatabase 变更**:
- 将 `version = 1` 改为 `version = 2`
- 更新 seed 回调的 INSERT 语句，为全新安装包含 `context_window_size`
- 在 `DatabaseModule.kt` 中通过 `.addMigrations(MIGRATION_1_2)` 注册迁移

#### 预置模型上下文窗口大小

| 模型 ID | Provider | 上下文窗口 |
|---------|----------|-----------|
| gpt-4o | OpenAI | 128,000 |
| gpt-4o-mini | OpenAI | 128,000 |
| o1 | OpenAI | 200,000 |
| o3-mini | OpenAI | 200,000 |
| claude-opus-4-5-20251101 | Anthropic | 200,000 |
| claude-sonnet-4-5-20250929 | Anthropic | 200,000 |
| claude-haiku-4-5-20251001 | Anthropic | 200,000 |
| gemini-2.0-flash | Gemini | 1,048,576 |
| gemini-2.5-pro | Gemini | 1,048,576 |

### API 设计

#### SessionDao 新增

```kotlin
@Query("UPDATE sessions SET compacted_summary = :summary, compact_boundary_timestamp = :boundaryTimestamp, updated_at = :updatedAt WHERE id = :id")
suspend fun updateCompactedSummary(id: String, summary: String?, boundaryTimestamp: Long?, updatedAt: Long)
```

#### SessionRepository 新增

```kotlin
suspend fun updateCompactedSummary(id: String, summary: String?, boundaryTimestamp: Long?)
```

#### Mapper 更新

**ProviderMapper.kt**: 在 `ModelEntity.toDomain()` 和 `AiModel.toEntity()` 中映射 `contextWindowSize`。

**SessionMapper.kt**: 在两个方向上映射 `compactedSummary` 和 `compactBoundaryTimestamp`。

#### ChatEvent 新增

```kotlin
sealed class ChatEvent {
    ...现有事件...
    data object CompactStarted : ChatEvent()
    data class CompactCompleted(val didCompact: Boolean) : ChatEvent()
}
```

### SendMessageUseCase 集成

对 `SendMessageUseCase` 的三处变更：

#### 变更 1：支持压缩的消息构建

替换：
```kotlin
val allMessages = messageRepository.getMessagesSnapshot(sessionId)
val apiMessages = allMessages.toApiMessages()
// ...
adapter.sendMessageStream(..., systemPrompt = agent.systemPrompt)
```

为：
```kotlin
val allMessages = messageRepository.getMessagesSnapshot(sessionId)
val session = sessionRepository.getSessionById(sessionId)!!
val (effectiveSystemPrompt, apiMessages) = CompactAwareMessageBuilder.build(
    session = session,
    allMessages = allMessages,
    originalSystemPrompt = agent.systemPrompt
)
// ...
adapter.sendMessageStream(..., systemPrompt = effectiveSystemPrompt)
```

#### 变更 2：工具结果截断

在工具结果保存部分，创建 Message 前截断：
```kotlin
val rawOutput = tr.result.result ?: tr.result.errorMessage ?: ""
val truncatedOutput = ToolResultTruncator.truncate(rawOutput)
// 使用 truncatedOutput 作为 toolOutput
```

#### 变更 3：响应完成后触发压缩

在 `send(ChatEvent.ResponseComplete(...))` 之后、`break` 之前：

```kotlin
if (pendingToolCalls.isEmpty()) {
    sessionRepository.updateMessageStats(...)
    send(ChatEvent.ResponseComplete(aiMessage, usage))

    // 触发自动压缩检查
    send(ChatEvent.CompactStarted)
    val compactResult = autoCompactUseCase.compactIfNeeded(sessionId, model, provider)
    send(ChatEvent.CompactCompleted(compactResult.didCompact))

    break
}
```

#### 构造函数变更

添加 `autoCompactUseCase: AutoCompactUseCase` 参数。

### DI 注册

**FeatureModule.kt**:

```kotlin
// FEAT-011: Auto Compact
factory { AutoCompactUseCase(get(), get(), get(), get()) }

// 更新 SendMessageUseCase 以包含 AutoCompactUseCase
factory { SendMessageUseCase(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
```

### 常量

```kotlin
object CompactConstants {
    const val COMPACT_THRESHOLD_RATIO = 0.85
    const val PROTECTED_WINDOW_RATIO = 0.25
    const val TOOL_RESULT_MAX_CHARS = 30_000
    const val CHARS_PER_TOKEN_ESTIMATE = 4
    const val SUMMARY_MAX_TOKENS = 2048
    const val MAX_RETRIES = 1
}
```

这些常量可以内联定义在各自的类中（`AutoCompactUseCase.Companion`、`ToolResultTruncator`、`TokenEstimator`），而非单独的常量对象，遵循代码库中的现有模式（如 `SendMessageUseCase.MAX_TOOL_ROUNDS`）。

## 数据流

### Auto Compact 流程

```
用户发送消息
  |
  v
SendMessageUseCase.execute()
  |
  +-> 获取 session + 所有消息
  +-> CompactAwareMessageBuilder.build(session, messages, systemPrompt)
  |     |
  |     +-> session.compactedSummary == null?
  |     |     是 -> 返回 (originalPrompt, allMessages.toApiMessages())
  |     |     否 -> 过滤 >= boundaryTimestamp 的消息
  |     |           将摘要前置到 systemPrompt
  |     |           返回 (enhancedPrompt, recentMessages.toApiMessages())
  |
  +-> adapter.sendMessageStream(enhancedPrompt, filteredMessages)
  +-> 收集流式响应
  +-> 保存 AI 响应消息
  |
  +-> pendingToolCalls.isEmpty()?
  |     否 -> 执行工具，保存结果（带截断），下一轮
  |     是 -> send(ResponseComplete)
  |            |
  |            v
  |            AutoCompactUseCase.compactIfNeeded(sessionId, model, provider)
  |              |
  |              +-> contextWindowSize == null? -> 返回（无操作）
  |              +-> TokenEstimator.estimateTotalTokens(messages)
  |              +-> totalTokens <= 阈值? -> 返回（无操作）
  |              +-> splitMessages(messages, protectedBudget)
  |              +-> olderMessages.isEmpty()? -> 返回（无操作）
  |              +-> buildSummarizationPrompt(older, existingSummary)
  |              +-> adapter.generateSimpleCompletion(prompt, 2048)
  |              +-> 成功? -> sessionRepository.updateCompactedSummary()
  |              +-> 失败? -> 重试一次 -> 仍失败? -> 返回（未压缩）
  |
  +-> break（流结束）
```

### Tool Result 截断流程

```
工具执行 -> 返回结果字符串
  |
  v
ToolResultTruncator.truncate(result)
  |
  +-> result.length <= 30,000? -> 原样返回
  +-> result.length > 30,000? -> 返回前 30K 字符 + 截断标记
  |
  v
将截断后的结果保存到 Message.toolOutput -> DB
```

## 错误处理

| 场景 | 操作 |
|------|------|
| `contextWindowSize` 为 null | 完全跳过压缩（无操作）。功能优雅降级。 |
| 所有消息都在保护窗口内 | 跳过压缩（无内容可总结）。 |
| 总结 API 失败（网络错误） | 静默重试一次。 |
| 重试也失败 | 返回 `CompactResult(false)`。UI 不显示错误。下次请求发送所有消息 -- 可能触发 API 限制，由现有错误流程处理。 |
| 总结返回空/空白 | 视为失败，重试一次。 |
| 压缩已在进行中（竞态） | 不可能发生 -- 压缩在 ResponseComplete 之后同步运行于 channelFlow 中，UI 在流式传输期间阻止新消息。 |
| 工具结果截断 | 永不抛出异常。低于限制时原样返回输入。 |

## 性能考虑

- **TokenEstimator**：O(n) 扫描消息，仅检查字符串长度。典型会话 < 1ms。
- **CompactAwareMessageBuilder**：O(n) 按时间戳过滤。开销可忽略。
- **AutoCompactUseCase**：`generateSimpleCompletion` 调用是瓶颈（网络 I/O）。预计 2-10 秒取决于模型/provider。在 `ResponseComplete` 已发送后运行，用户已看到响应。
- **ToolResultTruncator**：O(1) 长度检查，仅在需要时 O(n) 子串。< 1ms。
- **数据库迁移**：一次性成本。ALTER TABLE + 对 9 行的 UPDATE。< 100ms。

## 安全性考虑

- 压缩摘要可能包含对话中的敏感信息。它们存储在与原始消息相同的本地 Room 数据库中，具有相同的访问控制。
- 总结请求将对话内容发送给已经处理该对话的同一 API provider。没有新的数据暴露。
- 工具结果截断减少了本地存储的潜在敏感外部数据量。

## 实现步骤

### Phase 1：数据库 Schema 变更
1. [ ] 为 `AiModel` 和 `ModelEntity` 添加 `contextWindowSize: Int?`
2. [ ] 为 `Session` 和 `SessionEntity` 添加 `compactedSummary: String?` 和 `compactBoundaryTimestamp: Long?`
3. [ ] 创建 `Migrations.kt`，包含 `MIGRATION_1_2`
4. [ ] 将 `AppDatabase` 升级到版本 2，更新 seed 回调
5. [ ] 在 `DatabaseModule.kt` 中注册迁移
6. [ ] 更新 `ProviderMapper`（模型映射）
7. [ ] 更新 `SessionMapper`（会话映射）
8. [ ] 为 `SessionDao`、`SessionRepository`、`SessionRepositoryImpl` 添加 `updateCompactedSummary`

### Phase 2：Tool Result 截断
9. [ ] 创建 `ToolResultTruncator.kt`
10. [ ] 集成到 `SendMessageUseCase`（工具结果保存）
11. [ ] 编写 `ToolResultTruncatorTest.kt`

### Phase 3：Auto Compact 核心
12. [ ] 创建 `TokenEstimator.kt`
13. [ ] 创建 `AutoCompactUseCase.kt`
14. [ ] 创建 `CompactAwareMessageBuilder.kt`
15. [ ] 编写 `TokenEstimatorTest.kt`
16. [ ] 编写 `AutoCompactUseCaseTest.kt`
17. [ ] 编写 `CompactAwareMessageBuilderTest.kt`

### Phase 4：集成
18. [ ] 为 `ChatEvent` 添加 `CompactStarted` / `CompactCompleted`
19. [ ] 修改 `SendMessageUseCase`：支持压缩的消息构建、压缩触发、添加依赖
20. [ ] 在 `ChatViewModel` 中处理压缩事件（简短提示 / 回退时的 Snackbar）
21. [ ] 在 `FeatureModule.kt` 中注册 `AutoCompactUseCase`，更新 `SendMessageUseCase` factory
22. [ ] 更新现有 `SendMessageUseCaseTest` 以适配新的构造函数参数

### Phase 5：测试
23. [ ] 运行 `./gradlew test` -- 所有单元测试通过
24. [ ] 运行 `./gradlew connectedAndroidTest` -- 所有插桩测试通过（更新 DAO 测试以适配新列）
25. [ ] 编写迁移插桩测试
26. [ ] 如适用，执行 Layer 2 adb 验证
27. [ ] 编写测试报告

## 依赖关系

- **Room**：数据库迁移支持（已可用）
- **ModelApiAdapter.generateSimpleCompletion()**：已在所有 3 个 adapter 中实现，被 `GenerateTitleUseCase` 使用
- 无需新的外部库

## 风险和缓解

| 风险 | 可能性 | 影响 | 缓解 |
|------|--------|------|------|
| 基于字符的 token 估算不准确 | 中 | 低 | 高估是可接受的；会稍早触发压缩。低估可能导致 API 错误，由现有错误流程处理。 |
| 摘要质量降低上下文连续性 | 低 | 中 | Prompt 明确指定需要保留的内容。摘要是累积的。用户可以开始新会话。 |
| 现有安装的迁移失败 | 低 | 高 | SQLite 中 ALTER TABLE ADD COLUMN 是安全的。通过插桩迁移测试充分验证。 |
| `generateSimpleCompletion` 超时 | 低 | 低 | 重试一次。如果都失败，对话在没有压缩的情况下继续。 |

## 替代方案

1. **滑动窗口（丢弃最早消息）**：更简单但丢失旧消息的所有上下文。摘要方式保留关键信息。
2. **客户端 tokenizer**：更准确的 token 计数但增加依赖复杂度（tiktoken/sentencepiece）。字符估算对阈值检测足够。
3. **将摘要存储为 Message**：需要新的 `MessageType.COMPACT_SUMMARY`。存储在 Session 上更简洁 -- 每个会话一个字段，不混入消息序列。
4. **固定消息数作为保护窗口**：不可靠，因为消息长度差异很大（工具结果可达 30K 字符）。基于 token 的比例更健壮。

## 未来扩展

- [ ] 设置中用户可配置的压缩阈值
- [ ] 手动压缩触发按钮
- [ ] 聊天 UI 中的 token 使用量指示器
- [ ] 从 provider API 获取模型时自动填充 `contextWindowSize`
- [ ] "重置上下文"按钮清除 `compactedSummary`

## 变更历史

| 日期 | 版本 | 变更内容 | 变更人 |
|------|------|----------|--------|
| 2026-02-28 | 0.1 | 初始版本 | - |
