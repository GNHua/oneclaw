# RFC-031: 提供商网页搜索

## 文档信息
- **RFC ID**: RFC-031
- **关联 PRD**: [FEAT-031 (提供商网页搜索)](../../prd/features/FEAT-031-provider-web-search.md)
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景

OneClaw 目前具备两个客户端网页工具：`webfetch`（HTTP 抓取 + HTML 转 Markdown）和 `browser`（基于 WebView 的渲染）。这些工具需要 AI 显式调用，且每次获取页面都要消耗一次工具调用往返。与此同时，三家受支持的提供商均提供了内置的服务端网页搜索能力：

- **OpenAI**: Chat Completions API 中的 `web_search_options`（支持搜索的模型）
- **Anthropic**: Messages API 中的 `web_search_20250305` 服务端工具
- **Gemini**: generateContent API 中的 `google_search` 接地（grounding）

这些服务端搜索能力运行在提供商的基础设施上，可访问提供商的搜索索引，并返回结构化引用——无需消耗客户端工具调用往返。

### 目标

1. 在 Agent 模型中添加 `webSearchEnabled` 布尔字段，并持久化至数据库
2. 修改 `ModelApiAdapter.sendMessageStream()`，使其接受网页搜索标志并注入提供商特定的搜索配置
3. 将各提供商特定的引用/接地响应解析为统一的 `Citation` 领域模型
4. 为网页搜索生命周期事件和引用添加新的 `StreamEvent` 类型
5. 将引用以 JSON 格式存储在 `AI_RESPONSE` 消息中
6. 在聊天界面 AI 响应消息下方渲染"来源"区域
7. 处理 Anthropic 的 `server_tool_use` / `web_search_tool_result` 内容块类型，且不触发客户端工具执行

### 非目标

- 域名过滤（允许/屏蔽域名列表）——未来增强
- 用户位置配置以实现本地化搜索——未来增强
- 在响应文本中嵌入脚注式引用标记
- 显示提供商生成的搜索查询
- 在 AI 合成响应前展示搜索结果片段/预览
- 提供商搜索不可用时的客户端搜索回退

## 技术设计

### 架构概览

```
┌───────────────────────────────────────────────────────────────────────┐
│                         UI 层                                         │
│                                                                       │
│  AgentDetailScreen         ChatScreen                                │
│  └── webSearchEnabled      └── CitationSection（新）                 │
│      开关（新）                └── CitationItem（新，可点击）         │
│                                                                       │
├───────────────────────────────────────────────────────────────────────┤
│                         用例层                                        │
│                                                                       │
│  SendMessageUseCase                                                  │
│  └── 将 webSearchEnabled 传递给适配器                                │
│  └── 收集 StreamEvent.Citations → 存储到 Message                    │
│  └── 对 server_tool_use 事件跳过工具执行                            │
│                                                                       │
├───────────────────────────────────────────────────────────────────────┤
│                         适配器层                                      │
│                                                                       │
│  ModelApiAdapter.sendMessageStream()                                 │
│  └── 新增参数：webSearchEnabled: Boolean                             │
│                                                                       │
│  OpenAiAdapter                                                       │
│  └── 向请求体添加 "web_search_options": {}                           │
│  └── 从响应中解析 annotations[].url_citation                        │
│                                                                       │
│  AnthropicAdapter                                                    │
│  └── 向 tools 添加 {type: "web_search_20250305", name: "web_search"}│
│  └── 处理 server_tool_use + web_search_tool_result 内容块           │
│  └── 从文本块中提取引用                                              │
│                                                                       │
│  GeminiAdapter                                                       │
│  └── 向 tools 数组添加 {google_search: {}}                          │
│  └── 从响应候选中解析 groundingMetadata                             │
│                                                                       │
├───────────────────────────────────────────────────────────────────────┤
│                         数据层                                        │
│                                                                       │
│  Agent（领域模型）           → + webSearchEnabled: Boolean           │
│  AgentEntity（Room）         → + web_search_enabled 列              │
│  Message（领域模型）         → + citations: List<Citation>?          │
│  MessageEntity（Room）       → + citations TEXT 列（JSON）           │
│  Citation（新领域模型）                                               │
│                                                                       │
│  迁移 7→8：向 agents 和 messages 表添加列                           │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

### 数据模型

#### Citation（新领域模型）

```kotlin
// core/model/Citation.kt
data class Citation(
    val url: String,
    val title: String,
    val domain: String       // 从 url 中提取，用于展示
)
```

在三家提供商之间统一。提供商特定字段（Anthropic 的 `encrypted_content`、Gemini 的 `confidenceScores`）不持久化——它们仅在 API 往返期间使用。

#### Agent（已修改）

```kotlin
// core/model/Agent.kt
data class Agent(
    val id: String,
    val name: String,
    val description: String?,
    val systemPrompt: String,
    val preferredProviderId: String?,
    val preferredModelId: String?,
    val isBuiltIn: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val webSearchEnabled: Boolean = false  // 新增
)
```

#### AgentEntity（已修改）

```kotlin
// data/local/entity/AgentEntity.kt
@Entity(tableName = "agents")
data class AgentEntity(
    // ... 现有字段 ...
    @ColumnInfo(name = "web_search_enabled", defaultValue = "0")
    val webSearchEnabled: Boolean  // 新增
)
```

#### Message（已修改）

```kotlin
// core/model/Message.kt
data class Message(
    // ... 现有字段 ...
    val citations: List<Citation>? = null  // 新增
)
```

#### MessageEntity（已修改）

```kotlin
// data/local/entity/MessageEntity.kt
@Entity(tableName = "messages", ...)
data class MessageEntity(
    // ... 现有字段 ...
    @ColumnInfo(name = "citations")
    val citations: String? = null  // 新增 - Citation 对象的 JSON 数组
)
```

引用在映射器中使用 kotlinx.serialization 进行 JSON 序列化/反序列化：

```kotlin
// 在 MessageMapper 中
private val json = Json { ignoreUnknownKeys = true }

