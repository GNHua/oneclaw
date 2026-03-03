# Agent Skill

## Feature Information
- **Feature ID**: FEAT-014
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-014 (pending)

## User Story

**As** a user of OneClaw,
**I want to** trigger pre-defined skill workflows that guide the AI through structured, multi-step tasks,
**so that** I can accomplish complex or repetitive tasks consistently and efficiently without writing detailed instructions each time.

### Typical Scenarios
1. User wants to summarize a local file. Instead of writing a detailed prompt explaining what to read and how to summarize, they type `/summarize-file` and provide the file path. The AI follows the skill's structured workflow to read the file and produce a formatted summary.
2. User wants to check device storage status. They tap the Skill button in the input area, select "Storage Check" from the list, and the AI runs through the skill workflow to gather and report storage information.
3. User is chatting with the AI and mentions they need to translate a document. The AI recognizes this maps to the `translate-file` skill, proactively loads the skill prompt via the `load_skill` tool, and follows the structured translation workflow.
4. A power user creates a custom skill for their specific workflow (e.g., "daily report generator") and exports it as a Markdown file to share with colleagues.
5. A user imports a skill shared by a friend -- the skill appears in their skill list and is immediately usable.

## Feature Description

### Overview
Agent Skill introduces a "prompt template" layer that sits between the user and the Tool System. A Skill is a pre-defined Markdown file containing structured instructions that guide the AI through a specific workflow using existing Tools. Skills do not execute code themselves -- they orchestrate the AI's behavior by injecting detailed prompt instructions into the conversation.

This design follows the Claude Code model: the system prompt contains a lightweight registry of available skills (name + one-line description), and the full skill content is loaded on-demand when triggered. This keeps the system prompt compact while making skills discoverable.

### Core Concept: Skill = Prompt Template

A Skill is defined as a Markdown file with YAML frontmatter:

```markdown
---
name: summarize-file
display_name: "Summarize File"
description: "Read a local file and produce a structured summary"
version: "1.0"
tools_required:
  - read_file
parameters:
  - name: file_path
    type: string
    required: true
    description: "Absolute path to the file to summarize"
  - name: language
    type: string
    required: false
    description: "Output language (default: same as source)"
---

# Summarize File

## Instructions

1. Use the `read_file` tool to read the file at {{file_path}}
2. If the file cannot be read, report the error clearly and stop
3. Analyze the content and identify:
   - The main topic or purpose of the document
   - Key points and arguments
   - Notable data, statistics, or conclusions
4. Produce a structured summary:
   - **Title**: Inferred document title
   - **Main Topic**: 1-2 sentence overview
   - **Key Points**: Bulleted list (5-10 items)
   - **Notable Details**: Any important specifics
   - **Summary Length**: Keep under 500 words
5. If `language` is specified, write the summary in that language
```

### Skill Registry in System Prompt

The system prompt includes a brief listing of all available skills:

```
Available skills (use load_skill tool to load full instructions):
- summarize-file: Read a local file and produce a structured summary
- translate-file: Translate a file's content to a target language
- fetch-webpage: Fetch and summarize a webpage's content
- rewrite-text: Rewrite text in a specified style or tone
- device-info: Gather and report device information
```

This listing is auto-generated from skill frontmatter. It is lightweight (one line per skill) and allows the AI to know what skills exist without consuming excessive tokens.

### Skill Loading Mechanism: `load_skill` Tool

A new built-in tool `load_skill` is added to the Tool System:

| Component | Description |
|-----------|-------------|
| Name | `load_skill` |
| Description | Load the full prompt instructions for a skill |
| Parameters | `name` (string, required): The skill name to load |
| Required Permissions | None |
| Timeout | 5 seconds |
| Returns | The full Markdown content of the skill (everything after frontmatter) |

The `load_skill` tool reads the skill's Markdown file, parses out the frontmatter, resolves any parameter placeholders if parameter values are provided, and returns the prompt content. The AI then follows the loaded instructions.

### Three Trigger Paths

