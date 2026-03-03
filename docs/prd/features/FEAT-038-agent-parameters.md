# Agent Model Parameters (Temperature & Max Iterations)

## Feature Information
- **Feature ID**: FEAT-038
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-038 (pending)

## User Story

**As** a user of OneClaw,
**I want** to configure the model temperature and maximum iteration count per agent,
**so that** I can fine-tune each agent's creativity level and control how many tool-use rounds it performs before stopping.

### Typical Scenarios

1. A "Creative Writer" agent should use high temperature (e.g. 1.2) for varied, imaginative output, while a "Code Assistant" agent should use low temperature (e.g. 0.2) for deterministic, precise responses.
2. A data-analysis agent that performs many sequential tool calls should be allowed up to 50 iterations, while a simple Q&A agent should stop after 5 iterations to avoid runaway loops.
3. The built-in General Assistant uses system defaults (temperature = null / max iterations = null), meaning the provider's default temperature applies and the global 100-round cap is used.
4. A user clones the General Assistant and adjusts only the temperature, leaving max iterations at the default.
5. A user creates a new agent via prompt generation; the generated agent inherits default parameter values which the user can later adjust.

## Feature Description

### Overview

FEAT-038 adds two configurable parameters to each agent:

| Parameter | Type | Range | Default | Purpose |
|-----------|------|-------|---------|---------|
| **Temperature** | `Float?` | 0.0 -- 2.0 | `null` (provider default) | Controls randomness / creativity of model output |
| **Max Iterations** | `Int?` | 1 -- 100 | `null` (global default = 100) | Limits the number of tool-use rounds per conversation turn |

These parameters are stored in the agent's data model, persisted in Room, and passed through the chat pipeline at execution time.

### Architecture Overview

```
AgentDetailScreen  (UI: two new input fields)
       |
AgentDetailViewModel  (state + validation)
       |
AgentRepository  (CRUD, unchanged interface)
       |
   AgentEntity / Agent  (new fields: temperature, maxIterations)
       |
   AgentMapper  (maps new fields)
       |
  Room Migration  (v7 -> v8: ALTER TABLE ADD COLUMN x2)
       |
SendMessageUseCase  (reads agent.maxIterations for loop cap,
       |              passes agent.temperature to adapter)
       |
ModelApiAdapter.sendMessageStream()  (new `temperature` param)
       |
OpenAiAdapter / AnthropicAdapter / GeminiAdapter  (include temperature in API request JSON)
```

### User Interaction Flow

#### Temperature Setting
```
1. User navigates to Agent Detail screen (create or edit)
2. Below the Preferred Model dropdown, a "Temperature" slider or text field is shown
3. User adjusts the value (0.0 to 2.0, step 0.1)
4. A "Clear" option resets to null (provider default)
5. User saves the agent
6. On next chat message, the temperature is passed to the API
```

#### Max Iterations Setting
```
1. User navigates to Agent Detail screen (create or edit)
2. Below the Temperature field, a "Max Iterations" text field is shown
3. User enters a value (1 to 100)
4. A "Clear" option resets to null (global default = 100)
5. User saves the agent
6. On next chat message, the tool loop respects this limit
```

## Acceptance Criteria

Must pass (all required):

- [ ] Agent domain model includes `temperature: Float?` and `maxIterations: Int?`
- [ ] AgentEntity includes corresponding Room columns with default NULL
- [ ] Room migration v7 -> v8 adds both columns to the `agents` table
- [ ] AgentMapper maps both fields between entity and domain model
- [ ] AgentDetailScreen shows temperature input (slider or text field, 0.0--2.0)
- [ ] AgentDetailScreen shows max iterations input (text field, 1--100)
- [ ] Both fields support a "clear/reset to default" action
- [ ] `hasUnsavedChanges` detection includes the two new fields
- [ ] `ModelApiAdapter.sendMessageStream()` accepts an optional `temperature` parameter
- [ ] All three adapters (OpenAI, Anthropic, Gemini) include `temperature` in the API request when non-null
- [ ] `SendMessageUseCase` uses `agent.maxIterations ?: MAX_TOOL_ROUNDS` as the loop limit
- [ ] `SendMessageUseCase` passes `agent.temperature` to the adapter
- [ ] Built-in General Assistant agent defaults to null for both fields (no behavior change)
- [ ] Validation rejects temperature outside 0.0--2.0 and iterations outside 1--100
- [ ] All Layer 1A tests pass

Optional (nice to have):

- [ ] Temperature presets (e.g. "Precise 0.2", "Balanced 0.7", "Creative 1.2") as quick-select chips
- [ ] Visual indicator on agent list showing non-default parameter values

## UI/UX Requirements

### Agent Detail Screen (additions)

