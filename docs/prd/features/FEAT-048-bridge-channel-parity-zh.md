# Bridge 频道功能对等

## 功能信息
- **Feature ID**: FEAT-048
- **Created**: 2026-03-02
- **Last Updated**: 2026-03-02
- **Status**: 草稿
- **Priority**: P1（应有）
- **Owner**: TBD
- **Related RFC**: [RFC-048（Bridge 频道功能对等）](../../rfc/features/RFC-048-bridge-channel-parity.md)
- **Related Feature**: [FEAT-041（Bridge 改进）](FEAT-041-bridge-improvements.md)

## 用户故事

**作为**一名通过非 Telegram bridge 频道（Discord、Slack、Matrix、LINE）与 AI 代理交互的用户，
**我希望**这些频道能够提供与 Telegram 相同质量的体验——正确的消息分割、输入状态指示以及富文本渲染——
**以便**长篇 AI 回复不会被静默丢失，无论使用哪个频道，对话都能保持流畅响应。

### 典型场景

1. 用户通过 Discord 发送一个问题，触发了一个 3000 字符的 AI 回复。由于 Discord 强制限制为 2000 字符，该回复当前会静默失败。使用此功能后，回复将被分割为两条消息并依次发出。
2. 用户在 Discord 中等待 AI 回复。当前没有任何视觉反馈表明代理正在处理中。使用此功能后，在代理执行期间，Discord 将显示"Bot is typing..."。
3. 用户在 Matrix 中收到一条包含大量代码的 AI 回复。当前该回复以无格式纯文本到达。使用此功能后，代码块、加粗和斜体将通过 Matrix 的 HTML body 支持正确渲染。
4. 用户在 Slack 中收到一条结构化的 AI 回复。当前该回复以原始 Markdown 到达，Slack 不对其进行渲染。使用此功能后，Markdown 将被转换为 Slack 的 mrkdwn 格式并正确渲染。

## 功能描述

### 背景

Telegram bridge 经过多次开发迭代，已积累了几项关键能力：

- **消息分割**：`TelegramHtmlRenderer.splitForTelegram()` 在发送前按段落、句子或单词边界对长消息进行分割，遵守 Telegram 的 4096 字符限制。
- **输入状态指示**：`TelegramChannel.sendTypingIndicator()` 通过 Telegram Bot API 发送"typing"动作，使代理处理期间用户能看到"Bot is typing..."。
- **富文本渲染**：`TelegramHtmlRenderer.render()` 使用 CommonMark AST visitor 将 Markdown 转换为 Telegram 的 HTML 子集。

其他频道缺乏上述全部三项能力。最紧迫的缺口是 Discord 的 2000 字符限制，该限制会导致长回复静默失败（Discord API 返回错误，但 `DiscordChannel.sendResponse()` 在捕获后仅记录日志，不做重试或分割处理）。

### 概述

FEAT-048 通过四个独立阶段为非 Telegram 频道补齐功能：

1. **消息分割**（Discord、LINE）：将分割算法从 `TelegramHtmlRenderer` 提取到共享的 `MessageSplitter` 工具类中，并在 `DiscordChannel.sendResponse()`（限制 2000 字符）和 `LineChannel.sendResponse()`（限制 5000 字符）中调用该工具类。
2. **Discord 输入状态指示**：在 `DiscordChannel` 中覆写 `sendTypingIndicator()`，调用 `POST /channels/{id}/typing`。
3. **Matrix HTML 渲染**：复用 `TelegramHtmlRenderer.render()` 生成 HTML，并更新 `MatrixApi.sendMessage()` 以在纯文本 `body` 旁附带 `formatted_body` 字段。
4. **Slack mrkdwn 渲染**：创建新的 `SlackMrkdwnRenderer` 类，使用相同的 CommonMark AST visitor 模式将 Markdown 转换为 Slack 的 mrkdwn 格式。

### 验收标准

#### 阶段一：消息分割

1. bridge 模块的公共包中存在共享的 `MessageSplitter` 类，提供 `split(text, maxLength)` 方法。
2. `MessageSplitter.split()` 使用与当前 `TelegramHtmlRenderer.splitForTelegram()` 相同的算法：依次尝试段落边界、句子边界、单词边界，最后执行硬分割。
3. `DiscordChannel.sendResponse()` 在发送前按 2000 字符分割消息，每个部分作为独立的 Discord API 调用发送。
4. `LineChannel.sendResponse()` 在发送前按 5000 字符分割消息，每个部分作为独立的 LINE push 消息发送。
5. `TelegramHtmlRenderer.splitForTelegram()` 在内部委托给 `MessageSplitter.split()`（Telegram 行为不变）。
6. 超过频道限制的 AI 回复以多条连续消息的形式送达用户，内容不丢失。

#### 阶段二：Discord 输入状态指示

7. `DiscordChannel` 覆写 `sendTypingIndicator()`，调用 Discord API 端点 `POST /channels/{channel.id}/typing`。
8. 代理处理期间，Discord 客户端向用户显示"Bot is typing..."。

#### 阶段三：Matrix HTML 渲染

9. `MatrixApi.sendMessage()` 接受可选的 `htmlBody` 参数。
10. 当提供 `htmlBody` 时，Matrix 消息以 `format: "org.matrix.custom.html"` 和包含 HTML 内容的 `formatted_body` 字段发送。
11. `MatrixChannel.sendResponse()` 使用 `TelegramHtmlRenderer.render()` 渲染 AI 回复，并将结果作为 `htmlBody` 传入。
12. 代码块、加粗、斜体、链接和列表在 Matrix 客户端中正确渲染。

#### 阶段四：Slack mrkdwn 渲染

13. bridge 模块的 Slack 包中存在 `SlackMrkdwnRenderer` 类，提供 `render(markdown)` 方法。
14. 该渲染器将 Markdown 转换为 Slack 的 mrkdwn 格式：`*bold*`、`_italic_`、`` `code` ``、` ```preformatted``` `、`~strikethrough~`、`>blockquote`。
15. `SlackChannel.sendResponse()` 在发送前使用 `SlackMrkdwnRenderer.render()` 渲染 AI 回复。
16. 格式化后的回复在 Slack 客户端中正确渲染。

### 功能边界（超出范围）

- **图片支持扩展**：将图片处理能力扩展至 Telegram 以外的频道。
- **LINE/Slack 输入状态指示**：LINE 不支持输入状态指示；Slack 的输入状态 API 能力有限，不适用于长时间的代理处理场景。
- **WebChat 格式化**：WebChat 使用 WebSocket JSON 协议，由客户端负责渲染，无需服务端格式转换。
- **出站图片发送**：将图片从代理发送至 bridge 频道（本期范围仅限文本回复）。
- **Slack 消息分割**：Slack 的实际限制（约 4000 字符）在典型 AI 回复场景下很少触发；如有需要可在后续添加分割能力。
- **Matrix 消息分割**：Matrix 没有强制的消息大小限制。
