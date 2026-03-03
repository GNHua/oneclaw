# Concurrent User Input

## Feature Information
- **Feature ID**: FEAT-010
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-010 (Concurrent User Input)](../../rfc/features/RFC-010-concurrent-user-input.md)
- **Related Feature**: [FEAT-001 (Chat Interaction)](FEAT-001-chat.md)

## User Story

**As** a user of OneClaw,
**I want to** send additional messages while the AI is still responding or executing tools,
**so that** I can add context, follow up, or redirect the AI without waiting for the current response to finish.

### Typical Scenarios

1. The AI is streaming a long response. The user realizes they forgot to mention an important detail. They type and send a follow-up message without waiting. When the AI finishes its current response, it immediately sees the follow-up and continues.

2. The AI is executing a chain of tool calls (e.g., reading multiple files). During the tool execution pause, the user sends "also check the config file". The AI picks this up in the next iteration and includes the config file.

3. The AI is streaming a response. The user sends a new message, then changes their mind and presses Stop. The AI's partial response is preserved. The new message stays visible in the chat but is marked as abandoned. The user then sends a third message, and the AI responds to that one, ignoring the abandoned message.

4. The user types quickly and sends two messages in rapid succession before the AI has started responding. Both messages appear in the chat, and the AI sees both when it makes its first API call.

## Feature Description

### Overview

This feature removes the restriction that prevents users from sending messages while the AI agent loop is running. Currently, the send button and text field are both disabled during streaming. With this feature, the user can always type and send messages. Messages sent during an active agent loop are queued and injected at the next natural pause point (between AI response completion and the next API call, or between tool execution rounds). The AI sees these messages in the correct chronological order as part of the conversation history.

### Detailed Behavior

#### Sending During Streaming

- The text input field is always enabled, regardless of whether the AI is streaming.
- The send button is always visible and active when text is non-empty.
- When the user sends a message during streaming:
  1. The message immediately appears as a user bubble in the chat (same styling as normal user messages).
  2. The message is saved to the database immediately.
  3. The message is held in a queue until the current AI iteration finishes.
  4. At the next iteration boundary, the AI's context is refreshed from the database, which now includes the queued message.
  5. The AI responds to the full conversation including the new message.

#### Stop Button and Send Button Are Independent

- The stop button and send button are two separate, independent UI elements.
- During streaming/agent execution: both buttons are visible simultaneously.
  - **Stop button**: appears to the left of the send button, with a spinning progress indicator. Tapping it cancels the current agent loop.
  - **Send button**: always present on the right. Enabled when the text field is non-empty and a provider is configured.
- When not streaming: only the send button is visible (stop button disappears).

#### Stop With Queued Messages

When the user presses Stop while there are queued (unprocessed) messages:

1. The current AI response is cancelled. The partial response is saved to the conversation.
2. The queued message(s) remain visible in the chat (they were already displayed when sent).
3. A system note is inserted into the conversation history after the queued message(s). This note instructs the AI to ignore the queued messages and respond to the user's next message instead.
4. The agent loop terminates.
5. When the user later sends a new message, the AI sees the full history including the partial response, the abandoned queued messages, and the system note. It responds to the new message, not the abandoned ones.

#### Rapid Double-Send

If the user sends two messages before the agent loop has started (e.g., typing and sending very quickly on a new conversation):

- Both messages are saved to the database as separate USER messages.
- The agent loop's first API call reads both messages from the database.
- The AI sees two consecutive user messages and responds to both.

### User Interaction Flows

#### Flow A: Send during streaming (normal)

```
1. User sends message A
2. AI starts streaming response to A
3. User types message B and taps Send
4. Message B appears in chat immediately
5. AI finishes responding to A
6. AI automatically starts responding to B (no user action needed)
7. AI response to B streams in
```

#### Flow B: Send during tool execution

```
1. User sends message A
2. AI starts responding, makes tool calls
3. Tools are executing (spinner visible)
4. User types message B and taps Send
5. Message B appears in chat immediately
6. Tools finish executing
7. AI sees tool results + message B in next iteration
8. AI responds considering both tool results and message B
```

