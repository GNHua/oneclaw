# OneClawShadow - Product Requirements Document Overview

## Document Information
- Created: 2026-02-26
- Last Updated: 2026-03-01
- Document Status: Draft
- Owner: TBD

## Product Vision

OneClawShadow is a mobile AI Agent runtime environment for Android. It allows users to interact with AI models through a chat interface, where the AI can autonomously call tools to complete tasks. The core idea is that the user's phone itself becomes the execution environment for the AI agent -- no need to set up a server or rely on any backend service.

The app is **zero backend dependency** by design: users bring their own API keys, their own cloud storage accounts, and all data stays under their control.

OneClawShadow is NOT a coding tool or IDE. It is a general-purpose AI agent that runs on your phone.

## Target Users

### Primary User Groups
1. **Tech-savvy users** who want an AI agent on their phone that can do more than just chat -- it can take actions (read files, call APIs, fetch data) autonomously.
2. **Users who value data ownership** -- they want to use their own API keys, store data locally, and not depend on any third-party backend.
3. **Power users** who want to customize AI behavior through custom Agents with different system prompts and tool configurations.

## Core Value Proposition

1. **AI Agent on your phone** -- no server setup required, the phone is the agent runtime
2. **Tool-augmented AI** -- the AI doesn't just chat, it can take actions through a well-designed, extensible tool system
3. **Full user control** -- users own their API keys, their data, and their Agent configurations
4. **Extensible architecture** -- clean tool interface that allows adding new capabilities over time

## Product Scope

### In Scope (V1)
- Chat-based interaction with AI models (text only)
- Streaming response display
- Tool call execution and visualization (compact/detailed modes, user-switchable)
- Agent management (create/edit Agents with custom system prompts and tool sets)
- Model/Provider management (multiple providers, API key management, model selection)
- Extensible tool system with well-defined interfaces
- Built-in starter tools (local file read/write, get current time, HTTP requests)
- Session management (flat list of conversations)
- Token usage tracking and cost estimation
- Local data storage
- Google Drive sync
- Push notifications (agent task completion)
- Offline access (view history, app usable but cannot send requests)

### Out of Scope (V1)
- Multimodal input (images, voice) -- architecture will be designed to support this later
- Session organization (folders, tags, groups)
- Agent configuration sharing/import/export
- Additional cloud sync providers (Dropbox, etc.)
- Local backup export (zip packaging)
- SSH connections or remote server terminal access
- Any backend service operated by us
- User authentication system (no accounts, no login)

## Feature Module Overview

### Core Feature Modules

| Feature ID | Feature Name | Priority | Status | Detailed Docs |
|-----------|-------------|----------|--------|---------------|
| FEAT-001 | Chat Interaction | P0 | Planning | [chat.md](features/FEAT-001-chat.md) |
| FEAT-002 | Agent Management | P0 | Planning | [agent.md](features/FEAT-002-agent.md) |
| FEAT-003 | Model/Provider Management | P0 | Planning | [provider.md](features/FEAT-003-provider.md) |
| FEAT-004 | Tool System | P0 | Planning | [tool-system.md](features/FEAT-004-tool-system.md) |
| FEAT-005 | Session Management | P0 | Planning | [session.md](features/FEAT-005-session.md) |
| FEAT-006 | Token/Cost Tracking | P1 | Planning | [token-tracking.md](features/FEAT-006-token-tracking.md) |
| FEAT-007 | Data Storage & Sync | P1 | Planning | [data-sync.md](features/FEAT-007-data-sync.md) |
| FEAT-008 | Notifications | P1 | Planning | [notifications.md](features/FEAT-008-notifications.md) |
| FEAT-009 | Settings | P2 | Planning | [settings.md](features/FEAT-009-settings.md) |
| FEAT-013 | Agent Memory System | P1 | Planning | [memory.md](features/FEAT-013-memory.md) |
| FEAT-024 | Messaging Bridge | P1 | Planning | [messaging-bridge.md](features/FEAT-024-messaging-bridge.md) |
| FEAT-032 | Search History Tool | P1 | Draft | [search-history.md](features/FEAT-032-search-history.md) |

### Module Descriptions

#### FEAT-001: Chat Interaction
The core conversation interface. Users type text messages and send them to the AI model. The model's response is displayed with streaming support. When the model makes tool calls, the execution process is shown in the chat. Users can toggle between compact view ("Calling tool X...") and detailed view (tool name, parameters, results, expandable/collapsible).

#### FEAT-002: Agent Management
Users can create, edit, and delete Agents. Each Agent has:
- A name and description
- A custom system prompt
- A configured set of available tools (which tools this Agent can use)
- A selected model/provider

The app ships with a few built-in default Agents. Users can create their own custom Agents.

#### FEAT-003: Model/Provider Management
Users configure their own API providers and keys:
- Support multiple providers (OpenAI, Anthropic, custom API endpoints, etc.)
- Securely store API keys locally
- Select which model to use per Agent or per session
- Manage and test API connections

#### FEAT-004: Tool System
The extensible tool execution framework. This is architecturally the most important module:
- Well-defined tool registration and invocation interface/protocol
- Tool execution engine that handles the model -> tool call -> result -> model loop
- Built-in starter tools: local file read/write, current time, HTTP requests
- Each tool declares what Android permissions it needs (if any)
- When a tool requires Android system permissions (location, storage, etc.), the standard Android permission request dialog is shown
- Tools execute automatically by default without user confirmation
- Designed for extensibility -- adding new tools should be straightforward

