# Chat Input Redesign

## Feature Information
- **Feature ID**: FEAT-016
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: TBD

## User Story

**As** a user of OneClaw,
**I want to** have a modern, visually polished chat input area that matches the style of the Google Gemini app,
**so that** the experience of composing and sending messages feels premium, intuitive, and consistent with contemporary mobile AI chat interfaces.

### Typical Scenarios
1. User opens a conversation and sees a rounded, pill-shaped input field with a soft filled background instead of a sharp outlined border. The overall impression is modern and inviting.
2. User begins typing a message. The input field starts as a single line and smoothly expands in height as the text wraps, up to approximately 6 lines, giving the user enough room to compose longer messages without an abrupt layout shift.
3. User taps the Skill button on the bottom-left action row, opens the skill selection sheet, selects a skill, and the slash command is inserted into the input field -- all existing skill and slash-command functionality works as before.
4. User is chatting while a response is streaming. The stop button appears in the bottom-right action area. The user taps it to halt generation. The button reverts to the send button.
5. User types "/" as the first character. The slash command popup appears above the input area, just as it does today, with no change in behavior.

## Feature Description

### Overview
This feature is a visual redesign of the `ChatInput` composable in `ChatScreen.kt`. The current input area uses an `OutlinedTextField` wrapped in a `Surface` with tonal elevation, with action buttons arranged in a single horizontal row alongside the text field. The redesign replaces this with a Google Gemini-style input area: a filled, rounded container with the text field on top and an action button row below it. This is a purely cosmetic and layout change -- no data layer, ViewModel, or architecture changes are involved.

### Current Implementation

The current `ChatInput` composable has this structure:
- A `Surface` with `tonalElevation = 2.dp` wrapping a single `Row`
- Inside the `Row` (left to right): Skill button, `OutlinedTextField`, Stop button (conditional), Send button
- The `OutlinedTextField` uses `MaterialTheme.shapes.extraLarge` and `maxLines = 6`
- All elements are vertically aligned to `Alignment.Bottom`

### New Design: Gemini-Style Input

The redesigned input area uses a two-layer vertical layout inside a rounded container:

#### Layer 1: Text Field Area
- A `BasicTextField` (or a `TextField` with no border decoration) replaces the `OutlinedTextField`
- The background is a filled surface color (e.g., `surfaceContainerHigh` or `surfaceContainer`) instead of an outlined border
- The entire container has a large rounded corner radius (28dp) to create a pill/capsule shape
- Placeholder text ("Message or /skill") is left-aligned inside the field
- The text field starts at 1 visible line and auto-expands as the user types, up to a maximum of approximately 6 lines
- When the content exceeds the maximum height, the text field becomes scrollable

#### Layer 2: Bottom Action Row
- Positioned below the text field, inside the same rounded container
- Left side: Skill button (existing `AutoAwesome` icon), with space reserved for a future attachment button
- Right side: Send button (filled circle, primary color), or Stop button (when streaming)
- The action row has a smaller vertical padding than the text field area, keeping it visually compact
- A subtle horizontal divider or spacing separates the text field from the action row (visual separation, not a hard line)

#### Container
- The outer container replaces the current `Surface` with `tonalElevation`
- Background: `MaterialTheme.colorScheme.surfaceContainerHigh` (a filled, slightly elevated surface -- not a stroke outline)
- Shape: `RoundedCornerShape(28.dp)` -- a large radius that produces a capsule/pill shape when the input is short
- Horizontal margin: 12dp from screen edges
- Bottom padding: respects navigation bar insets and IME padding, same as current

### Visual Comparison

#### Before (Current)

```
+------------------------------------------------------------------+
| Surface (tonalElevation = 2dp, full width)                       |
|                                                                  |
|  [Skill]  +---[OutlinedTextField]---+  [Stop?] [Send]            |
|    btn    | Message or /skill       |   btn     btn              |
|           +-------------------------+                            |
|                                                                  |
+------------------------------------------------------------------+
```

Key characteristics:
- Single row layout: buttons and text field side by side
- `OutlinedTextField` with a visible border stroke
- Full-width surface, rectangular, with tonal elevation
- Buttons are squeezed beside the text field

#### After (Gemini Style)

```
   +------------------------------------------------------------+
   |  (surfaceContainerHigh, roundedCorner 28dp)                 |
   |                                                             |
   |   Message or /skill                                         |
   |   (auto-expanding text area, 1-6 lines)                     |
   |                                                             |
   |  -------  (subtle spacing / visual separator)  -------      |
   |                                                             |
   |   [Skill]  [Attach*]              [Stop?] [Send]            |
   |    btn      future                 btn     btn              |
   |                                                             |
   +------------------------------------------------------------+

   * Attach button is a placeholder for future functionality
```

