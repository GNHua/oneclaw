# Configuration Management Tools

## Feature Information
- **Feature ID**: FEAT-036
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P0 (Must Have)
- **Owner**: TBD
- **Related RFC**: RFC-036 (pending)

## User Story

**As** a user of OneClawShadow,
**I want to** configure every aspect of the app through conversation with the AI agent,
**so that** I never need to leave the chat to adjust providers, models, agents, themes, tool states, or environment variables.

### Typical Scenarios

1. User says "Add an OpenAI provider with base URL https://api.openai.com/v1" -- the agent calls `create_provider` and returns a confirmation. The user then sets the API key through the Settings UI (the only operation requiring UI).
2. User says "What providers do I have?" -- the agent calls `list_providers` and returns a formatted list with IDs, names, types, base URLs, and active status.
3. User says "Switch to the Anthropic provider" -- the agent calls `update_provider` to set `is_active=true` on the Anthropic provider.
4. User says "What models does my OpenAI provider have?" -- the agent calls `list_models` with the provider ID and returns the list.
5. User says "Set claude-sonnet-4-20250514 as my default model" -- the agent calls `set_default_model` with the model and provider IDs.
6. User says "Show me my agents" -- the agent calls `list_agents` and returns all agents with their details.
7. User says "Update the Python Helper agent's system prompt to include error handling guidelines" -- the agent calls `update_agent` with the new system prompt.
8. User says "Delete the old test agent" -- the agent calls `delete_agent` with the agent ID.
9. User says "Switch to dark mode" -- the agent calls `set_config` with `key=theme_mode, value=dark`.
10. User says "What tools are available and which are enabled?" -- the agent calls `list_tool_states` and returns the full list.
11. User says "Disable the browser tool" -- the agent calls `set_tool_enabled` with the tool name and `enabled=false`.
12. User says "Set the OPENAI_API_KEY environment variable for JS tools to sk-xxx" -- the agent calls `set_env_var` with the key and value.
13. User says "Refresh the model list from my Gemini provider" -- the agent calls `fetch_models` with the provider ID.
14. User says "Add a custom model gpt-4-turbo to my OpenAI provider" -- the agent calls `add_model`.

## Feature Description

### Overview

OneClawShadow aims to be fully configurable via conversation. Currently, users must navigate to various Settings screens to manage providers, models, agents, themes, tool states, and environment variables. This feature adds a comprehensive set of built-in tools that enable the AI agent to read and modify all app configuration -- with the sole exception of API key/authentication credential management, which remains UI-only for security.

### Tool Suite

The feature adds 17 built-in tools organized into 6 domains:

| Domain | Tool | Purpose |
|--------|------|---------|
| **Provider** | `list_providers` | List all providers with details |
| | `create_provider` | Create a new provider |
| | `update_provider` | Update provider fields (name, URL, active) |
| | `delete_provider` | Delete a provider |
| **Model** | `list_models` | List models for a provider |
| | `fetch_models` | Refresh model list from provider API |
| | `set_default_model` | Set global default model |
| | `add_model` | Add a manual model to a provider |
| | `delete_model` | Delete a manual model |
| **Agent** | `list_agents` | List all agents |
| | `update_agent` | Update agent fields |
| | `delete_agent` | Delete an agent |
| **Settings** | `get_config` | Read an app setting |
| | `set_config` | Write an app setting |
| **Tools** | `list_tool_states` | List tools with enabled/disabled status |
| | `set_tool_enabled` | Enable or disable a tool or tool group |
| **Env Vars** | `manage_env_var` | List, set, or delete JS environment variables |

### Architecture Overview

```
AI Agent (in chat)
    |  tool call: create_provider / update_agent / set_config / ...
    v
ToolExecutionEngine (existing, unchanged)
    |
    v
ToolRegistry
    |
    +-- Provider tools -----> ProviderRepository
    +-- Model tools --------> ProviderRepository
    +-- Agent tools --------> AgentRepository
    +-- Settings tools -----> SettingsRepository / ThemeManager
    +-- Tool state tools ---> ToolEnabledStateStore / ToolRegistry
    +-- Env var tools ------> EnvironmentVariableStore
```

### Security Constraint

**API keys and authentication credentials are explicitly excluded.** These must be set through the UI (EncryptedSharedPreferences via the Provider setup screen). The config tools can create a provider and configure its URL, but the user must visit Settings > Providers to enter the API key. This is the only operation that requires leaving the chat.

### User Interaction Flow

