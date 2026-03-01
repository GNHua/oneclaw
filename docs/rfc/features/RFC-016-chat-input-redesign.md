# RFC-016: Chat Input Redesign

## Document Information
- **RFC ID**: RFC-016
- **Related PRD**: FEAT-016 (Chat Input Redesign)
- **Dependencies**: RFC-001 (Chat Interaction), RFC-014 (Agent Skill)
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
The current `ChatInput` composable in `ChatScreen.kt` uses an `OutlinedTextField` wrapped in a `Surface` with `tonalElevation = 2.dp`. Action buttons (Skill, Stop, Send) sit alongside the text field in a single horizontal `Row`. This layout feels dated compared to modern mobile AI chat interfaces like Google Gemini, which use a two-layer vertical layout inside a rounded, filled container.

### Goals
1. Replace the single-row layout with a two-layer vertical layout: text field on top, action buttons on bottom
2. Replace `OutlinedTextField` with `BasicTextField` inside a filled, rounded container (`surfaceContainerHigh`, `RoundedCornerShape(28.dp)`)
3. Add horizontal margins so the input container is not full-width
4. Preserve all existing behavior: text input, send, stop, skill button, slash commands, focus, IME padding

### Non-Goals
- Any changes to `ChatViewModel`, `ChatUiState`, or data layer
- Any changes to `SlashCommandPopup` or `SkillSelectionBottomSheet` components
- Attachment button functionality (placeholder space only)
- Voice input
- Custom animations for text field height expansion
- Dark/light mode specific customizations beyond what Material 3 provides automatically

## Technical Design

### Architecture Overview

This is a **single-file, layout-only change** within `ChatScreen.kt`. No new classes, no new files, no ViewModel or state changes. The `ChatInput` composable is rewritten internally while keeping its function signature identical.

```
┌─────────────────────────────────────────────────────┐
│                    ChatScreen.kt                     │
│                                                     │
│  ChatInput(text, onTextChange, onSend, onStop,      │
│            onSkillClick, isStreaming,                │
│            hasConfiguredProvider, focusRequester)    │
│                                                     │
│  Signature: UNCHANGED                               │
│  Internal layout: REWRITTEN                         │
│  ViewModel/State: UNCHANGED                         │
└─────────────────────────────────────────────────────┘
```

**Files modified**: 1 (`ChatScreen.kt`)
**Files added**: 0
**Files deleted**: 0

### Current vs New Layout

#### Current Layout (Row-based)

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

- Single `Row` with all elements side-by-side
- `OutlinedTextField` with border stroke, `MaterialTheme.shapes.extraLarge`
- Full-width `Surface` with `tonalElevation = 2.dp`
- Buttons squeezed alongside the text field

#### New Layout (Column-based, Gemini-style)

```
   +------------------------------------------------------------+
   |  (surfaceContainerHigh, RoundedCornerShape(28.dp))          |
   |                                                             |
   |   Message or /skill                                         |
   |   (BasicTextField, auto-expanding 1-6 lines)                |
   |                                                             |
   |   [Skill]                               [Stop?] [Send]     |
   |    btn                                   btn     btn        |
   |                                                             |
   +------------------------------------------------------------+
       ^--- 12.dp margin from screen edges ---^
```

- `Column` with two layers: text field area + action row
- `BasicTextField` with no border, transparent background
- `Surface` with `surfaceContainerHigh` fill color, `RoundedCornerShape(28.dp)`
- 12.dp horizontal margin from screen edges
- Action buttons in a separate row below the text field

### Detailed Implementation

#### New ChatInput Composable

```kotlin
@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSkillClick: () -> Unit = {},
    isStreaming: Boolean,
    hasConfiguredProvider: Boolean,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Layer 1: Text Field
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .padding(top = 12.dp, bottom = 4.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                minLines = 1,
                maxLines = 6,
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = "Message or /skill",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Layer 2: Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Skill button (left)
                IconButton(
                    onClick = onSkillClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = "Skills",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Stop button (right, conditional)
                if (isStreaming) {
                    IconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.size(40.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Send button (right)
                IconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank() && hasConfiguredProvider,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}
```

