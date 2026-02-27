# UI Design Specification

## Document Information
- **Created**: 2026-02-27
- **Last Updated**: 2026-02-27
- **Status**: Draft
- **Reference Style**: Google Gemini App (Material 3, clean, spacious)

## Design Principles

1. **Clean and spacious**: Generous whitespace, no visual clutter. Content takes center stage.
2. **Minimal chrome**: Reduce visual noise from borders, dividers, and decorations. Use spacing and subtle backgrounds to create hierarchy.
3. **Warm and approachable**: Gold/amber accent color conveys warmth. The overall feel is friendly, not clinical.
4. **Consistent interaction patterns**: Same gestures and patterns across all screens. No surprises.
5. **Content-first**: UI elements serve the content (conversations, agent configs, etc.), not the other way around.

## Color & Theme

The app uses a Material 3 theme with warm gold/amber primary color. Theme files are exported from Material Theme Builder and stored in `/material-theme/`.

- **Primary**: `#6D5E0F` (warm gold/amber)
- **Tertiary**: Green accent
- **Variants**: Light, Dark, Medium Contrast, High Contrast
- **Dynamic Color**: Enabled on Android 12+ (follows user wallpaper); falls back to defined scheme on older devices

See `material-theme/ui/theme/Color.kt` for full color definitions.

## Typography

- **Font family**: Roboto (via Google Fonts provider)
- **Scale**: Standard Material 3 typography scale
- Display, Headline, Title, Body, Label levels as defined in Material 3

See `material-theme/ui/theme/Type.kt` for definitions.

## Shapes

- **Corner radius**: Material 3 defaults
  - Small components (chips, small cards): 8dp rounded
  - Medium components (cards, dialogs): 12dp rounded
  - Large components (sheets, large cards): 16dp rounded
  - Extra-large (input field pill shape): 28dp rounded

---

## Overall App Structure

### Navigation Model

```
+-----------------------------------------------+
|  [hamburger]  OneClawShadow         [settings] |  <- Top App Bar
|-----------------------------------------------|
|                                               |
|              Main Content Area                |
|         (Chat / Settings / etc.)              |
|                                               |
+-----------------------------------------------+
```

- **Left top**: Hamburger menu icon -- opens a Navigation Drawer from the left
- **Right top**: Settings gear icon -- navigates to Settings screen
- **No bottom navigation bar**: Single top-level entry via drawer
- **Navigation Drawer**: Contains session list and new-conversation button only

### Navigation Drawer (Session List)

The drawer is the primary way to access and manage sessions.

```
+----------------------------------+
| +------------------------------+ |
| |  [+]  New conversation       | |  <- New conversation button (top)
| +------------------------------+ |
|                                  |
|  Today                           |  <- Date group header (optional)
|  +----------------------------+  |
|  | Session title              |  |
|  | Last message preview...    |  |  <- Muted secondary text
|  | 2 min ago        [Agent]   |  |  <- Time + agent badge
|  +----------------------------+  |
|  +----------------------------+  |
|  | Another session            |  |
|  | Preview text here...       |  |
|  | Yesterday        [Agent]   |  |
|  +----------------------------+  |
|  +----------------------------+  |
|  | Older session              |  |
|  | ...                        |  |
|  | Feb 20            [Agent]  |  |
|  +----------------------------+  |
|                                  |
|  (Swipe left on item to delete)  |
|                                  |
+----------------------------------+
```

**Session list item contents:**
- **Title**: Primary text, single line, ellipsis overflow
- **Last message preview**: Secondary text, 1-2 lines, muted color
- **Timestamp**: Relative time ("2 min ago", "Yesterday", "Feb 20")
- **Agent badge**: Small chip/label showing current agent name

**Session list behaviors:**
- Sorted by last active time (most recent first)
- Swipe left on an item to delete (with Snackbar undo, ~5 seconds)
- Long-press to enter selection mode for batch delete
- Tap to open/resume that session
- "New conversation" button at the top creates a fresh chat

**Lazy session creation**: Tapping "New conversation" closes the drawer and shows an empty chat screen. No session is created in the database until the user sends the first message.

