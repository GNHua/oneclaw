# 测试报告：RFC-001 — 对话交互

## 报告信息

| 字段 | 值 |
|------|-----|
| RFC | RFC-001 |
| 提交 | `bdea03c` |
| 日期 | 2026-02-27 |
| 测试人 | AI（OpenCode） |
| 状态 | PASS |

## 概述

RFC-001 实现了完整的对话交互循环：来自 OpenAI、Anthropic（含 thinking blocks）和 Gemini 的 SSE 流式传输；`SendMessageUseCase` 中的多轮工具调用循环；以及完整的 Gemini 风格聊天 UI，包含消息气泡、工具调用卡片、思考块、流式光标和 Agent 选择器。所有可行的测试层均已成功执行。

| 层 | 步骤 | 结果 | 说明 |
|----|------|------|------|
| 1A | JVM 单元测试 | PASS | 245 个测试，0 个失败 |
| 1B | 设备 DAO 测试 | PASS | 48 个测试，0 个失败 |
| 1C | Roborazzi 截图测试 | PASS | 8 张新截图 |
| 2 | adb 视觉验证 | SKIP | API key 在当前环境不可用；见下文说明 |

## Layer 1A：JVM 单元测试

**命令：** `./gradlew test`

**结果：** PASS

**测试数量：** 245 个测试，0 个失败

主要变更：
- `OpenAiAdapterTest.sendMessageStream returns a Flow without throwing` — 替换了过时的"throws NotImplementedError"测试。该方法现在返回 `Flow<StreamEvent>`，此测试验证它不会抛出异常。
- 所有现有适配器测试（listModels、testConnection、generateSimpleCompletion）继续通过。

## Layer 1B：设备测试

**命令：** `ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest`

**结果：** PASS

**设备：** Medium_Phone_API_36.1（AVD）— emulator-5554

**测试数量：** 48 个测试，0 个失败

RFC-001 未新增设备测试（Chat 逻辑在适配器层可进行单元测试；DAO 层无变更）。

## Layer 1C：Roborazzi 截图测试

**命令：**
```bash
./gradlew recordRoborazziDebug
./gradlew verifyRoborazziDebug
```

**结果：** PASS

在 `AgentScreenshotTest`（RFC-001 和 RFC-002 共享文件）中录制的新截图：

### ChatTopBar

<img src="screenshots/RFC-001_ChatTopBar.png" width="250">

视觉检查：左侧汉堡菜单图标；中央"General Assistant"标题带下拉箭头（金/琥珀色文字）；右侧设置齿轮图标。

### ChatInput — 空状态

<img src="screenshots/RFC-001_ChatInput_empty.png" width="250">

视觉检查：带"Message"占位符的边框输入框；无文字时发送按钮禁用（灰色）。

### ChatInput — 含文字

<img src="screenshots/RFC-001_ChatInput_withText.png" width="250">

视觉检查：输入框中显示文字；发送按钮启用（有颜色）。

### ChatEmptyState

<img src="screenshots/RFC-001_ChatEmptyState.png" width="250">

视觉检查：无消息时显示居中的空状态占位符。

### MessageList — 对话

<img src="screenshots/RFC-001_ChatMessageList_conversation.png" width="250">

视觉检查：用户消息以金/琥珀色圆角气泡显示在右侧；AI 回复以 Surface 色卡片显示在左侧，Markdown 渲染正确（**粗体**显示正常）；AI 消息下方显示模型 ID "gpt-4o" 及复制/重新生成图标。

### MessageList — 工具调用

<img src="screenshots/RFC-001_ChatMessageList_toolCall.png" width="250">

视觉检查：用户消息气泡，然后是显示工具名称"get_current_time"的 TOOL_CALL 卡片，再是显示输出的 TOOL_RESULT 卡片，最后是 AI 最终回复。

### MessageList — 流式传输中

<img src="screenshots/RFC-001_ChatMessageList_streaming.png" width="250">

视觉检查：用户消息气泡，然后是 AI 气泡中显示的流式文字（流式光标可见）。

### MessageList — 活跃工具调用

<img src="screenshots/RFC-001_ChatMessageList_activeToolCall.png" width="250">

视觉检查：用户消息气泡，然后是显示 PENDING 状态的活跃 TOOL_CALL 卡片，工具名"read_file"及参数可见。

## Layer 2：adb 视觉验证

**结果：** SKIP

**原因：** Layer 2 需要将 API key 设置为环境变量（`ONECLAW_ANTHROPIC_API_KEY`、`ONECLAW_OPENAI_API_KEY`、`ONECLAW_GEMINI_API_KEY`）。当前 session 中这些 key 不可用。此外，Layer 2 应作为完整集成测试，同时覆盖 Provider → Agent → Chat 流程，建议在模拟器上设置 API key 后手动执行。

**手动测试步骤：**
1. 启动应用，通过 Setup 配置真实 API key
2. 开始新对话
3. 发送消息，验证流式回复正确显示
4. 验证工具调用（如"现在几点了？"）触发工具调用卡片和结果
5. 通过标题下拉切换 Agent
6. 验证会话已保存并出现在抽屉中

## 发现的问题

未发现任何问题。

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-02-27 | 初始报告 |
