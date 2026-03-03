# Bridge-App 会话同步

## 功能信息
- **Feature ID**: FEAT-045
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: 已完成
- **Priority**: P1（应有）
- **Owner**: TBD
- **Related RFC**: [RFC-045（Bridge 会话同步）](../../rfc/features/RFC-045-bridge-session-sync.md)
- **Related Feature**: [FEAT-041（Bridge 改进）](FEAT-041-bridge-improvements.md)

## 用户故事

**作为**一名同时通过 Telegram Bridge 和 OneClaw 应用控制 AI 代理的用户，
**我希望** ChatScreen 能够自动跟随 Bridge 当前使用的会话，
**以便**始终保持一个共享的活跃会话，不会丢失正在进行的对话。

### 典型场景

1. 用户通过 Telegram 发送 `/clear`。Bridge 创建一个新的空会话。3 秒内，应用的 ChatScreen 切换到该新会话，无需用户进行任何手动操作。
2. 普通 Bridge 消息到达。ChatScreen 已经在显示 Bridge 的当前会话（由 FEAT-041 建立）。消息在 Telegram 和应用中实时显示。
3. 用户在应用内手动切换到其他会话。后续 Bridge 消息路由到该会话（而非数据库中最近更新的会话）；ChatScreen 保持在该会话上。
4. 用户在应用中点击"新建对话"。新的空会话立即在数据库中创建并显示。后续 Bridge 消息路由到该新会话。
5. Bridge 收到新会话（`/clear` 后）的第一条消息。会话标题立即从"Bridge Conversation"更新为截断后的用户消息标题，之后再更新为 AI 生成的标题。

## 功能描述

### 背景

FEAT-041 确立了 Bridge 消息发送至应用最近更新的会话（`getMostRecentSessionId()`）的机制。这意味着只要用户不执行 `/clear` 命令，Bridge 和应用自然共享同一个活跃会话。

剩余的缺口在于 `/clear` 命令：当 Bridge 创建全新会话时，应用的 ChatScreen 无法感知此事，仍停留在之前的会话上。用户在 Telegram 中看到的是空对话，而应用中显示的却是过时的对话。

### 概述

FEAT-045 通过在 Bridge 层与 UI 层之间增加一条轻量级事件通知路径来弥补这一缺口。当 Bridge 创建新会话（由于 `/clear`）时，它通过共享的进程内事件总线广播新的会话 ID。ChatScreen 订阅此总线，并以新会话 ID 重新初始化自身。

无需新增数据库表、新增网络请求，也无需修改导航逻辑。

### 验收标准

1. 用户通过 Telegram 发送 `/clear` 后，ChatScreen 在 3 秒内切换至新创建的空会话并显示。
2. 抽屉中的会话列表自动更新（已通过现有 Room Flow 实现，无需额外工作）。
3. 若 ChatScreen 当前不可见（应用处于后台），切换将在屏幕下次回到前台时生效。
4. 普通 Bridge 消息（非 `/clear`）不触发 ChatScreen 中的任何会话切换。
5. 用户在应用中手动切换到某个会话后，后续 Bridge 消息路由到该会话（而非数据库中最近更新的会话）。
6. 在应用中点击"新建对话"后，立即创建数据库会话并将其注册为 Bridge 路由的活跃会话。
7. 点击"新建对话"（或发送 `/clear`）时，若当前会话没有消息，则在创建新会话前对其进行软删除。
8. Bridge 会话获得有意义的标题：第一条消息时立即生成截断的用户消息标题，第一次 AI 响应后生成 AI 生成的标题。
9. 所有现有的 Layer 1A 单元测试继续通过。

### 超出范围

- 每个频道对应不同应用会话的多会话映射。
- 应用未运行时执行 `/clear` 的推送通知。
- 支持 Telegram 以外的其他频道（该机制与频道无关，但目前唯一实现 `/clear` 命令的是 Telegram）。
