# Manual Test Guide — OneClaw

This document is the cumulative, always-up-to-date guide for manually testing the OneClaw app. It describes every user-facing flow that exists in the current implementation, and how to verify it on a real device or emulator.

**How to use this guide:**
- Before any release, execute all flows in this guide from a fresh install.
- Update this guide whenever an RFC adds, changes, or removes user-facing behavior.
- This guide reflects the current state of the app, not planned future features.

## Document Information

| Field | Value |
|-------|-------|
| Last updated | 2026-02-27 |
| App version | 0.1.0 |
| Last RFC implemented | RFC-001 (Chat Interaction) + RFC-002 (Agent Management) |
| Status | Full app implemented: Setup, Provider management, Tool system, Session management, Agent management, and Chat with streaming |

---

## Setup

### Prerequisites

- Android device or emulator (API 26+)
- App installed: `./gradlew installDebug` or `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- For provider connection tests: API key for at least one provider (Anthropic, OpenAI, or Gemini)

### Fresh Install

```bash
adb shell pm clear com.oneclaw.shadow
adb shell am start -n com.oneclaw.shadow/.MainActivity
```

---

## Current App State

As of the last implemented RFC (RFC-001 + RFC-002), the app supports:
- First-launch setup flow (choose provider, enter API key, select default model)
- Provider management (list, detail, API key, connection test, model list)
- Settings screen with "Manage Agents" entry point
- Tool system (backend + per-agent tool configuration in AgentDetailScreen)
- Session management (drawer UI with new/switch/delete/rename sessions)
- Agent management (list, create, edit, clone, delete custom agents; view built-in agents)
- Chat with streaming: SSE streaming from OpenAI/Anthropic/Gemini, tool call loop, thinking blocks, message history

**All planned RFCs have been implemented.**

---

## Flow 1: First Launch — Setup Screen

**When:** Fresh install, `has_completed_setup` flag is false.

**Expected behavior:** App launches directly to the Setup screen (3-step wizard).

### Step 1.1: Launch app fresh

```bash
adb shell pm clear com.oneclaw.shadow
adb shell am start -n com.oneclaw.shadow/.MainActivity
```

**Verify:**
- Setup screen is displayed (not chat screen)
- Step 1 "Choose Provider" is shown
- Three provider options are visible: OpenAI, Anthropic, Google Gemini
- "Skip for now" button is visible

### Step 1.2: Skip setup

On the setup screen, tap "Skip for now".

**Verify:**
- App navigates to the Chat screen
- Chat screen shows a placeholder/greeting
- `has_completed_setup` is now true (relaunching the app should go directly to Chat, not Setup)

### Step 1.3: Complete setup — choose provider

From a fresh install, tap "Anthropic" (or another provider).

**Verify:**
- Step 2 "Enter API Key" is shown
- An API key input field is visible
- "Test Connection" button is visible
- "Skip for now" button is still visible

### Step 1.4: Enter API key and test connection

Enter a valid API key in the field. Tap "Test Connection".

**Verify:**
- Loading indicator appears while testing
- On success: a green success indicator or "Connected" message appears
- On failure: an error message appears (e.g., "Invalid API key" or "Network error")

### Step 1.5: Continue to model selection

After a successful connection test, proceed to Step 3.

**Verify:**
- Step 3 "Select Default Model" is shown
- A list of models from the provider is displayed
- Each model has a name and can be selected
- "Get Started" button is enabled after selecting a model

### Step 1.6: Complete setup

Select a model and tap "Get Started".

**Verify:**
- App navigates to the Chat screen
- `has_completed_setup` is true (app goes to Chat on next launch)

---

## Flow 2: Settings Screen

**Access:** From the Chat screen, tap the Settings gear icon (top-right).

### Step 2.1: Open Settings

**Verify:**
- Settings screen opens
- "Manage Providers" list item is visible with subtitle "Add API keys, configure models"
- "Manage Agents" list item is visible with subtitle "Create and configure agents"
- Back navigation works (arrow in top-left)

### Step 2.2: Navigate to Provider List

Tap "Manage Providers".

**Verify:**
- Provider list screen opens
- Three built-in providers are shown: OpenAI, Anthropic, Google Gemini
- Each provider row shows: name, model count, and a status chip
- Status chip is one of: "Connected" (green/purple), "Not configured" (grey), "Disconnected" (red/pink)
- "+" button in top-right is visible (for future custom provider support)

### Step 2.3: Navigate to Agent List

Go back to Settings, then tap "Manage Agents".

**Verify:**
- Agent list screen opens (see Flow 7 for detailed agent management flows)

---

## Flow 3: Provider Detail — API Key Management

**Access:** Settings → Manage Providers → tap a provider (e.g., Anthropic).

### Step 3.1: Open provider detail

**Verify:**
- Provider detail screen opens
- Provider name in top bar
- API key section: masked input field (shows "••••" if key exists, empty if not)
- Eye icon to toggle key visibility
- Save button
- "Test Connection" button
- Model list section showing available models
- Each model has: name, source label (PRESET or MANUAL), star icon for default model

### Step 3.2: Enter and save API key

Tap the API key field. Enter a key. Tap Save.

**Verify:**
- Key is saved (masked display updates)
- No plaintext key visible in UI after save
- Success toast or message appears

### Step 3.3: Toggle API key visibility

Tap the eye icon next to the API key field.

**Verify:**
- Key is revealed in plaintext
- Eye icon changes to "hide" variant
- Tap again: key is masked again

### Step 3.4: Test connection

With a valid API key entered, tap "Test Connection".

**Verify:**
- Loading indicator appears
- On success: "Connected" result shown (green indicator or success card)
- On failure: error message with type (auth error, network error, timeout)

### Step 3.5: View model list

**Verify:**
- Models are listed under the "Available Models" section
- Each model shows: ID, source (PRESET or MANUAL), star icon if it is the global default

### Step 3.6: Set a model as default

Tap the star icon next to a non-default model.

**Verify:**
- That model's star becomes filled
- Previous default model's star becomes unfilled
- Success message appears

### Step 3.7: Refresh models from API

Tap "Refresh Models" button.

**Verify:**
- Loading indicator appears
- Model list updates (new models fetched from the provider's API)
- Success or error message shown

---

## Flow 4: Provider Detail — Manual Model

**Access:** Settings → Manage Providers → [any provider] → Add Manual Model.

### Step 4.1: Add a manual model

Tap "Add Manual Model" button. Enter a model ID (e.g., `gpt-4-turbo`). Confirm.

**Verify:**
- Model appears in the list with source label "MANUAL"
- Star icon is available to set it as default

### Step 4.2: Delete a manual model

Tap the delete icon next to a MANUAL model. Confirm deletion.

**Verify:**
- Model is removed from the list
- PRESET models cannot be deleted (no delete icon shown)

---

## Flow 5: Relaunch — First Launch Detection

**Verify the app remembers setup state.**

### Step 5.1: After completing setup, close and relaunch

```bash
adb shell am force-stop com.oneclaw.shadow
adb shell am start -n com.oneclaw.shadow/.MainActivity
```

**Verify:**
- App launches directly to Chat screen (not Setup)
- Previously entered API keys are still configured (check Settings → Providers)

---

## Flow 6: Chat — Sending Messages and Streaming

**Prerequisites:** At least one provider configured with a valid API key (see Flow 3).

### Step 6.1: Navigate to Chat

From any screen, navigate to the Chat screen (main screen after setup).

**Verify:**
- Chat screen is visible
- Empty state placeholder is shown when no messages exist
- Input text field is visible at the bottom
- Agent name is shown in the top bar (default: "General Assistant")
- Settings gear icon is in the top-right
- Hamburger/drawer icon is in the top-left

### Step 6.2: Keyboard does not push top bar off-screen

Tap the message input field to open the soft keyboard.

**Verify:**
- The soft keyboard appears above the input field
- **The top app bar remains fully visible** — the hamburger icon (top-left) and settings gear (top-right) must not be hidden or pushed off-screen
- The message list is still visible between the top bar and the input field
- If the top bar is pushed off-screen by the keyboard, this is a bug

### Step 6.3: Send a message

Type a message in the input field (e.g., "Hello, what can you do?"). Tap the Send button.

**Verify:**
- User message bubble appears on the right (gold/amber background)
- Send button changes to Stop button while streaming
- AI response appears on the left, text streams in progressively
- After streaming completes, model ID label appears below the AI message
- Copy and Regenerate icons appear below the AI message

### Step 6.4: Stop generation mid-stream

While the AI is streaming, tap the Stop button.

**Verify:**
- Streaming stops immediately
- Partial response remains visible
- **Stop button switches back to the send button** — if the stop button remains visible after tapping, this is a bug (UI stuck in streaming state)
- Input field becomes active and a new message can be sent

### Step 6.5: Copy an AI message

Tap the copy icon below a completed AI message.

**Verify:**
- The message content is copied to clipboard (verify by pasting elsewhere)

### Step 6.6: Regenerate response

Tap the Regenerate icon below an AI message.

**Verify:**
- AI generates a new response to the same conversation
- New response streams in

### Step 6.7: Session drawer — navigation

Tap the hamburger icon (top-left).

**Verify:**
- Drawer opens
- "New Conversation" button is at the top
- Any saved sessions are listed with: title, message preview, relative timestamp
- Tapping a session switches to that conversation
- Long-pressing enters selection mode (checkboxes + bulk-delete toolbar)
- Swiping left on a session reveals delete; undo snackbar appears after deletion

### Step 6.8: Start a new conversation

In the drawer, tap "New Conversation".

**Verify:**
- Drawer closes
- Chat screen resets to empty state
- Top bar shows current agent name

### Step 6.9: Tool call visualization (if applicable)

Send a message that triggers a tool, e.g., "What is the current time?".

**Verify:**
- A tool call card appears showing: tool name, status (PENDING → SUCCESS/FAILED), input arguments
- A tool result card appears after the tool completes, showing output and duration
- Final AI response references the tool result

---

## Flow 7: Agent Management

**Access:** Settings → Manage Agents.

### Step 7.1: View agent list

**Verify:**
- Agent list screen opens
- "BUILT-IN" section shows built-in agents (e.g., "General Assistant", "Code Helper")
- Built-in agents have a "Built-in" chip label
- Tool count is shown under each agent name
- "CUSTOM" section appears if any custom agents exist
- "No custom agents yet. Tap + to create one." hint when no custom agents

### Step 7.2: View a built-in agent

Tap a built-in agent (e.g., "General Assistant").

**Verify:**
- Agent detail screen opens with title = agent name (not "Edit Agent")
- Name, description, and system prompt fields are read-only
- Tool checkboxes are shown but disabled (cannot change built-in tools)
- "Preferred Model" section is shown (read-only)
- "Clone Agent" button is visible
- No "Save" button; no "Delete Agent" button (built-in agents cannot be deleted)

### Step 7.3: Clone a built-in agent

On a built-in agent's detail screen, tap "Clone Agent".

**Verify:**
- A new custom agent is created (copy of the built-in agent, name prefixed with "Copy of")
- Navigation returns to the agent list
- The cloned agent appears in the "CUSTOM" section

### Step 7.4: Create a custom agent

From the agent list, tap the "+" button.

**Verify:**
- Agent detail screen opens with title "Create Agent"
- Name field is empty and editable
- Description and system prompt fields are editable
- Tools can be toggled via checkboxes
- "Save" button appears in the top bar (disabled until name is filled)

Enter a name (e.g., "My Test Agent"), optionally edit other fields, tap "Save".

**Verify:**
- Navigation returns to agent list
- New agent appears in the "CUSTOM" section with correct name and tool count

### Step 7.5: Edit a custom agent

From the agent list, tap a custom agent.

**Verify:**
- Agent detail screen opens with title "Edit Agent"
- All fields are editable
- "Save" button is disabled until a change is made
- Make a change (e.g., update description) and tap "Save"
- Success snackbar or navigation back with updated values

### Step 7.6: Delete a custom agent

On a custom agent's detail screen, tap "Delete Agent".

**Verify:**
- Confirmation dialog appears: "Delete Agent? This agent will be permanently removed. Any sessions using this agent will switch to General Assistant."
- Tap "Cancel" — dialog dismisses, agent is not deleted
- Tap "Delete Agent" again, then confirm — agent is removed
- Navigation returns to agent list; the agent is no longer listed

### Step 7.7: Switch agent during chat

From the Chat screen, tap the agent name in the top bar.

**Verify:**
- Agent selector bottom sheet opens
- All available agents are listed (built-in and custom)
- Current agent is highlighted/checked
- Tap a different agent — sheet dismisses and top bar updates with new agent name
- New messages will use the selected agent

---

## Known Limitations (current state)

All planned RFCs are implemented. No known functional limitations.

---

## Change History

| Date | RFC | Changes |
|------|-----|---------|
| 2026-02-27 | RFC-003, RFC-004 | Initial guide — covers Setup, Settings, Provider management flows |
| 2026-02-27 | RFC-005 | Updated current state, Flow 6.2 session drawer detail, known limitations |
| 2026-02-27 | RFC-001, RFC-002 | Complete rewrite of Flow 6 (full Chat with streaming), added Flow 7 (Agent Management), updated app state and Settings step 2.3, removed all "not yet implemented" limitations |
