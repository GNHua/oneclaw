# 工具组会话持久化

## 功能信息
- **功能 ID**: FEAT-044
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **优先级**: P0（必须具备）
- **负责人**: 待定
- **相关 RFC**: [RFC-044（工具组会话持久化）](../../rfc/features/RFC-044-tool-group-persistence.md)
- **相关功能**: [FEAT-040（工具组路由）](FEAT-040-tool-group-routing.md)

## 用户故事

**作为** OneClaw 的用户，
**我希望** 在对话中已加载的工具组在后续消息中仍然可用，
**以便** 我可以在多条消息中使用 Gmail、Drive、Calendar 等工具，而无需每次重新加载。

### 典型场景
1. 用户发送"查看我的邮件"——AI 加载 Gmail 工具组并列出邮件。用户随后发送"删除垃圾邮件"——AI 可以直接使用 `gmail_trash`，无需重新加载 Gmail 工具。
2. 用户发送"列出我的 Drive 文件"——AI 加载 Google Drive 工具组。用户随后发送"查看我明天的日程"——AI 加载 Calendar 工具组。Drive 和 Calendar 工具在本次会话的剩余时间内均保持可用。
3. 用户通过 Messaging Bridge 发送 `/clear`，开始新会话。新会话从空白状态开始，不预加载任何工具组。
4. 用户通过 Telegram 提问。AI 加载 Gmail 工具并成功执行。用户发送 Gmail 相关的追问——AI 可以直接使用 Gmail 工具，不会出现"该 Agent 不支持此工具"的错误。

## 功能描述

### 概述
RFC-040 引入了工具组路由机制，通过 `load_tool_group` 按需加载工具 schema，以减少 token 消耗。然而，已加载的工具组仅在单次 `SendMessageUseCase.execute()` 调用期间有效。当用户发送新消息时，所有已加载的工具组都会丢失，导致出现"该 Agent 不支持此工具"的错误。

本功能通过扫描对话历史中先前成功的 `load_tool_group` 调用，并在每次 `execute()` 开始时预加载这些工具组，使工具组在同一会话的多个对话轮次中持久保留。

### 问题

每次用户发送新消息时，`SendMessageUseCase.execute()` 都会重新初始化活跃工具列表：

```kotlin
val loadedGroupNames = mutableSetOf<String>()
val activeToolDefs = toolRegistry.getCoreToolDefinitions().toMutableList()
```

这意味着：
- **第 1 轮**：用户要求查看邮件。AI 加载 Gmail 工具，`gmail_search` 执行成功。
- **第 2 轮**：用户提出 Gmail 相关追问。再次调用 `execute()`，`activeToolDefs` 重置为仅包含核心工具。AI 直接调用 `gmail_search`（因历史中显示其曾成功）——失败，报"该 Agent 不支持此工具"。

AI 有时会通过重新加载工具组来恢复，但这会浪费一次 API 往返。更糟糕的是，有时 AI 会放弃，或者连续触发数十次失败的工具调用（已观察到：单轮中连续出现 61 次 `gmail_trash` ERROR 调用）。

### 解决方案

在每次 `SendMessageUseCase.execute()` 开始时，扫描会话消息历史，查找 `tool_name = "load_tool_group"` 且 `tool_status = "SUCCESS"` 的 `TOOL_CALL` 消息。从这些调用中提取工具组名称，并将其预加载到 `activeToolDefs` 中。

这是一种基于历史记录的最小化方案：
- 无需新增数据库列或持久化层
- 无需新增 API 接口——直接使用现有消息历史
- 前几轮已加载的工具组会自动恢复
- 新会话从空白状态开始（无历史记录 = 不预加载工具组）

### 验收标准

1. 在某条消息中加载工具组后，该工具在同一会话的后续消息中保持可用。
2. 对于本次会话中已加载的工具，不再出现"该 Agent 不支持此工具"的错误。
3. 新会话（包括 `/clear` 之后）仅以核心工具启动，不预加载任何工具组。
4. AI 无需对本次会话中已加载过的工具组再次调用 `load_tool_group`。
5. 所有现有单元测试继续通过。

### 超出范围
- 跨会话的工具组持久化（工具组不在不同会话之间延续）
- 基于不活跃状态的工具组自动卸载
- `unload_tool_group` 命令（如有需要可在后续添加）
- 用于查看当前已加载工具组的 UI