#### Path 1: / Command (Input Box)
1. User types `/` in the chat input box
2. A popup/autocomplete list of available skills appears (filtered as user types)
3. User selects a skill (e.g., `/summarize-file`)
4. If the skill has required parameters, a simple parameter input form is shown
5. The system sends a message invoking `load_skill` with the skill name and parameters
6. The AI receives the full skill prompt and follows the instructions

#### Path 2: UI Skill Button
1. User taps the Skill button (next to or above the input box)
2. A bottom sheet or popup shows the categorized list of available skills
3. User selects a skill
4. If the skill has required parameters, a parameter input form is shown
5. Same flow as Path 1 from step 5 onward

#### Path 3: AI Self-Invocation
1. During conversation, the AI recognizes the user's request maps to an available skill
2. The AI calls `load_skill` with the skill name
3. The AI follows the loaded instructions
4. The user sees in the chat that a skill was loaded (displayed as a tool call)

### Skill and Agent Relationship

Skills are **independent of Agents**. They are a global resource available to any Agent in any session. This means:
- All skills are available regardless of which Agent is active
- The skill registry in the system prompt is the same for all Agents
- An Agent's tool set does NOT affect skill availability (but a skill may fail to execute if it requires a tool that the current Agent doesn't have access to -- the AI should handle this gracefully)

### Skill Storage

Each skill is stored as a `SKILL.md` file inside a directory named after the skill (following the Claude Code convention):

```
skills/
  summarize-file/
    SKILL.md
  translate-file/
    SKILL.md
  fetch-webpage/
    SKILL.md
  my-custom-skill/
    SKILL.md
```

The directory name serves as the skill identifier. The `SKILL.md` file contains the complete skill definition (YAML frontmatter + Markdown prompt content).

#### Built-in Skills
- Stored as asset files bundled with the app (in `assets/skills/<skill-name>/SKILL.md`)
- Read-only, cannot be modified or deleted by the user
- Updated with app updates

#### User-defined Skills
- Stored in app-internal storage (e.g., `files/skills/<skill-name>/SKILL.md`)
- User can create, edit, and delete
- Created through in-app UI (skill editor) or imported from file

#### Skill File Locations
- Built-in: `assets/skills/summarize-file/SKILL.md`
- User-defined: `{app-internal}/skills/my-custom-skill/SKILL.md`

The directory-based structure also allows future expansion -- a skill directory could contain additional resources (e.g., example files, locale-specific variants) alongside the `SKILL.md`.

### User-Defined Skill Creation

Users can create custom skills through the app:

1. Navigate to Skill Management screen
2. Tap "Create Skill"
3. Fill in:
   - Name (required, snake-case, used as identifier)
   - Display Name (required, human-readable)
   - Description (required, one sentence)
   - Parameters (optional, define name + type + required flag + description for each)
   - Prompt Content (required, the Markdown instructions -- the core of the skill)
   - Required Tools (optional, list of tools the skill needs)
4. Save -- the skill is immediately available

For advanced users, they can also directly create/edit the `SKILL.md` file in the skill directory using the file system (or via the `write_file` tool).

### Skill Import / Export / Sharing

#### Export
- User can export any skill (built-in or custom) as its `SKILL.md` file (or as a zip of the skill directory for future extensibility)
- The exported file contains the complete skill definition (frontmatter + content)
- User can share the file via Android share intent (email, messaging apps, file transfer, etc.)

#### Import
- User can import a `SKILL.md` file from any source
- The app validates the file structure (valid frontmatter, has required fields)
- The app creates a skill directory named after the skill's `name` field and stores the `SKILL.md` inside it
- If a skill with the same name already exists, the user is prompted to rename or replace
- Imported skills are stored as user-defined skills (editable)

### Skill Display in Chat

When a skill is loaded and executing, the chat shows:
1. **Skill load indicator**: Shows which skill was loaded (e.g., "Loaded skill: Summarize File")
2. **Tool calls**: Normal tool call display as defined in FEAT-001
3. **Skill result**: The AI's response following the skill's instructions (normal AI message)

The skill load is displayed as a regular tool call (`load_skill`), keeping the UI consistent with the existing tool call display.

### User Interaction Flows