---

## Screen Specifications

### 1. Chat Screen (Main Screen)

This is the primary screen the user sees. It is the chat conversation interface.

#### Layout Structure

```
+-----------------------------------------------+
|  [hamburger] [General Assistant v]  [settings] |  <- Top App Bar
|-----------------------------------------------|
|                                               |
|                                               |
|           (empty state or messages)           |
|                                               |
|                                               |
|                                               |
|                                               |
|  +---+                                        |
|  |   | AI response text goes here. This       |
|  |ico| is left-aligned with no bubble          |
|  +---+ background. Can be multiple lines of   |
|        Markdown-rendered content.              |
|                                               |
|        [copy] [regenerate] [share]            |  <- Action row
|                                               |
|                        +--------------------+ |
|                        | User message text  | |  <- Right-aligned bubble
|                        +--------------------+ |
|                                               |
|-----------------------------------------------|
|  +---------------------------------------+    |
|  | [+]  Message...                  [=>] |    |  <- Input field (pill)
|  +---------------------------------------+    |
+-----------------------------------------------+
```

#### Top App Bar
- **Left**: Hamburger icon (opens drawer)
- **Center**: Current agent name with dropdown chevron -- tapping opens agent selector (bottom sheet or dropdown)
- **Right**: Settings gear icon

#### Empty State (New Conversation)
When there are no messages yet:

```
+-----------------------------------------------+
|  [hamburger] [General Assistant v]  [settings] |
|-----------------------------------------------|
|                                               |
|                                               |
|                                               |
|                                               |
|          How can I help you today?            |  <- Centered greeting
|                                               |
|                                               |
|                                               |
|                                               |
|-----------------------------------------------|
|  +---------------------------------------+    |
|  | [+]  Message...                  [=>] |    |
|  +---------------------------------------+    |
+-----------------------------------------------+
```

- Greeting text: "How can I help you today?" (or localized equivalent)
- Centered vertically in the message area
- Subdued text color (Material 3 `onSurfaceVariant`)
- No logo, no illustration, no suggestion chips -- just the simple greeting

#### Message Display

**User messages:**
- Right-aligned
- Background: `primaryContainer` color, rounded corners (18dp)
- Text color: `onPrimaryContainer`
- Padding: 12dp horizontal, 8dp vertical
- Max width: ~80% of screen width
- No avatar/icon

**AI responses:**
- Left-aligned, full width (no bubble, no background)
- Small AI icon on the left side (16dp, aligned to the first line of text)
- Text starts to the right of the icon
- Text color: `onSurface`
- Supports full Markdown rendering (bold, italic, lists, headings, inline code, code blocks)
- No background color -- visually distinguished from user messages by alignment and lack of bubble

**AI response action row:**
- Appears below each completed AI response
- Row of small icon buttons, left-aligned under the response text
- Icons: Copy, Regenerate, Share
- Icon size: 20dp, muted color (`onSurfaceVariant`), with 8dp spacing between icons
- Regenerate and Share are V1-optional (record as future if too much work)
- Tapping Copy copies the raw text (Markdown source, not rendered)

**Message spacing:**
- 16dp vertical space between messages
- Messages grouped visually by sender (consecutive messages from the same sender have reduced spacing: 4dp)

#### Thinking Block

Attached to an AI response message, displayed above the main response text.

```
+-------------------------------------------+
| [>] Thinking...                           |  <- Collapsed state
+-------------------------------------------+
  AI icon  The actual response text goes here
           after the thinking block.
           [copy] [regenerate] [share]
```

```
+-------------------------------------------+
| [v] Thinking                              |  <- Expanded state
|                                           |
|   The model's internal reasoning text     |
|   is displayed here in a lighter/muted    |
|   style with a subtle background...       |
|                                           |
+-------------------------------------------+
  AI icon  The actual response text goes here
           after the thinking block.
           [copy] [regenerate] [share]
```

