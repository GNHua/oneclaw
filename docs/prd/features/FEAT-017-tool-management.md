# Tool Management

## Feature Information
- **Feature ID**: FEAT-017
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-018 (JS Tool Group)

## User Story

**As** a user of OneClaw,
**I want to** view all available tools in one place and enable or disable them globally,
**so that** I can control which tools are available across all my agents without having to configure each agent individually.

### Typical Scenarios

1. User wants to see what tools the app provides. They open Settings, tap "Manage Tools", and see a full list of all tools -- built-in Kotlin tools, JavaScript extension tools, tool groups, and the `load_skill` tool -- with their names, descriptions, and enabled/disabled status.
2. User decides they do not want the AI to make HTTP requests. They open the tool list, find `http_request`, and toggle it off. From that point on, no agent can use `http_request`, regardless of per-agent configuration.
3. User wants to understand what a tool does before enabling it. They tap the tool name in the list and see a detail view showing the tool's description, parameter schema, required Android permissions, timeout, source type, and group membership (if any).
4. User re-enables a previously disabled tool. The tool immediately becomes available again to any agent whose per-agent configuration includes it.
5. A developer adds several custom JS tools to the tools directory. They open the Tool Management screen to verify all tools loaded correctly and review their parameter schemas.
6. User wants to disable an entire group of related tools at once. They find the tool group (e.g., "Google Drive") and toggle the group switch off. All tools within the group are immediately disabled without needing to toggle each one individually.
7. User wants to disable only specific tools within a group while keeping the rest enabled. They expand the group, then toggle off individual tools as needed.

## Feature Description

### Overview

Tool Management provides a centralized screen for viewing and controlling all tools registered in the app. Currently, tools are only visible through per-agent configuration (FEAT-002), and there is no way to globally disable a tool or inspect its full details (parameters, permissions, timeout). This feature adds a "Manage Tools" entry to the Settings screen that opens a dedicated Tool Management screen.

The key design principle is **global enable/disable**: a tool that is disabled globally is completely unavailable to all agents, regardless of their per-agent tool selection. When a tool is globally enabled, its availability to individual agents is still governed by the existing per-agent tool configuration from FEAT-002.

### Tool Enable/Disable Model

The system introduces a global enabled/disabled flag for each tool:

```
Global state: ENABLED    + Per-agent: SELECTED     = Tool available to agent
Global state: ENABLED    + Per-agent: NOT SELECTED  = Tool NOT available to agent
Global state: DISABLED   + Per-agent: SELECTED     = Tool NOT available to agent
Global state: DISABLED   + Per-agent: NOT SELECTED  = Tool NOT available to agent
```

In other words:
- Global disable is an absolute override -- a disabled tool is invisible to the tool execution system.
- Global enable is a prerequisite -- per-agent config provides the second layer of control.

### Storage of Enable/Disable State

The global enabled/disabled state for each tool is stored in a local key-value store (SharedPreferences or similar). Each tool's state is keyed by its tool name. The default state for all tools is **enabled** -- newly registered tools (including newly added JS tools after a reload) start as enabled. This ensures backward compatibility: existing behavior is unchanged until the user explicitly disables a tool.

For tool groups (RFC-018), an additional group-level enable/disable state is stored. The key format is `tool_group_enabled_{group_name}` in SharedPreferences (boolean). Individual tool keys remain `tool_enabled_{tool_name}`. New groups default to **enabled**.

### Tool List Screen

The Tool Management screen displays all tools currently registered in the `ToolRegistry`. Tools are organized in a three-tier hierarchical layout:

#### 1. Built-in Section

Single-file built-in tools shipped with the app (Kotlin or built-in JS from FEAT-015). These are displayed flat (not collapsible):

| Tool | Description |
|------|-------------|
| `get_current_time` | Get the current date and time |
| `http_request` | Make an HTTP request to a URL |
| `load_skill` | Load the full prompt instructions for a skill |
| `read_file` | Read the contents of a file |
| `webfetch` | Fetch a web page and return its content |
| `write_file` | Write contents to a file |

Each tool shows: name, one-line description, "Built-in" source badge, and individual enable/disable toggle.

#### 2. Tool Groups Section

Tools loaded from array manifest files (RFC-018). Each group is a **collapsible section** with the following properties:

| Property | Description |
|----------|-------------|
| Group name | Derived from the manifest filename (e.g., `google_drive.json` -> "Google Drive") |
| Tool count badge | Shows the number of tools in the group (e.g., "3 tools") |
| Group-level toggle | Enable/disable all tools in the group at once |
| Collapse/expand control | Chevron or arrow to expand/collapse; **default collapsed** |

When expanded, each individual tool within the group is shown with:
- Tool name, one-line description, "Tool Group" source badge, and individual enable/disable toggle.

