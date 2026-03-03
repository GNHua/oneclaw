# AI Provider Integration

OneClaw supports three AI providers through a unified adapter interface. Each provider has its own adapter that handles the differences in request format, streaming protocol, and tool definitions.

## Supported Providers

| Provider | Type | Auth | Streaming |
|----------|------|------|-----------|
| OpenAI (and compatible) | `OPENAI` | Bearer token header | SSE via `/chat/completions` |
| Anthropic | `ANTHROPIC` | `x-api-key` header | SSE via `/messages` |
| Google Gemini | `GEMINI` | API key query parameter | SSE via `:streamGenerateContent` |

## ModelApiAdapter Interface

All adapters implement `ModelApiAdapter`, which defines five operations:

```
sendMessageStream(apiBaseUrl, apiKey, modelId, messages, tools, systemPrompt, webSearchEnabled, temperature)
    -> Flow<StreamEvent>

listModels(apiBaseUrl, apiKey) -> AppResult<List<AiModel>>

testConnection(apiBaseUrl, apiKey) -> AppResult<ConnectionTestResult>

formatToolDefinitions(tools) -> Any

generateSimpleCompletion(apiBaseUrl, apiKey, modelId, prompt, maxTokens) -> AppResult<String>
```

`ModelApiAdapterFactory` creates the correct adapter based on `ProviderType`:

```
ProviderType.OPENAI    -> OpenAiAdapter
ProviderType.ANTHROPIC -> AnthropicAdapter
ProviderType.GEMINI    -> GeminiAdapter
```

## StreamEvent

The streaming response is modeled as a `Flow<StreamEvent>` with these event types:

| Event | Description |
|-------|-------------|
| `TextDelta(text)` | Incremental text chunk |
| `ThinkingDelta(text)` | Extended thinking content (Anthropic) |
| `ToolCallStart(toolCallId, toolName)` | Tool call begins |
| `ToolCallDelta(toolCallId, argumentsDelta)` | Tool call arguments chunk |
| `ToolCallEnd(toolCallId)` | Tool call complete |
| `Usage(inputTokens, outputTokens)` | Token usage report |
| `Error(message, code)` | Error during streaming |
| `Done` | Stream complete |
| `WebSearchStart(query)` | Web search initiated |
| `Citations(citations)` | Citation references from web search |

## OpenAI Adapter

**Endpoint:** `POST {apiBaseUrl}/chat/completions`

**Request format:**
```json
{
  "model": "gpt-4-turbo",
  "stream": true,
  "stream_options": { "include_usage": true },
  "messages": [...],
  "tools": [{ "type": "function", "function": {...} }],
  "web_search_options": { "search_context_size": "medium" }
}
```

**Key behaviors:**
- System prompt sent as a message with role `system`
- Tool definitions wrapped in `{ "type": "function", "function": {...} }`
- Streams parsed via SSE; `[DONE]` token signals completion
- Tool calls accumulated by index from streamed chunks
- Model list filtered to `gpt-*`, `o1`, `o3`, `o4`, `chatgpt-*` prefixes
- Supports multimodal input (images as `image_url` with base64)
- Web search via `web_search_options`; citations parsed from `url_citation` annotations

## Anthropic Adapter

**Endpoint:** `POST {apiBaseUrl}/messages`

**Request format:**
```json
{
  "model": "claude-sonnet-4-20250514",
  "max_tokens": 16000,
  "stream": true,
  "system": "...",
  "thinking": { "type": "enabled", "budget_tokens": 10000 },
  "messages": [...],
  "tools": [{ "name": "...", "description": "...", "input_schema": {...} }]
}
```

**Key behaviors:**
- System prompt sent as a separate top-level `system` field
- Tool definitions use `input_schema` instead of `parameters`
- Requires merging consecutive `ToolResult` messages into a single user message
- Extended thinking supported: temperature must be 1.0 when enabled
- SSE event types: `content_block_start`, `content_block_delta`, `content_block_stop`, `message_start`, `message_delta`, `message_stop`
- Web search via `web_search_20250305` server tool with `max_uses: 5`
- Supports PDF attachments as base64 `document` content blocks

## Gemini Adapter

**Endpoint:** `POST {apiBaseUrl}/models/{modelId}:streamGenerateContent?key={apiKey}&alt=sse`

**Request format:**
```json
{
  "system_instruction": { "parts": [{ "text": "..." }] },
  "contents": [...],
  "tools": [
    { "function_declarations": [...] },
    { "google_search": {} }
  ],
  "generationConfig": { "temperature": 0.7 }
}
```

**Key behaviors:**
- API key passed as query parameter, not in headers
- System prompt sent as `system_instruction`
- Tool definitions wrapped in `function_declarations` array
- Tool calls emitted inline (not streamed) as `functionCall` objects
- Tool call IDs generated as `call_{toolName}_{timestamp}`
- Model list filtered to those supporting `generateContent`
- Supports multimodal input (images as `inlineData`)
- Web search via `google_search` tool; citations from `groundingMetadata`

## SSE Parser

All providers use a shared `SseParser` that converts an HTTP response body into a `Flow<SseEvent>`:

```
ResponseBody.asSseFlow() -> Flow<SseEvent>
```

The parser:
- Reads the response body line-by-line on `Dispatchers.IO`
- Recognizes `event:` and `data:` prefixes
- Emits an `SseEvent(type, data)` on each blank line (event boundary)
- Uses `channelFlow` for backpressure-aware streaming
- Flushes remaining data when the stream ends

## API Message Types

Messages sent to adapters use a sealed class hierarchy:

- `ApiMessage.User(content, attachments)` -- User messages, optionally with image/file attachments
- `ApiMessage.Assistant(content, toolCalls)` -- AI responses, optionally with tool calls
- `ApiMessage.ToolResult(toolCallId, content)` -- Tool execution results

Attachments are modeled as `ApiAttachment(type, mimeType, base64Data, fileName)`.

## DTO Structure

Each provider has its own DTO package for model listing:

```
data/remote/dto/
  openai/     -> OpenAiModelListResponse, OpenAiModelDto
  anthropic/  -> AnthropicModelListResponse, AnthropicModelDto
  gemini/     -> GeminiModelListResponse, GeminiModelDto
```

All DTOs use `@Serializable` from kotlinx.serialization.

## Configuring a Provider

1. Go to Settings > Providers
2. Add a provider with name, type, and API base URL
3. Enter the API key (stored in `EncryptedSharedPreferences`, never in Room)
4. Fetch available models from the API
5. Set a default model for conversations

Alternatively, use the `create_provider`, `fetch_models`, and `set_default_model` tools via chat.