- **Collapsed (default)**: Single line with chevron icon and "Thinking..." label
- **Expanded**: Full thinking text in a card with subtle `surfaceVariant` background
- **Text style**: `bodySmall`, color `onSurfaceVariant` (lighter than main response)
- Tap the header row to toggle expand/collapse

#### Tool Call Display

Tool calls appear inline in the conversation flow, between or within AI responses.

**Compact mode (default):**
```
  [tool-icon] Calling read_file... Done       <- Single line
```
- Tool icon (16dp) + tool name + status
- Status: "Calling..." (in progress) or "Done" (completed) or "Failed" (error)
- Tap to expand and see details (input/output)
- Subtle `surfaceVariant` background, rounded corners (8dp)

**Detailed mode:**
```
+-------------------------------------------+
| [tool-icon] read_file                     |
|                                           |
|  Input:                                   |
|    { "path": "/storage/notes.txt" }       |
|                                           |
|  Result:                                  |
|    "Contents of the file..."              |
|                                           |
|  Duration: 120ms                          |
+-------------------------------------------+
```
- Card with `surfaceVariant` background
- Tool name as header
- Input and Result sections, each collapsible
- Code-style rendering for JSON content (monospace font)
- Duration shown at the bottom

**Mode switching:**
- Toggle in the top app bar (or overflow menu): switch between compact/detailed globally
- Preference persisted in settings

**Tool call status indicators:**
- Pending/Executing: Subtle circular progress indicator next to tool name
- Success: Checkmark icon or "Done" text
- Error: Error icon in `error` color + error message
- Timeout: Clock icon + "Timed out" text

#### Streaming Indicator

While waiting for the first token from the AI:
```
  AI icon  [... pulsing dots ...]
```
- Three pulsing dots animation, left-aligned where the AI response will appear
- Replaced by actual text once the first token arrives

During streaming:
- Text renders progressively, character by character (or chunk by chunk)
- Cursor/caret indicator at the end of the streaming text (subtle blinking line)
- Auto-scroll follows the new content

#### Input Area

```
+-----------------------------------------------+
|  +---------------------------------------+    |
|  | [+]  Type a message...           [=>] |    |  <- Input field
|  +---------------------------------------+    |
+-----------------------------------------------+
```

The agent selector has been moved to the top app bar (see above). The input area is clean and focused on message composition only.

**Input field:**
- Pill shape (extra-large rounded corners, 28dp radius)
- Background: `surfaceContainerHigh` (slightly elevated from screen background)
- Left: Attachment/plus button (for future use; can be hidden in V1)
- Center: Text input with hint "Type a message..."
- Right: Send button
  - Enabled: `primary` color, filled circular icon
  - Disabled: `onSurfaceVariant` with reduced opacity (when input is empty or request in-flight)
- During generation: Send button transforms to Stop button (square icon in `error` color)
- Multi-line expansion: Input field grows vertically up to ~4 lines, then scrolls internally

**Agent selection (on top bar agent name tap):**

```
+-----------------------------------------------+
|         Select an Agent                       |
|-----------------------------------------------|
|  [check] General Assistant         Built-in   |
|          Writing Helper                       |
|          Code Assistant                       |
|          Data Analyst                         |
+-----------------------------------------------+
```

- Bottom sheet or popup menu
- Shows all available agents
- Current agent has a checkmark
- Built-in agents have a subtle badge
- Tapping an agent switches immediately
- A system message appears in the chat: "Switched to [Agent Name]"

#### Agent Switch Indicator (in Chat)

When the agent is switched mid-conversation, a system message appears:

```
        --- Switched to Writing Helper ---
```

- Centered, full-width
- Muted text color (`onSurfaceVariant`)
- Small font (`labelMedium`)
- Thin horizontal lines on either side of the text (divider style)
- This is a persisted SYSTEM message type in the database

#### Error Messages

Errors appear inline in the conversation:

```
  [!] Unable to connect. Check your network and try again.
      [Retry]
```

- Error icon (`error` color) on the left
- Error text in `onErrorContainer` color
- Background: `errorContainer` with rounded corners
- Optional "Retry" text button

