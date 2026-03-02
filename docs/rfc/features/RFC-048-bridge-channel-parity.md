# RFC-048: Bridge Channel Parity

## Document Information
- **RFC ID**: RFC-048
- **Related PRD**: [FEAT-048 (Bridge Channel Parity)](../../prd/features/FEAT-048-bridge-channel-parity.md)
- **Created**: 2026-03-02
- **Last Updated**: 2026-03-02
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

The Telegram bridge has three capabilities that other channels lack:

1. **Message splitting** -- `TelegramHtmlRenderer.splitForTelegram()` breaks long messages at natural boundaries (paragraph, sentence, word) to stay within Telegram's 4096-character limit.
2. **Typing indicator** -- `TelegramChannel.sendTypingIndicator()` sends a "typing" chat action during agent execution.
3. **Rich-text rendering** -- `TelegramHtmlRenderer.render()` converts Markdown to Telegram's HTML subset via a CommonMark AST visitor.

The most urgent gap is Discord: its 2000-character limit causes `DiscordChannel.sendResponse()` to fail silently for long AI responses. LINE has a similar (but less frequently hit) 5000-character limit. Matrix and Slack both receive raw Markdown that their clients do not render.

This RFC describes four independent phases that bring feature parity to non-Telegram channels.

### Goals

1. Extract message splitting into a shared utility so any channel can use it.
2. Add message splitting to Discord (2000 chars) and LINE (5000 chars).
3. Add a typing indicator to Discord.
4. Add HTML rendering to Matrix by reusing `TelegramHtmlRenderer.render()`.
5. Add mrkdwn rendering to Slack with a new `SlackMrkdwnRenderer`.

### Non-Goals

- Modifying `TelegramChannel` or `TelegramHtmlRenderer` behavior (only extracting the split algorithm).
- Modifying the `MessagingChannel` base class.
- Adding typing indicators to LINE or Slack.
- Adding message splitting to Slack or Matrix (limits are rarely hit or nonexistent).
- Rich-text rendering for Discord (Discord natively supports Markdown).
- Image support for any channel beyond current Telegram support.

## Technical Design

### Changed Files Overview

```
bridge/src/main/kotlin/com/oneclaw/shadow/bridge/
├── util/
│   └── MessageSplitter.kt                              # NEW -- Phase 1
├── channel/
│   ├── discord/
│   │   └── DiscordChannel.kt                           # MODIFIED -- Phase 1 + 2
│   ├── line/
│   │   └── LineChannel.kt                              # MODIFIED -- Phase 1
│   ├── matrix/
│   │   ├── MatrixApi.kt                                # MODIFIED -- Phase 3
│   │   └── MatrixChannel.kt                            # MODIFIED -- Phase 3
│   ├── slack/
│   │   ├── SlackMrkdwnRenderer.kt                      # NEW -- Phase 4
│   │   └── SlackChannel.kt                             # MODIFIED -- Phase 4
│   └── telegram/
│       └── TelegramHtmlRenderer.kt                     # MODIFIED -- Phase 1 (delegate only)
└── (no other files changed)
```

## Detailed Design

### Phase 1: Extract `MessageSplitter` and Add Splitting to Discord + LINE

#### Change 1.1: New `MessageSplitter` Utility

Create `bridge/src/main/kotlin/com/oneclaw/shadow/bridge/util/MessageSplitter.kt`.

The splitting algorithm is extracted verbatim from `TelegramHtmlRenderer.splitForTelegram()`. The class has no dependencies beyond the Kotlin standard library.

