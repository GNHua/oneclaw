# Agent Management

## Feature Information
- **Feature ID**: FEAT-002
- **Created**: 2026-02-26
- **Last Updated**: 2026-02-26
- **Status**: Draft
- **Priority**: P0 (Must Have)
- **Owner**: TBD
- **Related RFC**: [RFC-002 (Agent Management)](../../rfc/features/RFC-002-agent-management.md)
- **Related Design**: [UI Design Spec](../../design/ui-design-spec.md)

## User Story

**As** a user of OneClawShadow,
**I want to** create and manage AI agents with different system prompts and tool configurations,
**so that** I can customize the AI's behavior for different tasks and use cases.

### Typical Scenarios
1. User opens the app for the first time and starts a conversation with the built-in "General Assistant" agent.
2. User creates a custom "Writing Assistant" agent with a writing-focused system prompt and no file-access tools.
3. User is mid-conversation with the General Assistant, realizes they need a specialized agent, and switches to their "Data Analyst" agent without losing conversation history.
4. User clones the built-in General Assistant to create a customized version with a modified system prompt.
5. User deletes a custom agent they no longer need.

## Feature Description

### Overview
Agent Management allows users to create, configure, and manage AI agents. An Agent is a pre-configured AI persona defined by a name, description, system prompt, a set of available tools, and a preferred model/provider. The app ships with built-in default agents that serve as starting points. Users can clone built-in agents and create fully custom agents. Agents can be switched mid-conversation, changing the system prompt and available tools while preserving conversation history.

### Agent Data Model

Each Agent consists of:

| Field | Required | Description |
|-------|----------|-------------|
| ID | Yes | Unique identifier, auto-generated |
| Name | Yes | Display name (e.g., "General Assistant", "Writing Helper") |
| Description | No | Short description of what this agent does |
| System Prompt | Yes | The system prompt sent to the model |
| Tool Set | Yes | List of tools this agent can use (can be empty) |
| Model/Provider | No | Preferred model and provider for this agent. If not set, uses the session's or global default |
| Is Built-in | Yes | Whether this is a built-in agent (read-only) |
| Created At | Yes | Timestamp of creation |
| Updated At | Yes | Timestamp of last update |

### Built-in Agents

The app ships with the following built-in agent(s):

1. **General Assistant** (default)
   - System prompt: A general-purpose helpful assistant prompt
   - Tool set: All available built-in tools
   - This is the default agent for new sessions

Additional built-in agents may be added in future versions. Built-in agents serve as templates and references for users to understand how to configure their own agents.

### Agent Management Operations

#### View Agent List
- Display all agents in a list (built-in first, then custom sorted by last updated)
- Each list item shows: agent name, description (truncated), built-in badge (if applicable)
- Tapping an agent opens its detail/edit view

#### Create Agent
- User taps "Create Agent" button
- Fills in: name (required), description (optional), system prompt (required)
- Selects available tools from the full tool list (checkboxes)
- Optionally selects a preferred model/provider
- Saves the agent

#### Edit Agent
- Only custom (non-built-in) agents can be edited
- User can modify all fields: name, description, system prompt, tool set, model/provider
- Changes are saved and take effect on the next message sent in any session using this agent

#### Delete Agent
- Only custom (non-built-in) agents can be deleted
- Confirmation dialog before deletion
- If any existing sessions are currently using this agent, those sessions fall back to the General Assistant

#### Clone Agent
- Available for all agents (built-in and custom)
- Creates a copy of the agent with all its configuration
- The clone is named "[Original Name] (Copy)" by default
- The clone is a custom agent and can be freely edited
- This is the primary way for users to customize built-in agents

### Agent and Session Relationship

- Each session has a "current agent" field
- New sessions default to the General Assistant
- Users can switch the current agent mid-conversation
- When switching agents:
  - The system prompt changes to the new agent's system prompt
  - The available tool set changes to the new agent's tools
  - All existing conversation history is preserved
  - The next request to the model uses: [new system prompt] + [full conversation history] + [new user message]
- Sessions do not "depend on" agents existing -- if an agent is deleted, the session falls back to General Assistant