#### Scroll Behavior
- Auto-scrolls to bottom on new messages and during streaming
- When user scrolls up during streaming, auto-scroll pauses
- A "scroll to bottom" FAB appears when scrolled away from bottom:
  ```
                                    [v]   <- Small circular FAB
  ```
  - Small circular button with down-arrow icon
  - Positioned bottom-right, above the input area
  - Tap to scroll to the latest message

---

### 2. Settings Screen

Accessed from the gear icon in the top-right of the chat screen.

#### Layout Structure

```
+-----------------------------------------------+
|  [<-]          Settings                       |
|-----------------------------------------------|
|                                               |
|  MODEL                                        |  <- Section header
|  +-------------------------------------------+|
|  | Manage Providers                  [>]     ||
|  +-------------------------------------------+|
|  | Default Model                             ||
|  | GPT-4o (OpenAI)                   [>]     ||
|  +-------------------------------------------+|
|                                               |
|  AGENTS                                       |
|  +-------------------------------------------+|
|  | Manage Agents                     [>]     ||
|  +-------------------------------------------+|
|                                               |
|  CHAT                                         |
|  +-------------------------------------------+|
|  | Tool call display     [Compact v]         ||
|  +-------------------------------------------+|
|                                               |
|  APPEARANCE                                   |
|  +-------------------------------------------+|
|  | Theme                 [System default v]  ||
|  +-------------------------------------------+|
|                                               |
|  DATA                                         |
|  +-------------------------------------------+|
|  | Sync & Backup                     [>]     ||
|  | Clear cache                               ||
|  +-------------------------------------------+|
|                                               |
|  ABOUT                                        |
|  +-------------------------------------------+|
|  | Version 1.0.0                             ||
|  | Open source licenses             [>]     ||
|  +-------------------------------------------+|
|                                               |
+-----------------------------------------------+
```

- **Navigation**: Back arrow returns to chat
- **Sections**: Standard Material 3 list with section headers
- **Manage Providers**: Navigates to a dedicated Provider List screen (see below)
- **Default Model**: Shows current default model name and provider; tap navigates to a model picker
- **Tappable items**: Navigate to detail screens (provider list, agent list, etc.)
- Settings page stays compact regardless of how many providers the user has configured

---

### 3. Provider List Screen

Accessed from Settings > Manage Providers.

```
+-----------------------------------------------+
|  [<-]        Providers                  [+]   |
|-----------------------------------------------|
|                                               |
|  +-------------------------------------------+|
|  | [O]  OpenAI                               ||
|  |      3 models         Connected           ||
|  +-------------------------------------------+|
|  | [A]  Anthropic                            ||
|  |      2 models         Connected           ||
|  +-------------------------------------------+|
|  | [G]  Google Gemini                        ||
|  |      Not configured                       ||
|  +-------------------------------------------+|
|                                               |
|  CUSTOM                                       |
|  +-------------------------------------------+|
|  | My Local Server                           ||
|  |      1 model          Connected           ||
|  +-------------------------------------------+|
|                                               |
+-----------------------------------------------+
```

- **[+] button**: Top-right action to add a custom provider
- **Pre-configured providers**: Always shown (OpenAI, Anthropic, Gemini), with status
- **Custom providers**: Listed in a separate section below
- **Each item shows**: Provider name, type icon, model count, connection status
- **Unconfigured providers**: Show "Not configured" in muted text
- **Tap**: Opens Provider Detail screen

### 4. Provider Detail Screen

Accessed from tapping a provider in the Provider List.

```
+-----------------------------------------------+
|  [<-]        OpenAI                           |
|-----------------------------------------------|
|                                               |
|  API Key                                      |
|  +-------------------------------------------+|
|  | sk-...abc1234                   [eye]     ||
|  +-------------------------------------------+|
|  [Test Connection]                            |
|                                               |
|  Connection: Connected (checked 2 min ago)    |  <- Status text
|                                               |
|  AVAILABLE MODELS                             |
|  +-------------------------------------------+|
|  | (*) gpt-4o              Dynamic    [star] ||  <- Default model
|  | ( ) gpt-4o-mini         Dynamic           ||
|  | ( ) o1                  Dynamic           ||
|  | ( ) o3-mini             Dynamic           ||
|  +-------------------------------------------+|
|  [Refresh Models]                             |
|                                               |
|  +-------------------------------------------+|
|  | Active                          [toggle]  ||
|  +-------------------------------------------+|
|                                               |
+-----------------------------------------------+
```