```kotlin
package com.oneclaw.shadow.bridge.util

/**
 * Splits long text messages at natural boundaries to fit within
 * a channel's character limit. Used by all channels that enforce
 * a maximum message length.
 *
 * Splitting strategy (greedy, in priority order):
 * 1. Paragraph boundary (\n\n) if found after 50% of maxLength
 * 2. Sentence boundary (". ") if found after 50% of maxLength
 * 3. Word boundary (" ") if found after 50% of maxLength
 * 4. Hard split at maxLength
 */
object MessageSplitter {

    fun split(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) return listOf(text)

        val parts = mutableListOf<String>()
        var remaining = text

        while (remaining.length > maxLength) {
            var splitAt = maxLength

            // Try to split at paragraph boundary
            val paragraphEnd = remaining.lastIndexOf("\n\n", maxLength)
            if (paragraphEnd > maxLength / 2) {
                splitAt = paragraphEnd + 2
            } else {
                // Try sentence boundary
                val sentenceEnd = remaining.lastIndexOf(". ", maxLength)
                if (sentenceEnd > maxLength / 2) {
                    splitAt = sentenceEnd + 1
                } else {
                    // Try word boundary
                    val wordEnd = remaining.lastIndexOf(' ', maxLength)
                    if (wordEnd > maxLength / 2) {
                        splitAt = wordEnd
                    }
                }
            }

            parts.add(remaining.substring(0, splitAt).trim())
            remaining = remaining.substring(splitAt).trim()
        }

        if (remaining.isNotEmpty()) parts.add(remaining)
        return parts
    }
}
```

#### Change 1.2: `TelegramHtmlRenderer.splitForTelegram()` Delegates to `MessageSplitter`

`splitForTelegram()` becomes a thin wrapper. No behavior change for Telegram.

```kotlin
// TelegramHtmlRenderer.kt -- updated splitForTelegram()

fun splitForTelegram(text: String, maxLength: Int = TELEGRAM_MAX_MESSAGE_LENGTH): List<String> {
    return MessageSplitter.split(text, maxLength)
}
```

#### Change 1.3: `DiscordChannel.sendResponse()` -- Split at 2000 Characters

```kotlin
// DiscordChannel.kt

import com.oneclaw.shadow.bridge.util.MessageSplitter

companion object {
    private const val TAG = "DiscordChannel"
    private const val DISCORD_MAX_MESSAGE_LENGTH = 2000
    private const val INITIAL_BACKOFF_MS = 3_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
}

override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
    val parts = MessageSplitter.split(message.content, DISCORD_MAX_MESSAGE_LENGTH)
    for (part in parts) {
        try {
            val body = buildJsonObject { put("content", part) }
            val request = Request.Builder()
                .url("https://discord.com/api/v10/channels/$externalChatId/messages")
                .addHeader("Authorization", "Bot $botToken")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            okHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "sendResponse error: ${e.message}")
        }
    }
}
```

Design decisions:
- Discord natively renders Markdown, so no format conversion is needed -- only splitting.
- Each part is sent in a separate API call. Discord rate limiting (5 messages per 5 seconds per channel) is unlikely to be hit for typical 2-3 part splits.

#### Change 1.4: `LineChannel.sendResponse()` -- Split at 5000 Characters

```kotlin
// LineChannel.kt

import com.oneclaw.shadow.bridge.util.MessageSplitter

companion object {
    private const val TAG = "LineChannel"
    private const val LINE_MAX_MESSAGE_LENGTH = 5000
}

override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
    val parts = MessageSplitter.split(message.content, LINE_MAX_MESSAGE_LENGTH)
    for (part in parts) {
        api.pushMessage(externalChatId, part)
    }
}
```

---

### Phase 2: Discord Typing Indicator

#### Change 2.1: `DiscordChannel.sendTypingIndicator()`

Override the no-op `sendTypingIndicator()` from `MessagingChannel` to call the Discord API.

```kotlin
// DiscordChannel.kt

override suspend fun sendTypingIndicator(externalChatId: String) {
    try {
        val request = Request.Builder()
            .url("https://discord.com/api/v10/channels/$externalChatId/typing")
            .addHeader("Authorization", "Bot $botToken")
            .post("".toRequestBody(null))
            .build()
        okHttpClient.newCall(request).execute()
    } catch (e: Exception) {
        Log.e(TAG, "sendTypingIndicator error: ${e.message}")
    }
}
```