fun MessageEntity.toDomain(): Message {
    val parsedCitations = citations?.let {
        try { json.decodeFromString<List<Citation>>(it) } catch (_: Exception) { null }
    }
    return Message(
        // ... 现有映射 ...
        citations = parsedCitations
    )
}

fun Message.toEntity(): MessageEntity {
    return MessageEntity(
        // ... 现有映射 ...
        citations = citations?.let { json.encodeToString(it) }
    )
}
```

#### 数据库迁移（7 -> 8）

```kotlin
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // RFC-031: 向 agents 添加 web_search_enabled
        db.execSQL("ALTER TABLE agents ADD COLUMN web_search_enabled INTEGER NOT NULL DEFAULT 0")
        // RFC-031: 向 messages 添加 citations
        db.execSQL("ALTER TABLE messages ADD COLUMN citations TEXT")
    }
}
```

### StreamEvent 变更

向 `StreamEvent` 添加新的事件类型：

```kotlin
sealed class StreamEvent {
    // ... 现有事件 ...

    /** 提供商正在执行服务端网页搜索。 */
    data class WebSearchStart(val query: String?) : StreamEvent()

    /** 从提供商响应中提取的引用。 */
    data class Citations(val citations: List<Citation>) : StreamEvent()
}
```

- `WebSearchStart`：当 Anthropic 的 `server_tool_use` 块开始时发出（携带搜索查询）。对于 OpenAI 和 Gemini，此事件不发出，因为搜索是透明的。
- `Citations`：当引用数据可用时发出。对于 OpenAI，来自最终消息块中的 `annotations`；对于 Anthropic，从文本块的 `citations` 字段中提取；对于 Gemini，从 `groundingMetadata.groundingChunks` 解析。

### 适配器变更

#### ModelApiAdapter 接口（已修改）

```kotlin
interface ModelApiAdapter {
    fun sendMessageStream(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?,
        webSearchEnabled: Boolean = false  // 新增
    ): Flow<StreamEvent>