#### 3. Standalone Section

Single-file JS Extension tools that are not part of any group. These are displayed flat (not collapsible), identical in layout to the Built-in section but with "JS Extension" source badge.

#### Group Toggle and Individual Toggle Relationship

The group toggle and individual tool toggles follow a parent-child relationship similar to Android permission group logic:

- **Group OFF**: All child tools are disabled. Individual toggles within the group are visually grayed out and cannot be interacted with.
- **Group ON**: Each child tool can be toggled independently.
- **All children OFF**: When the user manually disables every individual tool in a group, the group toggle automatically turns OFF.
- **Group re-enabled**: When a group is toggled back ON, individual tools restore to their previously saved states (not all forced ON).

Within each section, tools are sorted alphabetically by name.

### Tool Detail View

Tapping a tool in the list opens a detail view showing the tool's full information:

| Field | Source | Description |
|-------|--------|-------------|
| Name | `ToolDefinition.name` | Unique tool identifier |
| Description | `ToolDefinition.description` | Human-readable description |
| Parameters | `ToolDefinition.parametersSchema` | Each parameter listed with name, type, description, required/optional flag, enum values (if any), and default value (if any) |
| Required Permissions | `ToolDefinition.requiredPermissions` | List of Android permissions the tool needs (e.g., `READ_EXTERNAL_STORAGE`), or "None" |
| Timeout | `ToolDefinition.timeoutSeconds` | Maximum execution time in seconds |
| Source | Derived from tool origin | "Built-in" (loaded from app assets or Kotlin-based), "JS Extension" (loaded from device file system), or "Tool Group" (loaded from array manifest) |
| Group | Group name or "None" | The tool group this tool belongs to (if loaded from an array manifest), or "None" for standalone/built-in tools |
| Enabled | Global state | Current global enable/disable state with a toggle |

For JS Extension and Tool Group tools, an additional field is shown:
- **File path**: The path to the `.js` file (or manifest `.json` file for groups) on the device

### Integration with Per-Agent Tool Selection (FEAT-002)

The existing per-agent tool selection in Agent configuration (FEAT-002) continues to work as before, with one modification:

- When displaying the tool list in the Agent Detail/Edit screen, globally disabled tools are shown with a visual indicator (e.g., grayed out with a "Globally disabled" label) and their checkboxes are disabled. The user cannot select a globally disabled tool for an agent.
- When the `ToolExecutionEngine` resolves the tool set for a session, it filters out any tools that are globally disabled, even if the agent's configuration includes them.

### Integration with Tool Execution

The `ToolExecutionEngine` already validates that a requested tool exists and is available to the current agent. With this feature, an additional check is added:

```
1. Model requests tool call (tool name + parameters)
2. Engine checks tool exists in registry                    [existing]
3. Engine checks tool is globally enabled                   [NEW]
4. Engine checks tool is in the current agent's tool set    [existing]
5. Engine validates parameters                              [existing]
6. Engine checks permissions                                [existing]
7. Engine executes                                          [existing]
```

If a tool is globally disabled and the model requests it, the engine returns an error: "Tool [name] is globally disabled and not available."

### Settings Screen Entry Point

A new entry is added to the Settings screen (FEAT-009) in a new "Tools" section, placed between the "Agents" section and the "Usage" section:

```
Settings

Appearance
  Theme                          System default >

Providers & Models
  Manage Providers               Add API keys, configure models >

Agents
  Manage Agents                  Create and configure agents >

Tools
  Manage Tools                   View and enable/disable tools >

Usage
  Usage Statistics               View token usage by model >

Memory
  Agent Memory                   View and edit long-term memory, daily logs >

Skills
  Manage Skills                  Create, edit, and delete skills >

Data & Backup
  Data & Backup                  Google Drive sync, export/import backup >
```

### User Interaction Flows

#### Viewing the Tool List
```
1. User opens Settings
2. User taps "Manage Tools"
3. System shows the Tool Management screen with all registered tools
4. Tools are organized in three sections: Built-in (flat), Tool Groups (collapsible), Standalone (flat)
5. Each tool shows name, description, source badge, and enable/disable toggle
6. Each tool group shows group name, tool count badge, group toggle, and collapse/expand control
```

#### Disabling a Tool Globally
```
1. User opens Tool Management screen
2. User finds the tool they want to disable (e.g., http_request)
3. User taps the toggle switch to disable it
4. The toggle changes to the off state
5. A brief snackbar confirms: "http_request disabled"
6. The tool is immediately unavailable to all agents
7. If any agent had this tool selected, it remains in their config but is inactive
```