Design decisions:
- The Discord "typing" endpoint triggers a 10-second typing indicator. The `MessagingChannel` base class already sends typing indicators every `TYPING_INTERVAL_MS` (4 seconds) during agent execution, so the indicator stays active continuously.
- The endpoint requires an empty POST body.
- Failure is silently logged -- typing indicators are cosmetic and should not interrupt message delivery.

---

### Phase 3: Matrix HTML Rendering

#### Change 3.1: `MatrixApi.sendMessage()` -- Add `htmlBody` Parameter

```kotlin
// MatrixApi.kt -- updated sendMessage()

suspend fun sendMessage(roomId: String, text: String, htmlBody: String? = null): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val txnId = UUID.randomUUID().toString()
            val body = buildJsonObject {
                put("msgtype", "m.text")
                put("body", text)
                if (htmlBody != null) {
                    put("format", "org.matrix.custom.html")
                    put("formatted_body", htmlBody)
                }
            }
            val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
            val url = "$homeserverUrl/_matrix/client/v3/rooms/$encodedRoomId/send/m.room.message/$txnId"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .put(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage error: ${e.message}")
            false
        }
    }
```

Design decisions:
- The `htmlBody` parameter is optional with default `null`, so existing callers are unaffected.
- Matrix's `org.matrix.custom.html` format supports the same HTML tags that `TelegramHtmlRenderer` produces (`<b>`, `<i>`, `<code>`, `<pre>`, `<a>`, `<s>`, `<blockquote>`), plus additional tags. No additional conversion is needed.
- The `body` field (plain text) is always included as a fallback for clients that do not support HTML rendering.

#### Change 3.2: `MatrixChannel.sendResponse()` -- Render HTML

```kotlin
// MatrixChannel.kt -- updated sendResponse()

import com.oneclaw.shadow.bridge.channel.telegram.TelegramHtmlRenderer

override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
    val htmlBody = try {
        TelegramHtmlRenderer.render(message.content)
    } catch (e: Exception) {
        Log.w(TAG, "HTML rendering failed, sending plain text", e)
        null
    }
    api.sendMessage(roomId = externalChatId, text = message.content, htmlBody = htmlBody)
}
```

Design decisions:
- Reuses `TelegramHtmlRenderer.render()` directly. Telegram's HTML subset is a strict subset of what Matrix supports, so no additional tags or escaping are needed.
- Falls back to plain text if rendering fails (same pattern as `TelegramChannel.sendResponse()`).
- The raw Markdown is always passed as the `text` (body) parameter, providing a readable fallback.

---

### Phase 4: Slack mrkdwn Rendering

#### Change 4.1: New `SlackMrkdwnRenderer`

Create `bridge/src/main/kotlin/com/oneclaw/shadow/bridge/channel/slack/SlackMrkdwnRenderer.kt`.

Uses the same CommonMark AST visitor pattern as `TelegramHtmlRenderer`, but produces Slack's mrkdwn format instead of HTML.