Key characteristics:
- Two-layer vertical layout: text area above, action row below
- Filled background, no border stroke
- Large rounded corners (pill shape)
- Horizontal margins from screen edges (not full-width)
- Action buttons have more breathing room
- Visually softer and more modern

### Behavioral Requirements

All existing behavior must be preserved exactly:
1. **Text input**: Typing, clearing, text selection all work as before
2. **Send action**: Send button triggers `onSend`, disabled when text is blank or no provider configured
3. **Stop action**: Stop button appears during streaming, triggers `onStop`
4. **Skill button**: Triggers `onSkillClick` to open the skill selection bottom sheet
5. **Slash commands**: Typing "/" as the first character triggers the `SlashCommandPopup` above the input area
6. **FocusRequester**: The text field still accepts a `FocusRequester` for programmatic focus (e.g., after selecting a skill)
7. **IME padding**: The input area adjusts for the software keyboard (`imePadding()`)
8. **Navigation bar padding**: The input area respects the navigation bar insets (`navigationBarsPadding()`)
9. **Auto-expand**: The text field expands from 1 line to a maximum of 6 lines as the user types, then becomes scrollable

### Composable Signature

The `ChatInput` composable signature remains the same:

```kotlin
@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSkillClick: () -> Unit,
    isStreaming: Boolean,
    hasConfiguredProvider: Boolean,
    focusRequester: FocusRequester
)
```

No new parameters are needed. No ViewModel changes. No state changes. This is a layout-only change within the composable body.

### Theme Consistency

The redesigned input must use the app's existing Material 3 theme:
- **Primary color** (gold/amber `#6D5E0F`): Used for the send button container color
- **surfaceContainerHigh**: Used for the input area background
- **onSurface**: Used for input text color
- **onSurfaceVariant**: Used for placeholder text and icon tints
- **errorContainer**: Used for the stop button container (unchanged)
- All colors are sourced from `MaterialTheme.colorScheme`, not hardcoded

## Acceptance Criteria

Must pass (all required):
- [ ] The chat input area uses a filled background (surfaceContainerHigh or similar) instead of an outlined border
- [ ] The container has large rounded corners (28dp) producing a pill/capsule shape
- [ ] The layout is two-layer vertical: text field on top, action buttons on bottom
- [ ] The text field auto-expands from 1 line to approximately 6 lines as the user types
- [ ] The text field becomes scrollable when content exceeds the maximum height
- [ ] Placeholder text "Message or /skill" is displayed when the field is empty
- [ ] The Skill button (AutoAwesome icon) is positioned in the bottom-left action row
- [ ] The Send button is positioned in the bottom-right action row
- [ ] The Stop button (with CircularProgressIndicator) replaces/appears alongside the Send button during streaming
- [ ] The Send button is disabled when text is blank or no provider is configured
- [ ] The input area has horizontal margins from screen edges (not full-width)
- [ ] Typing "/" as the first character still triggers the SlashCommandPopup above the input
- [ ] FocusRequester integration works (programmatic focus after skill selection)
- [ ] IME padding and navigation bar padding work correctly
- [ ] The design uses the app's Material 3 theme colors (gold/amber accent), no hardcoded colors
- [ ] The ChatInput composable signature (parameters) is unchanged
- [ ] All existing unit tests and screenshot tests pass without modification (or are updated to match new visuals)

Optional (nice to have for V1):
- [ ] Subtle animation when the text field expands/collapses in height
- [ ] A placeholder area for a future attachment button in the action row
- [ ] Slightly different corner radius when the input is multi-line vs single-line (dynamic shape)
- [ ] Haptic feedback on send button press

## UI/UX Requirements

### Layout Specification

#### Outer Container
- Background: `MaterialTheme.colorScheme.surfaceContainerHigh`
- Shape: `RoundedCornerShape(28.dp)`
- Horizontal margin: 12dp from each screen edge
- Bottom margin: 8dp above navigation bar (after `navigationBarsPadding()`)
- Internal padding: 12dp horizontal, 8dp vertical

#### Text Field
- No border, no outline
- Background: transparent (inherits container background)
- Text style: `MaterialTheme.typography.bodyLarge`
- Text color: `MaterialTheme.colorScheme.onSurface`
- Placeholder: "Message or /skill"
- Placeholder color: `MaterialTheme.colorScheme.onSurfaceVariant`
- Min height: 1 line (~24dp content + padding)
- Max visible lines: 6 (scrollable beyond that)
- Top padding: 12dp
- Bottom padding: 4dp (before action row)