#### Disabling a Tool Group
```
1. User opens Tool Management screen
2. User finds the tool group they want to disable (e.g., "Google Drive")
3. User taps the group-level toggle switch to disable it
4. The group toggle changes to the off state
5. All individual tool toggles within the group become grayed out and disabled
6. A brief snackbar confirms: "Google Drive group disabled"
7. All tools in the group are immediately unavailable to all agents
```

#### Expanding a Tool Group and Toggling Individual Tools
```
1. User opens Tool Management screen
2. User taps the expand control (chevron) on a tool group
3. The group expands to show all individual tools within it
4. User taps the toggle on a specific tool within the group to disable it
5. The tool toggle changes to the off state
6. A brief snackbar confirms: "[tool_name] disabled"
7. If the user has now disabled all tools in the group, the group toggle auto-turns OFF
```

#### Re-enabling a Tool Group
```
1. User opens Tool Management screen
2. User finds the disabled tool group (group toggle in off state)
3. User taps the group toggle to enable it
4. The group toggle changes to the on state
5. Individual tool toggles within the group become interactive again
6. Each tool restores to its previously saved individual state (not all forced ON)
7. A brief snackbar confirms: "Google Drive group enabled"
```

#### Viewing Tool Details
```
1. User opens Tool Management screen
2. User taps on a tool name (not the toggle)
3. System shows the Tool Detail view
4. User can see: name, description, all parameters with types, permissions, timeout, source, and group membership
5. User can also toggle the enabled state from this view
6. User taps back to return to the tool list
```

#### Re-enabling a Disabled Tool
```
1. User opens Tool Management screen
2. User finds the disabled tool (shown with toggle in off state)
3. User taps the toggle switch to enable it
4. The toggle changes to the on state
5. A brief snackbar confirms: "http_request enabled"
6. The tool is immediately available to agents that have it in their per-agent config
```

## Acceptance Criteria

Must pass (all required):
- [ ] Settings screen shows a "Tools" section with a "Manage Tools" entry
- [ ] Tapping "Manage Tools" navigates to the Tool Management screen
- [ ] Tool Management screen lists all registered tools (built-in, JS extension, tool group, skill-related)
- [ ] Each tool in the list shows: name, description, source badge, and enable/disable toggle
- [ ] Tools are organized in three sections: Built-in (flat), Tool Groups (collapsible), Standalone (flat)
- [ ] Tool groups are displayed as collapsible sections with group name, tool count badge, and group-level toggle
- [ ] Tool groups default to collapsed state
- [ ] Group-level toggle OFF disables all child tools and grays out individual toggles
- [ ] Group-level toggle ON allows individual tool toggles to be controlled independently
- [ ] When all individual tools in a group are manually disabled, the group toggle auto-turns OFF
- [ ] When a group is re-enabled, individual tools restore to their previously saved states
- [ ] Group name is derived from manifest filename (e.g., `google_drive.json` -> "Google Drive")
- [ ] User can toggle a tool's global enabled/disabled state
- [ ] Disabling a tool globally prevents all agents from using it
- [ ] Enabling a tool globally makes it available to agents that have it selected per-agent
- [ ] Global disable state persists across app restarts
- [ ] Group-level disable state persists across app restarts
- [ ] Default state for all tools is enabled
- [ ] Default state for new groups is enabled
- [ ] Tapping a tool opens a detail view showing: name, description, parameters, permissions, timeout, source, and group membership
- [ ] Parameter details show: name, type, description, required/optional, enum values, defaults
- [ ] Tool detail view shows "Group" field indicating which group the tool belongs to (or "None")
- [ ] The tool detail view allows toggling the enabled state
- [ ] In Agent configuration (FEAT-002), globally disabled tools are shown as grayed out and cannot be selected
- [ ] When a globally disabled tool is called by the model, the engine returns an appropriate error message
- [ ] Newly registered tools (e.g., after JS tool reload) default to enabled

Optional (nice to have):
- [ ] Search/filter in the tool list
- [ ] Tool count badge in the Settings entry (e.g., "12 tools, 2 disabled")
- [ ] Bulk enable/disable all tools across all groups

## UI/UX Requirements

### Tool Management Screen (Tool List) -- Collapsed Groups

