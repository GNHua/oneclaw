# Messaging Bridge

## Feature Information
- **Feature ID**: FEAT-024
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-024 (Messaging Bridge)](../../rfc/features/RFC-024-messaging-bridge.md)

## User Story

**As** a user of OneClaw,
**I want to** interact with my AI agents through external messaging platforms (Telegram, Discord, Slack, Matrix, LINE, WebChat),
**so that** I can send messages to and receive responses from my agents without opening the app directly, using whichever chat platform I already use daily.

### Typical Scenarios
1. User sets up a Telegram bot token in the Bridge settings, enables the Telegram channel, and starts the bridge service. They can now chat with their AI agent by sending messages to the Telegram bot from any device.
2. User runs a Discord server and adds their OneClaw bot. Team members can interact with the AI agent in a designated Discord channel.
3. User enables the WebChat channel, which starts a local WebSocket server on the phone. A web browser on the same network can connect and chat with the agent via a simple web interface.
4. User enables multiple channels simultaneously (Telegram + Slack). Messages from both platforms are routed to the same agent, and responses from the agent are sent back to the originating platform.
5. User sends an image in Telegram to the bot. The image is downloaded, stored locally, and passed to the agent along with the text message for multimodal processing.
6. User sends `/clear` in any channel to start a fresh conversation with the agent.

## Feature Description

### Overview
The Messaging Bridge is a background service that connects external messaging platforms to the OneClaw AI agent runtime. It receives messages from supported platforms, routes them to the agent for processing, and sends the agent's responses back to the originating platform. This enables users to interact with their AI agents from any device or platform without opening the Android app directly.

The bridge runs as an Android foreground service with a persistent notification, ensuring reliable background operation. Each messaging platform is implemented as an independent channel with its own connection mechanism (polling, WebSocket, or webhook server).

### Detailed Specification

#### Supported Channels

| Channel | Connection Mode | Protocol | Key Features |
|---------|----------------|----------|--------------|
| **Telegram** | Long-polling | HTTP API | Bot API, image download via `getFile()`, HTML message formatting, 4096 char message splitting |
| **Discord** | WebSocket Gateway | WebSocket + REST | Gateway events, heartbeat, message intents, attachment image download |
| **Slack** | Socket Mode | WebSocket + REST | App token + bot token, envelope acknowledgment, event filtering |
| **Matrix** | Long-polling Sync | HTTP API | Homeserver URL config, `/sync` endpoint, room-based messaging |
| **LINE** | Webhook Server | HTTP (NanoHTTPD) | HMAC-SHA256 signature verification, Push API responses, configurable port |
| **WebChat** | WebSocket Server | WebSocket (NanoWSD) | Local network access, optional JWT auth, configurable port |

#### Message Flow

```
Inbound:
  External Platform --> Channel.processInboundMessage()
    --> Deduplication (500-message LRU cache)
    --> Access control (whitelist check)
    --> Conversation resolution (reuse existing or create new)
    --> Insert user message into conversation
    --> Execute agent with message
    --> Await assistant response (with typing indicators)
    --> Format and send response back to platform

Outbound (Broadcast):
  BridgeBroadcaster.broadcast(message)
    --> All registered channels
    --> Each channel sends to its last known chat ID
```

#### Commands
- `/clear` -- Creates a new conversation and confirms to the user. The previous conversation is preserved but the bridge starts routing to a fresh one.

#### Image Support
- Channels that support images (Telegram, Discord) can receive image messages
- Images are downloaded to a local `bridge_images/` directory with UUID filenames
- Image paths are passed to the agent alongside text for multimodal processing
- Automatic format detection (PNG, GIF, WebP, BMP, JPG)

#### Service Management
- Foreground service with persistent notification indicating bridge status
- Optional wake lock to prevent device sleep during bridge operation
- Watchdog worker (WorkManager) that restarts the service if it unexpectedly stops
- Clean start/stop lifecycle with `ACTION_START` / `ACTION_STOP` intents

### User Interaction Flow
```
1. User opens Settings and navigates to the Bridge section
2. User configures credentials for desired channels (e.g., Telegram bot token)
3. User enables specific channels via toggle switches
4. User taps the master "Start Bridge" button
5. A foreground service starts with a persistent notification
6. The Bridge settings screen shows real-time channel status (connected, message count, errors)
7. User sends a message from an external platform (e.g., Telegram)
8. The agent processes the message and responds on the same platform
9. User can stop the bridge from the settings screen or the notification
```