#### Creating a Custom Skill
```
1. User navigates to Skill Management screen (via Settings or menu)
2. User taps "Create Skill"
3. System shows skill creation form
4. User fills in name, display name, description
5. User optionally defines parameters
6. User writes the prompt content (Markdown editor)
7. User optionally selects required tools
8. User taps "Save"
9. System validates and saves the skill
10. Skill is immediately available in / commands and Skill button
```

#### Using a Skill via / Command
```
1. User types "/" in chat input box
2. System shows autocomplete list of available skills
3. User selects or continues typing to filter (e.g., "/sum" shows "summarize-file")
4. User selects "summarize-file"
5. System shows parameter input for required params (e.g., file_path)
6. User provides the file path
7. System sends message: "Use the summarize-file skill on /path/to/file"
8. AI calls load_skill, receives instructions, follows the workflow
9. AI produces the structured summary
```

#### Importing a Shared Skill
```
1. User receives a SKILL.md file (via email, messaging, etc.)
2. User opens the file with OneClaw (Android intent filter)
   OR: User uses "Import Skill" in Skill Management screen and picks the file
3. System parses and validates the file
4. If valid: shows skill preview (name, description, content)
5. User taps "Import"
6. System creates a directory named after the skill and saves SKILL.md inside it
7. Skill appears in skill list and is immediately usable
```

## Acceptance Criteria

Must pass (all required):
- [ ] Built-in skills are bundled with the app and available on first launch
- [ ] `load_skill` tool exists and correctly loads skill content by name
- [ ] System prompt includes a brief registry of all available skills
- [ ] User can trigger skills via `/` command in the chat input box
- [ ] Typing `/` shows an autocomplete list of available skills
- [ ] User can trigger skills via a dedicated Skill UI button
- [ ] AI can autonomously call `load_skill` when it recognizes a skill matches the user's request
- [ ] Skill parameters are prompted to the user when a skill has required parameters
- [ ] Parameter values are substituted into the skill prompt template ({{param_name}} placeholders)
- [ ] Skill loading is displayed in chat as a `load_skill` tool call
- [ ] User can create custom skills via in-app skill editor
- [ ] User can edit and delete custom skills
- [ ] Built-in skills cannot be edited or deleted
- [ ] User can export any skill as a `SKILL.md` file
- [ ] User can import a `SKILL.md` file as a user-defined skill
- [ ] Import validates file structure and prompts for name conflicts
- [ ] Skills are independent of Agents (available to all Agents)
- [ ] If a skill requires a tool not available to the current Agent, the AI handles this gracefully (informs the user)

Optional (nice to have for V1):
- [ ] Skill usage statistics (how often each skill is used)
- [ ] Skill version checking on import (warn if importing older version)
- [ ] Skill categories / tags for organization
- [ ] Clone built-in skill to create an editable copy
- [ ] Skill preview before import (full content view)

## UI/UX Requirements

### Skill Trigger: / Command
- When user types `/` as the first character in the input box, show a popup list above the input
- List shows skill display names with descriptions
- Filter as user types (e.g., `/sum` filters to skills matching "sum")
- Tapping a skill selects it
- If skill has required parameters, show a compact inline form or dialog
- The `/` trigger only works at the beginning of a message (to avoid interfering with normal text containing `/`)

### Skill Trigger: UI Button
- A small icon button near the input area (e.g., a lightning bolt or similar icon)
- Tapping opens a bottom sheet listing all available skills
- Skills grouped by category (File, Network, Text, System)
- Built-in skills visually distinguished from user-defined
- Tapping a skill triggers it (with parameter prompt if needed)

### Skill Management Screen
- Accessible from Settings or a dedicated navigation item
- List view with two sections: "Built-in" and "Custom"
- Each item shows: display name, description, badge (built-in/custom)
- "Create Skill" FAB or top action button
- Tap to view/edit, swipe or long-press for actions (delete, export, clone)

### Skill Editor Screen
- Form layout for creating/editing skills:
  - Name (text input, snake-case enforced)
  - Display Name (text input)
  - Description (text input, single line)
  - Version (text input, default "1.0")
  - Parameters section (add/remove parameter entries, each with name/type/required/description)
  - Required Tools (multi-select from available tools)
  - Prompt Content (multiline text editor, Markdown supported, large area)