```
┌──────────────────────────────────┐
│ <- Edit Agent              [Save]│
├──────────────────────────────────┤
│ ... existing fields ...          │
├──────────────────────────────────┤
│ PREFERRED MODEL (optional)       │
│ ┌──────────────────────────────┐ │
│ │ Using global default       v │ │
│ └──────────────────────────────┘ │
├──────────────────────────────────┤
│ TEMPERATURE (optional)           │
│ ┌──────────────────────────────┐ │
│ │ [0.0 ====|======== 2.0] 0.7 │ │
│ └──────────────────────────────┘ │
│ Provider default when not set    │
│                        [Clear]   │
├──────────────────────────────────┤
│ MAX ITERATIONS (optional)        │
│ ┌──────────────────────────────┐ │
│ │ 10                           │ │
│ └──────────────────────────────┘ │
│ Global default (100) when not set│
│                        [Clear]   │
├──────────────────────────────────┤
│ [      Clone Agent             ] │
│ [      Delete Agent            ] │
└──────────────────────────────────┘
```

- Temperature: Slider with numeric display (step 0.1), or OutlinedTextField with numeric keyboard
- Max Iterations: OutlinedTextField with number keyboard, validated 1--100
- Both show helper text explaining the default behavior when null
- "Clear" button appears only when a value is set

## Feature Boundary

### Included

- `temperature` and `maxIterations` fields on Agent model and entity
- Room migration (v7 -> v8)
- UI controls on AgentDetailScreen
- Temperature passthrough in `ModelApiAdapter.sendMessageStream()`
- All three adapter implementations updated
- Max iteration enforcement in `SendMessageUseCase`
- Validation logic
- Change detection for unsaved changes

### Not Included (V1)

- Top-P, frequency penalty, presence penalty, or other sampling parameters
- Per-message temperature override (always uses agent-level setting)
- Max tokens / output length control (separate feature)
- Temperature presets UI
- Agent parameter display in chat header

## Business Rules

1. `temperature = null` means "use provider default" -- the field is omitted from the API request
2. `maxIterations = null` means "use global default" -- the existing `MAX_TOOL_ROUNDS = 100` applies
3. Temperature range is 0.0 to 2.0 inclusive, matching the common range across OpenAI, Anthropic, and Gemini APIs
4. Max iterations range is 1 to 100 inclusive
5. Both fields are nullable -- agents are not required to set them
6. Built-in agents default to null for both fields
7. Cloning an agent copies its temperature and maxIterations values
8. The `create_agent` tool (RFC-020) does not set these parameters -- they can only be configured via the UI

## Non-Functional Requirements

### Performance

- No measurable performance impact -- two additional nullable columns in Room, two additional fields in API request JSON

### Compatibility

- Room migration is additive (ALTER TABLE ADD COLUMN with DEFAULT NULL) -- fully backwards compatible
- API compatibility: all three providers (OpenAI, Anthropic, Gemini) support the `temperature` parameter

## Dependencies

### Depends On

- **FEAT-002 (Agent Management)**: Agent model, entity, repository, CRUD
- **FEAT-001 (Chat Interaction)**: SendMessageUseCase, streaming pipeline
- **RFC-020 (Agent Enhancement)**: Agent detail UI, model selector

### Depended On By

- None currently

## Error Handling

### Error Scenarios

1. **Temperature out of range**: User enters a value outside 0.0--2.0 -- show inline validation error, prevent save
2. **Max iterations out of range**: User enters a value outside 1--100 -- show inline validation error, prevent save
3. **Non-numeric input**: User enters text in a numeric field -- ignore non-numeric characters
4. **Migration failure**: Room migration v7->v8 fails -- standard Room migration error handling applies (app will not crash; fallback migration if configured)

## Test Points

### Functional Tests

- Verify Agent domain model includes temperature and maxIterations with null defaults
- Verify AgentEntity includes both columns
- Verify Room migration v7->v8 adds both columns successfully
- Verify saving an agent with temperature = 0.7 persists and reloads correctly
- Verify saving an agent with maxIterations = 10 persists and reloads correctly
- Verify null values persist correctly (both fields optional)
- Verify AgentDetailScreen displays temperature control
- Verify AgentDetailScreen displays max iterations control
- Verify clearing temperature resets to null
- Verify clearing maxIterations resets to null
- Verify hasUnsavedChanges detects temperature changes
- Verify hasUnsavedChanges detects maxIterations changes
- Verify SendMessageUseCase respects agent.maxIterations
- Verify SendMessageUseCase passes agent.temperature to adapter
- Verify OpenAI adapter includes temperature in request when non-null
- Verify Anthropic adapter includes temperature in request when non-null
- Verify Gemini adapter includes temperature in request when non-null
- Verify adapter omits temperature when null

### Edge Cases

- Temperature set to 0.0 (minimum) -- should work, produces deterministic output
- Temperature set to 2.0 (maximum) -- should work, produces highly varied output
- Max iterations set to 1 -- agent gets exactly one round (no tool loop)
- Max iterations set to 100 -- same as current default behavior
- Clone agent with non-null temperature -- cloned agent should have same temperature
- Built-in agent temperature remains null after migration

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