## Acceptance Criteria

Must pass (all required):
- [ ] Bridge runs as an Android foreground service with persistent notification
- [ ] Telegram channel connects via long-polling and receives/responds to messages
- [ ] Discord channel connects via WebSocket gateway and receives/responds to messages
- [ ] Slack channel connects via Socket Mode and receives/responds to messages
- [ ] Matrix channel connects via long-polling sync and receives/responds to messages
- [ ] LINE channel runs a webhook server and receives/responds to messages
- [ ] WebChat channel runs a WebSocket server and receives/responds to messages
- [ ] Messages are deduplicated (same message not processed twice)
- [ ] Access control via per-channel user ID whitelist works correctly
- [ ] `/clear` command creates a new conversation in any channel
- [ ] Image messages from Telegram are downloaded and passed to the agent
- [ ] Image messages from Discord are downloaded and passed to the agent
- [ ] Long messages are split at paragraph/sentence boundaries before sending
- [ ] Channel credentials are stored using EncryptedSharedPreferences
- [ ] Bridge settings screen shows per-channel enable/disable toggles
- [ ] Bridge settings screen shows per-channel credential input fields
- [ ] Bridge settings screen shows real-time channel status (connected, message count, error)
- [ ] Bridge service survives app backgrounding and continues to process messages
- [ ] Typing indicators are sent while waiting for agent response (Telegram, Matrix, WebChat)
- [ ] Bridge service can be started and stopped from the settings screen
- [ ] Watchdog worker restarts the service if it unexpectedly dies
- [ ] The bridge module is an independent Gradle module (`:bridge`)

Optional (nice to have):
- [ ] Broadcast: send a message to all active channels' last known chat IDs
- [ ] Wake lock option to prevent device sleep
- [ ] Bridge status visible in the main app notification
- [ ] Exponential backoff on connection errors (initial 3s, max 60s)

## UI/UX Requirements

### Bridge Settings Screen

#### Layout
- Accessible from the main Settings screen as a dedicated "Messaging Bridge" entry
- Top section: Master toggle switch + service start/stop button
- Global settings section: Wake lock toggle, bridge conversation selector
- Per-channel sections: Expandable cards for each of the 6 channels, each containing:
  - Enable/disable toggle
  - Credential input fields (password-masked)
  - Allowed user IDs field (comma-separated)
  - Channel-specific settings (port numbers, homeserver URL)
  - Status indicator (connected/disconnected/error, connected since, message count)

#### Visual Design
- Material 3 design consistent with existing settings screens
- Gold/amber accent color `#6D5E0F` for active/enabled states
- Status indicators: green dot for connected, red dot for error, grey dot for disabled
- Channel icons for visual identification (Telegram, Discord, Slack, Matrix, LINE, WebChat)

#### Interaction Feedback
- Starting/stopping the bridge shows a loading indicator
- Channel connection status updates in real-time via StateFlow observation
- Error messages displayed inline below the affected channel card
- Credential fields validate on input (non-empty for required fields)
- Toast or snackbar confirmation when bridge starts/stops

## Feature Boundary

### Included
- Independent `:bridge` Gradle module with all bridge logic
- Foreground service with notification and lifecycle management
- All 6 channel implementations (Telegram, Discord, Slack, Matrix, LINE, WebChat)
- Channel abstraction layer (MessagingChannel base class)
- Message deduplication, access control, conversation mapping
- Image download and storage for Telegram and Discord
- Typing indicators for supported channels
- Credential storage via EncryptedSharedPreferences
- Preferences storage for channel config and state
- State tracking via StateFlow for UI observation
- Full Bridge settings screen with per-channel configuration
- Watchdog worker for service reliability
- Broadcast capability to all active channels
- Message splitting for platform character limits
- `/clear` command for conversation reset
- Koin DI module for bridge dependencies
- Bridge-specific interfaces (BridgeAgentExecutor, BridgeMessageObserver, BridgeConversationManager) implemented in the `:app` module

### Not Included
- End-to-end encryption for WebChat (plain WebSocket)
- Rich media responses (only text responses; images are inbound only)
- Voice message support
- File attachment support (beyond images)
- Multi-agent routing (all messages go to a single agent/conversation)
- Remote bridge management (bridge is controlled locally on the device)
- Web-based admin panel for bridge configuration
- Push notification integration for channels (only real-time messaging)
- Rate limiting or throttling on inbound messages