- **API key field**: Masked by default. Eye icon toggles visibility. Tap to edit.
- **Test Connection button**: Outlined button. Shows loading spinner during test, then result text below.
- **Model list**: Radio buttons or star icons for selecting default. Each model shows its source label (Dynamic/Preset/Manual).
- **Refresh Models button**: Text button, triggers re-fetch of model list.
- **Active toggle**: Enable/disable this provider.
- For pre-configured providers: name and URL are read-only. For custom: all fields editable.
- **Delete button**: At the bottom (for custom providers), with confirmation dialog.

---

### 5. Agent List Screen

Accessed from Settings > Manage Agents.

```
+-----------------------------------------------+
|  [<-]        Agents                     [+]   |
|-----------------------------------------------|
|                                               |
|  BUILT-IN                                     |
|  +-------------------------------------------+|
|  | General Assistant              [Built-in] ||
|  | A general-purpose helpful...              ||
|  +-------------------------------------------+|
|                                               |
|  CUSTOM                                       |
|  +-------------------------------------------+|
|  | Writing Helper                            ||
|  | Helps with writing and editing...         ||
|  +-------------------------------------------+|
|  | Code Assistant                            ||
|  | Specialized in coding tasks...            ||
|  +-------------------------------------------+|
|                                               |
+-----------------------------------------------+
```

- **Sections**: Built-in agents first, then custom agents
- **List items**: Agent name + description preview (1-2 lines)
- **Built-in badge**: Subtle label on built-in agents
- **[+] button**: Top-right action to create new agent
- **Tap**: Opens agent detail/edit screen
- **Swipe/long-press on custom agent**: Quick actions (delete, clone)

---

### 6. Agent Detail / Edit Screen

```
+-----------------------------------------------+
|  [<-]     Edit Agent              [Save]      |
|-----------------------------------------------|
|                                               |
|  Name                                         |
|  +-------------------------------------------+|
|  | Writing Helper                            ||
|  +-------------------------------------------+|
|                                               |
|  Description (optional)                       |
|  +-------------------------------------------+|
|  | Helps with writing and editing tasks      ||
|  +-------------------------------------------+|
|                                               |
|  System Prompt                                |
|  +-------------------------------------------+|
|  | You are a writing assistant. Help the     ||
|  | user improve their writing, suggest       ||
|  | edits, and provide feedback on style...   ||
|  |                                           ||
|  |                                           ||
|  +-------------------------------------------+|
|                                               |
|  TOOLS                                        |
|  +-------------------------------------------+|
|  | [x] get_current_time                      ||
|  | [x] read_file                             ||
|  | [ ] write_file                            ||
|  | [x] http_request                          ||
|  +-------------------------------------------+|
|                                               |
|  PREFERRED MODEL (optional)                   |
|  +-------------------------------------------+|
|  | Not set (uses global default)     [>]     ||
|  +-------------------------------------------+|
|                                               |
|  +-------------------------------------------+|
|  | [Clone]               [Delete]            ||
|  +-------------------------------------------+|
|                                               |
+-----------------------------------------------+
```

- **For built-in agents**: All fields read-only. Only "Clone" button visible (no Delete, no Save).
- **For custom agents**: All fields editable. Save, Clone, and Delete buttons visible.
- **System prompt field**: Large text area, expandable
- **Tool selection**: Checkbox list of all available tools
- **Preferred model**: Tap to open picker (shows all models from all active providers). "Not set" means global default is used.
- **Save button**: In top-right of app bar. Disabled until changes are made.

---

### 7. First-Time Setup Screen (Welcome Screen)

Shown once on first app launch when no provider is configured. This screen is **skippable** -- the user is not forced to configure a provider.