    // ... 其他方法不变 ...
}
```

#### OpenAI 适配器变更

当 `webSearchEnabled = true` 时，向请求体添加 `web_search_options`：

```kotlin
// 在 buildOpenAiRequest() 中
private fun buildOpenAiRequest(
    modelId: String,
    messages: List<ApiMessage>,
    tools: List<ToolDefinition>?,
    systemPrompt: String?,
    webSearchEnabled: Boolean
): JsonObject = buildJsonObject {
    put("model", modelId)
    put("stream", true)
    // ... 现有字段 ...

    if (webSearchEnabled) {
        putJsonObject("web_search_options") {
            put("search_context_size", "medium")
        }
    }
}
```

从流式响应中解析 annotations：

```kotlin
// 在 processOpenAiStreamLine() 中
// 积累完整消息后，从最终 delta 中解析 annotations
// OpenAI 在 message.annotations[] 中返回 annotations，类型为 "url_citation"
private fun parseAnnotations(messageJson: JsonObject): List<Citation> {
    val annotations = messageJson.jsonArray("annotations") ?: return emptyList()
    return annotations.mapNotNull { ann ->
        val obj = ann.jsonObject
        if (obj.string("type") == "url_citation") {
            val url = obj.string("url") ?: return@mapNotNull null
            Citation(
                url = url,
                title = obj.string("title") ?: url,
                domain = extractDomain(url)
            )
        } else null
    }.distinctBy { it.url }
}
```

引用在最终文本 delta 之后（在 `[DONE]` 信号或找到 annotations 时）作为 `StreamEvent.Citations` 发出。

#### Anthropic 适配器变更

当 `webSearchEnabled = true` 时，将网页搜索工具注入 tools 数组：

```kotlin
// 在 buildAnthropicRequest() 中
private fun buildAnthropicRequest(
    modelId: String,
    messages: List<ApiMessage>,
    tools: List<ToolDefinition>?,
    systemPrompt: String?,
    webSearchEnabled: Boolean
): JsonObject = buildJsonObject {
    // ... 现有字段 ...

    // 构建 tools 数组
    putJsonArray("tools") {
        // 添加客户端工具
        tools?.forEach { tool ->
            addJsonObject { /* 现有工具格式化 */ }
        }
        // 添加服务端网页搜索工具
        if (webSearchEnabled) {
            addJsonObject {
                put("type", "web_search_20250305")
                put("name", "web_search")
                put("max_uses", 5)
            }
        }
    }
}
```

在流式解析中处理新的内容块类型：

```kotlin
// 在 processAnthropicStreamEvent() 中
"content_block_start" -> {
    val contentBlock = data.jsonObject("content_block") ?: return
    val blockType = contentBlock.string("type")
    val blockIndex = data.int("index") ?: return

    when (blockType) {
        "text" -> { /* 现有：跟踪文本块索引 */ }
        "thinking" -> { /* 现有：跟踪 thinking 块 */ }
        "tool_use" -> { /* 现有：发出 ToolCallStart */ }

        // 新增：服务端工具使用（网页搜索）
        "server_tool_use" -> {
            val name = contentBlock.string("name")
            if (name == "web_search") {
                serverToolUseIndices.add(blockIndex)
                // 查询将从 input_json_delta 中提取
            }
        }

        // 新增：网页搜索结果
        "web_search_tool_result" -> {
            // 解析搜索结果，提取引用
            val content = contentBlock.jsonArray("content")
            val citations = content?.mapNotNull { result ->
                val obj = result.jsonObject
                if (obj.string("type") == "web_search_result") {
                    val url = obj.string("url") ?: return@mapNotNull null
                    Citation(
                        url = url,
                        title = obj.string("title") ?: url,
                        domain = extractDomain(url)
                    )
                } else null
            } ?: emptyList()
            if (citations.isNotEmpty()) {
                emit(StreamEvent.Citations(citations))
            }
        }
    }
}