```
1. User: "Set up a new Anthropic provider at https://api.anthropic.com/v1"
2. Agent calls create_provider(name="Anthropic", type="ANTHROPIC", api_base_url="https://api.anthropic.com/v1")
3. Agent: "Provider 'Anthropic' created (ID: abc123). Please go to Settings > Providers to set the API key."
4. User sets API key in UI, returns to chat
5. User: "Fetch models from Anthropic"
6. Agent calls fetch_models(provider_id="abc123")
7. Agent: "Found 5 models: claude-sonnet-4-20250514, claude-haiku-4-5-20251001, ..."
8. User: "Set claude-sonnet-4-20250514 as default"
9. Agent calls set_default_model(provider_id="abc123", model_id="claude-sonnet-4-20250514")
10. Agent: "Default model set to claude-sonnet-4-20250514."
```

## Acceptance Criteria

### Provider Tools

#### TEST-036-01: List Providers
- **Given** one or more providers exist
- **When** the agent calls `list_providers`
- **Then** all providers are returned with id, name, type, apiBaseUrl, isActive, and whether an API key is configured

#### TEST-036-02: List Providers Empty
- **Given** no providers exist
- **When** the agent calls `list_providers`
- **Then** the tool returns "No providers configured."

#### TEST-036-03: Create Provider
- **Given** valid name, type, and api_base_url parameters
- **When** the agent calls `create_provider`
- **Then** a new provider is created and the tool returns its ID with a reminder to set the API key

#### TEST-036-04: Create Provider Validation
- **Given** missing or invalid parameters (e.g., empty name, invalid type)
- **When** the agent calls `create_provider`
- **Then** the tool returns a validation error

#### TEST-036-05: Update Provider
- **Given** an existing provider
- **When** the agent calls `update_provider` with partial fields
- **Then** only the provided fields are updated; others remain unchanged

#### TEST-036-06: Delete Provider
- **Given** an existing provider
- **When** the agent calls `delete_provider`
- **Then** the provider, its models, and its API key are removed

#### TEST-036-07: Delete Pre-configured Provider
- **Given** a pre-configured (built-in) provider
- **When** the agent calls `delete_provider`
- **Then** the tool returns an error: pre-configured providers cannot be deleted

### Model Tools

#### TEST-036-08: List Models
- **Given** a provider with models
- **When** the agent calls `list_models` with the provider ID
- **Then** all models are returned with id, displayName, source, isDefault, and contextWindowSize

#### TEST-036-09: Set Default Model
- **Given** a valid model and provider
- **When** the agent calls `set_default_model`
- **Then** the global default model is updated

#### TEST-036-10: Add Manual Model
- **Given** a valid provider ID and model ID
- **When** the agent calls `add_model`
- **Then** a new MANUAL model is added to the provider

#### TEST-036-11: Delete Manual Model
- **Given** a MANUAL model exists
- **When** the agent calls `delete_model`
- **Then** the model is removed

#### TEST-036-12: Delete Non-Manual Model
- **Given** a DYNAMIC or PRESET model
- **When** the agent calls `delete_model`
- **Then** the tool returns an error: only MANUAL models can be deleted

#### TEST-036-13: Fetch Models from API
- **Given** a provider with an API key configured
- **When** the agent calls `fetch_models`
- **Then** models are fetched from the provider API and the list is refreshed

### Agent Tools

#### TEST-036-14: List Agents
- **Given** one or more agents exist
- **When** the agent calls `list_agents`
- **Then** all agents are returned with id, name, description, isBuiltIn, preferredProvider, and preferredModel

#### TEST-036-15: Update Agent
- **Given** an existing agent
- **When** the agent calls `update_agent` with partial fields
- **Then** only the provided fields are updated

#### TEST-036-16: Update Built-in Agent
- **Given** a built-in agent
- **When** the agent calls `update_agent`
- **Then** the tool returns an error: built-in agents cannot be modified

#### TEST-036-17: Delete Agent
- **Given** a non-built-in agent
- **When** the agent calls `delete_agent`
- **Then** the agent is removed

#### TEST-036-18: Delete Built-in Agent
- **Given** a built-in agent
- **When** the agent calls `delete_agent`
- **Then** the tool returns an error: built-in agents cannot be deleted

### Settings Tools

#### TEST-036-19: Get Config
- **Given** a known config key (e.g., `theme_mode`)
- **When** the agent calls `get_config`
- **Then** the current value is returned

#### TEST-036-20: Get Config Unknown Key
- **Given** an unknown config key
- **When** the agent calls `get_config`
- **Then** the tool returns null/not set with a list of known keys

#### TEST-036-21: Set Config Theme
- **Given** a valid theme value (system/light/dark)
- **When** the agent calls `set_config` with `key=theme_mode`
- **Then** the theme is applied immediately via ThemeManager

#### TEST-036-22: Set Config Invalid Value
- **Given** an invalid value for a known key
- **When** the agent calls `set_config`
- **Then** the tool returns a validation error with allowed values

### Tool State Tools

#### TEST-036-23: List Tool States
- **When** the agent calls `list_tool_states`
- **Then** all registered tools are returned grouped by category with their enabled/disabled status

