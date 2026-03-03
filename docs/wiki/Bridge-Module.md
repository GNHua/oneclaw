# Messaging Bridge

The Messaging Bridge is a standalone Android library (`:bridge` module) that connects OneClaw to external messaging platforms. It runs as a foreground service, receiving messages from external channels and routing them through the AI agent.

## Supported Channels

| Channel | Protocol | Status |
|---------|----------|--------|
| Telegram | HTTP polling via Bot API | Full support (text + images) |
| Discord | WebSocket gateway | Full support (text + attachments) |
| Slack | Socket Mode | Full support (text) |
| LINE | Webhook (HTTP server) | Full support (text) |
| Matrix | HTTP API | Placeholder |
| WebChat | HTTP server | Placeholder |

## Architecture

```
External Platform
    |
    v
MessagingChannel (per-platform implementation)
    |
    v
processInboundMessage()
    |
    +---> Deduplication (LRU cache, 500 entries)
    +---> Access control (per-channel whitelist)
    +---> ConversationMapper.resolveConversationId()
    +---> Typing indicator loop (every 4 seconds)
    +---> BridgeAgentExecutor.executeMessage()
    |         |
    |         v
    |     SendMessageUseCase (same as in-app chat)
    |         |
    |         v
    |     AI response
    |
    +---> Channel.sendResponse() (platform-specific formatting)
    v
External Platform
```

## Key Components

### MessagingBridgeService

Foreground service that manages all channel lifecycles. Controlled via static intent methods:

- `MessagingBridgeService.start(context)` -- Start all enabled channels
- `MessagingBridgeService.stop(context)` -- Stop all channels and service
- `MessagingBridgeService.restart(context)` -- Reinitialize with fresh config
- `MessagingBridgeService.broadcast(context, text)` -- Broadcast text to all channels

The service creates a foreground notification and optionally acquires a wake lock for Doze mode resilience.

### MessagingChannel (Abstract Base)

All channel implementations extend this base class, which provides:

- **Message pipeline:** Deduplication, whitelist check, conversation mapping, agent execution
- **Typing indicators:** Sends typing status every 4 seconds while processing
- **Broadcasting:** Sends messages to the last known chat ID per channel
- **Timeout:** 5-minute timeout on agent responses

Subclasses implement the transport layer:
- `start()` / `stop()` -- Channel lifecycle
- `sendResponse(externalChatId, message)` -- Platform-specific response delivery
- `sendTypingIndicator(externalChatId)` -- Platform-specific typing status

### BridgeConversationManager

Interface for session management, implemented by `BridgeConversationManagerImpl` in the app module:

- `getActiveConversationId()` -- Returns the current bridge session
- `createNewConversation()` -- Creates a new session for bridge use
- `insertUserMessage(conversationId, content, imagePaths)` -- Persists user messages
- `conversationExists(conversationId)` -- Checks session validity

### BridgeStateTracker

Singleton in-process event bus (RFC-045) using SharedFlow and StateFlow:

- `serviceRunning: StateFlow<Boolean>` -- Whether the service is active
- `channelStates: StateFlow<Map<ChannelType, ChannelState>>` -- Per-channel status
- `newSessionFromBridge: SharedFlow<String>` -- Emitted when bridge creates a new session
- `activeAppSessionId: StateFlow<String?>` -- The currently active app session

This enables the app UI to observe bridge activity and sync session state without database dependencies.

### BridgeImageStorage

Caches images from external platforms to local storage:

- Downloads images with optional auth headers
- Detects format via magic bytes (JPEG, PNG, GIF, WebP)
- Stores in `context.filesDir/bridge_images/`
- Returns absolute file paths for agent consumption

### ConversationMapper

Maps external chat IDs to internal session IDs:

- Returns active session if available
- Creates new sessions as needed
- Prevents rapid duplicate session creation

### BridgeBroadcaster

Singleton for outbound messaging to all active channels:

- Channels register/unregister themselves
- `broadcast(content)` sends to each channel's last chat ID

## Channel Details

### Telegram

- **Protocol:** Long polling via `getUpdates(offset)` with exponential backoff (3s initial, 60s max)
- **Images:** Downloads via `getFile()` API, stored through `BridgeImageStorage`
- **Response rendering:** Markdown converted to Telegram HTML via `TelegramHtmlRenderer` (AST visitor pattern)
- **Message limit:** 4096 characters per message, auto-split for longer content
- **Typing:** `sendChatAction(action="typing")`

### Discord

- **Protocol:** WebSocket gateway via `DiscordGateway` with event callbacks
- **Attachments:** Downloads from event URLs
- **Response:** REST API `POST /channels/{channelId}/messages` with Bot token auth
- **Message limit:** 2000 characters per message
- **Typing:** `POST /channels/{channelId}/typing`

### Slack

- **Protocol:** Socket Mode with envelope ACK callback
- **Filtering:** Ignores bot messages and subtypes (edits, deletes)
- **Response:** `POST chat.postMessage` with mrkdwn formatting
- **Smart splitting:** MessageSplitter for long content

### LINE

- **Protocol:** Webhook via local HTTP server on configurable port (default: 8081)
- **Events:** Processes `message` events with type `text`
- **Response:** `LineApi.pushMessage(userId, text)`
- **Message limit:** 5000 characters per message

## Access Control

Each channel has an independent whitelist of allowed user IDs, stored in `BridgePreferences`. Messages from non-whitelisted users are silently ignored.

## Session Sync (RFC-045)

1. Bridge creates a new session via `BridgeConversationManager`
2. `BridgeStateTracker.emitNewSessionFromBridge(sessionId)` notifies the app
3. App observes the SharedFlow and can switch to the bridge session
4. `BridgeStateTracker.setActiveAppSession(sessionId)` keeps both sides in sync

## Configuration

Bridge settings are stored in `SharedPreferences("oneclaw_messaging_bridge")`:

- Master enable/disable toggle
- Per-channel enable flags
- Per-channel user whitelists
- WebChat port (default: 8080)
- LINE webhook port (default: 8081)
- Matrix homeserver URL
- Wake lock toggle

Configure through the Bridge Settings screen in the app.