## Business Rules

### Service Rules
1. The bridge service requires at least one channel to be enabled; if no channels are enabled when starting, the service stops immediately
2. The service runs as a foreground service with `START_STICKY` return, ensuring the system restarts it if killed
3. Channel start/stop operations are serialized via Mutex to prevent race conditions
4. The watchdog worker runs periodically to restart the service if it has died unexpectedly

### Channel Rules
1. Each channel runs independently -- one channel failing does not affect others
2. Channels use a SupervisorJob scope so coroutine failures are isolated
3. Connection errors trigger exponential backoff (3s initial, doubling up to 60s max)
4. Each channel maintains its own connection lifecycle (start/stop/reconnect)

### Access Control Rules
1. Per-channel allowed user IDs act as a whitelist
2. If the whitelist is empty, all users are allowed (open access)
3. If the whitelist is non-empty, only messages from listed user IDs are processed
4. Messages from unauthorized users are silently dropped (no error response)

### Message Rules
1. Messages are deduplicated using a 500-entry LRU cache per channel
2. The `/clear` command creates a new conversation; the old conversation is preserved
3. Long messages are split at natural boundaries (paragraph > sentence > word > character)
4. Platform-specific character limits are respected (e.g., Telegram 4096 chars)
5. Empty or whitespace-only messages are ignored

### Credential Rules
1. All API tokens and secrets are stored in EncryptedSharedPreferences
2. Credentials are never logged or exposed in error messages
3. Slack requires two tokens: bot token and app token (Socket Mode)
4. LINE requires two credentials: channel access token and channel secret

### Conversation Rules
1. All bridge messages route to a single bridge conversation
2. The bridge conversation is separate from UI-created conversations
3. `/clear` creates a new bridge conversation and updates the mapping
4. The conversation ID is persisted in BridgePreferences for service restart recovery

## Non-Functional Requirements

### Performance
- Message processing latency (bridge overhead, excluding agent execution): < 500ms
- Channel connection establishment: < 10s for polling/WebSocket channels
- Typing indicator interval: every 4 seconds while waiting for response
- Agent response timeout: 300 seconds (5 minutes)
- Memory overhead per channel: < 5MB

### Security
- API tokens stored in EncryptedSharedPreferences (AES256_SIV + AES256_GCM)
- LINE webhook server validates HMAC-SHA256 signatures on all incoming requests
- WebChat server supports optional token-based authentication
- No credentials logged at any log level
- Fallback to plain SharedPreferences only if encryption hardware is unavailable (documented edge case)

### Compatibility
- Android API 26+ (minSdk)
- Background execution: foreground service + optional wake lock
- Network: works on WiFi and mobile data
- WebChat/LINE webhook servers accessible only on local network (no port forwarding built in)

## Dependencies

### Depends On
- **FEAT-001 (Chat Interaction)**: Agent execution and message system
- **FEAT-004 (Tool System)**: Tool execution during agent processing
- **FEAT-008 (Session Management)**: Conversation/session lifecycle

### Depended On By
- None currently

### External Dependencies
- OkHttp: HTTP client and WebSocket support
- NanoHTTPD: Embedded HTTP/WebSocket server for LINE webhooks and WebChat
- Commonmark: Markdown to HTML rendering for Telegram message formatting
- AndroidX Security Crypto: EncryptedSharedPreferences for credential storage
- AndroidX WorkManager: Watchdog worker scheduling

## Test Points

### Functional Tests
- Telegram: send text message, receive response, send image, `/clear` command
- Discord: send text message, receive response, send image attachment
- Slack: send text message, receive response, envelope acknowledgment
- Matrix: send text message, receive response, room message routing
- LINE: webhook receives message, signature verification, push response
- WebChat: WebSocket connect, authenticate, send message, receive response
- Service: start with enabled channels, stop all channels, restart via watchdog
- Access control: allowed user passes, blocked user silently dropped
- Deduplication: same messageId not processed twice
- Message splitting: message over 4096 chars split correctly for Telegram
- `/clear`: new conversation created, subsequent messages route to new conversation

