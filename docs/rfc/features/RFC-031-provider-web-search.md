# RFC-031: Provider Web Search

## Document Information
- **RFC ID**: RFC-031
- **Related PRD**: [FEAT-031 (Provider Web Search)](../../prd/features/FEAT-031-provider-web-search.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

OneClaw currently has two client-side web tools: `webfetch` (HTTP fetch + HTML-to-Markdown) and `browser` (WebView-based rendering). These require the AI to explicitly call tools and consume a tool-call round-trip per page. Meanwhile, all three supported providers offer built-in server-side web search capabilities:

- **OpenAI**: `web_search_options` in Chat Completions API (search-capable models)
- **Anthropic**: `web_search_20250305` server-side tool in Messages API
- **Gemini**: `google_search` grounding in generateContent API

These server-side search capabilities run on the provider's infrastructure, have access to the provider's search index, and return structured citations -- all without consuming client-side tool-call rounds.

### Goals

1. Add a `webSearchEnabled` boolean to the Agent model and persist it in the database
2. Modify `ModelApiAdapter.sendMessageStream()` to accept a web search flag and inject provider-specific search configuration
3. Parse provider-specific citation/grounding responses into a unified `Citation` domain model
4. Add new `StreamEvent` types for web search lifecycle events and citations
5. Store citations as JSON on `AI_RESPONSE` messages
6. Render a "Sources" section below AI response messages in the chat UI
7. Handle Anthropic's `server_tool_use` / `web_search_tool_result` content block types without triggering client-side tool execution

### Non-Goals

- Domain filtering (allowed/blocked domain lists) -- future enhancement
- User location configuration for localized search -- future enhancement
- Inline footnote-style citation markers within response text
- Displaying the search query the provider generated
- Search result snippets/previews before the AI's synthesized response
- Client-side search fallback when provider search is unavailable

## Technical Design

### Architecture Overview

```
┌───────────────────────────────────────────────────────────────────────┐
│                         UI Layer                                      │
│                                                                       │
│  AgentDetailScreen         ChatScreen                                │
│  └── webSearchEnabled      └── CitationSection (new)                 │
│      toggle (new)              └── CitationItem (new, clickable)     │
│                                                                       │
├───────────────────────────────────────────────────────────────────────┤
│                         Use Case Layer                                │
│                                                                       │
│  SendMessageUseCase                                                  │
│  └── Passes webSearchEnabled to adapter                              │
│  └── Collects StreamEvent.Citations → stores on Message              │
│  └── Skips tool execution for server_tool_use events                 │
│                                                                       │
├───────────────────────────────────────────────────────────────────────┤
│                         Adapter Layer                                 │
│                                                                       │
│  ModelApiAdapter.sendMessageStream()                                 │
│  └── New parameter: webSearchEnabled: Boolean                        │
│                                                                       │
│  OpenAiAdapter                                                       │
│  └── Adds "web_search_options": {} to request body                   │
│  └── Parses annotations[].url_citation from response                 │
│                                                                       │
│  AnthropicAdapter                                                    │
│  └── Adds {type: "web_search_20250305", name: "web_search"} to tools│
│  └── Handles server_tool_use + web_search_tool_result blocks         │
│  └── Extracts citations from text blocks                             │
│                                                                       │
│  GeminiAdapter                                                       │
│  └── Adds {google_search: {}} to tools array                        │
│  └── Parses groundingMetadata from response candidates               │
│                                                                       │
├───────────────────────────────────────────────────────────────────────┤
│                         Data Layer                                    │
│                                                                       │
│  Agent (domain model)        → + webSearchEnabled: Boolean           │
│  AgentEntity (Room)          → + web_search_enabled column           │
│  Message (domain model)      → + citations: List<Citation>?         │
│  MessageEntity (Room)        → + citations TEXT column (JSON)        │
│  Citation (new domain model)                                         │
│                                                                       │
│  Migration 7→8: Add columns to agents and messages tables            │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

### Data Model

#### Citation (new domain model)

```kotlin
// core/model/Citation.kt
data class Citation(
    val url: String,
    val title: String,
    val domain: String       // extracted from url for display
)
```

Unified across all three providers. Provider-specific fields (encrypted_content for Anthropic, confidenceScores for Gemini) are not persisted -- they are only needed during the API round-trip.

#### Agent (modified)

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
    val webSearchEnabled: Boolean = false  // NEW
)
```

#### AgentEntity (modified)

```kotlin
// data/local/entity/AgentEntity.kt
@Entity(tableName = "agents")
data class AgentEntity(
    // ... existing fields ...
    @ColumnInfo(name = "web_search_enabled", defaultValue = "0")
    val webSearchEnabled: Boolean  // NEW
)
```

#### Message (modified)

```kotlin
// core/model/Message.kt
data class Message(
    // ... existing fields ...
    val citations: List<Citation>? = null  // NEW
)
```

#### MessageEntity (modified)

```kotlin
// data/local/entity/MessageEntity.kt
@Entity(tableName = "messages", ...)
data class MessageEntity(
    // ... existing fields ...
    @ColumnInfo(name = "citations")
    val citations: String? = null  // NEW - JSON array of Citation objects
)
```

Citations are serialized/deserialized as JSON using kotlinx.serialization in the mapper:

```kotlin
// In MessageMapper
private val json = Json { ignoreUnknownKeys = true }

fun MessageEntity.toDomain(): Message {
    val parsedCitations = citations?.let {
        try { json.decodeFromString<List<Citation>>(it) } catch (_: Exception) { null }
    }
    return Message(
        // ... existing mappings ...
        citations = parsedCitations
    )
}

fun Message.toEntity(): MessageEntity {
    return MessageEntity(
        // ... existing mappings ...
        citations = citations?.let { json.encodeToString(it) }
    )
}
```

#### Database Migration (7 -> 8)

```kotlin
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // RFC-031: Add web_search_enabled to agents
        db.execSQL("ALTER TABLE agents ADD COLUMN web_search_enabled INTEGER NOT NULL DEFAULT 0")
        // RFC-031: Add citations to messages
        db.execSQL("ALTER TABLE messages ADD COLUMN citations TEXT")
    }
}
```

### StreamEvent Changes

New event types added to `StreamEvent`:

```kotlin
sealed class StreamEvent {
    // ... existing events ...

    /** Provider is performing a server-side web search. */
    data class WebSearchStart(val query: String?) : StreamEvent()

    /** Citations extracted from the provider's response. */
    data class Citations(val citations: List<Citation>) : StreamEvent()
}
```

- `WebSearchStart`: Emitted when Anthropic's `server_tool_use` block starts (with the search query). For OpenAI and Gemini, this is not emitted since search is transparent.
- `Citations`: Emitted when citation data is available. For OpenAI, this comes from `annotations` in the final message chunk. For Anthropic, this is extracted from `citations` on text blocks. For Gemini, this is parsed from `groundingMetadata.groundingChunks`.

### Adapter Changes

#### ModelApiAdapter Interface (modified)

```kotlin
interface ModelApiAdapter {
    fun sendMessageStream(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?,
        webSearchEnabled: Boolean = false  // NEW
    ): Flow<StreamEvent>

    // ... other methods unchanged ...
}
```

#### OpenAI Adapter Changes

When `webSearchEnabled = true`, add `web_search_options` to the request body:

```kotlin
// In buildOpenAiRequest()
private fun buildOpenAiRequest(
    modelId: String,
    messages: List<ApiMessage>,
    tools: List<ToolDefinition>?,
    systemPrompt: String?,
    webSearchEnabled: Boolean
): JsonObject = buildJsonObject {
    put("model", modelId)
    put("stream", true)
    // ... existing fields ...

    if (webSearchEnabled) {
        putJsonObject("web_search_options") {
            put("search_context_size", "medium")
        }
    }
}
```

Parse annotations from streamed response:

```kotlin
// In processOpenAiStreamLine()
// After accumulating the full message, parse annotations from the final delta
// OpenAI returns annotations in message.annotations[] with type "url_citation"
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

Citations are emitted as `StreamEvent.Citations` after the final text delta (at `[DONE]` signal or when annotations are found).

#### Anthropic Adapter Changes

When `webSearchEnabled = true`, inject the web search tool into the tools array:

```kotlin
// In buildAnthropicRequest()
private fun buildAnthropicRequest(
    modelId: String,
    messages: List<ApiMessage>,
    tools: List<ToolDefinition>?,
    systemPrompt: String?,
    webSearchEnabled: Boolean
): JsonObject = buildJsonObject {
    // ... existing fields ...

    // Build tools array
    putJsonArray("tools") {
        // Add client-side tools
        tools?.forEach { tool ->
            addJsonObject { /* existing tool formatting */ }
        }
        // Add server-side web search tool
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

Handle new content block types in stream parsing:

```kotlin
// In processAnthropicStreamEvent()
"content_block_start" -> {
    val contentBlock = data.jsonObject("content_block") ?: return
    val blockType = contentBlock.string("type")
    val blockIndex = data.int("index") ?: return

    when (blockType) {
        "text" -> { /* existing: track text block index */ }
        "thinking" -> { /* existing: track thinking block */ }
        "tool_use" -> { /* existing: emit ToolCallStart */ }

        // NEW: Server-side tool use (web search)
        "server_tool_use" -> {
            val name = contentBlock.string("name")
            if (name == "web_search") {
                serverToolUseIndices.add(blockIndex)
                // Query will be extracted from input_json_delta
            }
        }

        // NEW: Web search results
        "web_search_tool_result" -> {
            // Parse search results, extract citations
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

// Also: when a server_tool_use block receives input_json_delta, extract query:
"content_block_delta" -> {
    val index = data.int("index") ?: return
    if (index in serverToolUseIndices) {
        // Extract search query from input_json_delta
        val delta = data.jsonObject("delta")
        val partialJson = delta?.string("partial_json")
        // Accumulate and parse query when complete
        // Emit WebSearchStart(query) when block stops
    }
}
```

**Critical**: The `server_tool_use` block must NOT be treated as a client-side tool call. It should NOT be added to `pendingToolCalls` and should NOT trigger tool execution in `SendMessageUseCase`.

Additionally, parse inline citations from text blocks:

```kotlin
// Anthropic text blocks may have citations array
// These are extracted during content_block_start or as separate fields
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

#### Anthropic Multi-Turn Context

Anthropic's web search results contain `encrypted_content` fields that must be passed back in subsequent turns. The current message-to-API conversion in `buildAnthropicMessages()` handles `AI_RESPONSE` messages by reconstructing content blocks. For web search context:

- The `server_tool_use` and `web_search_tool_result` content blocks from the assistant's response must be included when the message is sent back in context.
- Since we store the full response content in `Message.content`, and Anthropic needs the structured content blocks, we need to store the raw Anthropic content array for AI_RESPONSE messages that contain search results.

**Approach**: Store the raw Anthropic content blocks JSON in the `thinkingContent` field (repurposing for multi-turn context) is not ideal. Instead, add a `rawContentBlocks` nullable field to `Message` for this purpose. However, to minimize schema changes in v1, we will **not** persist raw content blocks. This means search context will not be preserved across message compaction or session reload -- an acceptable trade-off for v1. The model can re-search if needed.

#### Gemini Adapter Changes

When `webSearchEnabled = true`, add `google_search` to the tools array:

```kotlin
// In buildGeminiRequest()
private fun buildGeminiRequest(
    messages: List<ApiMessage>,
    tools: List<ToolDefinition>?,
    systemPrompt: String?,
    webSearchEnabled: Boolean
): JsonObject = buildJsonObject {
    // ... existing fields ...

    putJsonArray("tools") {
        // Existing function declarations tool
        if (!tools.isNullOrEmpty()) {
            addJsonObject {
                putJsonArray("function_declarations") {
                    tools.forEach { tool -> addJsonObject { /* ... */ } }
                }
            }
        }
        // NEW: Google Search grounding
        if (webSearchEnabled) {
            addJsonObject {
                putJsonObject("google_search") {}
            }
        }
    }
}
```

Parse `groundingMetadata` from response:

```kotlin
// In processGeminiStreamChunk()
// groundingMetadata typically appears in the final chunk
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

// Emit when found:
val citations = parseGroundingMetadata(candidate)
if (citations.isNotEmpty()) {
    emit(StreamEvent.Citations(citations))
}
```

### SendMessageUseCase Changes

The use case needs three modifications:

#### 1. Pass webSearchEnabled to adapter

```kotlin
// In execute()
val webSearchEnabled = agent.webSearchEnabled

adapter.sendMessageStream(
    apiBaseUrl = provider.apiBaseUrl,
    apiKey = apiKey,
    modelId = model.modelId,
    messages = apiMessages,
    tools = toolDefinitions,
    systemPrompt = finalSystemPrompt,
    webSearchEnabled = webSearchEnabled  // NEW
)
```

#### 2. Collect citations from stream

```kotlin
// In the stream collection loop
val accumulatedCitations = mutableListOf<Citation>()

when (event) {
    // ... existing event handling ...

    is StreamEvent.WebSearchStart -> {
        emit(ChatEvent.WebSearchStarted(event.query))
    }

    is StreamEvent.Citations -> {
        accumulatedCitations.addAll(event.citations)
    }
}

// When saving the AI_RESPONSE message:
val aiMessage = Message(
    // ... existing fields ...
    citations = accumulatedCitations.distinctBy { it.url }.ifEmpty { null }
)
```

#### 3. Skip tool execution for server-side tool events

The `server_tool_use` content blocks from Anthropic are emitted as `WebSearchStart`, not as `ToolCallStart`. Therefore, they will naturally not end up in `pendingToolCalls` and will not trigger local tool execution. No special handling is needed beyond using the correct `StreamEvent` types in the Anthropic adapter.

### ChatEvent Changes

New event type for the chat UI:

```kotlin
sealed class ChatEvent {
    // ... existing events ...

    /** Provider is performing a web search. */
    data class WebSearchStarted(val query: String?) : ChatEvent()
}
```

### UI Layer Design

#### Citation Section in Chat Messages

Below AI response messages that have citations, render a collapsible "Sources" section:

```
┌────────────────────────────────────────────┐
│ AI response text goes here. Based on       │
│ recent reports, Android 16 introduces...   │
│                                            │
│ ── Sources (3) ──────────────────────────  │
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
        // Divider with label
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

        // Citation items
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

#### Web Search Indicator

While a web search is in progress (Anthropic only), show an indicator similar to the thinking indicator:

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

#### Agent Configuration Toggle

Add to AgentDetailScreen:

```kotlin
// In the agent form, after the model selector
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

#### AgentDetailUiState (modified)

```kotlin
data class AgentDetailUiState(
    // ... existing fields ...
    val webSearchEnabled: Boolean = false,      // NEW
    val savedWebSearchEnabled: Boolean = false,  // NEW - for unsaved changes detection
)
```

### DI Registration

No new DI registrations needed -- the changes are modifications to existing components. The `Citation` model is a simple data class with no dependencies.

### Utility Function

```kotlin
// core/util/UrlUtils.kt (or add to existing utils)
fun extractDomain(url: String): String {
    return try {
        java.net.URI(url).host?.removePrefix("www.") ?: url
    } catch (_: Exception) {
        url
    }
}
```

## Implementation Steps

### Phase 1: Data Layer (models + migration)
1. [ ] Create `Citation` data class in `core/model/`
2. [ ] Add `webSearchEnabled` field to `Agent` domain model
3. [ ] Add `web_search_enabled` column to `AgentEntity`
4. [ ] Add `citations` field to `Message` domain model
5. [ ] Add `citations` column to `MessageEntity`
6. [ ] Update `AgentMapper` (entity <-> domain conversions)
7. [ ] Update `MessageMapper` with citation JSON serialization
8. [ ] Create `MIGRATION_7_8` and register in `AppDatabase`
9. [ ] Update database version to 8
10. [ ] Add `extractDomain()` utility function

### Phase 2: Stream Events + Adapter Interface
1. [ ] Add `StreamEvent.WebSearchStart` and `StreamEvent.Citations` to `StreamEvent`
2. [ ] Add `webSearchEnabled` parameter to `ModelApiAdapter.sendMessageStream()`
3. [ ] Update all three adapter implementations to accept the new parameter (no-op initially)

### Phase 3: OpenAI Adapter
1. [ ] Add `web_search_options` to request body when enabled
2. [ ] Parse `annotations` array from streamed response chunks
3. [ ] Emit `StreamEvent.Citations` with parsed `url_citation` entries

### Phase 4: Anthropic Adapter
1. [ ] Inject `web_search_20250305` tool into tools array when enabled
2. [ ] Handle `server_tool_use` content block type (track index, emit `WebSearchStart`)
3. [ ] Handle `web_search_tool_result` content block type (extract citations)
4. [ ] Parse `citations` from text content blocks
5. [ ] Emit `StreamEvent.Citations` with parsed citations

### Phase 5: Gemini Adapter
1. [ ] Add `google_search: {}` to tools array when enabled
2. [ ] Parse `groundingMetadata` from response candidates
3. [ ] Extract `groundingChunks` into `Citation` list
4. [ ] Emit `StreamEvent.Citations`

### Phase 6: SendMessageUseCase
1. [ ] Pass `agent.webSearchEnabled` to `adapter.sendMessageStream()`
2. [ ] Add `ChatEvent.WebSearchStarted` to `ChatEvent`
3. [ ] Collect `StreamEvent.Citations` into accumulated citation list
4. [ ] Handle `StreamEvent.WebSearchStart` -> emit `ChatEvent.WebSearchStarted`
5. [ ] Store accumulated citations on the saved `AI_RESPONSE` message

### Phase 7: Agent UI
1. [ ] Add `webSearchEnabled` to `AgentDetailUiState`
2. [ ] Add web search toggle to `AgentDetailScreen`
3. [ ] Update `AgentDetailViewModel` to load/save the new field
4. [ ] Update `AgentConstants` if the built-in agent should have web search disabled by default

### Phase 8: Chat UI
1. [ ] Create `CitationSection` composable
2. [ ] Create `CitationItem` composable
3. [ ] Create `WebSearchIndicator` composable
4. [ ] Integrate `CitationSection` into AI response message rendering (when `message.citations` is non-empty)
5. [ ] Show `WebSearchIndicator` when `ChatEvent.WebSearchStarted` is received (hide on next text delta or response complete)

## Testing Strategy

### Unit Tests

**CitationParsingTest (per adapter):**
- OpenAI: Parse `url_citation` annotations from mock response JSON
- Anthropic: Parse `web_search_tool_result` content blocks from mock SSE data
- Anthropic: Parse `citations` from text content blocks
- Gemini: Parse `groundingMetadata.groundingChunks` from mock response JSON
- All: Deduplicate citations by URL

**AgentMapperTest:**
- Map `webSearchEnabled` between entity and domain
- Default value is `false` when column is missing (migration compat)

**MessageMapperTest:**
- Serialize `List<Citation>` to JSON string
- Deserialize JSON string to `List<Citation>`
- Handle null and empty citations
- Handle malformed JSON gracefully (return null)

**SendMessageUseCaseTest:**
- Verify `webSearchEnabled` is passed through to adapter
- Verify `StreamEvent.Citations` are accumulated and saved on message
- Verify `StreamEvent.WebSearchStart` emits `ChatEvent.WebSearchStarted`
- Verify server-side tool events do not trigger local tool execution

### Integration Tests

- Verify database migration 7->8 runs without errors
- Verify agent with `webSearchEnabled = true` persists and loads correctly

### Manual Tests

- Enable web search on an agent, send a current-events question, verify citations appear
- Test with each provider (OpenAI, Anthropic, Gemini) individually
- Tap a citation link, verify it opens in browser
- Verify web search indicator appears for Anthropic searches
- Disable web search, verify no search configuration is sent
- Reload a session with citations, verify they display correctly
- Verify new agents default to web search OFF

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