#### Action Row
- Height: 40dp (icon button touch target)
- Top padding: 4dp
- Bottom padding: 8dp
- Left: Skill button (24dp icon, 40dp touch target)
- Left (future): Attachment placeholder (visually reserved space or hidden)
- Right: Send button (40dp, filled circle, primary color container) and/or Stop button
- Between left and right: flexible spacer

#### Send Button
- Container: filled circle, `MaterialTheme.colorScheme.primary`
- Icon: `Icons.Default.Send`, tint: `MaterialTheme.colorScheme.onPrimary`
- Size: 40dp
- Disabled state: reduced opacity or `MaterialTheme.colorScheme.surfaceVariant` container

#### Stop Button
- Container: filled circle, `MaterialTheme.colorScheme.errorContainer`
- Indicator: `CircularProgressIndicator` (18dp, 2dp stroke, error color)
- Size: 40dp
- Appears to the left of the Send button during streaming, or replaces it

#### Skill Button
- Icon: `Icons.Default.AutoAwesome`
- Tint: `MaterialTheme.colorScheme.onSurfaceVariant`
- Size: 40dp touch target, 24dp icon

### Visual Mockup: Empty State

```
   +------------------------------------------------------------+
   |                                                             |
   |   Message or /skill                                         |
   |                                                             |
   |   [*]                                           [>]         |
   |                                                             |
   +------------------------------------------------------------+

   [*] = Skill button (AutoAwesome icon)
   [>] = Send button (disabled, muted color)
```

### Visual Mockup: Typing (Multi-line)

```
   +------------------------------------------------------------+
   |                                                             |
   |   Can you help me write a summary of the meeting            |
   |   notes from today? I need it to cover the three            |
   |   main topics we discussed.                                 |
   |                                                             |
   |   [*]                                           [>]         |
   |                                                             |
   +------------------------------------------------------------+

   [*] = Skill button
   [>] = Send button (enabled, primary gold/amber color)
```

### Visual Mockup: Streaming

```
   +------------------------------------------------------------+
   |                                                             |
   |   (empty or previous text)                                  |
   |                                                             |
   |   [*]                                      [X]  [>]         |
   |                                                             |
   +------------------------------------------------------------+

   [*] = Skill button
   [X] = Stop button (with spinning indicator)
   [>] = Send button (disabled during streaming)
```

### Interaction Feedback
- Text field gains visual focus indication (cursor, text selection handles) per system defaults
- Send button color changes between enabled (primary) and disabled (muted) states
- Stop button uses error container color with a spinning progress indicator
- No additional animations required (expansion animation is optional)

## Feature Boundary

### Included
- Visual redesign of the `ChatInput` composable layout and styling
- Replacing `OutlinedTextField` with a borderless text field inside a filled container
- Changing from single-row layout to two-layer (text + action row) vertical layout
- Applying large rounded corners (pill/capsule shape) to the container
- Adding horizontal margins to the container
- Adjusting button positions (Skill on bottom-left, Send/Stop on bottom-right)
- Updating Roborazzi screenshot baselines for the chat screen

### Not Included (V1)
- Attachment button functionality (placeholder space only)
- Voice input button
- Any changes to the `ChatViewModel` or chat state management
- Any changes to the `SlashCommandPopup` component
- Any changes to the `SkillSelectionBottomSheet` component
- Any changes to message bubbles or message list
- Any data layer, repository, or database changes
- Any changes to the top app bar or navigation drawer
- Dark/light mode specific customizations beyond what the Material 3 theme provides automatically
- Custom input field decorations (e.g., formatting toolbar, emoji picker)

## Business Rules

### Layout Rules
1. The text field must always be above the action row (vertical stack)
2. The Skill button is always positioned on the left side of the action row
3. The Send button is always positioned on the right side of the action row
4. The Stop button appears only when `isStreaming` is true
5. The container must have consistent rounded corners regardless of content height
6. The container must not be full-width; it must have horizontal margins (12dp minimum)

### Behavioral Rules
1. All existing ChatInput parameters and callbacks must work identically
2. The composable must not introduce new state or side effects
3. Keyboard inset handling (imePadding, navigationBarsPadding) must be preserved
4. The SlashCommandPopup must continue to appear above the input area when "/" is typed
5. The FocusRequester must still be attached to the text field for programmatic focus