Slack mrkdwn reference:
- Bold: `*text*`
- Italic: `_text_`
- Strikethrough: `~text~`
- Inline code: `` `text` ``
- Code block: ` ```text``` `
- Blockquote: `> text` (per line)
- Links: `<url|text>`
- No heading syntax (bold is used instead)
- Lists: Plain-text bullets and numbers (same as Telegram's approach)

```kotlin
package com.oneclaw.shadow.bridge.channel.slack

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

/**
 * Converts standard Markdown to Slack mrkdwn format using CommonMark AST.
 */
object SlackMrkdwnRenderer {

    private val parser: Parser = Parser.builder()
        .extensions(listOf(StrikethroughExtension.create()))
        .build()

    fun render(markdown: String): String {
        if (markdown.isBlank()) return ""
        val document = parser.parse(markdown)
        val visitor = SlackMrkdwnVisitor()
        document.accept(visitor)
        return visitor.result().trimEnd()
    }

    private class SlackMrkdwnVisitor : AbstractVisitor() {
        private val sb = StringBuilder()
        private var orderedListCounter = 0
        private var inBlockQuote = false

        fun result(): String = sb.toString()

        // -- Block nodes --

        override fun visit(document: Document) {
            visitChildren(document)
        }

        override fun visit(heading: Heading) {
            sb.append("*")
            visitChildren(heading)
            sb.append("*")
            appendBlockSeparator(heading)
        }

        override fun visit(paragraph: Paragraph) {
            if (inBlockQuote) sb.append("> ")
            visitChildren(paragraph)
            appendBlockSeparator(paragraph)
        }

        override fun visit(blockQuote: BlockQuote) {
            val wasInBlockQuote = inBlockQuote
            inBlockQuote = true
            visitChildren(blockQuote)
            inBlockQuote = wasInBlockQuote
            if (blockQuote.next != null && (blockQuote.parent is Document || blockQuote.parent is BlockQuote)) {
                sb.append("\n")
            }
        }

        override fun visit(bulletList: BulletList) {
            visitChildren(bulletList)
            if (bulletList.parent is Document || bulletList.parent is BlockQuote) {
                appendBlockSeparator(bulletList)
            }
        }

        override fun visit(orderedList: OrderedList) {
            val prevCounter = orderedListCounter
            orderedListCounter = orderedList.markerStartNumber
            visitChildren(orderedList)
            orderedListCounter = prevCounter
            if (orderedList.parent is Document || orderedList.parent is BlockQuote) {
                appendBlockSeparator(orderedList)
            }
        }

        override fun visit(listItem: ListItem) {
            val parent = listItem.parent
            if (parent is OrderedList) {
                sb.append("${orderedListCounter}. ")
                orderedListCounter++
            } else {
                sb.append("\u2022 ")
            }
            var child = listItem.firstChild
            while (child != null) {
                if (child is Paragraph) {
                    visitChildren(child)
                } else {
                    child.accept(this)
                }
                child = child.next
            }
            sb.append("\n")
        }

        override fun visit(fencedCodeBlock: FencedCodeBlock) {
            sb.append("```\n")
            sb.append(fencedCodeBlock.literal?.trimEnd('\n') ?: "")
            sb.append("\n```")
            appendBlockSeparator(fencedCodeBlock)
        }

        override fun visit(indentedCodeBlock: IndentedCodeBlock) {
            sb.append("```\n")
            sb.append(indentedCodeBlock.literal?.trimEnd('\n') ?: "")
            sb.append("\n```")
            appendBlockSeparator(indentedCodeBlock)
        }

        override fun visit(thematicBreak: ThematicBreak) {
            sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
            appendBlockSeparator(thematicBreak)
        }

        override fun visit(hardLineBreak: HardLineBreak) {
            sb.append("\n")
        }

        override fun visit(softLineBreak: SoftLineBreak) {
            sb.append("\n")
        }

        // -- Inline nodes --

        override fun visit(text: Text) {
            sb.append(text.literal)
        }

        override fun visit(strongEmphasis: StrongEmphasis) {
            sb.append("*")
            visitChildren(strongEmphasis)
            sb.append("*")
        }

        override fun visit(emphasis: Emphasis) {
            sb.append("_")
            visitChildren(emphasis)
            sb.append("_")
        }

        override fun visit(code: Code) {
            sb.append("`")
            sb.append(code.literal)
            sb.append("`")
        }

        override fun visit(link: Link) {
            sb.append("<${link.destination ?: ""}|")
            visitChildren(link)
            sb.append(">")
        }

        override fun visit(image: Image) {
            sb.append("<${image.destination ?: ""}|")
            val before = sb.length
            visitChildren(image)
            if (sb.length == before) {
                sb.append("image")
            }
            sb.append(">")
        }

        override fun visit(customNode: CustomNode) {
            if (customNode is Strikethrough) {
                sb.append("~")
                visitChildren(customNode)
                sb.append("~")
            } else {
                visitChildren(customNode)
            }
        }

        // -- Helpers --

        private fun appendBlockSeparator(node: Node) {
            if (node.next != null) {
                sb.append("\n")
                if (node.parent is Document || node.parent is BlockQuote) {
                    sb.append("\n")
                }
            }
        }
    }
}
```

#### Change 4.2: `SlackChannel.sendResponse()` -- Render mrkdwn

```kotlin
// SlackChannel.kt -- updated sendResponse()