### User Interaction Flow

#### Creating a New Agent
```
1. User navigates to Agent Management screen
2. User taps "Create Agent"
3. System shows agent creation form
4. User fills in name and system prompt (required)
5. User optionally adds description
6. User selects available tools from the tool list
7. User optionally selects preferred model/provider
8. User taps "Save"
9. System validates and saves the agent
10. User returns to agent list, new agent is visible
```

#### Switching Agent Mid-Conversation
```
1. User is in an active chat session
2. User taps agent selector in the chat toolbar
3. System shows a list of available agents
4. User selects a different agent
5. System updates the current agent for this session
6. The next message uses the new agent's configuration
7. A system indicator in the chat shows "Switched to [Agent Name]"
```

#### Cloning a Built-in Agent
```
1. User views a built-in agent's details
2. User taps "Clone"
3. System creates a copy with "(Copy)" appended to the name
4. System opens the clone in edit mode
5. User modifies the configuration as desired
6. User taps "Save"
```

## Acceptance Criteria

Must pass (all required):
- [ ] App ships with a built-in "General Assistant" agent
- [ ] Built-in agents cannot be edited or deleted
- [ ] Built-in agents can be cloned
- [ ] User can create a new custom agent with name and system prompt
- [ ] User can select which tools an agent has access to
- [ ] User can optionally set a preferred model/provider for an agent
- [ ] User can edit custom agents (all fields)
- [ ] User can delete custom agents with confirmation dialog
- [ ] Deleting an agent that is in use by a session causes the session to fall back to General Assistant
- [ ] User can switch agent mid-conversation from the chat screen
- [ ] Switching agent preserves all conversation history
- [ ] After switching, the next model request uses the new agent's system prompt and tool set
- [ ] A visual indicator in the chat shows when the agent was switched
- [ ] New sessions default to the General Assistant
- [ ] Agent list displays built-in agents first, then custom agents
- [ ] Cloning an agent creates an editable copy with all configuration

Optional (nice to have for V1):
- [ ] Agent icon/avatar (user can pick an icon for their agent)
- [ ] Agent usage statistics (how many sessions use this agent)
- [ ] Search/filter in agent list

## UI/UX Requirements

### Agent List Screen
- List view of all agents
- Built-in agents visually distinguished (e.g., a badge or label)
- Each item shows: name, description snippet, built-in badge
- "Create Agent" button (FAB or top action)
- Tap to view details, long-press or swipe for quick actions (delete, clone)

### Agent Detail / Edit Screen
- Form layout with fields:
  - Name (text input)
  - Description (text input, multiline)
  - System Prompt (text input, multiline, large area)
  - Tool Selection (list of available tools with checkboxes)
  - Model/Provider (dropdown or picker, optional)
- For built-in agents: all fields are read-only, with a prominent "Clone" button
- For custom agents: all fields are editable, with "Save" and "Delete" buttons

### Agent Switcher (in Chat Screen)
- Accessible by tapping the Agent name in the top app bar center (displays as "[Agent Name v]" with dropdown chevron)
- Shows a bottom sheet or popup menu of available agents
- Current agent has a checkmark
- Built-in agents have a subtle badge
- Tapping a different agent switches immediately
- A system message appears in the chat: "Switched to [Agent Name]"
- See [UI Design Spec](../../design/ui-design-spec.md) for detailed layout

### Interaction Feedback
- Save: confirmation toast/snackbar
- Delete: confirmation dialog, then success toast
- Clone: opens edit view of the cloned agent
- Switch: inline indicator in chat

## Feature Boundary

### Included
- Built-in General Assistant agent
- Create, edit, delete, clone custom agents
- Agent configuration: name, description, system prompt, tool set, model/provider
- Mid-conversation agent switching
- Session fallback when agent is deleted
- Agent list management UI
- Agent switcher in chat UI

### Not Included (V1)
- Agent sharing / import / export
- Sub-agent / multi-agent orchestration
- Agent marketplace or community agents
- Agent-specific conversation history (all history stays in the session)
- Agent versioning (no history of agent configuration changes)
- Agent scheduling or automation (e.g., "run this agent at 9am daily")