```
+------------------------------------------+
| [<] Manage Tools                         |
+------------------------------------------+
|                                          |
| BUILT-IN                                 |
| +--------------------------------------+ |
| | get_current_time          [Built-in] | |
| | Get the current date and time        | |
| |                            [=ON===]  | |
| +--------------------------------------+ |
| | http_request              [Built-in] | |
| | Make an HTTP request to a URL        | |
| |                            [=ON===]  | |
| +--------------------------------------+ |
| | load_skill                [Built-in] | |
| | Load the full prompt instructions... | |
| |                            [=ON===]  | |
| +--------------------------------------+ |
| | read_file                 [Built-in] | |
| | Read the contents of a file from...  | |
| |                            [=ON===]  | |
| +--------------------------------------+ |
| | webfetch                  [Built-in] | |
| | Fetch a web page and return its...   | |
| |                            [=ON===]  | |
| +--------------------------------------+ |
| | write_file                [Built-in] | |
| | Write contents to a file on local... | |
| |                            [=ON===]  | |
| +--------------------------------------+ |
|                                          |
| TOOL GROUPS                              |
| +--------------------------------------+ |
| | [>] Google Drive    (3 tools)        | |
| |                            [=ON===]  | |
| +--------------------------------------+ |
| | [>] Slack           (5 tools)        | |
| |                            [===OFF]  | |
| +--------------------------------------+ |
|                                          |
| STANDALONE                               |
| +--------------------------------------+ |
| | weather_lookup        [JS Extension] | |
| | Look up current weather for a city   | |
| |                            [===OFF]  | |
| +--------------------------------------+ |
| | csv_parser            [JS Extension] | |
| | Parse CSV data and extract columns   | |
| |                            [=ON===]  | |
| +--------------------------------------+ |
|                                          |
+------------------------------------------+
```

### Tool Management Screen (Tool List) -- Expanded Group

```
+------------------------------------------+
| [<] Manage Tools                         |
+------------------------------------------+
|                                          |
| ...                                      |
|                                          |
| TOOL GROUPS                              |
| +--------------------------------------+ |
| | [v] Google Drive    (3 tools)        | |
| |                            [=ON===]  | |
| |                                      | |
| |   +--------------------------------+ | |
| |   | gdrive_list    [Tool Group]    | | |
| |   | List files in Google Drive     | | |
| |   |                    [=ON===]    | | |
| |   +--------------------------------+ | |
| |   | gdrive_read    [Tool Group]    | | |
| |   | Read a file from Google Drive  | | |
| |   |                    [=ON===]    | | |
| |   +--------------------------------+ | |
| |   | gdrive_upload  [Tool Group]    | | |
| |   | Upload a file to Google Drive  | | |
| |   |                    [===OFF]    | | |
| |   +--------------------------------+ | |
| |                                      | |
| +--------------------------------------+ |
| | [>] Slack           (5 tools)        | |
| |                            [===OFF]  | |
| +--------------------------------------+ |
|                                          |
| ...                                      |
|                                          |
+------------------------------------------+
```

### Tool Management Screen -- Disabled Group (Grayed Out)

```
+------------------------------------------+
| ...                                      |
|                                          |
| TOOL GROUPS                              |
| +--------------------------------------+ |
| | [v] Slack           (5 tools)        | |
| |                            [===OFF]  | |
| |                                      | |
| |   +--------------------------------+ | |
| |   | slack_send     [Tool Group]    | | |
| |   | Send a message to a channel    | | |
| |   |               [===OFF] (dim)   | | |
| |   +--------------------------------+ | |
| |   | slack_read     [Tool Group]    | | |
| |   | Read messages from a channel   | | |
| |   |               [===OFF] (dim)   | | |
| |   +--------------------------------+ | |
| |   ...                                | |
| +--------------------------------------+ |
|                                          |
| ...                                      |
+------------------------------------------+
```

When a group toggle is OFF, individual tool toggles are shown in a dimmed/grayed-out state and are not interactive.

### Tool Detail View

```
+------------------------------------------+
| [<] Tool Details                         |
+------------------------------------------+
|                                          |
| gdrive_list                              |
| List files in Google Drive               |
|                                          |
| Source: Tool Group                       |
| Group: Google Drive                      |
| Timeout: 30 seconds                      |
| Permissions: None                        |
|                                          |
| Enabled                      [=ON===]   |
|                                          |
| ---------------------------------------- |
|                                          |
| PARAMETERS                               |
|                                          |
| folder_id (string) optional              |
|   The folder ID to list files from       |
|   Default: root                          |
|                                          |
| max_results (integer) optional           |
|   Maximum number of results to return    |
|   Default: 20                            |
|                                          |
+------------------------------------------+
```

For tools that belong to a group, the detail view includes a "Group" field showing the group name. For standalone or built-in tools, the "Group" field shows "None".

### Tool Detail View (Standalone / Built-in Tool)

```
+------------------------------------------+
| [<] Tool Details                         |
+------------------------------------------+
|                                          |
| http_request                             |
| Make an HTTP request to a URL            |
|                                          |
| Source: Built-in                         |
| Group: None                             |
| Timeout: 30 seconds                      |
| Permissions: INTERNET                    |
|                                          |
| Enabled                      [=ON===]   |
|                                          |
| ---------------------------------------- |
|                                          |
| PARAMETERS                               |
|                                          |
| url (string) *required                   |
|   The URL to request                     |
|                                          |
| method (string) optional                 |
|   HTTP method                            |
|   Values: GET, POST, PUT, DELETE         |
|   Default: GET                           |
|                                          |
| headers (object) optional                |
|   Key-value pairs of HTTP headers        |
|                                          |
| body (string) optional                   |
|   Request body (for POST/PUT)            |
|                                          |
+------------------------------------------+
```