// 另外：当 server_tool_use 块收到 input_json_delta 时，提取查询：
"content_block_delta" -> {
    val index = data.int("index") ?: return
    if (index in serverToolUseIndices) {
        // 从 input_json_delta 中提取搜索查询
        val delta = data.jsonObject("delta")
        val partialJson = delta?.string("partial_json")
        // 积累并在完成时解析查询
        // 块停止时发出 WebSearchStart(query)
    }
}
```

**重要**：`server_tool_use` 块绝不能被当作客户端工具调用处理。它不应被添加到 `pendingToolCalls`，也不应在 `SendMessageUseCase` 中触发工具执行。

此外，从文本块中解析内联引用：

```kotlin
// Anthropic 文本块可能包含 citations 数组
// 这些在 content_block_start 期间或作为独立字段提取
"content_block_start" -> {
    val contentBlock = data.jsonObject("content_block")
    val blockType = contentBlock?.string("type")
    if (blockType == "text") {
        val citations = contentBlock.jsonArray("citations")?.mapNotNull { c ->
            val obj = c.jsonObject
            if (obj.string("type") == "web_search_result_location") {
                val url = obj.string("url") ?: return@mapNotNull null
                Citation(
                    url = url,
                    title = obj.string("title") ?: url,
                    domain = extractDomain(url)
                )
            } else null
        }
        if (!citations.isNullOrEmpty()) {
            emit(StreamEvent.Citations(citations))
        }
    }
}
```

#### Anthropic 多轮上下文

Anthropic 的网页搜索结果包含 `encrypted_content` 字段，必须在后续轮次中回传。当前 `buildAnthropicMessages()` 中的消息转 API 转换通过重建内容块来处理 `AI_RESPONSE` 消息。对于网页搜索上下文：

- 助手响应中的 `server_tool_use` 和 `web_search_tool_result` 内容块在消息作为上下文回传时必须包含在内。
- 由于我们将完整响应内容存储在 `Message.content` 中，而 Anthropic 需要结构化的内容块，因此需要为包含搜索结果的 `AI_RESPONSE` 消息存储原始 Anthropic 内容数组。

**方案**：将原始 Anthropic 内容块 JSON 存储在 `thinkingContent` 字段中（重新用于多轮上下文）并不理想。更好的做法是为此目的在 `Message` 中添加可空的 `rawContentBlocks` 字段。然而，为了尽量减少 v1 的 schema 变更，**不**持久化原始内容块。这意味着跨消息压缩或会话重载时不会保留搜索上下文——这是 v1 可接受的权衡。模型可在需要时重新搜索。

#### Gemini 适配器变更

当 `webSearchEnabled = true` 时，向 tools 数组添加 `google_search`：

```kotlin
// 在 buildGeminiRequest() 中
private fun buildGeminiRequest(
    messages: List<ApiMessage>,
    tools: List<ToolDefinition>?,
    systemPrompt: String?,
    webSearchEnabled: Boolean
): JsonObject = buildJsonObject {
    // ... 现有字段 ...

    putJsonArray("tools") {
        // 现有函数声明工具
        if (!tools.isNullOrEmpty()) {
            addJsonObject {
                putJsonArray("function_declarations") {
                    tools.forEach { tool -> addJsonObject { /* ... */ } }
                }
            }
        }
        // 新增：Google Search 接地
        if (webSearchEnabled) {
            addJsonObject {
                putJsonObject("google_search") {}
            }
        }
    }
}
```

从响应中解析 `groundingMetadata`：

```kotlin
// 在 processGeminiStreamChunk() 中
// groundingMetadata 通常出现在最后一个块中
private fun parseGroundingMetadata(candidate: JsonObject): List<Citation> {
    val metadata = candidate.jsonObject("groundingMetadata") ?: return emptyList()
    val chunks = metadata.jsonArray("groundingChunks") ?: return emptyList()

    return chunks.mapNotNull { chunk ->
        val web = chunk.jsonObject.jsonObject("web") ?: return@mapNotNull null
        val url = web.string("uri") ?: return@mapNotNull null
        Citation(
            url = url,
            title = web.string("title") ?: url,
            domain = web.string("domain") ?: extractDomain(url)
        )
    }.distinctBy { it.url }
}

// 找到时发出：
val citations = parseGroundingMetadata(candidate)
if (citations.isNotEmpty()) {
    emit(StreamEvent.Citations(citations))
}
```

### SendMessageUseCase 变更

用例需要三处修改：

#### 1. 将 webSearchEnabled 传递给适配器

```kotlin
// 在 execute() 中
val webSearchEnabled = agent.webSearchEnabled

adapter.sendMessageStream(
    apiBaseUrl = provider.apiBaseUrl,
    apiKey = apiKey,
    modelId = model.modelId,
    messages = apiMessages,
    tools = toolDefinitions,
    systemPrompt = finalSystemPrompt,
    webSearchEnabled = webSearchEnabled  // 新增
)
```

#### 2. 从流中收集引用

```kotlin
// 在流收集循环中
val accumulatedCitations = mutableListOf<Citation>()

when (event) {
    // ... 现有事件处理 ...

    is StreamEvent.WebSearchStart -> {
        emit(ChatEvent.WebSearchStarted(event.query))
    }

    is StreamEvent.Citations -> {
        accumulatedCitations.addAll(event.citations)
    }
}

