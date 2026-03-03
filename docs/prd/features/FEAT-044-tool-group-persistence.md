# Tool Group Session Persistence

## Feature Information
- **Feature ID**: FEAT-044
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P0 (Must Have)
- **Owner**: TBD
- **Related RFC**: [RFC-044 (Tool Group Session Persistence)](../../rfc/features/RFC-044-tool-group-persistence.md)
- **Related Feature**: [FEAT-040 (Tool Group Routing)](FEAT-040-tool-group-routing.md)

## User Story

**As** a user of OneClaw,
**I want** tool groups that I loaded earlier in a conversation to remain available in subsequent messages,
**so that** I can use tools like Gmail, Drive, and Calendar across multiple messages without re-loading them each time.

### Typical Scenarios
1. User sends "check my email" -- the AI loads the Gmail tool group and lists emails. User then sends "delete the spam ones" -- the AI can use `gmail_trash` directly without re-loading Gmail tools.
2. User sends "list my Drive files" -- AI loads Google Drive group. User later sends "now check my calendar for tomorrow" -- AI loads Calendar group. Both Drive and Calendar tools remain available for the rest of the session.
3. User sends `/clear` via the Messaging Bridge, starting a new session. The new session starts fresh with no tool groups pre-loaded.
4. User asks a question via Telegram. The AI loads Gmail tools and succeeds. User sends a follow-up Gmail question -- the AI can immediately use Gmail tools without the "Tool not available for this agent" error.

## Feature Description

### Overview
RFC-040 introduced tool group routing, where tool schemas are loaded on-demand via `load_tool_group` to reduce token usage. However, loaded tool groups only persist within a single `SendMessageUseCase.execute()` call. When the user sends a new message, all loaded groups are lost, causing "Tool not available for this agent" errors.

This feature makes tool groups persist across conversation turns within a session by scanning the conversation history for previously successful `load_tool_group` calls and pre-loading those groups at the start of each `execute()` invocation.

### Problem

When `SendMessageUseCase.execute()` is called for each new user message, it reinitializes the active tool list:

```kotlin
val loadedGroupNames = mutableSetOf<String>()
val activeToolDefs = toolRegistry.getCoreToolDefinitions().toMutableList()
```

This means:
- **Turn 1**: User asks to check email. AI loads Gmail tools, `gmail_search` succeeds.
- **Turn 2**: User asks a follow-up Gmail question. `execute()` is called again, `activeToolDefs` resets to core-only. AI calls `gmail_search` directly (seeing it succeeded in history) -- fails with "Tool not available for this agent".

The AI sometimes recovers by re-loading the group, but this wastes an API round-trip. Worse, sometimes the AI gives up or fires dozens of failing tool calls (observed: 61 consecutive `gmail_trash` ERROR calls in a single turn).

### Solution

At the start of each `SendMessageUseCase.execute()`, scan the session's message history for `TOOL_CALL` messages where `tool_name = "load_tool_group"` and `tool_status = "SUCCESS"`. Extract the group names from those calls and pre-load them into `activeToolDefs`.

This is a minimal, history-based approach:
- No new database columns or persistence layer needed
- No new API surface -- uses existing message history
- Tool groups loaded in previous turns are automatically restored
- New sessions start clean (no history = no pre-loaded groups)

### Acceptance Criteria

1. After loading a tool group in one message, the tools remain available in subsequent messages within the same session.
2. No "Tool not available for this agent" errors for tools that were previously loaded in the session.
3. New sessions (including after `/clear`) start with only core tools -- no groups pre-loaded.
4. The AI does not need to call `load_tool_group` again for a group that was already loaded earlier in the session.
5. All existing unit tests continue to pass.

### Out of Scope
- Cross-session group persistence (groups do not carry over between different sessions)
- Automatic group unloading based on inactivity
- `unload_tool_group` command (can be added later if needed)
- UI for viewing which tool groups are currently loaded
