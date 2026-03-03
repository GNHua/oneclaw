# AI 提供商集成

OneClaw 通过统一的适配器接口支持三个 AI 提供商。每个提供商都有自己的适配器，用于处理请求格式、流式传输协议和工具定义方面的差异。

## 支持的提供商

| 提供商 | 类型 | 认证方式 | 流式传输 |
|--------|------|----------|----------|
| OpenAI（及兼容接口） | `OPENAI` | Bearer token 请求头 | SSE 通过 `/chat/completions` |
| Anthropic | `ANTHROPIC` | `x-api-key` 请求头 | SSE 通过 `/messages` |
| Google Gemini | `GEMINI` | API key 查询参数 | SSE 通过 `:streamGenerateContent` |

## ModelApiAdapter 接口

所有适配器均实现 `ModelApiAdapter` 接口，该接口定义了五个操作：

```
sendMessageStream(apiBaseUrl, apiKey, modelId, messages, tools, systemPrompt, webSearchEnabled, temperature)
    -> Flow<StreamEvent>

listModels(apiBaseUrl, apiKey) -> AppResult<List<AiModel>>

testConnection(apiBaseUrl, apiKey) -> AppResult<ConnectionTestResult>

formatToolDefinitions(tools) -> Any

generateSimpleCompletion(apiBaseUrl, apiKey, modelId, prompt, maxTokens) -> AppResult<String>
```

`ModelApiAdapterFactory` 根据 `ProviderType` 创建对应的适配器：

```
ProviderType.OPENAI    -> OpenAiAdapter
ProviderType.ANTHROPIC -> AnthropicAdapter
ProviderType.GEMINI    -> GeminiAdapter
```

## StreamEvent

流式响应以 `Flow<StreamEvent>` 建模，包含以下事件类型：

| 事件 | 描述 |
|------|------|
| `TextDelta(text)` | 增量文本片段 |
| `ThinkingDelta(text)` | 扩展思考内容（Anthropic） |
| `ToolCallStart(toolCallId, toolName)` | 工具调用开始 |
| `ToolCallDelta(toolCallId, argumentsDelta)` | 工具调用参数片段 |
| `ToolCallEnd(toolCallId)` | 工具调用完成 |
| `Usage(inputTokens, outputTokens)` | Token 用量报告 |
| `Error(message, code)` | 流式传输过程中发生错误 |
| `Done` | 流式传输完成 |
| `WebSearchStart(query)` | 网络搜索已发起 |
| `Citations(citations)` | 来自网络搜索的引用参考 |

## OpenAI 适配器

**端点：** `POST {apiBaseUrl}/chat/completions`

**请求格式：**
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

**关键行为：**
- 系统提示作为 `system` 角色消息发送
- 工具定义包装在 `{ "type": "function", "function": {...} }` 结构中
- 通过 SSE 解析流式数据；`[DONE]` 标记表示传输完成
- 工具调用从流式片段中按索引累积
- 模型列表过滤为 `gpt-*`、`o1`、`o3`、`o4`、`chatgpt-*` 前缀的模型
- 支持多模态输入（图片以 base64 格式通过 `image_url` 传入）
- 网络搜索通过 `web_search_options` 启用；引用从 `url_citation` 注解中解析

## Anthropic 适配器

**端点：** `POST {apiBaseUrl}/messages`

**请求格式：**
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

**关键行为：**
- 系统提示作为独立的顶层 `system` 字段发送
- 工具定义使用 `input_schema` 而非 `parameters`
- 需要将连续的 `ToolResult` 消息合并为单条用户消息
- 支持扩展思考：启用时温度参数必须为 1.0
- SSE 事件类型：`content_block_start`、`content_block_delta`、`content_block_stop`、`message_start`、`message_delta`、`message_stop`
- 网络搜索通过 `web_search_20250305` 服务器工具启用，`max_uses: 5`
- 支持将 PDF 附件作为 base64 编码的 `document` 内容块

## Gemini 适配器

**端点：** `POST {apiBaseUrl}/models/{modelId}:streamGenerateContent?key={apiKey}&alt=sse`

**请求格式：**
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

**关键行为：**
- API key 通过查询参数传入，而非请求头
- 系统提示通过 `system_instruction` 发送
- 工具定义包装在 `function_declarations` 数组中
- 工具调用以内联方式（非流式）作为 `functionCall` 对象发出
- 工具调用 ID 生成格式为 `call_{toolName}_{timestamp}`
- 模型列表过滤为支持 `generateContent` 的模型
- 支持多模态输入（图片以 `inlineData` 方式传入）
- 网络搜索通过 `google_search` 工具启用；引用来自 `groundingMetadata`

## SSE 解析器

所有提供商共享一个 `SseParser`，将 HTTP 响应体转换为 `Flow<SseEvent>`：

```
ResponseBody.asSseFlow() -> Flow<SseEvent>
```

该解析器：
- 在 `Dispatchers.IO` 上逐行读取响应体
- 识别 `event:` 和 `data:` 前缀
- 在每个空行（事件边界）处发出 `SseEvent(type, data)`
- 使用 `channelFlow` 实现背压感知流式传输
- 流结束时刷新剩余数据

## API 消息类型

发送给适配器的消息使用密封类层次结构：

- `ApiMessage.User(content, attachments)` -- 用户消息，可选附带图片或文件附件
- `ApiMessage.Assistant(content, toolCalls)` -- AI 响应，可选附带工具调用
- `ApiMessage.ToolResult(toolCallId, content)` -- 工具执行结果

附件建模为 `ApiAttachment(type, mimeType, base64Data, fileName)`。

## DTO 结构

每个提供商都有各自用于模型列表的 DTO 包：

```
data/remote/dto/
  openai/     -> OpenAiModelListResponse, OpenAiModelDto
  anthropic/  -> AnthropicModelListResponse, AnthropicModelDto
  gemini/     -> GeminiModelListResponse, GeminiModelDto
```

所有 DTO 均使用 kotlinx.serialization 的 `@Serializable` 注解。

## 配置提供商

1. 进入设置 > 提供商
2. 添加提供商，填写名称、类型和 API 基础 URL
3. 输入 API key（存储在 `EncryptedSharedPreferences` 中，不存入 Room）
4. 从 API 获取可用模型列表
5. 为对话设置默认模型

也可以通过聊天界面使用 `create_provider`、`fetch_models` 和 `set_default_model` 工具完成配置。