#### New Import Statements

The following imports are added to `ChatScreen.kt`:

```kotlin
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
```

The following import is removed:

```kotlin
// Remove: import androidx.compose.material3.OutlinedTextField
// (only if no other usage exists in the file -- currently OutlinedTextField is only used in ChatInput)
```

### Layout Specification

#### Outer Container (Surface)
| Property | Value |
|----------|-------|
| Background | `MaterialTheme.colorScheme.surfaceContainerHigh` |
| Shape | `RoundedCornerShape(28.dp)` |
| Horizontal margin | 12.dp from each screen edge |
| Bottom margin | 8.dp (via `padding(vertical = 8.dp)`) |
| Internal padding | 12.dp horizontal, 8.dp vertical |

#### Text Field (BasicTextField)
| Property | Value |
|----------|-------|
| Type | `BasicTextField` (no border, no outline) |
| Background | Transparent (inherits container) |
| Text style | `MaterialTheme.typography.bodyLarge` |
| Text color | `MaterialTheme.colorScheme.onSurface` |
| Cursor color | `MaterialTheme.colorScheme.primary` |
| Placeholder | "Message or /skill" |
| Placeholder color | `MaterialTheme.colorScheme.onSurfaceVariant` |
| Min lines | 1 |
| Max lines | 6 (scrollable beyond) |
| Top padding | 12.dp |
| Bottom padding | 4.dp (before action row) |

#### Action Row
| Property | Value |
|----------|-------|
| Height | 40.dp |
| Top padding | 4.dp |
| Layout | Skill button left, flexible spacer, Stop/Send buttons right |

#### Buttons
| Button | Size | Container Color | Icon/Content |
|--------|------|----------------|-------------|
| Skill | 40.dp | None (icon button) | `Icons.Default.AutoAwesome`, tint `onSurfaceVariant` |
| Send | 40.dp | `primary` (filled) | `Icons.Default.Send`, tint `onPrimary` |
| Send (disabled) | 40.dp | Muted (default disabled state) | `Icons.Default.Send` |
| Stop | 40.dp | `errorContainer` (filled) | `CircularProgressIndicator` 18.dp, `error` color |

### Inset and Padding Handling

The modifier chain on the outer `Surface` is:

```kotlin
Modifier
    .fillMaxWidth()
    .imePadding()              // Push above keyboard
    .navigationBarsPadding()   // Respect navigation bar
    .padding(horizontal = 12.dp, vertical = 8.dp)  // Container margins
```

**Order matters**: `imePadding()` and `navigationBarsPadding()` must come before `padding()` so the insets are applied to the full-width surface, then margins are applied within the safe area.

In the current implementation, `imePadding()` is on the `Surface` and `navigationBarsPadding()` is on the inner `Row`. The new implementation consolidates both on the `Surface` modifier for simplicity, since the horizontal margin means the surface is no longer full-width and the visual result is the same.

### Behavioral Preservation Checklist

| Behavior | Mechanism | Preserved? |
|----------|-----------|-----------|
| Text input | `onValueChange = onTextChange` | Yes |
| Send on button tap | `onClick = onSend` | Yes |
| Send disabled (blank/no provider) | `enabled = text.isNotBlank() && hasConfiguredProvider` | Yes |
| Stop button during streaming | `if (isStreaming) { ... }` | Yes |
| Skill button | `onClick = onSkillClick` | Yes |
| Slash command popup | Triggered by `ChatViewModel` based on text starting with "/" -- popup rendered in `ChatScreen` `bottomBar` column, above `ChatInput` | Yes (no change needed) |
| FocusRequester | `Modifier.focusRequester(focusRequester)` on `BasicTextField` | Yes |
| IME padding | `Modifier.imePadding()` on `Surface` | Yes |
| Navigation bar padding | `Modifier.navigationBarsPadding()` on `Surface` | Yes |
| Auto-expand 1-6 lines | `minLines = 1, maxLines = 6` on `BasicTextField` | Yes |
| Scrollable beyond 6 lines | `maxLines = 6` enables scroll within the text field | Yes |
| Placeholder text | `decorationBox` with conditional `Text("Message or /skill")` | Yes |

