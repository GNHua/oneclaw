# FEAT-053: Scheduled Task Execution Log

## Document Information
- **Feature ID**: FEAT-053
- **Status**: Implemented
- **Created**: 2026-03-06
- **Last Updated**: 2026-03-06

## Background

RFC-028 introduced scheduled task execution with a detail screen that shows execution history. Each history entry displayed a timestamp, a status badge, and an arrow icon — but tapping it navigated to the chat screen unconditionally, which was confusing because:

1. The chat screen opened without loading the associated conversation.
2. Failed executions have no associated session at all, so the arrow was hidden and the row was not tappable.
3. There was no way to see **why** an execution failed.

Users need a dedicated place to inspect what happened during an execution: its timing, its outcome, and — most importantly — the error message when something went wrong.

## Goals

1. Every execution history row is always tappable, regardless of whether a session exists.
2. Tapping a row opens a dedicated Execution Log screen that shows structured information about that single execution.
3. Failed executions display the error message prominently so the user can understand what went wrong.
4. Executions that produced a conversation offer a "View Conversation" button that opens the correct session in the chat screen.
5. Fix the pre-existing bug where navigating to a chat session via a route parameter did not load the session.

## Non-Goals

- Inline rendering of conversation messages inside the Execution Log screen.
- Retry or re-run actions from the Execution Log screen (already available via "Run Now" on the detail screen).
- Filtering or searching execution history.

## User Stories

**US-1: Understand a failure**
As a user, I want to tap a failed execution and immediately see the error message, so I know why the task did not complete.

**US-2: Read the conversation**
As a user, I want to tap "View Conversation" from an execution that succeeded and be taken directly to the chat session that was created for that run.

**US-3: Review timing**
As a user, I want to see when an execution started, when it finished, and how long it took, so I can understand the task's performance.

## Functional Requirements

### FR-1: Execution History Rows Always Tappable
All rows in the execution history list on the Scheduled Task Detail screen are tappable. The arrow icon is shown unconditionally. Tapping navigates to the Execution Log screen for that record.

### FR-2: Execution Log Screen
A new full-screen destination that displays:

- **Status header**: colored status label (Running / Completed successfully / Failed) and the task name.
- **Timing card**: started-at timestamp, completed-at timestamp (if available), and computed duration.
- **Error card** (failed executions only): a red card showing the `errorMessage`, or a fallback message if none was recorded.
- **"View Conversation" button** (only when `sessionId` is non-null): navigates to the chat screen and loads the associated session.

### FR-3: Chat Session Loading Fixed
When navigating to `Route.ChatSession`, the `sessionId` route argument is passed to `ChatScreen` as `initialSessionId`, which triggers `ChatViewModel.initialize()` via `LaunchedEffect`. This fixes the pre-existing bug where the chat screen appeared blank.

## Non-Functional Requirements

- The Execution Log screen loads its data from a new `getRecordById` repository method; the query is lightweight (primary key lookup).
- The screen must handle a missing record gracefully (show "Execution record not found").
