# Model/Provider Management

## Feature Information
- **Feature ID**: FEAT-003
- **Created**: 2026-02-26
- **Last Updated**: 2026-02-26
- **Status**: Draft
- **Priority**: P0 (Must Have)
- **Owner**: TBD
- **Related RFC**: [RFC-003 (Provider Management)](../../rfc/features/RFC-003-provider-management.md)
- **Related Design**: [UI Design Spec](../../design/ui-design-spec.md)

## User Story

**As** a user of OneClaw,
**I want to** configure my own AI model providers and API keys,
**so that** I can use the AI models I have access to without depending on any third-party backend.

### Typical Scenarios
1. User opens the app for the first time and adds their OpenAI API key to the pre-configured OpenAI provider.
2. User adds an Anthropic provider with their API key and the app automatically fetches available models.
3. User sets Claude Sonnet as the global default model.
4. User adds a custom API endpoint for a self-hosted model and manually enters the model name.
5. User tests a provider connection to make sure the API key is valid before starting a conversation.
6. User updates their API key after rotating it for security reasons.

## Feature Description

### Overview
Model/Provider Management allows users to configure the AI model providers they want to use. Since OneClaw has no backend, users bring their own API keys. The app comes with pre-configured templates for major providers (OpenAI, Anthropic, Google Gemini), and supports fully custom API endpoints. Model lists are dynamically fetched from provider APIs when possible, with pre-set fallback models for reliability. Users set a global default model that is used when an Agent does not specify a preferred model.

### Core Concepts

**Provider**: An API service that hosts AI models. Defined by a name, API endpoint base URL, API key, and API protocol type. Examples: OpenAI, Anthropic, Google Gemini, or a custom endpoint. The "type" field represents the API protocol format (OpenAI, Anthropic, or Gemini), not the service identity. Custom endpoints choose whichever protocol they are compatible with (most commonly OpenAI). The `isPreConfigured` field distinguishes built-in templates from user-created providers.

**Model**: A specific AI model available through a provider. Examples: gpt-4o, claude-sonnet-4-20250514, gemini-2.0-flash. A provider can have multiple models.

**Global Default**: The model/provider combination used when no Agent-level preference is set.

### Provider Data Model

| Field | Required | Description |
|-------|----------|-------------|
| ID | Yes | Unique identifier, auto-generated |
| Name | Yes | Display name (e.g., "OpenAI", "My Custom Server") |
| Type | Yes | API protocol format: `openai`, `anthropic`, `gemini` (no separate `custom` type; custom endpoints choose a compatible protocol) |
| API Base URL | Yes | The base URL for API requests |
| API Key | Yes | The user's API key, stored encrypted |
| Is Pre-configured | Yes | Whether this is a pre-configured provider template |
| Available Models | Yes | List of available models (dynamically fetched or manually added) |
| Is Active | Yes | Whether this provider is currently enabled |
| Created At | Yes | Timestamp |
| Updated At | Yes | Timestamp |

### Model Data Model

| Field | Required | Description |
|-------|----------|-------------|
| ID | Yes | Model identifier (e.g., "gpt-4o", "claude-sonnet-4-20250514") |
| Display Name | No | Human-friendly name (e.g., "GPT-4o", "Claude Sonnet 4") |
| Provider ID | Yes | Which provider this model belongs to |
| Is Default | No | Whether this is the global default model |
| Source | Yes | How this model was added: `dynamic` (fetched from API), `preset` (pre-configured fallback), `manual` (user-added) |

### Pre-configured Providers

The app ships with templates for major providers. Users only need to enter their API key:

| Provider | Type | API Base URL | Has List Models API | Preset Fallback Models |
|----------|------|-------------|---------------------|----------------------|
| OpenAI | `openai` | `https://api.openai.com/v1` | Yes | gpt-4o, gpt-4o-mini, o1, o3-mini |
| Anthropic | `anthropic` | `https://api.anthropic.com/v1` | Yes | claude-sonnet-4-20250514, claude-haiku-4-20250414 |
| Google Gemini | `gemini` | `https://generativelanguage.googleapis.com/v1beta` | Yes | gemini-2.0-flash, gemini-2.5-pro |

Note: Preset fallback models are used when the dynamic model list fetch fails. They do not need to be frequently updated since the dynamic fetch is the primary mechanism.

### Model List Strategy

The strategy for populating available models per provider follows this priority:

1. **Dynamic fetch (preferred)**: When a provider is configured with an API key, the app attempts to call the provider's list-models API to get the current list of available models. This runs:
   - When a provider is first configured
   - When the user manually triggers a refresh
   - Periodically in the background (e.g., once per day)

2. **Preset fallback**: If dynamic fetch fails (network error, API not supported, etc.), the app falls back to the pre-configured list of common models for known provider types.

3. **Manual entry**: For custom endpoints (type `custom`), or when a user wants to use a specific model not in the list, they can manually type in a model ID. This is the only option for custom endpoints since we cannot know their available models.

### Custom Endpoint Support

Users can add fully custom API endpoints:
- Must specify the API base URL
- Must specify authentication (API key)
- Must manually add model names (no dynamic fetch for custom endpoints)
- The API must be compatible with one of the supported provider protocols (OpenAI-compatible is the most common)
- User selects the provider type (OPENAI, ANTHROPIC, or GEMINI) based on which API protocol the custom endpoint is compatible with

### API Key Security

- API keys are encrypted at rest using Android Keystore
- In the UI, API keys are masked by default (showing only the last 4 characters, e.g., `sk-...abc1234`)
- An "eye" toggle icon allows the user to reveal/hide the full key
- When editing, the full key is visible in the input field
- API keys are never sent to any server other than the configured provider endpoint
- API keys are included in Google Drive sync only if the user explicitly opts in (default: not synced)

### Connection Testing

Users can test a provider connection to verify:
- The API endpoint is reachable
- The API key is valid
- At least one model is accessible

Test results are displayed as:
- Success: "Connection successful. Found X available models."
- Auth failure: "Authentication failed. Please check your API key."
- Network failure: "Cannot reach the server. Please check the URL and your network."
- Other error: Display the error message from the API

### Global Default Model

- One model/provider combination is designated as the global default
- This is used when an Agent does not specify a preferred model (see FEAT-002 model resolution order)
- The user must set a global default before starting their first conversation
- If the global default provider becomes invalid (API key removed, provider deleted), the app prompts the user to set a new default

### Provider Management Operations

#### View Provider List
- Display all providers (pre-configured first, then custom)
- Each item shows: provider name, type badge, number of available models, active/inactive status
- Active providers are visually distinguished from inactive ones

#### Add Provider (from template)
- User selects a pre-configured provider template
- User enters their API key
- App tests the connection and fetches models
- Provider is saved and activated

#### Add Custom Provider
- User taps "Add Custom Provider"
- Fills in: name, API base URL, API key, protocol type
- Manually adds model names
- Tests connection
- Saves

#### Edit Provider
- User can modify: name (custom only), API key, active/inactive status
- For pre-configured providers: cannot change base URL or type
- For custom providers: can change all fields

#### Delete Provider
- Confirmation dialog
- If this provider is the global default, user must set a new default first
- If any Agent references this provider as its preferred provider, those Agents fall back to the global default

#### Refresh Models
- Manual refresh: user taps refresh to re-fetch the model list from the API
- Shows loading state during fetch
- Updates the available model list on success
- Shows error on failure, keeps the existing list

### User Interaction Flow

#### First-Time Setup
```
1. User opens app for the first time
2. App shows a welcome/setup screen: "Set up your AI provider to get started"
3. User sees pre-configured provider options (OpenAI, Anthropic, Gemini) and "Custom"
4a. User selects a provider (e.g., OpenAI)
    5. User enters their API key
    6. User taps "Test Connection"
    7. App validates the key and fetches available models
    8. User selects a default model from the list
    9. Setup complete, user enters the chat screen
4b. User taps "Skip for now"
    5. User goes directly to the chat screen
    6. User can browse history, manage agents, etc. but cannot send messages
    7. When user tries to send a message, an inline error prompts them to configure a provider
    8. The welcome screen is not shown again; user configures providers via Settings
```

Note: The welcome/setup screen is only shown once on first app launch. It is NOT a blocking gate -- users can skip it. After skipping, subsequent app launches go directly to the chat screen.

#### Adding a Second Provider
```
1. User navigates to Provider Management
2. User taps "Add Provider"
3. User selects provider type or "Custom"
4. User enters API key (and URL if custom)
5. User tests connection
6. User saves
7. New models are now available for selection in Agent configs and as global default
```

## Acceptance Criteria

