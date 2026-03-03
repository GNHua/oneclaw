# Chat Interaction

## Feature Information
- **Feature ID**: FEAT-001
- **Created**: 2026-02-26
- **Last Updated**: 2026-02-26
- **Status**: Draft
- **Priority**: P0 (Must Have)
- **Owner**: TBD
- **Related RFC**: [RFC-001 (Chat Interaction)](../../rfc/features/RFC-001-chat-interaction.md)
- **Related Design**: [UI Design Spec](../../design/ui-design-spec.md)

## User Story

**As** a user of OneClaw,
**I want to** send text messages to an AI model and see its responses in real-time, including any tool calls it makes,
**so that** I can interact with an AI agent on my phone that can take actions on my behalf.

### Typical Scenarios
1. User opens a session, types a question, and receives a streamed AI response.
2. User asks the AI to perform a task that requires tool calls (e.g., "What time is it?"). The AI calls a tool, the result is shown in the chat, and the AI provides a final answer.
3. User scrolls up to review earlier messages in a long conversation.
4. User stops a response mid-generation because the AI is going in the wrong direction.

## Feature Description

### Overview
Chat Interaction is the core interface of OneClaw. It provides a chat-based conversation view where users send text messages to an AI model and receive streamed responses. When the AI model makes tool calls, the execution process and results are displayed inline in the conversation. This module handles the full request-response cycle including streaming, tool call orchestration display, and error states.

### Message Types

The chat displays the following types of messages:

1. **User Message**: Text input from the user. Displayed on the right side of the chat.

2. **AI Response**: The model's text reply. Displayed on the left side of the chat. Supports Markdown rendering. May include an attached thinking/reasoning block (see below).

3. **Thinking Block**: The model's internal reasoning (from extended thinking / chain-of-thought). This is displayed as a collapsible section attached to the AI response message, not as a standalone message. Collapsed by default; user can tap to expand and read the reasoning process.

4. **Tool Call Message**: Displayed when the AI invokes a tool. Shows the tool execution lifecycle (invocation, execution, result). Has two display modes:
   - **Compact mode**: A single line such as "Calling [tool name]...", then "Done" with a brief result summary.
   - **Detailed mode**: Shows tool name, input parameters, and full return value. Expandable/collapsible.
   - User can switch between these two modes globally.

5. **Error Message**: Displayed when something goes wrong -- API request failure, tool execution error, network timeout, etc. Shows a clear error description.

### Core Interactions

#### Sending a Message
- Text input field at the bottom of the screen
- Send button (enabled only when input is non-empty and no request is in-flight)
- After sending, the user message appears in the chat immediately
- The input field is cleared after sending

#### Receiving a Response
- AI response streams in token by token (or chunk by chunk)
- A typing/loading indicator is shown while waiting for the first token
- Text renders progressively as it arrives
- Markdown is rendered in real-time as the response streams

#### Tool Call Flow
When the model's response includes tool calls:
1. The tool call message appears in the chat (compact or detailed based on current mode)
2. The tool executes automatically (no user confirmation required by default)
3. The tool result is displayed
4. The result is sent back to the model
5. The model continues its response (which may include more tool calls or a final text answer)
6. This loop continues until the model produces a final response with no more tool calls

#### Stopping Generation
- While a response is being generated, a stop button replaces the send button
- Tapping stop cancels the in-flight request
- Whatever has been received so far is kept in the chat
- The stop button **must** switch back to the send button immediately after the user taps it — the UI must never remain stuck in the "streaming" state after a stop
- The user can then send a new message

#### Copying Messages
- All messages (user messages, AI responses, tool call details, error messages) can be copied
- Long-press on a message to copy its text content
- For AI responses with Markdown, copy provides the raw text

#### Scrolling and History
- Chat scrolls vertically, newest messages at the bottom
- Auto-scrolls to the bottom when a new message arrives or during streaming
- User can scroll up to view earlier messages
- When scrolled up during streaming, a "scroll to bottom" button appears

### Tool Call Display Modes

#### Compact Mode
```
[icon] Calling read_file... Done
```
A single line per tool call. Shows tool name and status. Result is hidden unless the user taps to expand.

#### Detailed Mode
```
[icon] Tool: read_file
  Input: { "path": "/storage/notes.txt" }
  Result: "Contents of the file..."
[collapsible]
```
Shows full tool name, input parameters, and return value. Each section is collapsible.

#### Mode Switching
- A toggle in the chat screen (e.g., toolbar icon) allows switching between compact and detailed mode
- The setting applies to all tool call messages in the current view
- The preference is persisted

## Acceptance Criteria