## Business Rules

### Agent Rules
1. Every session must have a current agent at all times
2. The General Assistant is the system default and cannot be removed
3. Built-in agents are read-only; users must clone to customize
4. Agent names must be non-empty; duplicates are allowed (but discouraged by UI)
5. An agent's system prompt must be non-empty
6. An agent's tool set can be empty (the agent just chats without tools)
7. If no model/provider is set on the agent, the session's or global default is used. The model resolution order is:
   1. Agent's preferred model/provider (if set)
   2. Global default model/provider (configured in FEAT-003)
   
   This means the agent's model setting is optional -- it overrides the global default when present, but falls back gracefully when not set. This allows users to create agents that are model-agnostic (just a system prompt + tools) or model-specific (e.g., an agent that always uses a particular model for best results)

### Switching Rules
1. Agent switching is instant; no confirmation dialog needed
2. Switching does not modify past messages
3. The switch point is recorded in the session (for display purposes)
4. If the new agent has a different tool set, only the new tools are available from that point forward

### Deletion Rules
1. Deleting a custom agent requires user confirmation
2. Sessions using a deleted agent automatically fall back to General Assistant
3. Historical messages in those sessions remain unchanged
4. Built-in agents cannot be deleted

## Non-Functional Requirements

### Performance
- Agent list should load in < 100ms
- Agent switching should take effect in < 100ms (no network call needed, it's a local config change)
- Saving an agent should complete in < 200ms

### Data
- All agent data stored locally
- Agent data included in Google Drive sync (FEAT-007)
- Built-in agents are defined in app code, not in the database (they reset on app update)

## Dependencies

### Depends On
- **FEAT-004 (Tool System)**: Agent configuration references the available tool list
- **FEAT-003 (Model/Provider Management)**: Agent can optionally reference a model/provider

### Depended On By
- **FEAT-001 (Chat Interaction)**: Chat uses the current session's agent to determine system prompt and tools
- **FEAT-005 (Session Management)**: Sessions reference an agent

## Error Handling

### Error Scenarios

1. **Agent save fails (e.g., storage error)**
   - Display: Error toast "Failed to save agent. Please try again."
   - Data: No partial save; either all fields save or none

2. **Agent referenced by session is deleted by another process**
   - Handling: Session falls back to General Assistant on next load
   - Display: No error; the fallback is silent

3. **System prompt is empty on save attempt**
   - Display: Validation error on the form field "System prompt is required"
   - Save button remains disabled until corrected

## Future Improvements

- [ ] **Agent sharing / import / export**: Export agent configurations to share with others, import others' agents
- [ ] **Sub-agent / multi-agent**: A main agent can delegate tasks to sub-agents with their own profiles
- [ ] **Agent versioning**: Track changes to agent configuration over time
- [ ] **Agent icons / avatars**: Visual customization for agents
- [ ] **Agent templates library**: A curated set of agent templates beyond the built-in ones

## Test Points

### Functional Tests
- Verify General Assistant exists on first app launch
- Verify built-in agents cannot be edited or deleted
- Verify clone creates an editable copy with all configuration
- Create a custom agent and verify all fields are saved
- Edit a custom agent and verify changes persist
- Delete a custom agent and verify it's removed from the list
- Delete an agent in use by a session and verify fallback to General Assistant
- Switch agent mid-conversation and verify system prompt changes
- Verify conversation history is preserved after agent switch
- Verify switch indicator appears in chat
- Verify new sessions default to General Assistant
- Verify agent list ordering (built-in first, then custom)

### Edge Cases
- Create agent with maximum length name and system prompt
- Delete all custom agents (only built-in remains)
- Switch agent rapidly multiple times in one session
- Create two agents with the same name
- Clone an agent and then delete the original

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-26 | 0.1 | Initial version | - |
| 2026-02-27 | 0.2 | Updated agent switcher to chip above input area (per UI Design Spec); added design spec reference | - |
| 2026-02-27 | 0.3 | Added RFC-002 reference | - |