Must pass (all required):
- [ ] App ships with pre-configured templates for OpenAI, Anthropic, and Google Gemini
- [ ] User can add a provider by selecting a template and entering an API key
- [ ] User can add a fully custom provider with custom endpoint URL
- [ ] Model list is dynamically fetched from provider API when available
- [ ] Preset fallback models are shown when dynamic fetch fails
- [ ] User can manually add model names for custom providers
- [ ] API keys are encrypted at rest (Android Keystore)
- [ ] API keys are masked in UI by default, with toggle to reveal
- [ ] User can test provider connections and see clear success/failure results
- [ ] User can set a global default model/provider
- [ ] User is prompted (but not forced) to configure a provider on first launch via a skippable welcome screen
- [ ] When user tries to send a message with no active provider, an inline error prompts them to configure one
- [ ] User can edit provider API keys
- [ ] User can delete providers (with confirmation)
- [ ] Deleting the global default provider prompts user to set a new default
- [ ] User can enable/disable providers without deleting them
- [ ] User can manually trigger a model list refresh
- [ ] Custom endpoints require the user to select a compatible API protocol type (OPENAI, ANTHROPIC, or GEMINI)

Optional (nice to have for V1):
- [ ] Automatic periodic model list refresh (e.g., daily)
- [ ] Provider health indicator (last successful connection time)
- [ ] API key sync opt-in for Google Drive backup

## UI/UX Requirements

### Provider List Screen
- List of all providers
- Each item: provider name, type icon/badge, model count, active status toggle
- Pre-configured providers with no API key show a "Setup" prompt
- "Add Provider" button
- Tap to view/edit details

### Provider Detail / Edit Screen
- Provider name (editable for custom, read-only for pre-configured)
- API Base URL (editable for custom, read-only for pre-configured)
- API Key field:
  - Masked by default (e.g., `sk-...abc1234`)
  - Eye icon to toggle visibility
  - Editable text field when tapped
- Protocol type (for custom providers)
- Active/Inactive toggle
- "Test Connection" button with result display
- Model list section:
  - Dynamically fetched models shown with "dynamic" label
  - Preset fallback models shown with "preset" label
  - Manually added models shown with "manual" label and delete option
  - "Add Model" option (for manual entry)
  - "Refresh Models" button
- Default model selector (radio button or star icon next to a model)
- "Delete Provider" button (with confirmation)

### First-Time Setup Screen (Welcome Screen)
- Shown only once on first app launch, skippable
- Clean, focused layout
- Provider template cards (OpenAI, Anthropic, Gemini, Custom)
- API key input with test button
- Model selection after successful connection
- "Get Started" button
- "Skip for now" text button at the bottom (navigates to chat without configuring)

### Interaction Feedback
- Connection test: loading spinner -> success/failure result
- Model refresh: loading spinner -> updated list or error
- Save: confirmation toast
- Delete: confirmation dialog -> success toast

## Feature Boundary

### Included
- Pre-configured provider templates (OpenAI, Anthropic, Gemini)
- Custom provider endpoint support
- API key management (add, edit, masked display, toggle visibility)
- API key storage in Android Keystore (EncryptedSharedPreferences, not in database)
- Dynamic model list fetching
- Preset fallback models
- Manual model entry (for custom endpoints)
- Connection testing
- Global default model selection
- Provider enable/disable
- Provider deletion with dependency handling
- First-time setup flow
- Model list refresh

### Not Included (V1)
- OAuth-based authentication (only API key auth)
- Provider usage analytics (which provider is used most)
- Cost-per-model configuration (handled in FEAT-006)
- Automatic provider failover (if one provider is down, auto-switch to another)
- API key rotation reminders
- Multiple API keys per provider

## Business Rules

### Provider Rules
1. At least one active provider with a valid API key is needed to send messages, but the app is usable without one (browsing history, managing agents, etc.). The user is guided but not forced to configure a provider.
2. Pre-configured provider templates cannot be deleted, only deactivated (API key can be removed)
3. Custom providers can be fully deleted
4. A provider with no API key is shown as "Not configured" and cannot be used
5. Provider names must be non-empty

### Model Rules
1. There must be exactly one global default model at all times (after initial setup)
2. Dynamic models are refreshed on: first setup, manual refresh, and optionally on periodic schedule
3. If a dynamic fetch returns an empty list, the preset fallback list is used instead
4. Manually added models persist even when dynamic models are refreshed
5. Users can remove manually added models but not dynamic or preset models