### Theme Compliance

All colors and typography are sourced from `MaterialTheme`:

| Usage | Token |
|-------|-------|
| Container background | `colorScheme.surfaceContainerHigh` |
| Input text | `colorScheme.onSurface` |
| Placeholder text | `colorScheme.onSurfaceVariant` |
| Cursor | `colorScheme.primary` |
| Skill icon tint | `colorScheme.onSurfaceVariant` |
| Send button container | `colorScheme.primary` |
| Send button icon | `colorScheme.onPrimary` (via `filledIconButtonColors`) |
| Stop button container | `colorScheme.errorContainer` |
| Stop indicator | `colorScheme.error` |
| Text style | `typography.bodyLarge` |

No hardcoded color values. Both light and dark themes are supported automatically via Material 3 color scheme.

## Implementation Plan

This is a single-step implementation:

### Step 1: Rewrite ChatInput Composable
1. [ ] Add `BasicTextField` and `SolidColor` imports to `ChatScreen.kt`
2. [ ] Replace the `ChatInput` composable body (lines 336-401 in current code):
   - Replace `Surface(tonalElevation = 2.dp)` with `Surface(color = surfaceContainerHigh, shape = RoundedCornerShape(28.dp))`
   - Replace inner `Row` with `Column` containing two layers
   - Replace `OutlinedTextField` with `BasicTextField` + `decorationBox`
   - Move Skill button from before the text field to the action row (left side)
   - Move Stop and Send buttons to the action row (right side)
   - Consolidate `imePadding()` and `navigationBarsPadding()` on the `Surface` modifier
   - Add horizontal and vertical padding for container margins
3. [ ] Remove unused `OutlinedTextField` import (if no other usages exist)
4. [ ] Verify the composable signature is unchanged

## Testing Strategy

### Layer 1A: JVM Compile Check
```bash
./gradlew compileDebugKotlin
./gradlew test
```
- Verify no compile errors from the layout changes
- All existing unit tests must pass (no functional changes)

### Layer 1C: Roborazzi Screenshot Tests
- Update screenshot baselines for `ChatScreen` composable since the input area appearance has changed
- Capture baseline for:
  - Empty input state (placeholder visible, send disabled)
  - Text entered state (send enabled)
  - Multi-line text state (expanded field)
  - Streaming state (stop button visible)

```bash
./gradlew recordRoborazziDebug     # record new baselines
./gradlew verifyRoborazziDebug     # verify against new baselines
```

### Layer 2: Visual Verification (adb)
Manual verification flows on a connected device:

| Flow | Steps | Expected |
|------|-------|----------|
| TC-016-01: Filled Background | Open chat screen | Input area has filled background, no outline border, rounded corners, horizontal margins |
| TC-016-02: Auto-Expansion | Type short text, then long multi-line text | Field expands from 1 to up to 6 lines, then scrolls |
| TC-016-03: Action Row | Observe empty state, type text, clear text | Skill button bottom-left, Send button bottom-right, enabled/disabled states correct |
| TC-016-04: Send | Type text, tap Send | Message sent, field clears |
| TC-016-05: Stop | Send message, observe streaming | Stop button appears with spinner, tap stops generation |
| TC-016-06: Skill Button | Tap Skill button | Bottom sheet opens, skill selection works, slash command inserted |
| TC-016-07: Slash Command | Type "/" | Popup appears above input, filtering works |
| TC-016-08: Keyboard Insets | Tap input to open keyboard | Input moves above keyboard, top bar remains visible |
| TC-016-09: Theme | Switch light/dark theme | Colors adapt correctly |

## Dependencies

### Depends On
- **RFC-001 (Chat Interaction)**: `ChatInput` is part of the chat screen defined by RFC-001
- **RFC-014 (Agent Skill)**: Skill button and slash command integration

### Depended On By
- None

### External Dependencies
- None (pure Compose UI change, no new libraries)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
