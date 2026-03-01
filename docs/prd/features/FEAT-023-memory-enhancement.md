# Memory System Enhancement

## Feature Information
- **Feature ID**: FEAT-023
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-023 (Memory System Enhancement)](../../rfc/features/RFC-023-memory-enhancement.md)
- **Extends**: [FEAT-013 (Memory System)](FEAT-013-memory.md)

## User Story

**As** a user of OneClawShadow,
**I want to** have the memory system trigger correctly when I switch sessions or return to the app on a new day, and I want the AI to proactively save important information to long-term memory during conversations,
**so that** my conversation context is reliably captured and I can build a persistent knowledge base without relying solely on automatic daily log summarization.

### Typical Scenarios
1. User has a long conversation in Session A, then navigates to Session B from the session list. The daily log for Session A should be flushed automatically -- currently it is not.
2. User leaves the app overnight and reopens it the next morning. The day-change trigger should flush the previous day's daily log for the active session -- currently it does not.
3. User is chatting with the AI and says "Remember that my project uses PostgreSQL 16 and runs on Ubuntu 22.04." The AI should be able to save this to long-term memory (MEMORY.md) immediately -- currently there is no tool to do this.
4. User asks the AI to "Save a summary of what we discussed today to memory." The AI uses the `save_memory` tool to write the summary directly to MEMORY.md.

## Feature Description

### Overview
This feature addresses two gaps in the existing Memory System (FEAT-013):

1. **Wire Missing Memory Triggers** -- Only 1 of 5 trigger methods in `MemoryTriggerManager` is actually called. Wire `onSessionSwitch` and `onDayChange` to their corresponding lifecycle events. The remaining two (`onSessionEnd`, `onPreCompaction`) are deferred.
2. **`save_memory` Built-in Tool** -- A new tool that allows the AI to proactively write content to MEMORY.md during a chat conversation. This complements the existing read-only memory injection (system prompt) with write capability.

### Detailed Specification

#### 1. Wire `onSessionSwitch` Trigger

Current state: `MemoryTriggerManager.onSessionSwitch(previousSessionId)` exists but is never called. When the user switches sessions, the daily log for the previous session is not flushed.

Target state: When `ChatViewModel.initialize(sessionId)` is called with a new session ID and there is a previous active session, call `memoryTriggerManager.onSessionSwitch(previousSessionId)` before loading the new session.

Implementation:
- Add `MemoryTriggerManager?` as an optional constructor parameter to `ChatViewModel` (nullable for backward compatibility)
- In `initialize()`, before loading the new session, capture the current `_uiState.value.sessionId` as `previousSessionId`
- If `previousSessionId` is non-null and differs from the new `sessionId`, call `memoryTriggerManager?.onSessionSwitch(previousSessionId)`
- Update `FeatureModule.kt` DI registration to inject `MemoryTriggerManager`

#### 2. Wire `onDayChange` Trigger

Current state: `MemoryTriggerManager.onDayChange(activeSessionId)` exists but is never called. When the app is used across midnight, the day-change trigger does not fire.

Target state: When the app returns to the foreground (via `ProcessLifecycleOwner.onStart`), compare the current date with a stored "last active date" in SharedPreferences. If the date has changed, flush the active session's daily log.

Implementation:
- In `OneclawApplication.kt`, add an `onStart` handler alongside the existing `onStop` handler in the ProcessLifecycleOwner observer
- Use `SharedPreferences("memory_trigger_prefs")` to store the last active date as `YYYY-MM-DD` string
- On `onStart`: compare stored date with current date. If different, call a new `MemoryTriggerManager.onDayChangeForActiveSession()` method and update the stored date
- `onDayChangeForActiveSession()` resolves the active session internally using `sessionRepository.getActiveSession()`, following the same pattern as `flushActiveSession()`

#### 3. `onSessionEnd` Trigger (Deferred)

No explicit "close session" action exists in the app. Session switch already covers the main use case of flushing the daily log when leaving a session. This trigger will be wired when an explicit session close action is added.

#### 4. `onPreCompaction` Trigger (Deferred)

This trigger is designed for integration with FEAT-011 Auto Compact. It will be wired when the compaction system calls `onPreCompaction` before compressing message history.

#### 5. `save_memory` Built-in Tool

A new built-in tool registered in the `ToolRegistry` that enables the AI to write content to MEMORY.md during a chat conversation.

**Tool Definition:**
- **Name**: `save_memory`
- **Description**: "Save important information to long-term memory (MEMORY.md). Use this when the user asks you to remember something, or when you identify critical information that should persist across conversations."
- **Parameters**:
  - `content` (string, required) -- The text to append to MEMORY.md. Max 5,000 characters.
- **Returns**: Success message confirming the content was saved, or an error message.

**User Interaction Flow:**
```
1. User is in a chat conversation
2. User says: "Remember that my API uses JWT tokens with RS256 signing"
3. The AI decides to use the save_memory tool, composing an appropriate
   memory entry from the user's statement
4. The tool appends the content to MEMORY.md and indexes it for search
5. The tool returns a success message to the AI
6. The AI confirms to the user that the information has been saved
7. In future conversations, this information appears in the system prompt
   via memory injection
```

The AI is responsible for deciding what and how to save. It should format the content appropriately (e.g., adding context, using clear language) before saving. The tool does not deduplicate with daily log entries -- they serve different purposes (proactive vs. automatic).

## Acceptance Criteria

