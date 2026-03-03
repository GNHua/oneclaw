# Agent Management Enhancement

## Feature Information
- **Feature ID**: FEAT-020
- **Created**: 2026-02-28
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-020 (Agent Management Enhancement)](../../rfc/features/RFC-020-agent-enhancement.md)
- **Extends**: [FEAT-002 (Agent Management)](FEAT-002-agent.md)

## User Story

**As** a user of OneClaw,
**I want to** have better control over agent management -- including a working model selector, clone/delete for all custom agents, and the ability to create agents from a prompt (both from the Agent screen and during chat via a tool),
**so that** I can efficiently create, customize, and manage agents to fit my needs without leaving my current workflow.

### Typical Scenarios
1. User opens a custom agent's detail page and wants to clone it to create a variant -- currently no clone button is shown.
2. User tries to select a preferred model for an agent but the selector does not respond to interaction.
3. User wants to quickly create an agent by describing its purpose in natural language rather than manually filling in all fields.
4. User is chatting with the AI and says "help me create a Python coding assistant agent" -- the AI uses the `create_agent` tool to create the agent directly from the conversation.

## Feature Description

### Overview
This feature addresses four areas of improvement to the existing Agent Management (FEAT-002):

1. **Fix Preferred Model Selector** -- The model selection UI currently shows available models but does not allow proper selection. Replace with a functional dropdown/picker.
2. **Clone & Delete for All Agents** -- Clone button should be available on all agents (built-in and custom). Delete button should be available on all custom agents. Custom agents show both Clone and Delete.
3. **Prompt-based Agent Creation (UI)** -- A new creation flow on the Agent screen where the user describes what kind of agent they want in natural language, and the system uses AI to generate the agent's name, description, and system prompt. The user can preview and edit before saving.
4. **`create_agent` Tool (Chat)** -- A built-in tool that allows the AI to create agents during a chat conversation. When a user asks the AI to create an agent in the chat, the AI can use this tool to directly create the agent without leaving the chat screen.

### Detailed Specification

#### 1. Preferred Model Selector Fix

Current state: The `PreferredModelSelector` composable shows model names as `TextButton` items, but the interaction is not intuitive and may not be functional in some contexts.

Target state: Replace with an `ExposedDropdownMenuBox` (Material 3 dropdown) that:
- Shows the current selection or "Using global default" as the trigger
- Opens a scrollable dropdown list of available models grouped by provider
- Allows clearing the selection back to global default
- Is disabled (read-only) for built-in agents

#### 2. Clone & Delete Button Logic

Current button visibility:
| Agent Type | Clone | Delete |
|-----------|-------|--------|
| Built-in | Yes | No |
| Custom (existing) | No | Yes |
| Custom (new/unsaved) | No | No |

Target button visibility:
| Agent Type | Clone | Delete |
|-----------|-------|--------|
| Built-in | Yes | No |
| Custom (existing) | Yes | Yes |
| Custom (new/unsaved) | No | No |

Key rules:
- Built-in agents: Clone only (cannot delete)
- Custom agents (already saved): Both Clone and Delete
- New agents (not yet saved): Neither (save first)

#### 3. Prompt-based Agent Creation

New creation flow in addition to the existing manual creation:

```
1. User navigates to Agent List screen
2. User taps "Create Agent" button
3. System shows the Create Agent screen (same as today) but with an additional
   "Generate from Prompt" section at the top
4. User types a description of the desired agent (e.g., "A Python coding assistant
   that helps with debugging and code review")
5. User taps "Generate"
6. System sends the description to the configured AI model and generates:
   - Agent name
   - Agent description
   - Agent system prompt
7. Generated fields populate the form below
8. User reviews and optionally edits the generated content
9. User taps "Save" to create the agent
```

The generation prompt template is hardcoded in the app. It instructs the AI to produce a JSON object with `name`, `description`, and `systemPrompt` fields.

#### 4. `create_agent` Tool

A new built-in tool registered in the `ToolRegistry` that enables agent creation from within a chat conversation.