#### Flow C: Stop with queued message

```
1. User sends message A
2. AI starts streaming response to A
3. User types message B and taps Send
4. Message B appears in chat immediately
5. User taps Stop
6. AI partial response is saved and visible
7. Message B remains visible in chat
8. System note is inserted (not prominently visible to user)
9. User later sends message C
10. AI responds to C (ignoring B)
```

#### Flow D: Rapid double-send

```
1. User types message A and taps Send
2. User immediately types message B and taps Send
3. Both A and B appear in chat
4. AI starts and sees both A and B in its context
5. AI responds to the combined context of A and B
```

## Acceptance Criteria

Must pass (all required):
- [ ] Text input field is always enabled during streaming (user can type at any time)
- [ ] Send button is always visible and enabled when text is non-empty, even during streaming
- [ ] Stop button and send button are separate, independent UI elements during streaming
- [ ] Stop button shows a spinning progress indicator during streaming
- [ ] Message sent during streaming appears immediately in the chat as a user bubble
- [ ] Message sent during streaming is processed by the AI after the current iteration completes
- [ ] AI response to the queued message starts automatically (no user action needed)
- [ ] When Stop is pressed with queued messages: partial AI response is saved, queued messages remain visible, system note is inserted
- [ ] After Stop with queued messages, the next user message is responded to by the AI (queued messages are ignored)
- [ ] Rapid double-send before AI starts: both messages appear and AI responds to both
- [ ] No UI state corruption: after all flows above, the chat remains in a consistent, usable state

Optional (nice to have):
- [ ] Badge or indicator showing number of queued messages
- [ ] Queued message has a subtle visual distinction (e.g., slight opacity) until processed

## UI/UX Requirements

### Input Area Layout During Streaming

```
+--------------------------------------------------+
| [text input field - always enabled           ]    |
|                          [Stop spinner] [Send]    |
+--------------------------------------------------+
```

- Text field: full width, always accepts input.
- Stop button: `CircularProgressIndicator` icon, `errorContainer` background. Only visible during streaming.
- Send button: `Send` icon, `primary` background. Always visible. Disabled only when text is empty or no provider configured.

### Input Area Layout When Not Streaming

```
+--------------------------------------------------+
| [text input field                            ]    |
|                                        [Send]    |
+--------------------------------------------------+
```

- Same as current behavior, minus the `canSend` / `isStreaming` disabling.

### Message Display

- Queued messages look identical to normal user messages. No special styling required in V1.
- System notes (abandon markers) use the existing `SystemMessageCard` style (small grey centered text).

### Interaction Feedback

- Sending during streaming: message appears instantly, same as normal send. No additional toast or animation needed.
- Stop with queued messages: stop behavior is the same as current (partial response saved). The system note is a small grey text label in the chat, not a modal or toast.

## Feature Boundary

### Included
- Always-enabled text input during streaming
- Independent stop and send buttons
- Queue-based message injection at iteration boundaries
- Stop + queued message handling with abandon system note
- Rapid double-send support

### Not Included
- Interrupting a streaming response mid-token to inject a message (injection only at iteration boundaries)
- Editing or cancelling a queued message after it is sent
- Visual distinction for queued vs. processed messages (optional, not required)
- Multiple concurrent agent loops

## Business Rules

### Message Rules
1. A user CAN send a new message while a response is being generated (changes rule 3 from FEAT-001).
2. Messages sent during streaming are queued and processed at the next iteration boundary.
3. The AI sees queued messages in chronological order as part of the conversation history.
4. Queued messages are never lost -- they are saved to the database the moment the user sends them.

### Stop Rules
1. When Stop is pressed with queued messages, the partial AI response is saved (not discarded).
2. Queued messages remain in the database and chat UI after Stop.
3. A system note is inserted after the queued messages instructing the AI to ignore them.
4. The system note is visible in the chat as a small system message.

### Queue Rules
1. There is no limit on the number of messages that can be queued.
2. Messages are injected in the order they were sent.
3. If the agent loop finishes naturally (no Stop) with queued messages, all queued messages are injected and the AI responds to them.