### Settings Screen with New "Tools" Section

```
+------------------------------------------+
| [<] Settings                             |
+------------------------------------------+
|                                          |
| Appearance                               |
|   Theme                  System default >|
|                                          |
| Providers & Models                       |
|   Manage Providers     Add API keys... > |
|                                          |
| Agents                                   |
|   Manage Agents      Create and conf.. > |
|                                          |
| Tools                                    |
|   Manage Tools     View and enable/d.. > |
|                                          |
| Usage                                    |
|   Usage Statistics  View token usage.. > |
|                                          |
| Memory                                   |
|   Agent Memory     View and edit lo... > |
|                                          |
| Skills                                   |
|   Manage Skills    Create, edit, an... > |
|                                          |
| Data & Backup                            |
|   Data & Backup    Google Drive sync.. > |
|                                          |
+------------------------------------------+
```

### Interaction Feedback

- Toggle on: brief snackbar "[tool_name] enabled"
- Toggle off: brief snackbar "[tool_name] disabled"
- Group toggle on: brief snackbar "[group_name] group enabled"
- Group toggle off: brief snackbar "[group_name] group disabled"
- Navigation transitions: standard Material 3 shared axis transitions (consistent with other Settings sub-screens)

### Visual Design Notes

- Section headers ("BUILT-IN", "TOOL GROUPS", "STANDALONE") use `labelLarge` style, `primary` color (consistent with Settings screen)
- Each tool item is a card or list tile with:
  - Tool name in `bodyLarge` style
  - Description in `bodySmall` style, `onSurfaceVariant` color, truncated to one line
  - Source badge as a small chip (e.g., "Built-in", "JS Extension", "Tool Group")
  - Material 3 Switch on the trailing edge for enable/disable
- Tool group headers display:
  - Expand/collapse chevron icon on the leading edge
  - Group name in `bodyLarge` style
  - Tool count badge in `bodySmall` style
  - Material 3 Switch on the trailing edge for group enable/disable
- Tapping anywhere on a tool item (except the switch) navigates to the detail view
- Tapping the switch toggles the enabled state without navigating
- Tapping the expand/collapse chevron on a group header toggles expansion without navigating
- When a group is OFF, child tool items have reduced opacity and switches are non-interactive

## Feature Boundary

### Included
- "Manage Tools" entry in the Settings screen
- Tool Management screen listing all registered tools with enable/disable toggles
- Tool Detail view showing full tool information (name, description, parameters, permissions, timeout, source, group membership)
- Global enable/disable toggle per tool, persisted locally
- Tool group display with collapsible sections, group-level toggle, and tool count badge
- Group-level enable/disable state, persisted locally
- Group toggle and individual toggle parent-child relationship
- Integration with ToolExecutionEngine to respect global disable state
- Visual indication of globally disabled tools in the Agent configuration tool selection (FEAT-002)
- Three-tier tool organization: Built-in (flat), Tool Groups (collapsible), Standalone (flat)

### Not Included
- Per-agent tool enable/disable from this screen (that remains in FEAT-002 Agent configuration)
- Tool creation, editing, or deletion (JS tools are managed via file system per FEAT-012; skills via FEAT-014)
- Tool execution history or logs
- Tool testing or dry-run capability
- Tool reordering or custom sorting
- Tool categories or tags beyond source type and group
- Tool search or filtering (deferred to optional enhancement)
- Notifications when a tool is disabled while in active use by a conversation
- Bulk import/export of tool enable/disable configuration
- Drag-and-drop reordering of groups

## Business Rules

### Enable/Disable Rules
1. All tools default to enabled when first registered.
2. The global enabled/disabled state is stored per tool name.
3. Disabling a tool takes effect immediately -- any subsequent tool call to the disabled tool will fail with an error, even in an active conversation.
4. Enabling a tool takes effect immediately -- agents with the tool in their per-agent config can use it on the next tool call.
5. If a tool is unregistered (e.g., JS tool file removed) and later re-registered with the same name, the previously stored enable/disable state is preserved.
6. The enable/disable state is independent of the tool's source (built-in, JS extension, tool group, or skill-related).