override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
    val mrkdwnText = try {
        SlackMrkdwnRenderer.render(message.content)
    } catch (e: Exception) {
        Log.w(TAG, "mrkdwn rendering failed, sending plain text", e)
        message.content
    }
    try {
        val body = buildJsonObject {
            put("channel", externalChatId)
            put("text", mrkdwnText)
        }
        val request = Request.Builder()
            .url("https://slack.com/api/chat.postMessage")
            .addHeader("Authorization", "Bearer $botToken")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        okHttpClient.newCall(request).execute()
    } catch (e: Exception) {
        Log.e(TAG, "sendResponse error: ${e.message}")
    }
}
```

Design decisions:
- Slack's `chat.postMessage` automatically detects mrkdwn in the `text` field. No additional `mrkdwn: true` flag is needed.
- Falls back to the original content if rendering fails.
- No message splitting is added for Slack at this time (4000-char practical limit is rarely hit).

## Testing

### Unit Tests

#### `MessageSplitterTest`

New test class: `bridge/src/test/kotlin/com/oneclaw/shadow/bridge/util/MessageSplitterTest.kt`

```kotlin
class MessageSplitterTest {
    @Test fun `text within limit returns single part`()
    @Test fun `splits at paragraph boundary`()
    @Test fun `splits at sentence boundary when no paragraph found`()
    @Test fun `splits at word boundary when no sentence found`()
    @Test fun `hard splits when no boundary found`()
    @Test fun `multiple splits for very long text`()
    @Test fun `trims whitespace from parts`()
    @Test fun `empty text returns single empty part`()
}
```

#### `SlackMrkdwnRendererTest`

New test class: `bridge/src/test/kotlin/com/oneclaw/shadow/bridge/channel/slack/SlackMrkdwnRendererTest.kt`

```kotlin
class SlackMrkdwnRendererTest {
    @Test fun `renders bold`()
    @Test fun `renders italic`()
    @Test fun `renders strikethrough`()
    @Test fun `renders inline code`()
    @Test fun `renders code block`()
    @Test fun `renders link`()
    @Test fun `renders blockquote`()
    @Test fun `renders heading as bold`()
    @Test fun `renders unordered list`()
    @Test fun `renders ordered list`()
    @Test fun `renders mixed content`()
    @Test fun `blank input returns empty string`()
}
```

#### Existing Test Updates

- `TelegramHtmlRendererTest`: Verify `splitForTelegram()` still passes all existing tests after delegating to `MessageSplitter`.

### Manual Verification

1. **Discord splitting**: Send a question that triggers a 3000+ character AI response via Discord. Verify the response arrives as two sequential messages with no content loss.
2. **Discord typing**: Send a question via Discord. Verify "Bot is typing..." appears in the Discord client while the agent processes.
3. **LINE splitting**: Send a question that triggers a 6000+ character AI response via LINE. Verify the response arrives as two sequential messages.
4. **Matrix HTML**: Send a question via Matrix that triggers a response with code blocks, bold text, and links. Verify they render with proper formatting in the Matrix client (e.g., Element).
5. **Slack mrkdwn**: Send a question via Slack that triggers a response with bold, italic, code blocks, and links. Verify they render correctly in the Slack client.
6. **Regression -- Telegram**: Send a long message via Telegram. Verify splitting and HTML rendering still work correctly.

## Migration Notes

- No database schema changes.
- No changes to `MessagingChannel` base class.
- `TelegramHtmlRenderer.splitForTelegram()` delegates to `MessageSplitter.split()` internally; all callers continue to work without changes.
- `MatrixApi.sendMessage()` adds an optional `htmlBody` parameter with default `null`; existing callers are unaffected.
- New file `MessageSplitter.kt` has no dependencies beyond Kotlin stdlib.
- New file `SlackMrkdwnRenderer.kt` depends on CommonMark parser (already a project dependency via `:bridge` module).
- All four phases are independent at the code level and can be implemented and merged separately.