## Dependencies

### Depends On
- **FEAT-001 (Chat Interaction)**: This feature modifies the chat input and agent loop behavior defined in FEAT-001.

### Depended On By
- None

## Test Points

### Functional Tests -- User Operating the App

These tests describe what a person does on the device and what they should see. They are the primary verification for this feature.

#### Test 1: Send a message during AI streaming

**Setup**: Provider configured, chat screen open.

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Type "Tell me a long story about dragons" and tap Send | Message A appears as user bubble. AI starts streaming response. Stop button (spinning) appears next to Send button. |
| 2 | While AI is streaming, type "Make it funny" in the input field | Text field accepts input. Send button is enabled. |
| 3 | Tap Send | "Make it funny" appears as a second user bubble below the streaming AI response. Input field clears. |
| 4 | Wait for AI to finish current response | AI response to message A completes. |
| 5 | Observe | AI automatically starts a new streaming response that acknowledges "Make it funny". No user action needed. |

#### Test 2: Send a message during tool execution

**Setup**: Provider configured, agent with tools enabled.

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Type "What time is it?" and tap Send | Message appears. AI makes a tool call. Tool call card shows with spinner. |
| 2 | While tool is executing, type "And what's today's date?" and tap Send | New user bubble appears immediately. |
| 3 | Wait for tool to finish and AI to complete | AI finishes responding to the first query. Then AI processes the second message (may make another tool call for the date). |

#### Test 3: Stop with a queued message

**Setup**: Provider configured, chat screen open.

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Type "Write a 500-word essay about space" and tap Send | AI starts streaming a long response. |
| 2 | While AI is streaming, type "Never mind, talk about cats" and tap Send | "Never mind, talk about cats" appears as a user bubble. |
| 3 | Tap the Stop button | AI streaming stops. Partial response is visible in chat. Stop button disappears. |
| 4 | Observe the chat | Partial AI response is visible. "Never mind, talk about cats" is visible below it. A small system note may be visible. |
| 5 | Type "What are the best cat breeds?" and tap Send | AI responds to "What are the best cat breeds?" -- it does NOT try to answer "Never mind, talk about cats". |

#### Test 4: Rapid double-send on new conversation

**Setup**: Provider configured, start a new conversation (empty chat).

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Type "Hello" and tap Send | "Hello" appears as user bubble. |
| 2 | Immediately type "My name is Alice" and tap Send (before AI starts responding) | "My name is Alice" appears as a second user bubble. |
| 3 | Wait for AI response | AI responds to both messages -- its response acknowledges both the greeting and the name. |

#### Test 5: Stop and Send buttons are independent

**Setup**: Provider configured, chat screen open.

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Type "Hello" and tap Send | AI starts streaming. |
| 2 | Observe the input area | Stop button (spinning) is visible. Send button is also visible but disabled (input field is empty). |
| 3 | Type "Follow up" in the input field | Send button becomes enabled. Stop button is still visible and spinning. Both buttons are independently tappable. |
| 4 | Tap Stop (not Send) | AI stops. Stop button disappears. Send button remains visible and enabled (text is still in field). |
| 5 | Tap Send | "Follow up" is sent as a new message. AI responds. |

#### Test 6: No regression -- normal chat still works

**Setup**: Provider configured, chat screen open.

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Type "Hi" and tap Send | User bubble appears. AI streams response. |
| 2 | Wait for AI to finish | AI response completes. Stop button disappears. |
| 3 | Type "How are you?" and tap Send | User bubble appears. AI streams response. Normal back-and-forth works as before. |

### Edge Cases

- Send 5 messages in a row while AI is streaming (verify all 5 appear and AI eventually responds to all)
- Stop generation with no queued messages (verify: same behavior as current, no system note inserted)
- Send a message, AI errors out, send another message (verify: error message visible, new message processed)
- Very long queued message (10,000+ characters)
- Empty input during streaming (verify: send button stays disabled, tapping does nothing)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | TBD |
