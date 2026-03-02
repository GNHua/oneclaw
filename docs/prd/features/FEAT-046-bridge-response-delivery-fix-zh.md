# Bridge 响应投递修复

## 功能标识
- **功能 ID**: FEAT-046
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **优先级**: P0（必须实现）
- **负责人**: 待定
- **关联 RFC**: [RFC-046（Bridge 响应投递修复）](../../rfc/features/RFC-046-bridge-response-delivery-fix.md)
- **关联功能**: [FEAT-041（Bridge 改进）](FEAT-041-bridge-improvements.md)、[FEAT-024（消息桥接）](FEAT-024-messaging-bridge.md)

## 用户故事

**作为**一名通过 Telegram 与 AI Agent 对话的用户，
**我希望** Agent 的最终响应能够可靠地送达，
**以便**我能收到实际的回答（例如文件列表），而不是只看到 Agent 的规划消息（例如"我将加载工具……"）。

### 典型场景

1. 用户通过 Telegram 发送"列出我的 Google Drive 根目录"。Agent 回复"我将加载 Google Drive 工具组，然后列出您的根目录"，随后加载工具、列出文件，并生成包含目录内容的最终响应。**当前行为**：Telegram 只收到第一条规划消息，最终的文件列表从未送达。**期望行为**：Telegram 收到最终的文件列表。
2. 用户要求 Agent 执行任何需要工具调用的任务（网络搜索、文件操作、MCP 工具）。Agent 经历多轮工具执行。**当前行为**：Telegram 可能收到中间消息，而非最终答案。**期望行为**：所有工具轮次完成后，Telegram 收到最终答案。
3. 用户提出一个无需工具调用的简单问题。**当前行为**：运行正常。**期望行为**：继续正常运行（无回归）。

## 功能描述

### 功能概述

当 AI Agent 处理涉及工具调用（例如加载工具组、调用 MCP 工具、网络搜索）的请求时，会经历多个轮次。每个轮次都会产生一条 `AI_RESPONSE` 消息并保存到数据库。消息桥接（Messaging Bridge）本应将最终响应投递给 Telegram，但目前它依赖一种间接机制——在 Agent 完成后轮询数据库——该机制无法可靠地返回正确的（最终）响应。

### 根因分析

| 缺陷 | 位置 | 影响 |
|------|------|------|
| Agent 执行结果被丢弃 | `BridgeAgentExecutorImpl.kt` | `.collect()` 丢弃了所有 `ChatEvent`，包括含有最终响应的 `ResponseComplete` |
| 间接响应获取 | `MessagingChannel.kt` | 通过 `BridgeMessageObserver` 轮询数据库，可能返回错误的 `AI_RESPONSE` |
| 不必要的间接层 | 架构层面 | Agent 结果被丢弃后，再通过轮询从数据库中重新获取 |
| 执行后异常导致管道崩溃 | `BridgeAgentExecutorImpl.kt` | `generateAiTitle()` 在 try/catch 外调用；其失败异常向上传播，阻断响应投递 |

### 问题详述

当前消息投递流程存在架构缺陷：

1. `BridgeAgentExecutorImpl.executeMessage()` 调用 `sendMessageUseCase.execute().collect()`。`.collect()` 消费了所有 `ChatEvent` 发射，但**将其全部丢弃**——包括含有最终 AI 响应的 `ChatEvent.ResponseComplete`。

2. Agent 完成后，`MessagingChannel.processInboundMessage()` 调用 `BridgeMessageObserverImpl.awaitNextAssistantMessage()`，该方法通过 `maxByOrNull { it.createdAt }` 轮询数据库以查找最新的 `AI_RESPONSE`。

3. 这种间接数据库轮询方式十分脆弱。如果时间戳排序、数据库读取可见性，或工具调用轮次中的异常处理出现任何问题，观察者可能返回错误的消息（例如第一条中间规划消息，而非最终响应）。

### 修复方案

