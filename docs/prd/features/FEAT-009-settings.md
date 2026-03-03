# Settings

## Feature Information
- **Feature ID**: FEAT-009
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P2 (Nice to Have)
- **Owner**: TBD
- **Related RFC**: TBD

## User Story

**As** a user of OneClaw,
**I want to** customize the app's appearance (theme and font size),
**so that** the app matches my visual preferences and is comfortable to use.

### Typical Scenarios

1. User prefers dark mode. They open Settings, go to Appearance, and switch the theme to Dark. The app immediately changes to dark colors.
2. User has set a large system font size for accessibility. The app respects this and renders all text at the larger size without any extra configuration.

## Feature Description

### Overview

The Settings screen is the central hub for app configuration. It currently has entries for "Manage Providers" and "Manage Agents". This feature adds an "Appearance" section for theme control. Font size follows the Android system setting (no in-app override in V1). Language is English only in V1.

### Settings Screen Layout

The Settings screen is organized into sections:

```
Settings

Appearance
  Theme                          System default >

Providers & Models
  Manage Providers               Add API keys, configure models >

Agents
  Manage Agents                  Create and configure agents >

Usage
  Usage Statistics               View token usage by model >

Data & Backup
  Google Drive Sync              Not connected >
  Export Backup                  Save all data to a file >
  Import Backup                  Restore from a backup file >
```

### Appearance

#### Theme
Three options:
- **System default**: Follow Android system dark/light mode setting.
- **Light**: Always light mode.
- **Dark**: Always dark mode.

The selection is persisted in the local settings store. Change takes effect immediately (no app restart needed).

#### Font Size
V1: Follow Android system font size setting. The app uses `sp` units for all text, which automatically scales with the system font size. No in-app font size control is provided in V1.

#### Language
V1: English only. The app's UI strings are all in English. Localization to other languages (including Chinese) is deferred to a future version and would require:
- Android string resources (`strings.xml`) with translations
- Localized system prompts for built-in agents (optional -- could remain in English)

## Acceptance Criteria

Must pass (all required):
- [ ] Settings screen shows "Appearance" section with "Theme" entry
- [ ] Theme options: System default, Light, Dark
- [ ] Selecting a theme changes the app's appearance immediately (no restart)
- [ ] Theme preference is persisted across app restarts
- [ ] App text scales with Android system font size setting
- [ ] Settings screen shows all sections: Appearance, Providers & Models, Agents, Usage, Data & Backup
- [ ] Each entry navigates to its respective screen

Optional (nice to have):
- [ ] Theme preview in the selection dialog
- [ ] In-app font size slider (deferred to future version)

## UI/UX Requirements

### Theme Selection

Tapping "Theme" opens a dialog or navigates to a selection screen:

```
Theme

  ( ) System default
  (o) Light
  ( ) Dark
```

Radio button selection. Dismissing the dialog applies the selection immediately.

### Settings Screen Structure

Each section has a header label (`labelLarge`, `primary` color) and list items below it:
- Each item has a title and subtitle/description
- Tapping navigates to the relevant screen or opens a dialog
- Consistent with existing "Manage Providers" and "Manage Agents" entries

## Feature Boundary

### Included
- Theme selection (System / Light / Dark)
- System font size following (automatic via `sp` units)
- Settings screen reorganization into sections

### Not Included
- In-app font size control
- Language selection / localization
- Notification preferences toggle
- Data management (clear cache, manage storage) -- deferred
- About / version info screen -- deferred
- Chat display settings (bubble style, compact mode) -- already in FEAT-001

## Business Rules

1. Theme default is "System default" on fresh install.
2. Theme change is immediate -- no app restart required.
3. The theme preference is stored locally and is included in sync/backup data (FEAT-007).
4. Font size always follows the Android system setting. The app does not override system font size.

## Dependencies

### Depends On
- None (Settings screen already exists)

### Depended On By
- **FEAT-006 (Token Tracking)**: Usage Statistics entry lives in Settings.
- **FEAT-007 (Data Sync)**: Data & Backup entries live in Settings.

## Test Points

### Functional Tests -- User Operating the App

#### Test 1: Change theme to Dark

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Settings | Settings screen visible with "Appearance" section. |
| 2 | Tap "Theme" | Theme selection dialog opens. Current selection is "System default". |
| 3 | Select "Dark" | Dialog dismisses. App immediately switches to dark theme. |
| 4 | Navigate to Chat screen | Chat screen is in dark mode. |
| 5 | Force-stop and relaunch the app | App launches in dark mode (preference persisted). |

#### Test 2: Change theme to Light

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Settings > Theme | Theme selection dialog opens. |
| 2 | Select "Light" | App switches to light theme immediately. |
| 3 | Change Android system to dark mode (via system Settings) | App stays in light mode (overrides system). |

#### Test 3: System default theme

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Settings > Theme, select "System default" | App uses current system theme. |
| 2 | Change Android system from light to dark mode | App switches to dark mode automatically. |
| 3 | Change Android system back to light mode | App switches back to light mode. |

#### Test 4: System font size

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | In Android system Settings, set font size to "Largest" | System font size changes. |
| 2 | Open the app | All text in the app (messages, labels, input) is rendered at the larger font size. |
| 3 | Set system font size back to default | App text returns to normal size. |

#### Test 5: Settings screen sections

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Settings | All sections visible: Appearance, Providers & Models, Agents, Usage, Data & Backup. |
| 2 | Tap each entry | Each entry navigates to its respective screen (Manage Providers, Manage Agents, Usage Statistics, Google Drive Sync / Export / Import). |

### Edge Cases

- Fresh install: theme is "System default"
- Rapid theme switching (tap Light, Dark, System quickly): no crash, last selection wins
- Very large system font size: UI elements don't overlap or break layout

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | TBD |
