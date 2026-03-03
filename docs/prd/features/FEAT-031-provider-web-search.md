# Provider Web Search

## Feature Information
- **Feature ID**: FEAT-031
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-031 (Provider Web Search)](../../rfc/features/RFC-031-provider-web-search.md)

## User Story

**As** a user of OneClaw,
**I want to** enable AI agents to search the web using their provider's built-in search capabilities,
**so that** I can get responses grounded in real-time, up-to-date information with verifiable source citations.

### Typical Scenarios

1. User asks an agent "What's the latest news about Android 16?" -- the agent uses the provider's built-in web search to find current articles and responds with cited sources.
2. User enables web search on a research-focused agent profile, so all conversations with that agent automatically have access to live web data.
3. User sees inline citations in the agent's response and taps a source link to open the original article in a browser.
4. User disables web search for a coding-focused agent to save costs and avoid unnecessary search queries.

## Feature Description

### Overview

Provider Web Search leverages the built-in search capabilities offered by each AI model provider -- OpenAI's web search, Anthropic's server-side web search tool, and Google Gemini's grounding with Google Search. Unlike the app's existing client-side `webfetch` and `browser` tools (which the AI calls explicitly to fetch specific URLs), provider web search is a server-side capability where the provider's infrastructure performs web searches and injects relevant results into the model's context automatically.

This approach offers several advantages over client-side tools:
- **Lower latency**: Searches run on the provider's infrastructure, avoiding extra round-trips
- **Better integration**: The provider controls how search results are injected into the model's context
- **Automatic citations**: Providers return structured citation data that maps to specific parts of the response
- **No tool round-trip**: For OpenAI and Gemini, search happens within a single API call without the tool call loop

### Detailed Specification

#### 1. Per-Agent Web Search Toggle

Each agent has a "Web Search" toggle in its configuration:
- **Default**: OFF (web search disabled)
- **When ON**: The adapter includes provider-specific search configuration in API requests
- The toggle is stored as a boolean field on the Agent model

#### 2. Provider-Specific Behavior

| Provider | Mechanism | Search Trigger | Cost |
|----------|-----------|---------------|------|
| OpenAI | `web_search_options` in Chat Completions | Always searches when enabled | $0.03-0.05/search |
| Anthropic | `web_search_20250305` server-side tool | Model decides when to search | $0.01/search |
| Gemini | `google_search` in tools | Model decides when to search | $0.014/query |

- **OpenAI**: Requires search-capable models (e.g., `gpt-4o-search-preview`). When web search is enabled, the model always performs a search.
- **Anthropic**: The web search tool is added to the tools array. Claude decides autonomously whether to search based on the query. Multiple searches may occur in a single turn.
- **Gemini**: Google Search grounding is added to the tools array. Gemini decides autonomously whether to ground its response with search results.

#### 3. Citation Display

When the AI response includes citations from web search:
- A "Sources" section appears below the response text
- Each source shows: title (clickable) and domain name
- Tapping a source opens the URL in the device's default browser
- A small link icon indicates the number of sources

#### 4. Search Indicator

While the provider is performing a web search:
- A "Searching the web..." indicator appears in the chat (similar to the thinking indicator)
- This applies mainly to Anthropic where the search is visible as a server-side tool call

### Navigation Entry Points

- **Agent Edit Screen**: Web search toggle in the agent configuration form
- **Chat Screen**: Citations displayed inline in AI response messages

### User Interaction Flow

```
1. User opens Agent settings and enables "Web Search" toggle
2. User starts a conversation with the agent
3. User sends a message that benefits from web information
4. System sends request to provider with search enabled
5. Provider performs web search (if applicable)
6. AI response streams in with search-grounded content
7. Citations appear below the response text
8. User taps a citation to open the source URL
```

## Acceptance Criteria

### TEST-031-01: Enable Web Search on Agent
- **Given** the user is editing an agent's configuration
- **When** they toggle "Web Search" to ON and save
- **Then** the agent's webSearchEnabled setting is persisted

### TEST-031-02: OpenAI Search Request
- **Given** an agent with web search enabled using an OpenAI provider
- **When** the user sends a message
- **Then** the API request includes `web_search_options` field

### TEST-031-03: Anthropic Search Request
- **Given** an agent with web search enabled using an Anthropic provider
- **When** the user sends a message
- **Then** the API request includes the `web_search_20250305` tool in the tools array

### TEST-031-04: Gemini Search Request
- **Given** an agent with web search enabled using a Gemini provider
- **When** the user sends a message
- **Then** the API request includes `google_search` in the tools array

### TEST-031-05: Display Citations
- **Given** a provider returns a response with citation data
- **When** the response is rendered in the chat
- **Then** a "Sources" section appears below the response text showing source titles and domains

### TEST-031-06: Tap Citation Link
- **Given** citations are displayed below a response
- **When** the user taps a citation
- **Then** the source URL opens in the device's default browser

### TEST-031-07: Search Indicator for Anthropic
- **Given** an Anthropic agent with web search enabled
- **When** the model initiates a server-side web search
- **Then** a "Searching the web..." indicator appears during the search

### TEST-031-08: Web Search Disabled by Default
- **Given** a newly created agent
- **When** the user views the agent's configuration
- **Then** the web search toggle is OFF by default

### TEST-031-09: No Search When Disabled
- **Given** an agent with web search disabled
- **When** the user sends a message
- **Then** the API request does not include any search configuration

### TEST-031-10: Citations Persisted Across Sessions
- **Given** a response with citations has been saved
- **When** the user reopens the session
- **Then** the citations are still displayed correctly below the response

## Non-Functional Requirements

### Performance
- Web search should not add more than 3 seconds to the overall response time (provider-dependent)
- Citation rendering should not impact chat scroll performance

### Cost Awareness
- The agent configuration should note that web search incurs additional API costs
- Cost indication should be visible near the toggle

### Compatibility
- Web search is provider-dependent; the toggle should be available for all three providers but gracefully handle unsupported models

## Out of Scope

- Domain filtering or allowed/blocked domain lists (future enhancement)
- User location configuration for localized search results (future enhancement)
- Inline footnote-style citation markers within response text (v1 shows sources section only)
- Search query visibility (showing what the provider searched for)
- Search result preview/snippets before the AI's synthesized response
- Client-side web search fallback when provider search is unavailable

## Dependencies

### Depends On
- **FEAT-001 (Chat Interaction)**: Chat UI for displaying responses and citations
- **FEAT-002 (Agent Management)**: Agent configuration for the web search toggle
- **FEAT-003 (Provider Management)**: Provider/adapter infrastructure

### Depended On By
- None currently

## Error Handling

### Error Scenarios

1. **Provider does not support web search for the selected model**
   - The request proceeds without search; no error shown to user
   - The toggle remains available but search simply does not activate

2. **Search quota exceeded (rate limit)**
   - Provider returns a partial response without search results
   - Display the response as-is without citations

3. **Search temporarily unavailable**
   - For Anthropic: `web_search_tool_result_error` is returned in the response
   - Display the AI's response normally; show a subtle note that search was unavailable

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
