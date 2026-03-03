# 记忆系统增强

## 功能信息
- **Feature ID**: FEAT-023
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1（应该具备）
- **Owner**: TBD
- **Related RFC**: [RFC-023（记忆系统增强）](../../rfc/features/RFC-023-memory-enhancement.md)
- **Extends**: [FEAT-013（记忆系统）](FEAT-013-memory.md)

## 用户故事

**作为** OneClaw 的用户，
**我希望** 在切换会话或第二天重新打开应用时，记忆系统能够正确触发，并且希望 AI 在对话过程中能够主动将重要信息保存到长期记忆，
**以便** 我的对话上下文得到可靠的捕获，并且无需完全依赖自动每日日志汇总就能构建持久化的知识库。

### 典型场景
1. 用户在会话 A 中进行了较长的对话，然后从会话列表导航到会话 B。会话 A 的每日日志应当被自动刷新——目前并非如此。
2. 用户在关闭应用后过夜，次日早晨重新打开应用。日期变更触发器应当刷新活跃会话前一天的每日日志——目前并不会触发。
3. 用户在与 AI 聊天时说："记住我的项目使用 PostgreSQL 16，运行在 Ubuntu 22.04 上。" AI 应当能够立即将此信息保存到长期记忆（MEMORY.md）——目前没有工具可以做到这一点。
4. 用户要求 AI "将我们今天讨论的内容摘要保存到记忆中。" AI 使用 `save_memory` 工具将摘要直接写入 MEMORY.md。

## 功能描述

### 概述
本功能解决现有记忆系统（FEAT-013）中的两个缺口：

1. **接入缺失的记忆触发器** —— `MemoryTriggerManager` 中 5 个触发方法中只有 1 个被实际调用。将 `onSessionSwitch` 和 `onDayChange` 与对应的生命周期事件挂钩。其余两个（`onSessionEnd`、`onPreCompaction`）暂缓实现。
2. **`save_memory` 内置工具** —— 一个新工具，允许 AI 在聊天对话过程中主动将内容写入 MEMORY.md。这为现有的只读记忆注入（系统提示词）补充了写入能力。

### 详细规格

#### 1. 接入 `onSessionSwitch` 触发器

当前状态：`MemoryTriggerManager.onSessionSwitch(previousSessionId)` 存在，但从未被调用。用户切换会话时，前一个会话的每日日志不会被刷新。

目标状态：当 `ChatViewModel.initialize(sessionId)` 以新的会话 ID 被调用，且存在前一个活跃会话时，在加载新会话之前调用 `memoryTriggerManager.onSessionSwitch(previousSessionId)`。

实现方式：
- 在 `ChatViewModel` 中添加 `MemoryTriggerManager?` 作为可选构造函数参数（可空，以保持向后兼容）
- 在 `initialize()` 中，加载新会话之前，将当前的 `_uiState.value.sessionId` 捕获为 `previousSessionId`
- 若 `previousSessionId` 非空且与新的 `sessionId` 不同，则调用 `memoryTriggerManager?.onSessionSwitch(previousSessionId)`
- 更新 `FeatureModule.kt` 的依赖注入注册，以注入 `MemoryTriggerManager`

#### 2. 接入 `onDayChange` 触发器

当前状态：`MemoryTriggerManager.onDayChange(activeSessionId)` 存在，但从未被调用。跨越午夜使用应用时，日期变更触发器不会触发。

目标状态：当应用回到前台时（通过 `ProcessLifecycleOwner.onStart`），将当前日期与存储在 SharedPreferences 中的"最后活跃日期"进行比较。若日期已变更，则刷新活跃会话的每日日志。

实现方式：
- 在 `OneclawApplication.kt` 中，在 ProcessLifecycleOwner 观察者中的现有 `onStop` 处理器旁边添加 `onStart` 处理器
- 使用 `SharedPreferences("memory_trigger_prefs")` 以 `YYYY-MM-DD` 字符串格式存储最后活跃日期
- 在 `onStart` 时：将存储的日期与当前日期进行比较。若不同，则调用新方法 `MemoryTriggerManager.onDayChangeForActiveSession()` 并更新存储的日期
- `onDayChangeForActiveSession()` 内部通过 `sessionRepository.getActiveSession()` 解析活跃会话，遵循与 `flushActiveSession()` 相同的模式