1. **直接响应传播**：用直接响应传播替代间接数据库轮询方式，让 `BridgeAgentExecutorImpl` 从 Flow 中捕获最终的 `ChatEvent.ResponseComplete`，并将其直接返回给 `MessagingChannel`。数据库观察者作为兜底回退方案保留。
2. **异常安全**：将执行后代码（`generateAiTitle()`）包裹在 try/catch 中，使非关键失败（如 AI 标题生成 API 错误）无法中断响应投递管道。
3. **诊断日志**：在 `MessagingChannel` 和 `BridgeAgentExecutorImpl` 的关键节点添加结构化日志，便于后续调试。

## 验收标准

- [ ] 当 Agent 使用工具调用（多轮）时，Telegram 收到最终响应（而非中间规划消息）
- [ ] 当 Agent 无需工具调用（单轮）时，Telegram 收到响应（无回归）
- [ ] 当 Agent 执行失败时，Telegram 收到有意义的错误消息
- [ ] 当 Agent 超时时，Telegram 收到现有的超时消息
- [ ] `./gradlew test` 通过（所有现有测试及新增测试）
- [ ] `./gradlew compileDebugKotlin` 通过

## 功能边界

### 包含
- 将 `BridgeAgentExecutor.executeMessage()` 的返回类型从 `Unit` 改为 `BridgeMessage?`
- 更新 `BridgeAgentExecutorImpl`，从 `ChatEvent` Flow 中捕获最终响应
- 将 `generateAiTitle()` 包裹在 try/catch 中，防止执行后异常导致投递管道崩溃
- 简化 `MessagingChannel.processInboundMessage()`，直接使用返回的响应
- 当直接响应为 null 时，保留 `BridgeMessageObserver` 作为回退方案
- 在 `MessagingChannel` 和 `BridgeAgentExecutorImpl` 中添加诊断日志
- 更新现有测试

### 不包含
- 向 Telegram 发送中间消息（规划消息对用户无实际价值）
- 对 `SendMessageUseCase` 或流式处理/工具调用循环的修改
- 对 `TelegramHtmlRenderer` 或消息格式的修改
- 新的 Channel 实现
- 数据库 Schema 变更

## 依赖关系

### 依赖于
- **FEAT-041（Bridge 改进）**：当前包含打字指示符、会话路由的 Bridge 架构
- **FEAT-024（消息桥接）**：基础 Bridge 实现

### 被以下功能依赖
- 无

## 变更文件

| 文件 | 变更类型 |
|------|----------|
| `bridge/src/main/kotlin/com/oneclaw/shadow/bridge/BridgeAgentExecutor.kt` | 修改 |
| `app/src/main/kotlin/com/oneclaw/shadow/feature/bridge/BridgeAgentExecutorImpl.kt` | 修改 |
| `bridge/src/main/kotlin/com/oneclaw/shadow/bridge/channel/MessagingChannel.kt` | 修改 |
| `bridge/src/test/kotlin/com/oneclaw/shadow/bridge/channel/MessagingChannelTest.kt` | 修改 |

## 错误处理

- 若 `agentExecutor.executeMessage()` 抛出异常，`processInboundMessage()` 将捕获该异常并回退到数据库观察者。
- 若直接响应和数据库观察者均失败，则向 Telegram 发送面向用户的错误消息。
- `BridgeAgentExecutorImpl` 将整个 Flow 收集过程包裹在 try/catch 中，确保 `SendMessageUseCase` 中的异常不会向上未处理地传播。
- 执行后操作（`generateAiTitle()`）包裹在独立的 try/catch 块中，记录为非致命警告，不会阻断响应投递。

## 测试要点

- Agent 使用工具调用（多轮）：最终响应被正确返回并投递
- Agent 无工具调用（单轮）：响应被正确返回并投递
- Agent 执行失败：返回 null，回退到数据库观察者
- Agent 执行超时：超时消息被正确投递
- 现有的去重、白名单及 /clear 测试继续通过

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|----------|--------|
| 2026-03-01 | 1.0 | 初始草稿 | - |
| 2026-03-01 | 1.1 | 新增第 4 个根因（generateAiTitle 异常）、诊断日志、更新修复方案 | - |