### Edge Cases
- Start bridge with no channels enabled (should stop immediately)
- Start bridge with invalid credentials (channel reports error, others unaffected)
- Network disconnection and reconnection (exponential backoff, auto-reconnect)
- Multiple rapid messages from same user (concurrent processing, deduplication)
- Very long agent response (message splitting across all platforms)
- Image download failure (error logged, text-only message processed)
- Service killed by system (watchdog restarts, state recovered from preferences)
- App force-stopped (service and watchdog both stopped, clean state on next launch)

### Performance Tests
- Bridge overhead measurement (time from message receipt to agent invocation)
- Memory usage with all 6 channels active
- Sustained operation over 24+ hours (no memory leaks, no connection degradation)

## Data Requirements

### Channel Credentials
| Data Item | Type | Required | Description |
|-----------|------|----------|-------------|
| Telegram Bot Token | String | Yes (if Telegram enabled) | Bot API token from @BotFather |
| Discord Bot Token | String | Yes (if Discord enabled) | Bot token from Discord Developer Portal |
| Slack Bot Token | String | Yes (if Slack enabled) | Bot OAuth token (xoxb-) |
| Slack App Token | String | Yes (if Slack enabled) | App-level token for Socket Mode (xapp-) |
| Matrix Access Token | String | Yes (if Matrix enabled) | Access token for Matrix homeserver |
| Matrix Homeserver URL | String | Yes (if Matrix enabled) | Base URL of the Matrix homeserver |
| LINE Channel Access Token | String | Yes (if LINE enabled) | Channel access token from LINE Developers |
| LINE Channel Secret | String | Yes (if LINE enabled) | Channel secret for webhook signature verification |
| WebChat Access Token | String | No | Optional token for WebChat authentication |

### Configuration Data
| Data Item | Type | Required | Description |
|-----------|------|----------|-------------|
| Bridge Enabled | Boolean | Yes | Master toggle, default false |
| Per-channel Enabled | Boolean | Yes | Per-channel toggle, default false |
| Allowed User IDs | Set<String> | No | Whitelist per channel, empty = allow all |
| WebChat Port | Int | No | WebSocket server port, default 8080 |
| LINE Webhook Port | Int | No | Webhook HTTP server port, default 8081 |
| Wake Lock Enabled | Boolean | No | Prevent device sleep, default false |
| Bridge Conversation ID | String | No | Active bridge conversation |

### Data Storage
- Credentials: EncryptedSharedPreferences (`bridge_credentials`)
- Configuration: SharedPreferences (`messaging_bridge`)
- Downloaded images: Internal storage (`bridge_images/` directory)
- State tracking: In-memory StateFlow (not persisted)

## Error Handling

### Error Scenarios

1. **Invalid credentials**
   - Channel fails to connect and reports error in channel state
   - Error displayed on settings screen below the channel card
   - Other channels are unaffected

2. **Network disconnection**
   - Channel detects disconnect and enters exponential backoff
   - Auto-reconnects when network is available
   - State tracker shows error status during disconnection

3. **Agent execution timeout (> 5 minutes)**
   - Message observer returns timeout error
   - Channel sends error message to the external user
   - Channel remains running for future messages

4. **Image download failure**
   - Error logged, message processed as text-only
   - User not notified of image download failure

5. **Service killed by system**
   - Watchdog worker detects service death
   - Restarts the service with previously enabled channels
   - Conversation state recovered from BridgePreferences

6. **Port conflict (WebChat/LINE)**
   - Channel fails to start, error reported in channel state
   - User needs to change port in settings

7. **LINE webhook signature verification failure**
   - Request rejected with 401 status
   - Logged as security warning

8. **EncryptedSharedPreferences initialization failure**
   - Falls back to plain SharedPreferences
   - Logged as warning (security degradation)

## Open Questions

- [ ] Should the bridge support multiple simultaneous conversations (one per external chat ID) or a single shared conversation?
- [ ] Should the bridge conversation use a specific agent, or use the default General Assistant?
- [ ] Should there be a maximum message rate limit to prevent abuse?

## References

- Reference implementation: `oneclaw-1/lib-messaging-bridge`
- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Discord Gateway API](https://discord.com/developers/docs/topics/gateway)
- [Slack Socket Mode](https://api.slack.com/apis/connections/socket)
- [Matrix Client-Server API](https://spec.matrix.org/latest/client-server-api/)
- [LINE Messaging API](https://developers.line.biz/en/docs/messaging-api/)
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