```
+-----------------------------------------------+
|                                               |
|                                               |
|          Welcome to OneClawShadow             |
|                                               |
|       Set up your AI provider to get          |
|               started.                        |
|                                               |
|  +-------------------------------------------+|
|  |  [O]  OpenAI                          [>] ||
|  +-------------------------------------------+|
|  |  [A]  Anthropic                       [>] ||
|  +-------------------------------------------+|
|  |  [G]  Google Gemini                   [>] ||
|  +-------------------------------------------+|
|  |  [+]  Custom Provider                 [>] ||
|  +-------------------------------------------+|
|                                               |
|              [Skip for now]                   |  <- Text button, navigates to chat
|                                               |
+-----------------------------------------------+
```

After selecting a provider:

```
+-----------------------------------------------+
|  [<-]        OpenAI Setup                     |
|-----------------------------------------------|
|                                               |
|  Enter your API key                           |
|  +-------------------------------------------+|
|  | sk-...                          [eye]     ||
|  +-------------------------------------------+|
|                                               |
|  [Test Connection]                            |
|                                               |
|  Connection successful.                       |
|  Found 8 available models.                    |
|                                               |
|  SELECT DEFAULT MODEL                         |
|  +-------------------------------------------+|
|  | (*) gpt-4o                                ||
|  | ( ) gpt-4o-mini                           ||
|  | ( ) o1                                    ||
|  +-------------------------------------------+|
|                                               |
|                     [Get Started]             |  <- Primary filled button
|                                               |
+-----------------------------------------------+
```

- Clean, focused layout
- Step-by-step: choose provider -> enter key -> test -> select model -> done
- "Get Started" navigates to the main chat screen
- **"Skip for now"**: Text button at the bottom. Navigates directly to the chat screen without configuring any provider. The welcome screen will not be shown again on subsequent launches.
- **Only shown once**: After the first launch (whether user completes setup or skips), subsequent app launches go directly to the chat screen.
- **No provider configured + user sends message**: An inline error appears in the chat area prompting the user to go to Settings to configure a provider.

---

## Component Specifications

### Snackbar (Undo Delete)

Used for session deletion undo.

```
+-----------------------------------------------+
|  Session deleted                     [Undo]   |
+-----------------------------------------------+
```

- Standard Material 3 Snackbar
- Duration: ~5 seconds
- Action button: "Undo"
- Appears at the bottom of the screen, above the input area if on chat screen

### Confirmation Dialog

Used for agent deletion, provider deletion, etc.

```
+-------------------------------------------+
|                                           |
|  Delete "Writing Helper"?                 |
|                                           |
|  This agent will be permanently removed.  |
|  Sessions using it will switch to         |
|  General Assistant.                       |
|                                           |
|              [Cancel]    [Delete]         |
+-------------------------------------------+
```

- Standard Material 3 AlertDialog
- Destructive action button in `error` color
- Cancel button as text button

### Loading States

- **Full screen loading**: Centered circular progress indicator (used rarely, only for initial data load)
- **Inline loading**: Small circular indicator next to the triggering element (e.g., next to "Test Connection" button)
- **Streaming loading**: Pulsing dots in the message area (see Streaming Indicator above)
- **Skeleton loading**: Not used in V1 (keep it simple)

### Toast / Status Messages

- Use Snackbar for temporary status messages (save confirmation, etc.)
- Duration: Short (~3 seconds) for confirmations, Long (~5 seconds) for actions with undo
- Position: Bottom of screen

---

## Spacing & Layout Constants

| Element | Value |
|---------|-------|
| Screen horizontal padding | 16dp |
| Message horizontal padding (within bubble) | 12dp |
| Message vertical padding (within bubble) | 8dp |
| Space between messages (different sender) | 16dp |
| Space between messages (same sender) | 4dp |
| User message max width | 80% of screen |
| AI response icon size | 16dp |
| AI response icon-to-text gap | 8dp |
| Action row icon size | 20dp |
| Action row icon spacing | 8dp |
| Input field height (minimum) | 48dp |
| Input field corner radius | 28dp |
| Card corner radius | 12dp |
| Tool call card corner radius | 8dp |
| Top app bar height | 64dp (Material 3 default) |
| Drawer width | 300dp (or 80% of screen, whichever is smaller) |
| Session list item vertical padding | 12dp |