### Group Toggle Rules
1. A new tool group defaults to enabled when first loaded.
2. Group-level enable/disable state is stored with key `tool_group_enabled_{group_name}` in SharedPreferences.
3. When a group toggle is turned OFF, all child tools within the group become disabled. Individual tool toggles are grayed out and non-interactive.
4. When a group toggle is turned ON, each child tool can be toggled independently. Individual tools restore to their previously saved states (not all forced ON).
5. When the user manually disables every individual tool in an enabled group, the group toggle automatically turns OFF.
6. When the group is re-enabled after an auto-OFF (from rule 5), individual tools restore to their last explicitly saved states.
7. Group name is derived from the manifest filename: the filename without extension, with underscores replaced by spaces, and each word capitalized (e.g., `google_drive.json` -> "Google Drive").
8. Disabling a group takes effect immediately -- all tools in the group become unavailable to all agents.

### Relationship with Per-Agent Config
1. Global disable overrides per-agent selection unconditionally.
2. Global enable does not automatically add a tool to any agent's tool set -- per-agent config remains the fine-grained control.
3. When a tool is globally disabled, it still appears in the Agent configuration tool list but is visually marked as disabled and its checkbox cannot be toggled.
4. Re-enabling a tool does not change any agent's per-agent tool selection -- the agent's previous config is respected.

### Display Rules
1. The tool list shows all tools registered in the ToolRegistry at the time the screen is opened.
2. If tools are reloaded (e.g., JS tool reload from FEAT-012), the tool list updates on next screen visit.
3. Tool names are displayed exactly as registered (snake_case convention from FEAT-004).
4. Tool descriptions are displayed as-is from the ToolDefinition.
5. Source type is derived from the tool's loading origin: tools loaded from app assets (`js/tools/`) or implemented in Kotlin (e.g., `LoadSkillTool`) are "Built-in"; tools loaded from array manifest files (RFC-018) are "Tool Group"; single-file tools loaded from device file system (`/sdcard/OneClaw/tools/` or `{app_files}/tools/`) that are not part of a group are "JS Extension" (Standalone). After RFC-015, all built-in tools except `LoadSkillTool` are JavaScript-based, so class type alone cannot distinguish built-in from user-defined JS tools.
6. Tool groups are sorted alphabetically by group name. Tools within each group are sorted alphabetically by tool name.
7. If no tool groups exist, the "TOOL GROUPS" section header is hidden.
8. If no standalone JS extension tools exist, the "STANDALONE" section header is hidden.

## Non-Functional Requirements

### Performance
- Tool Management screen loads in < 200ms (reads from in-memory ToolRegistry + local SharedPreferences)
- Enable/disable toggle persists in < 50ms (SharedPreferences write)
- Tool Detail view renders in < 100ms
- Group expand/collapse animation completes in < 200ms
- No network calls required for any Tool Management operation

### Data
- Individual tool enable/disable state stored in local SharedPreferences (key: `tool_enabled_{tool_name}`, value: boolean)
- Group enable/disable state stored in local SharedPreferences (key: `tool_group_enabled_{group_name}`, value: boolean)
- Tool enable/disable configuration is included in data sync/backup (FEAT-007) if applicable
- No new Room entities required

### Reliability
- If the enable/disable store is corrupted or unavailable, all tools default to enabled (fail-open)
- If a group enable/disable state is corrupted or unavailable, the group defaults to enabled (fail-open)
- The Tool Management screen gracefully handles an empty ToolRegistry (shows an empty state message)

### Accessibility
- All UI elements have content descriptions for screen readers
- Toggle switches announce their current state ("enabled" / "disabled")
- Group expand/collapse state is announced ("expanded" / "collapsed")
- Tool names and descriptions are readable at large font sizes
- Color is not the sole indicator of enabled/disabled state (the switch position provides a non-color cue)
- Grayed-out tools within a disabled group are announced as "disabled by group" for screen readers

## Dependencies

### Depends On
- **FEAT-004 (Tool System)**: ToolRegistry provides the list of all tools and their definitions; ToolExecutionEngine needs the new global enable check
- **FEAT-009 (Settings)**: Settings screen provides the entry point for Tool Management
- **FEAT-012 (JavaScript Tool Engine)**: JS tools appear in the tool list alongside built-in tools
- **FEAT-014 (Agent Skill)**: The `load_skill` tool appears in the tool list
- **FEAT-015 (JS Tool Migration)**: If built-in tools are migrated to JS, they still appear as "Built-in" in the tool list
- **RFC-018 (JS Tool Group)**: Provides the array manifest format and group loading mechanism that enables tool group display and group-level management

### Depended On By
- **FEAT-002 (Agent Management)**: Agent configuration screen needs to respect global disable state when showing tool checkboxes

## Error Handling

### Error Scenarios

1. **SharedPreferences write failure**
   - Cause: Storage full or I/O error when persisting enable/disable state
   - Handling: Show error snackbar "Failed to save tool state. Please try again." Toggle reverts to previous state.