**Tool Definition:**
- **Name**: `create_agent`
- **Description**: "Create a new custom AI agent with a name, description, and system prompt."
- **Parameters**:
  - `name` (string, required) -- Agent name
  - `description` (string, optional) -- Short description of the agent
  - `system_prompt` (string, required) -- The system prompt for the agent
- **Returns**: Success message with the created agent's name and ID, or an error message

**User Interaction Flow:**
```
1. User is in a chat conversation
2. User says: "Create me a Python debugging assistant agent"
3. The AI decides to use the create_agent tool, composing the name,
   description, and system_prompt parameters itself
4. The tool creates the agent in the database
5. The tool returns a success message to the AI
6. The AI informs the user that the agent has been created
7. The user can find the new agent in the Agent List
```

The AI is responsible for composing the agent's fields based on the user's request. Unlike the UI "Generate from Prompt" flow, the tool does not call another AI to generate fields -- the current AI in the conversation directly provides the values as tool parameters.

## Acceptance Criteria

Must pass (all required):
- [ ] Preferred model selector uses a proper dropdown menu that responds to user taps
- [ ] Preferred model dropdown shows all available models from active providers
- [ ] Preferred model can be cleared back to "Using global default"
- [ ] Preferred model selector is disabled for built-in agents
- [ ] Clone button is visible on custom (saved) agents
- [ ] Clone button is visible on built-in agents
- [ ] Delete button is visible only on custom (saved) agents
- [ ] Built-in agents do not show a Delete button
- [ ] New (unsaved) agents show neither Clone nor Delete
- [ ] Cloning a custom agent creates a new agent named "Copy of [Original Name]"
- [ ] "Generate from Prompt" input field is visible on the Create Agent screen
- [ ] User can type a prompt and tap "Generate" to auto-fill agent fields
- [ ] Generated name, description, and system prompt populate the form
- [ ] User can edit generated content before saving
- [ ] Generation shows a loading indicator while waiting for AI response
- [ ] Generation failure shows an error message without losing user input
- [ ] `create_agent` tool is registered in the ToolRegistry and available to all agents
- [ ] AI can invoke `create_agent` tool during a chat conversation to create a new agent
- [ ] `create_agent` tool validates that name and system_prompt are non-empty
- [ ] `create_agent` tool returns the created agent's name and ID on success
- [ ] `create_agent` tool returns an error message on failure
- [ ] Newly created agent via tool appears in the Agent List

Optional (nice to have):
- [ ] "Generate from Prompt" also available on existing agent edit screens to regenerate system prompt
- [ ] Prompt history / suggestions for common agent types

## UI/UX Requirements

### Preferred Model Selector
- Material 3 `ExposedDropdownMenuBox` pattern
- Trigger shows current model as "Provider / Model" or "Using global default"
- Dropdown items grouped by provider with provider name as section header
- Maximum dropdown height to allow scrolling for many models
- "Clear" option at the top of the dropdown to reset to global default

### Clone & Delete Buttons
- Both buttons appear in the action section at the bottom of the agent detail form
- Clone button: neutral/default color, text "Clone Agent"
- Delete button: error/red color, text "Delete Agent"
- Clone appears above Delete when both are visible
- Same confirmation dialog for delete as existing implementation

### Generate from Prompt Section
- Located at the top of the Create Agent screen, above the name field
- `OutlinedTextField` with label "Describe the agent you want to create..."
- Multiline, 3-5 lines visible
- "Generate" button to the right or below the text field
- Loading state: button shows a circular progress indicator, field becomes read-only
- After generation: form fields below are populated, the prompt field remains visible with the original text

## Feature Boundary

### Included
- Fix preferred model dropdown interaction
- Clone button on all saved agents
- Delete button on custom saved agents only
- Prompt-based agent creation on Create Agent screen
- AI-generated name, description, and system prompt from user description
- `create_agent` built-in tool for creating agents from within chat conversations

### Not Included
- Prompt-based editing of existing agents (future enhancement)
- Multi-turn conversation for agent refinement
- Agent template library or marketplace
- Automatic tool selection based on prompt (tools remain manual selection, deferred)
- Tool for deleting or editing existing agents from chat (future enhancement)
- Tool for switching the current session's agent from chat (already handled by agent switcher UI)