Must pass (all required):
- [ ] User can type and send a text message
- [ ] AI response is displayed with streaming (progressive token rendering)
- [ ] Markdown in AI responses is rendered correctly (bold, italic, lists, headings, inline code, code blocks)
- [ ] Thinking/reasoning blocks are displayed collapsed by default, expandable on tap
- [ ] Extended thinking is automatically enabled (no user action required) when the active model supports it — a "Thinking..." block appears in the response without any manual configuration
- [ ] Tool calls are displayed inline in the conversation
- [ ] Tool call compact mode shows tool name and status in one line
- [ ] Tool call detailed mode shows tool name, parameters, and result, collapsible
- [ ] User can switch between compact and detailed tool call display modes
- [ ] Display mode preference is persisted across sessions
- [ ] A typing/loading indicator is shown while waiting for the first response token
- [ ] User can stop an in-progress response generation
- [ ] Partially received content is preserved after stopping
- [ ] Stop button switches back to send button immediately after tapping stop (UI never stays stuck in streaming state)
- [ ] All message types can be copied (long-press to copy)
- [ ] Error messages are displayed clearly when API calls or tool execution fails
- [ ] Chat auto-scrolls to the bottom on new messages and during streaming
- [ ] User can scroll up to view history; a "scroll to bottom" button appears when scrolled away
- [ ] Send button is disabled when input is empty or a request is in-flight
- [ ] When the soft keyboard opens, the top app bar (hamburger icon and settings gear) remains fully visible and is not pushed off-screen

Optional (nice to have for V1):
- [ ] Haptic feedback on send
- [ ] Smooth scroll animations
- [ ] Message timestamp display

## UI/UX Requirements

For detailed visual specifications, layouts, spacing, and component designs, see the [UI Design Spec](../../design/ui-design-spec.md). Key points summarized below:

### Layout
- Full-screen chat view with Navigation Drawer for session list (accessed via hamburger menu)
- Top app bar: hamburger menu (left), Agent selector with dropdown chevron (center), settings gear (right)
- Message list occupies most of the screen
- Input area fixed at the bottom: pill-shaped text field + send/stop button
- When the soft keyboard opens, **only the message list and input area adjust** — the top app bar must remain fully visible. The hamburger icon and settings gear must never be pushed off-screen by the keyboard. This is achieved by using `WindowCompat.setDecorFitsSystemWindows(false)` with Compose `imePadding()` on the input area, rather than relying on `windowSoftInputMode=adjustResize`.

### Message Bubbles
- User messages: right-aligned, `primaryContainer` background, rounded corners (18dp)
- AI messages: left-aligned, no bubble/no background, small AI icon on the left
- AI response action row below each completed response: copy, regenerate (optional), share (optional)
- Tool call messages: full-width, `surfaceVariant` background, visually distinct
- Error messages: full-width, `errorContainer` background

### Thinking Block
- Attached to the AI message bubble, above the main response text
- Collapsed state: a small label such as "Thinking..." or "View reasoning" with a chevron icon
- Expanded state: shows the full thinking text in a visually distinct style (e.g., lighter text, different background)
- **Extended thinking is enabled by default** for all models that support it (e.g., Claude Opus 4, Claude Sonnet 4 series). The app detects model capability and includes the required thinking configuration in the API request automatically. No user action is needed to activate thinking mode — it is always on for capable models.

### Streaming Indicator
- While waiting for the first token: a pulsing dot or typing indicator in the AI message area
- During streaming: the AI message text grows progressively

### Interaction Feedback
- Loading: typing indicator while waiting for response
- Error: error message displayed in chat with retry option
- Success: message appears in chat flow naturally
- Stop: generation stops, partial content retained

## Feature Boundary

### Included
- Text message input and display
- Streaming AI response rendering
- Markdown rendering in AI responses
- Thinking block display (collapsed/expandable)
- Tool call inline display (compact and detailed modes)
- Tool call display mode switching
- Message copying (all types)
- Stop generation
- Auto-scroll and scroll-to-bottom
- Typing/loading indicator
- Error message display

### Not Included (V1)
- Message editing after send
- Message re-send / regenerate
- Code block syntax highlighting
- One-tap code block copy
- Conversation branching
- Voice input
- Image input or display
- Message search within a session
- Message reactions or annotations
- Rich text input (user always sends plain text)

## Business Rules

### Message Rules
1. User messages are always plain text (no Markdown rendering on user side)
2. AI responses support Markdown rendering
3. A user cannot send a new message while a response is being generated (must stop first)
4. Empty messages cannot be sent
5. Tool calls execute automatically without user confirmation (except when Android system permissions are needed)