// 保存 AI_RESPONSE 消息时：
val aiMessage = Message(
    // ... 现有字段 ...
    citations = accumulatedCitations.distinctBy { it.url }.ifEmpty { null }
)
```

#### 3. 跳过服务端工具事件的工具执行

来自 Anthropic 的 `server_tool_use` 内容块作为 `WebSearchStart` 发出，而非 `ToolCallStart`。因此，它们自然不会进入 `pendingToolCalls`，也不会触发本地工具执行。除了在 Anthropic 适配器中使用正确的 `StreamEvent` 类型之外，无需额外处理。

### ChatEvent 变更

为聊天界面添加新事件类型：

```kotlin
sealed class ChatEvent {
    // ... 现有事件 ...

    /** 提供商正在执行网页搜索。 */
    data class WebSearchStarted(val query: String?) : ChatEvent()
}
```

### UI 层设计

#### 聊天消息中的引用区域

在包含引用的 AI 响应消息下方，渲染可折叠的"来源"区域：

```
┌────────────────────────────────────────────┐
│ AI 响应文本。根据最新报告，Android 16 引入…  │
│                                            │
│ ── 来源 (3) ────────────────────────────── │
│                                            │
│   [link] Android 16 Preview Released       │
│          developer.android.com             │
│                                            │
│   [link] What's New in Android 16          │
│          blog.google                       │
│                                            │
│   [link] Android 16 Features List          │
│          arstechnica.com                   │
│                                            │
└────────────────────────────────────────────┘
```

```kotlin
@Composable
fun CitationSection(
    citations: List<Citation>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier.padding(top = 8.dp)) {
        // 带标签的分割线
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text = "Sources (${citations.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        // 引用条目
        citations.forEach { citation ->
            CitationItem(
                citation = citation,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(citation.url))
                    context.startActivity(intent)
                }
            )
        }
    }
}