#### FEAT-005: Session Management
Manage multiple conversation sessions:
- Flat list of all sessions
- Each session is associated with an Agent
- Session history is persisted locally
- Users can resume, delete, or review past sessions
- Sessions are accessible offline

#### FEAT-006: Token/Cost Tracking
Since users use their own API keys, usage visibility is important:
- Display token count per message (input/output tokens)
- Display token usage per session
- Cumulative usage statistics (daily, weekly, monthly, all-time)
- Cost estimation based on model pricing

#### FEAT-007: Data Storage & Sync
All data is stored locally first:
- Sessions, Agent configs, provider settings stored in local database
- Google Drive sync for cross-device data backup/restore
- Sync is user-initiated or configurable auto-sync
- User provides their own Google account for sync

#### FEAT-008: Notifications
Android push notifications:
- Notify when a long-running agent task completes
- Notification taps navigate back to the relevant session

#### FEAT-009: Settings
General app configuration:
- UI preferences (theme, font size, etc.)
- Default model/provider selection
- Notification preferences
- Data management (clear cache, manage storage)

#### FEAT-024: Messaging Bridge
Connect external messaging platforms to the AI agent runtime. Users can interact with their agents from Telegram, Discord, Slack, Matrix, LINE, or a local WebChat interface without opening the app. The bridge runs as a foreground service, receiving messages from external platforms, routing them to the agent, and sending responses back. Supports image messages, per-channel access control, and a dedicated settings screen for configuration and status monitoring. Implemented as an independent `:bridge` Gradle module.

## Future Exploration

These are features and improvements identified during planning that are deferred beyond V1. They should be revisited after V1 ships:

- [ ] **Session organization**: Folders, tags, groups for organizing conversations
- [ ] **Agent sharing/import/export**: Export Agent configurations to share with others, import others' Agents
- [ ] **Additional cloud sync providers**: Dropbox, OneDrive, etc.
- [ ] **Local backup export**: Package all data into a zip file and save to a local folder
- [ ] **Multimodal input**: Image and voice input support (architecture should be designed to accommodate this)

## Version Planning

### v1.0 - MVP
- Goal: A functional AI agent on Android with tool-calling capability, fully user-controlled
- Included Features: FEAT-001 through FEAT-009
- Key milestone: User can create an Agent, configure a model provider, start a conversation, and the agent can call tools to complete tasks
- Expected Release: TBD

### v1.1 - Enhancement
- Goal: Polish and expand based on V1 usage feedback
- Potential features from Future Exploration list
- Expected Release: TBD

## Non-Functional Requirements

### Performance Requirements
- App startup time < 2 seconds
- UI response time < 100ms
- Streaming response should render with minimal latency
- Tool execution should not block the UI

### Compatibility Requirements
- Android version: 8.0 (API 26) and above
- Supported screen sizes: Phones (tablets are nice-to-have)
- Language support: English (primary), Chinese

### Security Requirements
- API keys encrypted at rest on device
- All API communication over HTTPS
- No data sent to any server we operate
- Local data storage with Android's standard security mechanisms

### Availability Requirements
- App crash rate < 0.1%
- Graceful degradation when offline (view history, edit settings, manage Agents)
- Clear error states when API calls fail

## Dependencies and Assumptions

### External Dependencies
- AI model API providers (OpenAI, Anthropic, etc.) -- user-provided
- Google Drive API -- for data sync
- Android system APIs -- for permissions, notifications, file access

### Assumptions
- Users have their own API keys for at least one AI model provider
- Users have a Google account if they want to use sync
- Users understand the basic concept of AI agents and tool calling

## Risks and Mitigation

| Risk | Impact | Probability | Mitigation |
|------|--------|------------|-----------|
| API provider rate limits or downtime | Medium | Medium | Clear error messaging, retry logic |
| Tool execution on phone has limited capabilities vs server | Medium | High | Focus on the tools that make sense on mobile, extensible architecture |
| API key security on device | High | Low | Android Keystore encryption, follow security best practices |
| Large conversation history impacts performance | Medium | Medium | Pagination, lazy loading, periodic cleanup options |
| Google Drive API changes | Low | Low | Abstract sync interface to allow swapping providers |

## Design Documents

In addition to these PRDs, the following documents define the product:

| Document | Purpose | Location |
|----------|---------|----------|
| UI Design Spec | Visual design, interaction patterns, screen layouts | [ui-design-spec.md](../design/ui-design-spec.md) |
| RFC-000: Overall Architecture | Technology stack, architecture pattern, data models | [RFC-000](../rfc/architecture/RFC-000-overall-architecture.md) |
| Feature RFCs | Technical implementation details per feature | `docs/rfc/features/` (TBD) |

## Architecture Principles

These high-level principles should guide the technical design (detailed in RFCs):

1. **Tool interface first**: The tool system's interface/protocol is the most critical architectural decision. It must be clean, versioned, and extensible.
2. **Local-first**: All data is stored locally. Sync is an add-on, not a requirement.
3. **Provider-agnostic**: The model interaction layer should abstract away provider differences.
4. **Offline-resilient**: The app should be fully usable offline except for sending API requests.
5. **Extensibility over completeness**: Better to ship fewer tools with a great interface than many tools with a rigid system.

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-26 | 0.1 | Initial version based on brainstorming sessions | - |
| 2026-02-27 | 0.2 | Added Design Documents section referencing UI Design Spec and RFCs | - |