#### TEST-036-24: Enable/Disable Tool
- **Given** a valid tool name
- **When** the agent calls `set_tool_enabled` with enabled=false
- **Then** the tool is disabled and excluded from future agent tool calls

#### TEST-036-25: Enable/Disable Tool Group
- **Given** a valid group name
- **When** the agent calls `set_tool_enabled` with type=group
- **Then** all tools in the group are effectively disabled

### Environment Variable Tools

#### TEST-036-26: List Env Vars
- **When** the agent calls `manage_env_var` with action=list
- **Then** all environment variable keys are returned (values are masked)

#### TEST-036-27: Set Env Var
- **When** the agent calls `manage_env_var` with action=set, key, and value
- **Then** the environment variable is stored in EncryptedSharedPreferences

#### TEST-036-28: Delete Env Var
- **When** the agent calls `manage_env_var` with action=delete and key
- **Then** the environment variable is removed

## UI/UX Requirements

This feature has no new UI. All tools operate through the existing chat interface and tool call display. No additional settings screens are needed.

## Feature Boundary

### Included

- 17 built-in Kotlin tools for full app configuration management
- Provider CRUD (except API key)
- Model listing, default setting, manual add/delete, API fetch
- Agent listing, updating, and deletion (create already exists via FEAT-002)
- App settings read/write (theme)
- Tool enabled state management
- JS environment variable management
- Registration of all tools in ToolModule

### Not Included (V1)

- API key management (stays in UI for security)
- Skill management tools (covered by FEAT-014)
- Memory management tools (separate concern)
- Scheduled task tools (covered by FEAT-027)
- Session management tools (separate concern)
- Usage statistics tools (read-only, lower priority)
- Backup/sync tools (separate concern)
- Bulk operations (e.g., delete all providers)
- Provider connection testing via tool (use UI)

## Business Rules

1. API keys and authentication credentials **cannot** be read, set, or modified through tools -- this is a hard security constraint
2. Pre-configured (built-in) providers cannot be deleted, only deactivated
3. Built-in agents cannot be modified or deleted
4. Only MANUAL models can be deleted; DYNAMIC and PRESET models are managed by the system
5. Theme changes via `set_config` take effect immediately (via ThemeManager.setThemeMode)
6. Environment variable values are stored in EncryptedSharedPreferences and are masked when listed (show keys only)
7. Disabling a tool via `set_tool_enabled` prevents it from being included in agent tool calls
8. When a provider is deleted, its associated models and API key are also removed
9. Known config keys and their allowed values are validated; unknown keys are stored as free-form strings

## Non-Functional Requirements

### Performance

- All config tools: < 100ms response time (local database/preferences operations)
- `fetch_models`: up to 30s (network API call)

### Memory

- No significant memory impact -- tools are stateless and delegate to existing repositories

### Compatibility

- Works on all supported Android versions (API 26+)
- No additional libraries required

## Dependencies

### Depends On

- **FEAT-004 (Tool System)**: Tool interface, registry, execution engine
- **FEAT-003 (Provider)**: ProviderRepository, provider management
- **FEAT-002 (Agent)**: AgentRepository, agent management
- **FEAT-009 (Settings)**: SettingsRepository, ThemeManager

### Depended On By

- None currently

### External Dependencies

- None (all operations use existing internal components)

## Error Handling

### Error Scenarios

1. **Entity not found**
   - Cause: Provider/Agent/Model ID does not exist
   - Handling: Return `ToolResult.error("not_found", "Provider not found with ID 'xyz'.")`

2. **Validation error**
   - Cause: Missing required parameter, invalid enum value, constraint violation
   - Handling: Return `ToolResult.error("validation_error", "<specific message>")`

3. **Permission denied**
   - Cause: Attempting to modify built-in agent or delete pre-configured provider
   - Handling: Return `ToolResult.error("permission_denied", "<specific message>")`

4. **API key not set**
   - Cause: `fetch_models` called on provider without API key
   - Handling: Return `ToolResult.error("api_key_required", "API key not configured for provider 'X'. Please set it in Settings > Providers.")`

5. **Network error**
   - Cause: `fetch_models` fails due to network issue
   - Handling: Return `ToolResult.error("network_error", "<error message>")`

## Test Points

### Functional Tests

- Verify each tool's success path with valid parameters
- Verify each tool's error path with invalid/missing parameters
- Verify partial update semantics for update tools
- Verify pre-configured provider protection
- Verify built-in agent protection
- Verify MANUAL-only model deletion
- Verify theme change takes effect immediately
- Verify env var values are masked in list output
- Verify tool enable/disable affects tool availability

### Edge Cases

- Create provider with duplicate name
- Update provider to change its type (should not be allowed)
- Delete provider that is the global default model's provider
- Set default model to a model from an inactive provider
- Update agent with empty system prompt
- Set config with empty value
- Enable/disable a tool that does not exist
- Set env var with very long key or value
- List tools when no tools are registered
- Concurrent config updates

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
