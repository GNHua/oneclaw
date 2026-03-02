# RFC-048：Bridge 渠道功能对齐

## 文档信息
- **RFC ID**: RFC-048
- **Related PRD**: [FEAT-048（Bridge 渠道功能对齐）](../../prd/features/FEAT-048-bridge-channel-parity.md)
- **Created**: 2026-03-02
- **Last Updated**: 2026-03-02
- **Status**: 草稿
- **Author**: TBD

## 概述

### 背景

Telegram bridge 拥有其他渠道所不具备的三项能力：

1. **消息分割** -- `TelegramHtmlRenderer.splitForTelegram()` 在自然边界（段落、句子、单词）处拆分长消息，以满足 Telegram 的 4096 字符上限。
2. **正在输入指示器** -- `TelegramChannel.sendTypingIndicator()` 在 Agent 执行期间发送"typing" chat action。
3. **富文本渲染** -- `TelegramHtmlRenderer.render()` 通过 CommonMark AST visitor 将 Markdown 转换为 Telegram 的 HTML 子集。

最紧迫的差距在于 Discord：其 2000 字符上限会导致 `DiscordChannel.sendResponse()` 在处理长 AI 回复时静默失败。LINE 存在类似（但触发频率较低）的 5000 字符上限。Matrix 和 Slack 均接收原始 Markdown，而其客户端并不对其进行渲染。

本 RFC 描述四个独立阶段，以使非 Telegram 渠道在功能上与 Telegram 达到对等。

### 目标

1. 将消息分割逻辑提取为共享工具类，使任意渠道均可使用。
2. 为 Discord（2000 字符）和 LINE（5000 字符）添加消息分割功能。
3. 为 Discord 添加正在输入指示器。
4. 通过复用 `TelegramHtmlRenderer.render()` 为 Matrix 添加 HTML 渲染。
5. 通过新增 `SlackMrkdwnRenderer` 为 Slack 添加 mrkdwn 渲染。

### 非目标

- 修改 `TelegramChannel` 或 `TelegramHtmlRenderer` 的行为（仅提取分割算法）。
- 修改 `MessagingChannel` 基类。
- 为 LINE 或 Slack 添加正在输入指示器。
- 为 Slack 或 Matrix 添加消息分割（这些平台的上限极少被触发或不存在上限）。
- 为 Discord 添加富文本渲染（Discord 原生支持 Markdown）。
- 为 Telegram 以外的任何渠道添加图片支持。

## 技术设计

### 变更文件概览

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

## 详细设计

### 第一阶段：提取 `MessageSplitter` 并为 Discord + LINE 添加消息分割

#### 变更 1.1：新建 `MessageSplitter` 工具类

创建 `bridge/src/main/kotlin/com/oneclaw/shadow/bridge/util/MessageSplitter.kt`。

分割算法原样提取自 `TelegramHtmlRenderer.splitForTelegram()`。该类除 Kotlin 标准库外无任何依赖。

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

#### 变更 1.2：`TelegramHtmlRenderer.splitForTelegram()` 委托给 `MessageSplitter`

`splitForTelegram()` 变为一个轻量包装器。Telegram 的行为不变。

```kotlin
// TelegramHtmlRenderer.kt -- updated splitForTelegram()

fun splitForTelegram(text: String, maxLength: Int = TELEGRAM_MAX_MESSAGE_LENGTH): List<String> {
    return MessageSplitter.split(text, maxLength)
}
```

#### 变更 1.3：`DiscordChannel.sendResponse()` -- 在 2000 字符处分割

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

设计决策：
- Discord 原生渲染 Markdown，因此无需格式转换，仅需分割。
- 每个分段通过独立 API 调用发送。Discord 的频率限制（每频道每 5 秒最多 5 条消息）在典型的 2-3 段分割场景下不太可能被触发。

#### 变更 1.4：`LineChannel.sendResponse()` -- 在 5000 字符处分割

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

### 第二阶段：Discord 正在输入指示器

#### 变更 2.1：`DiscordChannel.sendTypingIndicator()`

重写 `MessagingChannel` 中无操作的 `sendTypingIndicator()`，调用 Discord API。

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

设计决策：
- Discord 的"typing"端点触发一个持续 10 秒的正在输入指示器。`MessagingChannel` 基类在 Agent 执行期间已每隔 `TYPING_INTERVAL_MS`（4 秒）发送一次正在输入指示器，因此指示器可持续保持活跃状态。
- 该端点需要一个空的 POST 请求体。
- 失败时仅记录日志——正在输入指示器属于视觉效果，不应中断消息投递。

---

### 第三阶段：Matrix HTML 渲染