Must pass (all required):
- [ ] When user switches from Session A to Session B, the daily log for Session A is flushed
- [ ] `onSessionSwitch` is NOT called when `initialize(null)` is called (new conversation reset)
- [ ] `onSessionSwitch` is NOT called when the previous session ID is the same as the new one
- [ ] When the app returns to foreground on a new day, the active session's daily log is flushed
- [ ] Day-change detection uses SharedPreferences to persist the last active date
- [ ] Day-change trigger does NOT fire on the first app launch of the day if the stored date matches
- [ ] `save_memory` tool is registered in the ToolRegistry and available to all agents
- [ ] AI can invoke `save_memory` tool during a chat conversation to save content to MEMORY.md
- [ ] `save_memory` tool validates that `content` is non-empty
- [ ] `save_memory` tool validates that `content` is within the 5,000 character limit
- [ ] `save_memory` tool returns a success message on successful save
- [ ] `save_memory` tool returns an error message on failure
- [ ] Saved content is indexed for hybrid search (available via `searchMemory()`)
- [ ] Saved content appears in future system prompt memory injection

Optional (nice to have):
- [ ] `save_memory` tool automatically adds a timestamp header to saved content
- [ ] Memory deduplication or conflict detection with existing entries

## Feature Boundary

### Included
- Wire `onSessionSwitch` trigger in `ChatViewModel.initialize()`
- Wire `onDayChange` trigger via `ProcessLifecycleOwner.onStart` in `OneclawApplication`
- New `onDayChangeForActiveSession()` method in `MemoryTriggerManager`
- New `saveToLongTermMemory(content)` method in `MemoryManager`
- New `SaveMemoryTool` built-in tool in `tool/builtin/`
- DI updates in `FeatureModule.kt` and `ToolModule.kt`

### Not Included
- Wiring `onSessionEnd` (no explicit close action exists; deferred)
- Wiring `onPreCompaction` (deferred to FEAT-011 integration)
- Memory editing or deletion tool (future enhancement)
- UI for viewing/editing MEMORY.md from the memory screen (already exists in FEAT-013)
- Automatic AI-initiated memory saves without user request (AI decides when to use the tool, but only in response to user conversation)
- Memory deduplication or merging with daily log entries

## Business Rules

### Session Switch Trigger Rules
1. Only fires when transitioning from one existing session to a different session
2. Does NOT fire on initial app load (`initialize(null)`)
3. Does NOT fire when reloading the same session
4. The flush is fire-and-forget (non-blocking for the session switch)
5. Concurrent flush protection is handled by the existing Mutex in `MemoryTriggerManager`

### Day-Change Trigger Rules
1. Date comparison uses the device's local date (`LocalDate.now()`)
2. The stored date is updated AFTER triggering the flush (to avoid missing the trigger on crash)
3. On first-ever app launch, the current date is stored without triggering a flush
4. The trigger fires at most once per `onStart` callback (no repeated checks during a single foreground period)

### `save_memory` Tool Rules
1. Content is appended to the end of MEMORY.md (never overwrites existing content)
2. Content max length is 5,000 characters (longer content is rejected with an error)
3. Empty or blank content is rejected with a validation error
4. The tool does NOT check for duplicates -- the AI should avoid redundant saves
5. Saved content is indexed immediately for search availability
6. No special formatting is enforced -- the AI is responsible for formatting the content appropriately

## Dependencies

### Depends On
- **FEAT-013 (Memory System)**: This feature extends FEAT-013
- **FEAT-001 (Chat)**: `ChatViewModel` is modified for session switch trigger
- **FEAT-004 (Tool System)**: The `save_memory` tool integrates with the tool system

### Depended On By
- None currently

## Error Handling

### Error Scenarios

1. **Session switch flush fails (I/O error)**
   - Handling: Error is logged via `Log.w()`, session switch proceeds normally
   - User impact: None (non-blocking; flush retried on next trigger)

2. **Day-change flush fails (I/O error)**
   - Handling: Error is logged, stored date is still updated
   - User impact: None (daily log may be incomplete for that day)

3. **`save_memory` tool called with empty content**
   - Tool returns: `ToolResult.error("validation_error", "Parameter 'content' is required and must be non-empty.")`
   - AI reports the error to the user in the chat

4. **`save_memory` tool called with content exceeding 5,000 characters**
   - Tool returns: `ToolResult.error("validation_error", "Parameter 'content' must be 5,000 characters or less.")`
   - AI reports the limit to the user and may offer to split the content

5. **`save_memory` tool fails to write (file I/O error)**
   - Tool returns: `ToolResult.error("save_failed", "Failed to save memory: ...")`
   - AI reports the error to the user

6. **`save_memory` tool fails to index (embedding error)**
   - Handling: Content is still saved to MEMORY.md; indexing failure is logged but does not cause tool failure
   - User impact: Content is saved but may not appear in search results until index rebuild

## Test Points

### Functional Tests
- Verify `onSessionSwitch` is called when switching from Session A to Session B in ChatViewModel
- Verify `onSessionSwitch` is NOT called when `initialize(null)` is called
- Verify `onSessionSwitch` is NOT called when reloading the same session ID
- Verify day-change detection fires when stored date differs from current date
- Verify day-change detection does NOT fire when dates match
- Verify `save_memory` tool is registered and listed in available tools
- Verify `save_memory` tool appends content to MEMORY.md
- Verify `save_memory` tool indexes saved content for search
- Verify `save_memory` tool returns error for empty content
- Verify `save_memory` tool returns error for content exceeding 5,000 chars

### Edge Cases
- Switch session when `MemoryTriggerManager` injection is null (backward compatibility)
- Day-change on first ever app launch (no stored date)
- Day-change when no active session exists
- `save_memory` with content exactly at 5,000 characters
- `save_memory` when MEMORY.md does not yet exist (first save)
- `save_memory` with unicode/multi-byte characters
- Multiple rapid session switches (Mutex contention)
- App backgrounded and foregrounded multiple times within the same day

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