- For built-in skills: all fields read-only, with "Clone" and "Export" buttons
- For custom skills: all fields editable, with "Save", "Delete", and "Export" buttons

### Skill Detail View (for viewing built-in skills)
- Shows all skill metadata and full prompt content in read-only mode
- "Clone" button to create an editable copy
- "Export" button to share

### Interaction Feedback
- Skill loaded: tool call display in chat
- Skill created/saved: success snackbar
- Skill deleted: confirmation dialog, then success snackbar
- Skill imported: success snackbar with skill name
- Import error: error dialog with details (invalid format, missing fields, etc.)

## Feature Boundary

### Included
- `load_skill` built-in tool for loading skill prompt content
- Skill registry injection into system prompt
- / command trigger with autocomplete in chat input
- UI button trigger with skill selection bottom sheet
- AI self-invocation via `load_skill` tool
- Built-in skills: file operations, network/info, text processing, system/device categories
- User-defined skill creation via in-app editor
- Skill editing and deletion (custom only)
- Skill export as `SKILL.md` file
- Skill import from `SKILL.md` file with validation
- Parameter definition and substitution in skill prompts
- Skill Management screen for viewing and managing all skills

### Not Included (V1)
- Skill marketplace or online skill store
- Skill versioning / version history (only current version stored)
- Skill chaining (one skill triggering another skill)
- Skill-specific UI beyond the generic parameter input form
- Conditional logic in skill prompts (no if/else, loops -- just linear prompt instructions)
- Skill execution approval flow (skills use existing tool permissions)
- Skill analytics dashboard (basic usage count is optional)
- Skill scheduling / automation (e.g., "run this skill daily")
- Encrypted or DRM-protected skills
- Cloud sync of skills (local only; share via export/import)

## Business Rules

### Skill Rules
1. Every skill must have a unique name (enforced at creation and import)
2. Skill names follow snake-case convention (e.g., `summarize-file`)
3. Skill names must be 2-50 characters, containing only lowercase letters, numbers, and hyphens
4. Display names must be non-empty
5. Description must be non-empty
6. Prompt content must be non-empty
7. Built-in skills are read-only and cannot be deleted
8. User-defined skills can be created, edited, and deleted freely
9. Skills are global resources, independent of Agents

### Parameter Rules
1. Parameter names follow snake_case convention
2. Supported parameter types: `string` (V1 only, more types can be added later)
3. Required parameters must be provided before skill execution
4. Parameter placeholders in prompt content use `{{parameter_name}}` syntax
5. If a required parameter is not provided, the AI should ask the user for it

### Trigger Rules
1. `/` command trigger only activates when `/` is the first character in the input
2. The autocomplete list filters based on both `name` and `display_name`
3. If a skill has no required parameters, it triggers immediately
4. If a skill has required parameters, a parameter input form is shown before triggering
5. AI can trigger skills autonomously via the `load_skill` tool without user intervention

### Import/Export Rules
1. Exported files are named `SKILL.md`
2. Import validates: file is valid Markdown, has valid YAML frontmatter, has required fields (name, display_name, description, prompt content)
3. On import, the system creates a directory named after the skill's `name` field and stores `SKILL.md` inside it
4. If a skill with the same name exists on import (i.e., directory already exists), user is prompted to choose: rename or replace
5. Imported skills are always stored as user-defined (even if exported from built-in)

## Non-Functional Requirements

### Performance
- Skill registry generation (for system prompt) completes in < 50ms
- `load_skill` tool execution completes in < 100ms (local file read)
- / command autocomplete list appears in < 100ms
- Skill import validation completes in < 200ms
- Skill creation/save completes in < 200ms

### Data
- Skill files use UTF-8 encoding
- Maximum skill file size: 100KB (prompt content is typically 1-10KB)
- No limit on number of user-defined skills (practical limit by storage)

### Security
- Skill prompt content is plain text -- it cannot execute code directly
- Imported skills should be treated as untrusted -- they can only guide the AI's behavior using existing tools, which already have their own permission checks
- Skill files do not contain any secrets or API keys
- The `load_skill` tool only reads from the designated skill directories (cannot read arbitrary files)