2. **ToolRegistry empty**
   - Cause: No tools registered (e.g., startup failure)
   - Handling: Show empty state on the Tool Management screen: "No tools available."

3. **Tool unregistered while detail view is open**
   - Cause: JS tool reload removes a tool while user is viewing its details
   - Handling: Show a message "This tool is no longer available" and navigate back to the tool list.

4. **Model calls a globally disabled tool**
   - Cause: The AI model requests a tool that has been disabled by the user
   - Handling: Return error to model: "Tool [name] is globally disabled and not available."
   - The model can inform the user or try a different approach.

5. **Group manifest file removed while group is displayed**
   - Cause: The manifest file for a tool group is deleted or becomes unreadable
   - Handling: On next screen visit or tool reload, the group and its tools are removed from the list. If the detail view for a tool in the removed group is open, show "This tool is no longer available" and navigate back.

## Future Improvements

- [ ] **Tool search and filter**: Search bar to find tools by name or description
- [ ] **Bulk enable/disable**: Select multiple tools and toggle them at once
- [ ] **Tool categories**: Categorize tools beyond source type (e.g., File, Network, System, Text)
- [ ] **Disable confirmation**: Optional confirmation dialog when disabling a tool that is actively selected by one or more agents
- [ ] **Tool usage statistics**: Show how many times each tool has been called, last used date
- [ ] **Tool health indicators**: Show if a JS tool has load errors (integrate with FEAT-012 error status)
- [ ] **Export/import tool enable/disable configuration**: Share tool settings across devices
- [ ] **Per-agent disable override display**: In tool detail, show which agents currently have this tool selected
- [ ] **Group reordering**: Allow user to reorder tool groups via drag-and-drop

## Test Points

### Functional Tests

#### Test 1: Navigate to Tool Management screen

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Settings | Settings screen visible with "Tools" section containing "Manage Tools" entry. |
| 2 | Tap "Manage Tools" | Tool Management screen opens showing all registered tools. |
| 3 | Verify tool list | All built-in tools, tool groups, and standalone JS tools are listed with name, description, source badge, and toggle. |

#### Test 2: Disable a tool globally

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Tool Management screen | Tool list visible. All tools enabled by default. |
| 2 | Tap the toggle for `http_request` | Toggle switches to off. Snackbar shows "http_request disabled". |
| 3 | Navigate back to Settings, then to Agent config for any agent | `http_request` is grayed out in the agent's tool list, checkbox disabled. |
| 4 | Start a chat session and ask the AI to make an HTTP request | The AI reports that the `http_request` tool is not available. |

#### Test 3: Re-enable a disabled tool

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Tool Management screen | `http_request` shown as disabled (toggle off). |
| 2 | Tap the toggle for `http_request` | Toggle switches to on. Snackbar shows "http_request enabled". |
| 3 | Navigate to Agent config for an agent that has `http_request` selected | `http_request` is no longer grayed out; checkbox is enabled and checked. |
| 4 | Start a chat session and ask the AI to make an HTTP request | The AI successfully uses `http_request`. |

#### Test 4: View tool details

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Tool Management screen | Tool list visible. |
| 2 | Tap on `http_request` (not on the toggle) | Tool Detail view opens. |
| 3 | Verify detail content | Shows: name "http_request", description, source "Built-in", group "None", timeout "30 seconds", permissions "INTERNET", and all four parameters (url, method, headers, body) with their types, descriptions, required/optional status, enum values, and defaults. |
| 4 | Tap the enable/disable toggle in the detail view | Toggle changes state. Behavior matches toggling from the list. |
| 5 | Tap back | Returns to tool list with updated toggle state. |

#### Test 5: Persistence across app restart

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Tool Management, disable `write_file` | Toggle shows off. |
| 2 | Force-stop and relaunch the app | App launches normally. |
| 3 | Open Tool Management | `write_file` still shows as disabled (toggle off). |
| 4 | All other tools still show as enabled | Toggle states preserved. |

#### Test 6: Newly added JS tool defaults to enabled

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Add a new JS tool to the tools directory | `.js` and `.json` files placed in the tools directory. |
| 2 | Trigger JS tool reload (or restart app) | New tool is registered. |
| 3 | Open Tool Management | New tool appears in the appropriate section (Standalone or Tool Group) with toggle in the on (enabled) state. |

#### Test 7: Global disable overrides per-agent selection

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Configure an agent to include `read_file` in its tool set | Agent saved with `read_file` selected. |
| 2 | Open Tool Management, disable `read_file` globally | Toggle shows off. |
| 3 | Open the agent's config | `read_file` is grayed out with "Globally disabled" indicator. The checkbox state is preserved but inactive. |
| 4 | Re-enable `read_file` globally | Agent config shows `read_file` as active and checked again. |