### Visual Rules
1. All colors must come from `MaterialTheme.colorScheme` -- no hardcoded color values
2. All typography must come from `MaterialTheme.typography` -- no hardcoded text styles
3. All shapes must use `RoundedCornerShape` with the specified radius, not `MaterialTheme.shapes`
4. The design must look correct in both light and dark themes (Material 3 handles this via color scheme)

## Non-Functional Requirements

### Performance
- The text field must respond to keystrokes with no perceptible delay (same as current)
- Height auto-expansion must not cause visible jank or flicker
- Layout recomposition during typing should be minimal (no unnecessary recompositions of the action row)

### Compatibility
- Minimum API level: same as the app's current minimum (API 26)
- Must work correctly with all supported screen sizes
- Must handle landscape and portrait orientations
- Must work with system font size scaling (accessibility)
- Must work with right-to-left (RTL) layouts

### Visual Quality
- The rounded container must render without aliasing artifacts at all screen densities
- The transition between enabled/disabled send button states must be smooth
- Text field expansion must be visually smooth (no sudden jumps if animation is implemented)

## Dependencies

### Depends On
- **FEAT-001 (Chat Interaction)**: The ChatInput composable is part of the chat screen defined by FEAT-001
- **FEAT-014 (Agent Skill)**: The Skill button and slash command integration are defined by FEAT-014

### Depended On By
- No other features currently depend on this redesign

### External Dependencies
- None (pure Compose UI change, no new libraries required)

## Test Points

### Functional Tests

#### TC-016-01: Filled Background Rendering

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Open the chat screen | The input area displays with a filled background (surfaceContainerHigh), not an outlined border |
| 2 | Observe the container shape | The container has large rounded corners (pill/capsule shape) |
| 3 | Observe horizontal margins | The container has visible margins from the screen edges |

#### TC-016-02: Text Input and Auto-Expansion

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Tap the input area | The text field gains focus, keyboard appears |
| 2 | Type a short message (one line) | Text appears, field remains single-line height |
| 3 | Type a longer message that wraps to 2-3 lines | The field smoothly expands in height |
| 4 | Continue typing until 6+ lines | The field stops expanding at ~6 lines and becomes scrollable |
| 5 | Delete text back to one line | The field shrinks back to single-line height |

#### TC-016-03: Action Row Layout

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Observe the input area when empty | Skill button on bottom-left, Send button (disabled) on bottom-right |
| 2 | Type some text | Send button becomes enabled (primary gold/amber color) |
| 3 | Clear the text | Send button becomes disabled again |

#### TC-016-04: Send Button Behavior

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | With text entered, tap the Send button | Message is sent, input field clears |
| 2 | With no provider configured, type text | Send button remains disabled |
| 3 | With empty input | Send button is disabled |

#### TC-016-05: Stop Button During Streaming

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Send a message and observe during streaming | Stop button appears in the action row with a spinning indicator |
| 2 | Tap the Stop button | Streaming stops, stop button disappears, send button returns to normal state |

#### TC-016-06: Skill Button Integration

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Tap the Skill button in the bottom-left of the action row | The SkillSelectionBottomSheet opens |
| 2 | Select a skill | The slash command is inserted into the text field, field gains focus |

#### TC-016-07: Slash Command Popup

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Type "/" as the first character in the input | The SlashCommandPopup appears above the input area |
| 2 | Continue typing to filter | The popup list filters correctly |
| 3 | Select a skill from the popup | The command is applied to the input field |

#### TC-016-08: Keyboard and Inset Handling

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Tap the input field to open the keyboard | The input area moves up above the keyboard |
| 2 | Observe the top app bar | The top app bar (hamburger, agent name, settings) remains visible |
| 3 | Observe the navigation bar area | The input area does not overlap with the system navigation bar |

#### TC-016-09: Theme Consistency

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | View the input area in light theme | Colors match the Material 3 light theme (gold/amber accent for send button) |
| 2 | Switch to dark theme | Colors adapt correctly via Material 3 color scheme |
| 3 | Observe all interactive elements | All icons and buttons use theme-derived colors, no hardcoded values visible |

### Edge Cases
- Very long single word without spaces (text field should handle horizontal overflow via wrapping or scrolling)
- Rapidly switching between typing and sending (no layout glitches)
- Rotating the device while text is entered (layout adapts, text is preserved)
- System font size set to maximum (layout accommodates larger text without clipping)
- RTL language input (layout mirrors correctly)
- Input area with slash command popup open while keyboard is visible (popup remains above input, below messages)
- Extremely narrow screen width (container margins should not cause the text field to become unusably small)
- Switching between streaming and non-streaming states rapidly (stop/send buttons toggle correctly)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