---

## Animations & Transitions

### Screen Transitions
- **Forward navigation** (e.g., Settings -> Provider Detail): Slide in from right
- **Back navigation**: Slide out to right
- **Drawer**: Slide in from left with scrim overlay

### In-Screen Animations
- **New message appears**: Fade in + slight slide up (100ms)
- **Thinking block expand/collapse**: Height animation (200ms, ease-in-out)
- **Tool call expand/collapse**: Height animation (200ms, ease-in-out)
- **Scroll-to-bottom FAB**: Fade in/out (150ms)
- **Send button -> Stop button**: Cross-fade (150ms)
- **Streaming dots**: Three dots with staggered pulse animation (loop)
- **Snackbar**: Slide up from bottom (Material 3 default)
- **Session delete (swipe)**: Item slides off screen, list items animate to close gap

### Haptic Feedback (Optional, V1 nice-to-have)
- Send message: Light tap
- Stop generation: Medium tap

---

## Accessibility

- All interactive elements have minimum 48dp touch target
- Color contrast ratios meet WCAG 2.1 AA standard (Material 3 theme handles this)
- All icons have content descriptions for screen readers
- Message content is readable by TalkBack
- Font sizes respect system accessibility settings
- Drawer and bottom sheets are navigable via accessibility services

---

## Dark Mode

The app supports three theme modes:
1. **Light**: Light background, dark text
2. **Dark**: Dark background, light text
3. **System default**: Follow Android system setting

Material 3 theme files already include both light and dark color schemes. Dynamic Color on Android 12+ automatically adapts to both modes.

Key dark mode considerations:
- User message bubbles: Use `primaryContainer` (adapts automatically)
- AI response text: Use `onSurface` (adapts automatically)
- Tool call cards: Use `surfaceVariant` (adapts automatically)
- Error messages: Use `errorContainer` (adapts automatically)
- No custom dark-mode overrides needed if Material 3 theme is applied correctly

---

## Design Decisions Log

| Decision | Rationale |
|----------|-----------|
| Drawer for session list (not home screen) | Matches Gemini app pattern. Chat is the primary experience. |
| No bubble for AI responses | Cleaner look, matches Gemini. Visual distinction via alignment + icon. |
| Agent selector in top bar center | Replaces static agent name; avoids duplication between top bar and input area; keeps input area clean. |
| Settings on right, drawer on left | Clear spatial separation. Matches common Android patterns. |
| Simple text greeting for empty state | Minimal, not overwhelming. No suggestion chips (agent-dependent). |
| Lazy session creation | Avoids empty sessions in the list. Session exists only when it has content. |
| System message for agent switch | Preserves switch history. Clear visual break in conversation. |
| Regenerate/Share as V1-optional | Core value is in chat + tool calls. These are polish features. |

---

## Relationship to Other Documents

- **PRD documents** define WHAT each feature does (functional requirements)
- **This UI Design Spec** defines HOW the app LOOKS and FEELS (visual and interaction design)
- **RFC documents** define HOW each feature is BUILT (technical implementation)
- RFCs should reference this spec for UI-related implementation details

### Documents that need updates based on this spec:
- **FEAT-005 (Session Management)**: Update "session list as home screen" to "session list in drawer"
- **FEAT-001 (Chat Interaction)**: UI section can reference this spec for detailed layout
- **RFC-000 (Overall Architecture)**: Navigation structure needs update (session list is no longer a standalone route)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-27 | 0.1 | Initial version | - |
| 2026-02-27 | 0.2 | Settings page: collapsed provider list into "Manage Providers" entry; added dedicated Provider List screen (Section 3) | - |
| 2026-02-27 | 0.3 | Moved agent selector from chip above input to top app bar center; removed input area chip; simplified input area | - |
