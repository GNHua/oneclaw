# RFC-041: Messaging Bridge Improvements

## Document Information
- **RFC ID**: RFC-041
- **Related PRD**: [FEAT-041 (Bridge Improvements)](../../prd/features/FEAT-041-bridge-improvements.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Completed
- **Author**: TBD

## Overview

### Background

After the initial Messaging Bridge implementation (RFC-024), user testing revealed three categories of issues: (1) Telegram message formatting with excessive blank lines, (2) typing indicator appearing after agent processing instead of before, and (3) bridge messages going to a dedicated bridge-only session instead of the app's active session. This RFC documents the technical changes to resolve these issues plus additional reliability improvements.

### Goals

1. Rewrite TelegramHtmlRenderer from regex-based to AST visitor pattern for correct formatting
2. Fix typing indicator timing to show before agent execution
3. Route bridge messages to the app's most recently used session
4. Add plain text fallback for HTML rendering failures
5. Consolidate all bridge improvements into documentation

### Non-Goals

- New channel implementations
- Rich media responses from agent to platforms
- Database schema migrations

## Technical Design

### Changed Files Overview

```
bridge/src/main/kotlin/com/oneclaw/shadow/bridge/
├── BridgeConversationManager.kt                    # MODIFIED (suspend fun)
├── channel/
│   ├── ConversationMapper.kt                       # MODIFIED (remove preferences)
│   ├── MessagingChannel.kt                         # MODIFIED (typing order)
│   └── telegram/
│       ├── TelegramApi.kt                          # MODIFIED (nullable parseMode)
│       ├── TelegramChannel.kt                      # MODIFIED (object + fallback)
│       └── TelegramHtmlRenderer.kt                 # REWRITTEN (AST visitor)
├── service/
│   └── MessagingBridgeService.kt                   # MODIFIED (mapper construction)
app/src/main/kotlin/com/oneclaw/shadow/
├── core/repository/
│   └── SessionRepository.kt                        # MODIFIED (new method)
├── data/
│   ├── local/dao/
│   │   └── SessionDao.kt                           # MODIFIED (new query)
│   └── repository/
│       └── SessionRepositoryImpl.kt                # MODIFIED (new method)
└── feature/bridge/
    └── BridgeConversationManagerImpl.kt            # MODIFIED (active session)
bridge/src/test/kotlin/com/oneclaw/shadow/bridge/
├── channel/
│   ├── ConversationMapperTest.kt                   # REWRITTEN
│   ├── MessagingChannelTest.kt                     # MODIFIED
│   └── telegram/
│       └── TelegramHtmlRendererTest.kt             # REWRITTEN
```

## Detailed Design

### Fix 1: TelegramHtmlRenderer Rewrite

**Problem**: The original renderer used a two-step process: markdown -> HTML (via commonmark HtmlRenderer) -> Telegram HTML (via regex replacement). The regex approach blindly appended `\n\n` after every `<p>` and `<h>` tag, causing excessive blank lines.

**Solution**: Replace with direct AST visitor pattern. Parse markdown into commonmark AST, then walk the tree with a custom `AbstractVisitor` subclass that emits Telegram-compatible HTML directly.

Key design decisions:
- Changed from `class` to `object` (stateless singleton, thread-safe)
- `TelegramHtmlVisitor` extends `AbstractVisitor` with overrides for all relevant node types
- `appendBlockSeparator(node)`: Only adds `\n` when `node.next != null`; adds `\n\n` only for top-level blocks (parent is `Document` or `BlockQuote`)
- List items: Unwraps inner `Paragraph` nodes to avoid extra newlines within list items
- Blockquotes: Uses native `<blockquote>` tag (not `<i>` workaround); strips trailing newlines before closing tag
- Ordered lists: Tracks counter, renders `1. `, `2. ` etc.
- Thematic break: Renders as 8x horizontal box drawing character (U+2500)
- HTML escaping via `escapeHtml()` for `&`, `<`, `>`
- `splitForTelegram()` moved to companion object

**Before** (regex approach):
```kotlin
class TelegramHtmlRenderer {
    fun render(markdown: String): String {
        val html = HtmlRenderer.builder().build().render(parser.parse(markdown))
        return convertToTelegramHtml(html)  // regex replacements
    }
}
```

**After** (AST visitor):
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

### Fix 2: Typing Indicator Timing

**Problem**: In `processInboundMessage()`, `agentExecutor.executeMessage()` was called synchronously (blocking via `.collect()`) BEFORE launching the typing indicator coroutine. The user never saw "typing..." because it started after the agent already finished.

**Solution**: Reorder the operations:

```
Before (broken):                          After (fixed):
  agentExecutor.executeMessage() [BLOCKS]   Launch typing indicator coroutine
  Launch typing [too late!]                 scope.launch { agentExecutor.executeMessage() }
  Await response [immediate]                Await response via messageObserver
  Cancel typing                             Cancel typing
```

The typing coroutine now starts immediately. `agentExecutor.executeMessage()` is wrapped in `scope.launch { }` so it runs concurrently. The `messageObserver.awaitNextAssistantMessage()` call still awaits the actual response with a 300-second timeout.

### Fix 3: Active Session Integration

**Problem**: Bridge messages went to a dedicated bridge-only session stored in `BridgePreferences.getBridgeConversationId()`. This session was invisible in the app's UI and disconnected from the user's workflow.

**Solution**: Use the most recently updated session as the bridge target.

**Interface change** -- `BridgeConversationManager`:
```kotlin
// Before
fun getActiveConversationId(): String?

// After
suspend fun getActiveConversationId(): String?
```

**New DAO query** -- `SessionDao`:
```kotlin
@Query("SELECT id FROM sessions WHERE deleted_at IS NULL ORDER BY updated_at DESC LIMIT 1")
suspend fun getMostRecentSessionId(): String?
```

**New repository method** -- `SessionRepository` + `SessionRepositoryImpl`:
```kotlin
suspend fun getMostRecentSessionId(): String?
```

**Implementation** -- `BridgeConversationManagerImpl`:
```kotlin
override suspend fun getActiveConversationId(): String? {
    return sessionRepository.getMostRecentSessionId()
}
```

**Simplified ConversationMapper** (removed `BridgePreferences` dependency):
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

### Fix 4: Plain Text Fallback

**TelegramChannel.sendResponse()** now wraps HTML rendering in try/catch:
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

**TelegramApi.sendMessage()** updated to accept nullable `parseMode`:
```kotlin
suspend fun sendMessage(chatId: String, text: String, parseMode: String? = "HTML")
```

## Testing

### Unit Tests

- **TelegramHtmlRendererTest**: Rewritten with exact `assertEquals` assertions covering paragraphs, headings, lists (ordered and unordered), blockquotes, code blocks, thematic breaks, links, HTML escaping, mixed content, and message splitting.
- **ConversationMapperTest**: Rewritten to test against `getActiveConversationId()` instead of `preferences.getBridgeConversationId()`. Removed all `BridgePreferences` mock interactions.
- **MessagingChannelTest**: Updated test for agent execution verification.

### Manual Verification

1. Send message via Telegram, verify response has compact formatting
2. Verify typing indicator shows in Telegram while agent processes
3. Verify bridge messages appear in the app's most recently used session
4. Send `/clear` via Telegram, verify new session is created
5. Reboot device, verify bridge auto-starts

## Migration Notes

- No database schema changes required
- `ConversationMapper` constructor signature changed: removed `BridgePreferences` parameter
- `BridgeConversationManager.getActiveConversationId()` changed from `fun` to `suspend fun`
- `TelegramHtmlRenderer` changed from `class` to `object` -- callers no longer instantiate it
