# Bridge Channel Parity

## Feature Information
- **Feature ID**: FEAT-048
- **Created**: 2026-03-02
- **Last Updated**: 2026-03-02
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-048 (Bridge Channel Parity)](../../rfc/features/RFC-048-bridge-channel-parity.md)
- **Related Feature**: [FEAT-041 (Bridge Improvements)](FEAT-041-bridge-improvements.md)

## User Story

**As** a user who interacts with the AI agent through non-Telegram bridge channels (Discord, Slack, Matrix, LINE),
**I want** these channels to deliver the same quality of experience as Telegram -- proper message splitting, typing indicators, and rich-text rendering --
**so that** long AI responses are never silently lost and conversations feel responsive regardless of the channel I use.

### Typical Scenarios

1. User sends a question via Discord that triggers a 3000-character AI response. The response currently fails silently because Discord enforces a 2000-character limit. After this feature, the response is split into two messages that arrive sequentially.
2. User waits for an AI response in Discord. Currently there is no visual feedback that the agent is processing. After this feature, Discord shows "Bot is typing..." during agent execution.
3. User receives a code-heavy AI response in Matrix. Currently the response arrives as plain text without formatting. After this feature, code blocks, bold, and italic render correctly using Matrix's HTML body support.
4. User receives a structured AI response in Slack. Currently the response arrives as raw Markdown that Slack does not render. After this feature, Markdown is converted to Slack's mrkdwn format and renders correctly.

## Feature Description

### Background

The Telegram bridge has accumulated several key capabilities through multiple development iterations:

- **Message splitting**: `TelegramHtmlRenderer.splitForTelegram()` splits long messages at paragraph, sentence, or word boundaries before sending, respecting Telegram's 4096-character limit.
- **Typing indicator**: `TelegramChannel.sendTypingIndicator()` sends a "typing" action via the Telegram Bot API so users see "Bot is typing..." while the agent processes.
- **Rich-text rendering**: `TelegramHtmlRenderer.render()` converts Markdown to Telegram's HTML subset using a CommonMark AST visitor.

Other channels lack all three capabilities. The most urgent gap is Discord's 2000-character limit, which causes long responses to fail silently (the Discord API returns an error, but `DiscordChannel.sendResponse()` catches and logs it without retrying or splitting).

### Overview

FEAT-048 brings feature parity to non-Telegram channels in four independent phases:

1. **Message splitting** (Discord, LINE): Extract the splitting algorithm from `TelegramHtmlRenderer` into a shared `MessageSplitter` utility and call it in `DiscordChannel.sendResponse()` (limit 2000) and `LineChannel.sendResponse()` (limit 5000).
2. **Discord typing indicator**: Override `sendTypingIndicator()` in `DiscordChannel` to call `POST /channels/{id}/typing`.
3. **Matrix HTML rendering**: Reuse `TelegramHtmlRenderer.render()` to produce HTML, and update `MatrixApi.sendMessage()` to include the `formatted_body` field alongside the plain-text `body`.
4. **Slack mrkdwn rendering**: Create a new `SlackMrkdwnRenderer` that converts Markdown to Slack's mrkdwn format using the same CommonMark AST visitor pattern.

### Acceptance Criteria

#### Phase 1: Message Splitting

1. A shared `MessageSplitter` class exists in the bridge module's common package with a `split(text, maxLength)` method.
2. `MessageSplitter.split()` uses the same algorithm as the current `TelegramHtmlRenderer.splitForTelegram()`: try paragraph boundary, then sentence boundary, then word boundary, then hard split.
3. `DiscordChannel.sendResponse()` splits messages at 2000 characters before sending. Each part is sent as a separate Discord API call.
4. `LineChannel.sendResponse()` splits messages at 5000 characters before sending. Each part is sent as a separate LINE push message.
5. `TelegramHtmlRenderer.splitForTelegram()` delegates to `MessageSplitter.split()` internally (no behavior change for Telegram).
6. AI responses longer than the channel's limit are received by the user as multiple sequential messages with no content loss.

#### Phase 2: Discord Typing Indicator

7. `DiscordChannel` overrides `sendTypingIndicator()` to call the Discord API endpoint `POST /channels/{channel.id}/typing`.
8. While the agent is processing, Discord clients display "Bot is typing..." for the user.

#### Phase 3: Matrix HTML Rendering

9. `MatrixApi.sendMessage()` accepts an optional `htmlBody` parameter.
10. When `htmlBody` is provided, the Matrix message is sent with `format: "org.matrix.custom.html"` and `formatted_body` containing the HTML.
11. `MatrixChannel.sendResponse()` renders the AI response using `TelegramHtmlRenderer.render()` and passes the result as `htmlBody`.
12. Code blocks, bold, italic, links, and lists render correctly in Matrix clients.

#### Phase 4: Slack mrkdwn Rendering

13. A `SlackMrkdwnRenderer` class exists in the bridge module's Slack package with a `render(markdown)` method.
14. The renderer converts Markdown to Slack's mrkdwn format: `*bold*`, `_italic_`, `` `code` ``, ` ```preformatted``` `, `~strikethrough~`, `>blockquote`.
15. `SlackChannel.sendResponse()` renders the AI response using `SlackMrkdwnRenderer.render()` before sending.
16. Formatted responses render correctly in Slack clients.

### Feature Boundary (Out of Scope)

- **Image support expansion**: Extending image handling to channels beyond Telegram.
- **LINE/Slack typing indicators**: LINE does not support typing indicators; Slack typing indicators have a limited API that is not practical for long agent processing.
- **WebChat formatting**: WebChat uses a WebSocket JSON protocol; the client is responsible for rendering and does not need server-side format conversion.
- **Outbound image sending**: Sending images from the agent to bridge channels (only text responses are in scope).
- **Slack message splitting**: Slack's practical limit (~4000 chars) rarely triggers with typical AI responses; splitting can be added later if needed.
- **Matrix message splitting**: Matrix has no enforced message size limit.