#### 3. `onSessionEnd` 触发器（暂缓）

应用中不存在明确的"关闭会话"操作。会话切换已经涵盖了离开会话时刷新每日日志的主要使用场景。待明确的会话关闭操作添加后，再接入此触发器。

#### 4. `onPreCompaction` 触发器（暂缓）

此触发器设计用于与 FEAT-011 自动压缩功能集成。待压缩系统在压缩消息历史前调用 `onPreCompaction` 时再进行接入。

#### 5. `save_memory` 内置工具

一个注册在 `ToolRegistry` 中的新内置工具，使 AI 能够在聊天对话过程中将内容写入 MEMORY.md。

**工具定义：**
- **Name**: `save_memory`
- **Description**: "Save important information to long-term memory (MEMORY.md). Use this when the user asks you to remember something, or when you identify critical information that should persist across conversations."
- **参数**：
  - `content`（string，必填）—— 要追加到 MEMORY.md 的文本内容，最多 5,000 个字符。
- **返回值**：确认内容已保存的成功消息，或错误消息。

**用户交互流程：**
```
1. 用户正在进行聊天对话
2. 用户说："记住我的 API 使用 RS256 签名的 JWT 令牌"
3. AI 决定使用 save_memory 工具，根据用户的陈述组织合适的记忆条目
4. 工具将内容追加到 MEMORY.md 并为其建立搜索索引
5. 工具向 AI 返回成功消息
6. AI 向用户确认信息已保存
7. 在未来的对话中，该信息通过记忆注入出现在系统提示词中
```

AI 负责决定保存什么内容以及如何保存。在保存之前，AI 应当对内容进行适当格式化（例如，添加上下文、使用清晰的语言）。该工具不会与每日日志条目进行去重——它们服务于不同的目的（主动保存 vs. 自动保存）。

## 验收标准

必须通过（全部必填）：
- [ ] 当用户从会话 A 切换到会话 B 时，会话 A 的每日日志被刷新
- [ ] 调用 `initialize(null)` 时，`onSessionSwitch` 不被调用（新对话重置场景）
- [ ] 当前一个会话 ID 与新的相同时，`onSessionSwitch` 不被调用
- [ ] 当应用在新的一天回到前台时，活跃会话的每日日志被刷新
- [ ] 日期变更检测使用 SharedPreferences 持久化最后活跃日期
- [ ] 若存储的日期与当日日期相符，日期变更触发器在当天首次启动时不触发
- [ ] `save_memory` 工具已在 ToolRegistry 中注册，并对所有 Agent 可用
- [ ] AI 可以在聊天对话中调用 `save_memory` 工具将内容保存到 MEMORY.md
- [ ] `save_memory` 工具验证 `content` 不为空
- [ ] `save_memory` 工具验证 `content` 在 5,000 字符限制以内
- [ ] `save_memory` 工具在保存成功时返回成功消息
- [ ] `save_memory` 工具在失败时返回错误消息
- [ ] 已保存的内容被建立索引以支持混合搜索（可通过 `searchMemory()` 获取）
- [ ] 已保存的内容在未来的系统提示词记忆注入中出现

可选（锦上添花）：
- [ ] `save_memory` 工具自动为保存的内容添加时间戳头部
- [ ] 与现有条目的记忆去重或冲突检测

## 功能边界

### 包含
- 在 `ChatViewModel.initialize()` 中接入 `onSessionSwitch` 触发器
- 在 `OneclawApplication` 中通过 `ProcessLifecycleOwner.onStart` 接入 `onDayChange` 触发器
- `MemoryTriggerManager` 中新增 `onDayChangeForActiveSession()` 方法
- `MemoryManager` 中新增 `saveToLongTermMemory(content)` 方法
- `tool/builtin/` 中新增 `SaveMemoryTool` 内置工具
- `FeatureModule.kt` 和 `ToolModule.kt` 中的依赖注入更新

### 不包含
- 接入 `onSessionEnd`（不存在明确的关闭操作；暂缓）
- 接入 `onPreCompaction`（暂缓至 FEAT-011 集成时）
- 记忆编辑或删除工具（未来增强项）
- 从记忆页面查看/编辑 MEMORY.md 的 UI（已在 FEAT-013 中实现）
- 无需用户请求的 AI 自动发起记忆保存（AI 决定何时使用工具，但仅响应用户对话）
- 与每日日志条目的记忆去重或合并