## Business Rules

### Model Selector Rules
1. Only models from active (enabled) providers appear in the dropdown
2. If the currently selected model's provider becomes inactive, the selection persists but shows a warning
3. Clearing the preferred model means the agent uses the global default

### Clone Rules
1. Cloning any agent (built-in or custom) creates a new custom agent
2. Clone name is "Copy of [Original Name]"
3. Clone inherits: description, system prompt, preferred model/provider
4. Clone does NOT inherit: isBuiltIn flag (always false)
5. After clone, user navigates back to agent list (existing behavior)

### Prompt Generation Rules (UI)
1. Generation requires at least one active provider with a configured API key
2. The model used for generation is the global default model
3. If generation fails, the form fields are not modified
4. Generated system prompt should be detailed and contextual based on the user's description
5. The user's prompt description is not saved as part of the agent

### `create_agent` Tool Rules
1. The tool creates a custom agent (never built-in)
2. `name` parameter is required and must be non-empty
3. `system_prompt` parameter is required and must be non-empty
4. `description` parameter is optional; defaults to empty string
5. The created agent uses global default model (no preferred model set via tool)
6. The tool does not switch the current session to the new agent; the user can switch manually
7. Duplicate agent names are allowed (consistent with existing behavior)

## Dependencies

### Depends On
- **FEAT-002 (Agent Management)**: This feature extends FEAT-002
- **FEAT-003 (Provider Management)**: Needed for model list and for AI generation
- **FEAT-001 (Chat)**: Reuses the API adapter infrastructure for prompt-based generation
- **FEAT-004 (Tool System)**: The `create_agent` tool integrates with the tool system

### Depended On By
- None currently

## Error Handling

### Error Scenarios

1. **No active providers configured when trying to generate from prompt**
   - Display: "No AI providers configured. Please set up a provider first."
   - User action: Navigate to Provider Management

2. **AI generation fails (network error, API error)**
   - Display: "Failed to generate agent. Please try again or fill in the fields manually."
   - User action: Retry or manually fill form

3. **AI returns malformed response**
   - Display: Same error as above
   - Handling: Parse what can be parsed, leave remaining fields empty

4. **Clone fails (storage error)**
   - Display: "Failed to clone agent. Please try again."
   - User action: Retry

5. **`create_agent` tool called with empty name or system_prompt**
   - Tool returns: `ToolResult.error("validation_error", "Parameter 'name' is required and must be non-empty")`
   - AI reports the error to the user in the chat

6. **`create_agent` tool fails to save (storage error)**
   - Tool returns: `ToolResult.error("creation_failed", "Failed to create agent: ...")`
   - AI reports the error to the user in the chat

## Test Points

### Functional Tests
- Verify preferred model dropdown opens and lists all active provider models
- Verify selecting a model from dropdown updates the agent's preferred model
- Verify clearing preferred model works
- Verify dropdown is disabled for built-in agents
- Verify Clone button is visible for saved custom agents
- Verify Clone button is visible for built-in agents
- Verify Delete button is NOT visible for built-in agents
- Verify Delete button is NOT visible for new unsaved agents
- Verify prompt-based generation populates name, description, and system prompt
- Verify generation loading state
- Verify generation error handling
- Verify `create_agent` tool is registered and listed in available tools
- Verify `create_agent` tool creates an agent when called with valid parameters
- Verify `create_agent` tool returns error for missing required parameters
- Verify agent created via tool appears in Agent List

### Edge Cases
- Generate from prompt with very short input (e.g., "helper")
- Generate from prompt with very long input
- Generate while no providers are active
- Clone an agent that was itself a clone
- Select a model, then deactivate its provider, then open agent detail
- `create_agent` tool called with very long system_prompt (up to 50,000 chars)
- `create_agent` tool called with name that exceeds 100 char limit

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
| 2026-03-01 | 0.2 | Added `create_agent` tool for chat-based agent creation | - |