### Display Rules
1. Messages are displayed in chronological order, oldest at top
2. Tool call display mode (compact/detailed) is a global setting, not per-message
3. Thinking blocks are always collapsed by default when first displayed
4. Error messages are displayed at the point in the conversation where the error occurred
5. Extended thinking is always enabled for models that support it — the app includes the thinking configuration in every API request to a thinking-capable model. There is no user-facing toggle to disable thinking for these models in V1.

### Data Rules
1. All messages in a session are persisted locally
2. Messages include metadata: timestamp, message type, token count (if available)
3. Tool call messages include: tool name, input parameters, output result, execution status, duration

## Non-Functional Requirements

### Performance
- Streaming response should render with < 50ms latency per chunk
- Scrolling through 1000+ messages should maintain 60fps
- Input field should respond to keystrokes with no perceptible delay
- Tool call execution should not block UI (runs on background thread)

### Reliability
- If a streaming response is interrupted (network drop), display what was received and show an error
- If a tool call fails, show the error in the tool call message and let the model handle it
- App should not crash on malformed API responses

### Accessibility
- Messages should support system font size settings
- Sufficient color contrast for all message types
- Screen reader support for message content

## Dependencies

### Depends On
- **FEAT-003 (Model/Provider Management)**: Needs a configured provider and model to send requests to
- **FEAT-004 (Tool System)**: Needs the tool execution engine for handling tool calls
- **FEAT-005 (Session Management)**: Chat exists within a session context
- **FEAT-002 (Agent Management)**: The active Agent determines system prompt and available tools

### Depended On By
- **FEAT-006 (Token/Cost Tracking)**: Reads token usage data from chat messages
- **FEAT-008 (Notifications)**: Triggers notifications when long-running tasks complete

## Error Handling

### Error Scenarios

1. **Network unavailable**
   - Display: Error message in chat "Unable to connect. Check your network and try again."
   - User action: Can retry by sending the message again

2. **API request failure (4xx/5xx)**
   - Display: Error message in chat with the error details (e.g., "API error: 429 Rate limit exceeded")
   - User action: Can retry by sending the message again

3. **API key invalid or expired**
   - Display: Error message in chat "API key is invalid or expired. Please check your provider settings."
   - User action: Navigate to provider settings to fix

4. **Tool execution failure**
   - Display: Error shown within the tool call message block
   - The error is sent back to the model as the tool result, letting the model decide how to proceed

5. **Streaming interrupted**
   - Display: Partial response preserved, error message appended
   - User action: Can send a new message to continue

6. **Response timeout**
   - Display: Error message in chat "Request timed out. Please try again."
   - User action: Can retry

## Future Improvements

These are tracked for future versions and are explicitly NOT in V1 scope:

- [ ] **Message editing**: Edit a sent message and re-send
- [ ] **Regenerate response**: Re-generate the AI's last response
- [ ] **Conversation branching**: Regenerated responses create branches instead of replacing
- [ ] **Code block enhancements**: Syntax highlighting, one-tap copy for code blocks
- [ ] **Message search**: Search for text within the current session
- [ ] **Rich media display**: Display images, files, or other media in the chat

## Test Points

### Functional Tests
- Send a message and verify it appears in chat
- Verify streaming response renders progressively
- Verify Markdown is rendered correctly (test various elements)
- Verify thinking block is collapsed by default and expands on tap
- Verify tool call compact mode display
- Verify tool call detailed mode display
- Verify mode switching works and persists
- Verify stop generation works and preserves partial content
- Verify message copy works for all message types
- Verify auto-scroll behavior
- Verify scroll-to-bottom button appears when scrolled up
- Verify error messages display correctly for each error scenario
- Verify send button state (disabled when empty, disabled during generation)

### Performance Tests
- Streaming latency measurement
- Scroll performance with large message history (1000+ messages)
- Memory usage during long conversations

### Edge Cases
- Very long messages (10,000+ characters)
- Rapid consecutive messages
- Multiple tool calls in a single response
- Nested tool calls (tool call result triggers another tool call)
- Empty AI response
- Network switch during streaming (WiFi to cellular)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-26 | 0.1 | Initial version | - |
| 2026-02-27 | 0.2 | Updated UI section to reference UI Design Spec; added agent selector chip, action row details, Gemini-style layout | - |
| 2026-02-27 | 0.3 | Added RFC-001 reference | - |
| 2026-02-27 | 0.4 | Added requirement: extended thinking always enabled by default for capable models; updated Thinking Block UI/UX section, Display Rules, and Acceptance Criteria | - |
