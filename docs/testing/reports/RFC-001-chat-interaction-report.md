# Test Report: RFC-001 — Chat Interaction

## Report Information

| Field | Value |
|-------|-------|
| RFC | RFC-001 |
| Commit | `bdea03c` |
| Date | 2026-02-27 |
| Tester | AI (OpenCode) |
| Status | PASS |

## Summary

RFC-001 implements the full chat interaction loop: SSE streaming from OpenAI, Anthropic (with thinking blocks), and Gemini; a multi-turn tool call loop in `SendMessageUseCase`; and the complete Gemini-style chat UI with message bubbles, tool call cards, thinking blocks, streaming cursor, and agent selector. All feasible testing layers were executed.

| Layer | Step | Result | Notes |
|-------|------|--------|-------|
| 1A | JVM Unit Tests | PASS | 245 tests, 0 failures |
| 1B | Instrumented DAO Tests | PASS | 48 tests, 0 failures |
| 1C | Roborazzi Screenshot Tests | PASS | 8 new screenshots |
| 2 | adb Visual Flows | SKIP | API keys not available in CI; see note below |

## Layer 1A: JVM Unit Tests

**Command:** `./gradlew test`

**Result:** PASS

**Test count:** 245 tests, 0 failures

Notable changes:
- `OpenAiAdapterTest.sendMessageStream returns a Flow without throwing` — replaces the obsolete "throws NotImplementedError" test. The method now returns a `Flow<StreamEvent>` and this test verifies it does not throw.
- All existing adapter tests (listModels, testConnection, generateSimpleCompletion) continue to pass.

## Layer 1B: Instrumented Tests

**Command:** `ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest`

**Result:** PASS

**Device:** Medium_Phone_API_36.1 (AVD) — emulator-5554

**Test count:** 48 tests, 0 failures

No new instrumented tests were added for RFC-001 (chat logic is unit-testable at the adapter level; DAO layer unchanged).

## Layer 1C: Roborazzi Screenshot Tests

**Commands:**
```bash
./gradlew recordRoborazziDebug
./gradlew verifyRoborazziDebug
```

**Result:** PASS

New screenshots recorded in `AgentScreenshotTest` (shared file for RFC-001 and RFC-002):

### ChatTopBar

<img src="screenshots/RFC-001_ChatTopBar.png" width="250">

Visual check: Hamburger menu icon on left; "General Assistant" title with dropdown arrow in center (gold/amber text); Settings gear icon on right.

### ChatInput — empty

<img src="screenshots/RFC-001_ChatInput_empty.png" width="250">

Visual check: Outlined text field with "Message" placeholder; Send icon button is disabled (greyed out) when no text.

### ChatInput — with text

<img src="screenshots/RFC-001_ChatInput_withText.png" width="250">

Visual check: Text "Explain how coroutines work" fills the field; Send icon button is enabled (colored).

### ChatEmptyState

<img src="screenshots/RFC-001_ChatEmptyState.png" width="250">

Visual check: Centered empty state placeholder shown when no messages exist.

### MessageList — conversation

<img src="screenshots/RFC-001_ChatMessageList_conversation.png" width="250">

Visual check: User messages appear as gold/amber rounded bubbles on the right; AI response appears as a surface-colored card on the left with markdown rendering (**bold** text correct); model ID "gpt-4o" shown below AI message with copy/regenerate icons.

### MessageList — with tool call

<img src="screenshots/RFC-001_ChatMessageList_toolCall.png" width="250">

Visual check: User message bubble, then a TOOL_CALL card showing tool name "get_current_time", then a TOOL_RESULT card showing the output, then final AI response.

### MessageList — streaming

<img src="screenshots/RFC-001_ChatMessageList_streaming.png" width="250">

Visual check: User message bubble, then the streaming AI response text appears in an AI bubble (streaming cursor visible).

### MessageList — active tool call

<img src="screenshots/RFC-001_ChatMessageList_activeToolCall.png" width="250">

Visual check: User message bubble, then an active TOOL_CALL card with PENDING status for "read_file" with arguments shown.

## Layer 2: adb Visual Verification

**Result:** SKIP

**Reason:** Layer 2 requires API keys set as environment variables (`ONECLAW_ANTHROPIC_API_KEY`, `ONECLAW_OPENAI_API_KEY`, `ONECLAW_GEMINI_API_KEY`). These are not available in the current session. Additionally, Layer 2 should be performed as a complete integration test covering Provider → Agent → Chat flows together. Recommended to run manually after setting up API keys on the emulator.

**Manual testing steps to perform:**
1. Launch app, complete Setup with a real API key
2. Start a new conversation
3. Send a message and verify streaming response appears
4. Verify tool calls (e.g., "What time is it?") trigger tool call cards and results
5. Switch agent via the title dropdown
6. Verify session is saved and appears in the drawer

## Issues Found

No issues found.

## Change History

| Date | Change |
|------|--------|
| 2026-02-27 | Initial report |