### Security Rules
1. API keys are stored in EncryptedSharedPreferences backed by Android Keystore (not in the Room database)
2. API keys are only sent to their associated provider endpoint, never elsewhere
3. API key sync to Google Drive is opt-in and off by default
4. The app never logs API keys (not even in debug builds)

## Non-Functional Requirements

### Performance
- Provider list loads in < 100ms
- Connection test completes in < 10 seconds (timeout)
- Model list fetch completes in < 10 seconds (timeout)
- API key encryption/decryption is transparent to the user (no perceivable delay)

### Security
- API keys encrypted at rest using Android Keystore
- No plaintext API key storage anywhere (database, shared preferences, logs)
- HTTPS enforced for all provider API communication

### Reliability
- If dynamic model fetch fails, fall back to preset models gracefully
- If no network is available, show cached model list from last successful fetch
- Provider configuration changes take effect immediately (no restart needed)

## Dependencies

### Depends On
- None (this is a foundational module)

### Depended On By
- **FEAT-001 (Chat Interaction)**: Needs a provider/model to send requests to
- **FEAT-002 (Agent Management)**: Agents can reference a preferred model/provider
- **FEAT-006 (Token/Cost Tracking)**: Needs to know which model is used for cost estimation
- **FEAT-007 (Data Storage & Sync)**: Provider configs (excluding API keys by default) are included in sync

## Error Handling

### Error Scenarios

1. **Invalid API key**
   - Detected during: connection test or first chat request
   - Display: "API key is invalid. Please check your key in provider settings."
   - Action: Link to provider settings

2. **Network error during model fetch**
   - Display: "Could not fetch model list. Using preset models."
   - Action: Show preset models, offer retry button

3. **Custom endpoint unreachable**
   - Display: "Cannot reach [URL]. Please verify the URL and your network."
   - Action: User checks URL and network

4. **No providers configured (user tries to send a message)**
   - Display: Inline error in chat: "No provider configured. Go to Settings to add one."
   - Action: Link to Settings > Manage Providers

5. **Global default provider deleted or deactivated**
   - Display: "Your default model is no longer available. Please select a new default."
   - Action: Navigate to provider settings

6. **API key format validation (if applicable)**
   - Display: Inline validation on the API key field (e.g., "OpenAI keys start with 'sk-'")
   - Action: User corrects the key

## Future Improvements

- [ ] **Automatic provider failover**: If the primary provider fails, automatically try a secondary provider
- [ ] **API key rotation reminders**: Remind users to rotate API keys periodically
- [ ] **Multiple API keys per provider**: Support load balancing across multiple keys
- [ ] **OAuth authentication**: Support OAuth-based provider authentication in addition to API keys
- [ ] **Provider usage analytics**: Track which providers/models are used most
- [ ] **Cost-per-model configuration**: User-defined pricing overrides (related to FEAT-006)

## Test Points

### Functional Tests
- Verify pre-configured providers (OpenAI, Anthropic, Gemini) are available
- Add a provider with API key and verify models are dynamically fetched
- Verify preset fallback models appear when dynamic fetch fails
- Add a custom provider with manual model entry
- Test connection with valid API key and verify success
- Test connection with invalid API key and verify error
- Test connection with unreachable endpoint and verify error
- Set global default model and verify it persists
- Edit API key and verify the change takes effect
- Delete a provider and verify it's removed
- Delete the global default provider and verify the app prompts for a new default
- Enable/disable a provider and verify status change
- Verify API key is masked by default in UI
- Verify API key eye toggle works
- Verify first-time setup flow when no providers are configured
- Manually refresh model list and verify update

### Security Tests
- Verify API key is encrypted in local storage (not plaintext in database)
- Verify API key is not present in app logs
- Verify API key is only sent to the configured provider endpoint

### Edge Cases
- Provider with expired/revoked API key
- Provider API returns unexpected model list format
- Network drops during model fetch
- User enters API key with leading/trailing whitespace
- Custom endpoint with non-standard response format
- Two providers with the same name

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-26 | 0.1 | Initial version | - |
| 2026-02-27 | 0.2 | Added UI Design Spec reference; provider management accessible from Settings screen (not standalone) | - |
| 2026-02-27 | 0.3 | Added RFC-003 reference; type field now represents API protocol format (removed `custom` as a separate type); updated custom endpoint section | - |