#### 变更 3.1：`MatrixApi.sendMessage()` -- 添加 `htmlBody` 参数

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

设计决策：
- `htmlBody` 参数为可选，默认值为 `null`，因此现有调用方不受影响。
- Matrix 的 `org.matrix.custom.html` 格式支持 `TelegramHtmlRenderer` 所输出的全部 HTML 标签（`<b>`、`<i>`、`<code>`、`<pre>`、`<a>`、`<s>`、`<blockquote>`），以及更多额外标签，无需任何额外转换。
- `body` 字段（纯文本）始终包含在内，作为不支持 HTML 渲染的客户端的回退内容。

#### 变更 3.2：`MatrixChannel.sendResponse()` -- 渲染 HTML

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

设计决策：
- 直接复用 `TelegramHtmlRenderer.render()`。Telegram 的 HTML 子集是 Matrix 所支持格式的严格子集，因此无需额外的标签处理或转义。
- 渲染失败时回退到纯文本（与 `TelegramChannel.sendResponse()` 的模式相同）。
- 原始 Markdown 始终作为 `text`（body）参数传入，提供可读的回退内容。

---

### 第四阶段：Slack mrkdwn 渲染

#### 变更 4.1：新建 `SlackMrkdwnRenderer`

创建 `bridge/src/main/kotlin/com/oneclaw/shadow/bridge/channel/slack/SlackMrkdwnRenderer.kt`。

采用与 `TelegramHtmlRenderer` 相同的 CommonMark AST visitor 模式，但输出 Slack 的 mrkdwn 格式而非 HTML。

Slack mrkdwn 参考：
- 粗体：`*text*`
- 斜体：`_text_`
- 删除线：`~text~`
- 行内代码：`` `text` ``
- 代码块：` ```text``` `
- 引用块：`> text`（逐行处理）
- 链接：`<url|text>`
- 无标题语法（以粗体代替）
- 列表：纯文本项目符号和数字（与 Telegram 的处理方式相同）

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

#### 变更 4.2：`SlackChannel.sendResponse()` -- 渲染 mrkdwn

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

设计决策：
- Slack 的 `chat.postMessage` 会自动识别 `text` 字段中的 mrkdwn，无需额外设置 `mrkdwn: true` 标志。
- 渲染失败时回退到原始内容。
- 本阶段暂不为 Slack 添加消息分割（实际 4000 字符上限极少被触发）。

## 测试

### 单元测试

#### `MessageSplitterTest`

新建测试类：`bridge/src/test/kotlin/com/oneclaw/shadow/bridge/util/MessageSplitterTest.kt`

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

新建测试类：`bridge/src/test/kotlin/com/oneclaw/shadow/bridge/channel/slack/SlackMrkdwnRendererTest.kt`

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

#### 现有测试更新

- `TelegramHtmlRendererTest`：验证 `splitForTelegram()` 在委托给 `MessageSplitter` 后仍能通过所有现有测试。

### 手动验证

1. **Discord 消息分割**：通过 Discord 发送一个能触发 3000 字符以上 AI 回复的问题。验证回复以两条连续消息到达且内容无丢失。
2. **Discord 正在输入指示器**：通过 Discord 发送一个问题。验证 Agent 处理期间 Discord 客户端显示"Bot is typing..."。
3. **LINE 消息分割**：通过 LINE 发送一个能触发 6000 字符以上 AI 回复的问题。验证回复以两条连续消息到达。
4. **Matrix HTML 渲染**：通过 Matrix 发送一个能触发包含代码块、粗体文本和链接的回复的问题。验证这些内容在 Matrix 客户端（如 Element）中以正确格式渲染。
5. **Slack mrkdwn 渲染**：通过 Slack 发送一个能触发包含粗体、斜体、代码块和链接的回复的问题。验证这些内容在 Slack 客户端中正确渲染。
6. **回归测试 -- Telegram**：通过 Telegram 发送一条长消息。验证消息分割和 HTML 渲染仍能正常工作。

## 迁移说明

- 无数据库 schema 变更。
- `MessagingChannel` 基类无任何变更。
- `TelegramHtmlRenderer.splitForTelegram()` 在内部委托给 `MessageSplitter.split()`；所有调用方无需修改即可继续使用。
- `MatrixApi.sendMessage()` 新增一个默认值为 `null` 的可选 `htmlBody` 参数；现有调用方不受影响。
- 新文件 `MessageSplitter.kt` 除 Kotlin 标准库外无任何依赖。
- 新文件 `SlackMrkdwnRenderer.kt` 依赖 CommonMark 解析器（已通过 `:bridge` 模块作为项目依赖引入）。
- 四个阶段在代码层面相互独立，可分别实现和合并。