#### Test 8: Tool group display and collapse/expand

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Tool Management screen | Tool groups are visible in the "TOOL GROUPS" section, each showing group name, tool count badge, and group toggle. |
| 2 | Verify groups are collapsed by default | No individual tools are visible within any group. Expand chevron points right ([>]). |
| 3 | Tap the expand chevron on a group (e.g., "Google Drive") | The group expands to show all individual tools. Chevron changes to down ([v]). |
| 4 | Tap the collapse chevron on the expanded group | The group collapses, hiding individual tools. Chevron changes back to right ([>]). |

#### Test 9: Group toggle disables all child tools

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Tool Management screen | Tool groups visible with all groups enabled by default. |
| 2 | Expand a tool group (e.g., "Google Drive") | Individual tools visible, all enabled. |
| 3 | Tap the group-level toggle to turn it OFF | Group toggle switches to off. Snackbar shows "Google Drive group disabled". |
| 4 | Verify individual tools | All individual tool toggles within the group are grayed out and in the off state. They cannot be tapped. |
| 5 | Start a chat session and ask the AI to use any tool from that group | The AI reports the tool is not available. |

#### Test 10: Group toggle ON allows individual control

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Tool Management screen with a disabled group | Group toggle shows off, individual tools grayed out. |
| 2 | Tap the group-level toggle to turn it ON | Group toggle switches to on. Snackbar shows "Google Drive group enabled". |
| 3 | Verify individual tools | Individual tool toggles become interactive. Each tool restores to its previously saved state. |
| 4 | Tap an individual tool toggle within the group to disable it | That specific tool toggles off. Other tools in the group remain enabled. |

#### Test 11: All children disabled auto-turns group OFF

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Tool Management screen, expand a group with 3 tools (all enabled) | Group toggle ON, all 3 tool toggles ON. |
| 2 | Disable the first tool individually | Tool 1 off, tools 2-3 still on. Group toggle remains ON. |
| 3 | Disable the second tool individually | Tool 2 off, tool 3 still on. Group toggle remains ON. |
| 4 | Disable the third (last) tool individually | Tool 3 off. Group toggle automatically turns OFF. |
| 5 | Turn the group toggle back ON | Individual tools restore to their last explicitly saved states (all three off in this case, since the user explicitly disabled each). |

#### Test 12: Tool detail view shows group membership

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Tool Management screen, expand a tool group | Individual tools visible. |
| 2 | Tap on a tool within the group (not on the toggle) | Tool Detail view opens. |
| 3 | Verify the "Group" field | Shows the group name (e.g., "Google Drive"). |
| 4 | Navigate back, then tap on a built-in tool | Tool Detail view opens. |
| 5 | Verify the "Group" field | Shows "None". |

#### Test 13: Mix of groups and standalone tools

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Set up tools directory with: one array manifest (group), one single-file JS tool (standalone) | Both types of tools present. |
| 2 | Open Tool Management screen | Built-in section shows built-in tools flat. Tool Groups section shows the group as collapsible. Standalone section shows single-file JS tools flat. |
| 3 | Verify section headers | "BUILT-IN", "TOOL GROUPS", and "STANDALONE" headers are all visible. |

#### Test 14: Group state persistence across app restart

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Open Tool Management, disable a tool group | Group toggle shows off. |
| 2 | Force-stop and relaunch the app | App launches normally. |
| 3 | Open Tool Management | The group still shows as disabled (toggle off). |
| 4 | Expand the group | Individual tools are still grayed out and non-interactive. |

### Edge Cases

- Disable all tools: all toggles off. Agents can only chat without tool use. No crash.
- Toggle a tool on and off rapidly: no state corruption, last toggle wins.
- JS tool reload while Tool Management screen is open: screen updates on next navigation or refresh.
- Tool with no parameters: detail view shows "No parameters" instead of an empty section.
- Tool with no required permissions: detail view shows "None" for permissions.
- Tool with very long description: text truncates in list, shows full text in detail view.
- Screen orientation change on Tool Management screen: state, scroll position, and group expand/collapse states preserved.
- Very large number of tools (50+): list scrolls smoothly without performance degradation.
- Tool name containing special characters: displayed correctly.
- Group with only one tool: displayed as a collapsible group (same as multi-tool groups).
- Group manifest added while another group exists: new group appears in the Tool Groups section on next screen visit.
- Group manifest removed: group and its tools disappear on next screen visit or reload.
- Empty group (manifest exists but contains no tools): group is not displayed in the list.

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
| 2026-02-28 | 0.2 | Added tool group support (RFC-018): three-tier layout, collapsible groups, group-level toggle, group toggle/individual toggle relationship, group detail field, group storage, group-related acceptance criteria and test points | - |