## 业务规则

### 会话切换触发规则
1. 仅在从一个已有会话切换到不同会话时触发
2. 初始应用加载时不触发（`initialize(null)`）
3. 重新加载相同会话时不触发
4. 刷新操作采用"即发即忘"方式（不阻塞会话切换）
5. 并发刷新保护由 `MemoryTriggerManager` 中现有的 Mutex 处理

### 日期变更触发规则
1. 日期比较使用设备的本地日期（`LocalDate.now()`）
2. 存储的日期在触发刷新**之后**更新（以避免在崩溃时错过触发）
3. 首次启动应用时，存储当前日期，不触发刷新
4. 每次 `onStart` 回调最多触发一次（在单次前台期间不会重复检查）

### `save_memory` 工具规则
1. 内容追加到 MEMORY.md 末尾（从不覆盖现有内容）
2. 内容最大长度为 5,000 个字符（超出限制的内容将被拒绝并返回错误）
3. 空内容或空白内容将被拒绝并返回验证错误
4. 该工具不检查重复内容——AI 应当避免冗余保存
5. 保存的内容立即被建立索引以支持搜索
6. 不强制要求特定格式——AI 负责对内容进行适当格式化

## 依赖关系

### 依赖于
- **FEAT-013（记忆系统）**：本功能扩展自 FEAT-013
- **FEAT-001（聊天）**：`ChatViewModel` 因会话切换触发器而被修改
- **FEAT-004（工具系统）**：`save_memory` 工具与工具系统集成

### 被依赖于
- 暂无

## 错误处理

### 错误场景

1. **会话切换刷新失败（I/O 错误）**
   - 处理方式：错误通过 `Log.w()` 记录，会话切换正常继续
   - 用户影响：无（非阻塞；下次触发时重试刷新）

2. **日期变更刷新失败（I/O 错误）**
   - 处理方式：错误被记录，存储的日期仍然更新
   - 用户影响：无（当天的每日日志可能不完整）

3. **`save_memory` 工具以空内容调用**
   - 工具返回：`ToolResult.error("validation_error", "Parameter 'content' is required and must be non-empty.")`
   - AI 在聊天中向用户报告错误

4. **`save_memory` 工具以超过 5,000 字符的内容调用**
   - 工具返回：`ToolResult.error("validation_error", "Parameter 'content' must be 5,000 characters or less.")`
   - AI 向用户报告限制，并可能提议拆分内容

5. **`save_memory` 工具写入失败（文件 I/O 错误）**
   - 工具返回：`ToolResult.error("save_failed", "Failed to save memory: ...")`
   - AI 向用户报告错误

6. **`save_memory` 工具索引建立失败（嵌入向量错误）**
   - 处理方式：内容仍然保存到 MEMORY.md；索引失败被记录，但不导致工具失败
   - 用户影响：内容已保存，但在索引重建之前可能不出现在搜索结果中

## 测试要点

### 功能测试
- 验证在 ChatViewModel 中从会话 A 切换到会话 B 时，`onSessionSwitch` 被调用
- 验证调用 `initialize(null)` 时，`onSessionSwitch` 不被调用
- 验证重新加载相同会话 ID 时，`onSessionSwitch` 不被调用
- 验证存储日期与当前日期不同时，日期变更检测触发
- 验证日期相符时，日期变更检测不触发
- 验证 `save_memory` 工具已注册并出现在可用工具列表中
- 验证 `save_memory` 工具将内容追加到 MEMORY.md
- 验证 `save_memory` 工具为保存的内容建立搜索索引
- 验证 `save_memory` 工具对空内容返回错误
- 验证 `save_memory` 工具对超过 5,000 字符的内容返回错误

### 边界情况
- 当 `MemoryTriggerManager` 注入为 null 时切换会话（向后兼容性）
- 应用首次启动时的日期变更（无存储日期）
- 无活跃会话时的日期变更
- `save_memory` 内容恰好为 5,000 个字符
- MEMORY.md 尚不存在时的 `save_memory`（首次保存）
- 包含 unicode/多字节字符的 `save_memory`
- 多次快速会话切换（Mutex 竞争）
- 同一天内应用多次切换到后台和前台

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | 初始版本 | - |