## Dependencies

### Depends On
- **FEAT-004 (Tool System)**: `load_skill` is a new built-in tool; skills reference existing tools
- **FEAT-001 (Chat Interaction)**: Skill trigger via `/` command in chat input; skill load display in chat

### Depended On By
- No other features currently depend on Agent Skill

### External Dependencies
- None (all local, no network required)

## Error Handling

### Error Scenarios

1. **Skill not found**
   - Cause: `load_skill` called with a name that doesn't match any skill
   - Handling: Return error to AI: "Skill [name] not found. Available skills: [list]"
   - The AI can suggest available skills to the user

2. **Invalid skill file on import**
   - Cause: Imported file is not valid Markdown, missing frontmatter, or missing required fields
   - Handling: Show error dialog: "Invalid skill file: [specific reason]"
   - User can fix the file and try again

3. **Skill requires unavailable tool**
   - Cause: Skill's `tools_required` includes a tool not in the current Agent's tool set
   - Handling: `load_skill` succeeds (skill is still loaded), but includes a warning: "Note: this skill requires [tool_name] which is not available to the current Agent"
   - The AI should inform the user of the limitation

4. **Parameter placeholder not resolved**
   - Cause: Skill prompt contains `{{param}}` but the parameter was not provided
   - Handling: The AI should detect unresolved placeholders and ask the user for the missing values

5. **Skill name conflict on import**
   - Cause: Importing a skill with a name that already exists
   - Handling: Prompt user: "A skill named [name] already exists. Rename or replace?"

6. **Skill file read error**
   - Cause: File system error when reading skill file
   - Handling: Return error to AI: "Failed to load skill [name]: [error details]"

## Future Improvements

- [ ] **Skill marketplace**: Online repository of community-contributed skills
- [ ] **Skill chaining**: One skill can invoke another skill as a sub-workflow
- [ ] **Advanced parameter types**: Support for `number`, `boolean`, `enum`, `file_picker` parameter types
- [ ] **Conditional logic**: Support for `{{#if}}` / `{{#each}}` in skill templates
- [ ] **Skill versioning**: Track changes, support rollback
- [ ] **Cloud sync**: Sync skills across devices via Google Drive
- [ ] **Skill analytics**: Detailed usage statistics, success/failure rates
- [ ] **Skill templates**: Pre-defined templates for common skill patterns (to help users create skills)
- [ ] **Skill categories**: User-defined categories/tags for organizing large skill libraries
- [ ] **Context-aware skill suggestions**: AI proactively suggests relevant skills based on conversation context

## Test Points

### Functional Tests
- Verify built-in skills are available on fresh install
- Verify `load_skill` tool loads correct skill content
- Verify `load_skill` returns error for non-existent skill
- Verify system prompt contains skill registry listing
- Verify `/` command in input box shows skill autocomplete
- Verify autocomplete filters by name and display name
- Verify selecting a skill with no required params triggers immediately
- Verify selecting a skill with required params shows parameter form
- Verify parameter values are substituted in prompt content
- Verify UI Skill button opens skill selection bottom sheet
- Verify AI can autonomously call `load_skill`
- Verify custom skill creation saves correctly
- Verify custom skill editing updates correctly
- Verify custom skill deletion removes from list and / command
- Verify built-in skills cannot be edited or deleted
- Verify skill export produces valid `SKILL.md` file
- Verify skill import parses and stores correctly
- Verify import rejects invalid files with clear error
- Verify import handles name conflicts (rename/replace)
- Verify skills work regardless of which Agent is active

### Edge Cases
- Skill with empty parameters list (no params needed)
- Skill with maximum size prompt content (approaching 100KB)
- Import a skill exported from a different app version
- Create two skills with very similar names
- Delete a skill while it's being used in a conversation
- Import a skill that requires a tool not yet implemented
- `/` command when no skills are available
- Very long parameter values
- Skill prompt with no `{{}}` placeholders (no parameter substitution needed)
- Rapid consecutive skill triggers

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