@Composable
fun CitationItem(
    citation: Citation,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Link,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = citation.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = citation.domain,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

#### 网页搜索指示器

当网页搜索进行中时（仅限 Anthropic），展示类似思考指示器的提示：

```kotlin
@Composable
fun WebSearchIndicator(query: String?) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (query != null) "Searching: $query" else "Searching the web...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic
        )
    }
}
```

#### Agent 配置开关

在 AgentDetailScreen 中添加：

```kotlin
// 在 agent 表单中，模型选择器之后
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { onWebSearchToggle(!webSearchEnabled) }
        .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Icon(Icons.Default.TravelExplore, contentDescription = null)
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
        Text("Web Search", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Allow the agent to search the web for up-to-date information. Additional API costs apply.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Switch(
        checked = webSearchEnabled,
        onCheckedChange = onWebSearchToggle
    )
}
```

#### AgentDetailUiState（已修改）

```kotlin
data class AgentDetailUiState(
    // ... 现有字段 ...
    val webSearchEnabled: Boolean = false,      // 新增
    val savedWebSearchEnabled: Boolean = false,  // 新增 - 用于检测未保存的变更
)
```

### 依赖注入注册

无需新的 DI 注册——这些变更都是对现有组件的修改。`Citation` 模型是一个简单的数据类，无依赖。

### 工具函数

```kotlin
// core/util/UrlUtils.kt（或添加到现有工具类）
fun extractDomain(url: String): String {
    return try {
        java.net.URI(url).host?.removePrefix("www.") ?: url
    } catch (_: Exception) {
        url
    }
}
```

## 实现步骤

### 阶段 1：数据层（模型 + 迁移）
1. [ ] 在 `core/model/` 中创建 `Citation` 数据类
2. [ ] 向 `Agent` 领域模型添加 `webSearchEnabled` 字段
3. [ ] 向 `AgentEntity` 添加 `web_search_enabled` 列
4. [ ] 向 `Message` 领域模型添加 `citations` 字段
5. [ ] 向 `MessageEntity` 添加 `citations` 列
6. [ ] 更新 `AgentMapper`（实体 <-> 领域转换）
7. [ ] 更新 `MessageMapper`，支持引用的 JSON 序列化
8. [ ] 创建 `MIGRATION_7_8` 并在 `AppDatabase` 中注册
9. [ ] 将数据库版本更新至 8
10. [ ] 添加 `extractDomain()` 工具函数

### 阶段 2：流事件 + 适配器接口
1. [ ] 向 `StreamEvent` 添加 `StreamEvent.WebSearchStart` 和 `StreamEvent.Citations`
2. [ ] 向 `ModelApiAdapter.sendMessageStream()` 添加 `webSearchEnabled` 参数
3. [ ] 更新三个适配器实现以接受新参数（初始为空操作）

### 阶段 3：OpenAI 适配器
1. [ ] 启用时向请求体添加 `web_search_options`
2. [ ] 从流式响应块中解析 `annotations` 数组
3. [ ] 以解析后的 `url_citation` 条目发出 `StreamEvent.Citations`

### 阶段 4：Anthropic 适配器
1. [ ] 启用时向 tools 数组注入 `web_search_20250305` 工具
2. [ ] 处理 `server_tool_use` 内容块类型（跟踪索引，发出 `WebSearchStart`）
3. [ ] 处理 `web_search_tool_result` 内容块类型（提取引用）
4. [ ] 从文本内容块中解析 `citations`
5. [ ] 以解析后的引用发出 `StreamEvent.Citations`

### 阶段 5：Gemini 适配器
1. [ ] 启用时向 tools 数组添加 `google_search: {}`
2. [ ] 从响应候选中解析 `groundingMetadata`
3. [ ] 将 `groundingChunks` 提取为 `Citation` 列表
4. [ ] 发出 `StreamEvent.Citations`

### 阶段 6：SendMessageUseCase
1. [ ] 将 `agent.webSearchEnabled` 传递给 `adapter.sendMessageStream()`
2. [ ] 向 `ChatEvent` 添加 `ChatEvent.WebSearchStarted`
3. [ ] 将 `StreamEvent.Citations` 收集到积累的引用列表中
4. [ ] 处理 `StreamEvent.WebSearchStart` -> 发出 `ChatEvent.WebSearchStarted`
5. [ ] 将积累的引用存储到已保存的 `AI_RESPONSE` 消息中

### 阶段 7：Agent UI
1. [ ] 向 `AgentDetailUiState` 添加 `webSearchEnabled`
2. [ ] 向 `AgentDetailScreen` 添加网页搜索开关
3. [ ] 更新 `AgentDetailViewModel` 以加载/保存新字段
4. [ ] 如果内置 agent 默认应禁用网页搜索，更新 `AgentConstants`

### 阶段 8：聊天 UI
1. [ ] 创建 `CitationSection` Composable
2. [ ] 创建 `CitationItem` Composable
3. [ ] 创建 `WebSearchIndicator` Composable
4. [ ] 将 `CitationSection` 集成到 AI 响应消息渲染中（当 `message.citations` 非空时）
5. [ ] 在收到 `ChatEvent.WebSearchStarted` 时展示 `WebSearchIndicator`（在下一个文本 delta 或响应完成时隐藏）

## 测试策略

### 单元测试

**CitationParsingTest（每个适配器各一份）：**
- OpenAI：从模拟响应 JSON 中解析 `url_citation` annotations
- Anthropic：从模拟 SSE 数据中解析 `web_search_tool_result` 内容块
- Anthropic：从文本内容块中解析 `citations`
- Gemini：从模拟响应 JSON 中解析 `groundingMetadata.groundingChunks`
- 全部：按 URL 去重引用

**AgentMapperTest：**
- 在实体和领域之间映射 `webSearchEnabled`
- 当列缺失时默认值为 `false`（迁移兼容性）

**MessageMapperTest：**
- 将 `List<Citation>` 序列化为 JSON 字符串
- 将 JSON 字符串反序列化为 `List<Citation>`
- 处理 null 和空引用
- 优雅地处理格式错误的 JSON（返回 null）

**SendMessageUseCaseTest：**
- 验证 `webSearchEnabled` 被正确传递至适配器
- 验证 `StreamEvent.Citations` 被积累并保存到消息中
- 验证 `StreamEvent.WebSearchStart` 发出 `ChatEvent.WebSearchStarted`
- 验证服务端工具事件不触发本地工具执行

### 集成测试

- 验证数据库迁移 7->8 无错误运行
- 验证 `webSearchEnabled = true` 的 agent 能正确持久化和加载

### 手动测试

- 在 agent 上启用网页搜索，发送关于时事的问题，验证引用出现
- 分别使用每家提供商（OpenAI、Anthropic、Gemini）进行测试
- 点击引用链接，验证在浏览器中打开
- 验证 Anthropic 搜索时出现网页搜索指示器
- 禁用网页搜索，验证不发送搜索配置
- 重新加载包含引用的会话，验证其正确显示
- 验证新 agent 默认关闭网页搜索

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|---------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
