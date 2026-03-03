# JavaScript Tool Migration & Library System

## Feature Information
- **Feature ID**: FEAT-015
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-015 (pending)

## User Story

**As** a developer or power user of OneClaw,
**I want** built-in tools to be implemented in JavaScript (backed by native bridges) and third-party JavaScript libraries to be available to all JS tools,
**so that** the tool ecosystem is easy to extend with community JS libraries, and new tools like `webfetch` can leverage libraries like Turndown for rich processing without requiring app recompilation.

### Typical Scenarios

1. The agent calls `webfetch` with a URL. The tool fetches the HTML via the native HTTP bridge, passes it through the bundled Turndown library, and returns clean Markdown -- ready for the model to read.
2. A developer wants to add a new tool that parses RSS feeds. They find a pure-JS RSS parser library, place it in the shared library directory, and write a JS tool that imports it. No Kotlin changes needed.
3. The existing `http_request` built-in tool is now a JS file shipped with the app. A user inspects it as a reference when writing their own HTTP-based tool.
4. The agent uses `read_file` exactly as before -- the migration is fully transparent to the AI model and the user.

## Feature Description

### Overview

FEAT-015 has two tightly coupled goals:

1. **JS Library Bundle System**: Allow shared JavaScript libraries (e.g., Turndown) to be bundled with the app as assets and made available to all JS tools running in the QuickJS runtime.
2. **Built-in Tool JS Migration**: Re-implement the existing Kotlin built-in tools (`get_current_time`, `read_file`, `write_file`, `http_request`) as JavaScript tools that ship as app assets, backed by the same native bridges introduced in FEAT-012. Add a new `webfetch` built-in JS tool that uses Turndown.

After this migration, the app ships zero Kotlin tool implementations. All tools are JS files -- the Kotlin layer only provides bridges to native Android capabilities.

### Architecture Overview

```
AI Model
    ↓ tool call
ToolExecutionEngine  (Kotlin, unchanged)
    ↓
ToolRegistry  (Kotlin, unchanged)
    ├── Built-in JS Tools  [NEW - shipped as assets/js/tools/]
    │     get_current_time.js
    │     read_file.js
    │     write_file.js
    │     http_request.js
    │     webfetch.js          ← new tool
    └── User JS Tools  (FEAT-012 - from /sdcard/OneClaw/tools/)

QuickJS Runtime  (FEAT-012 bridge)
    ├── Native Bridges: _time(), _readFile(), _writeFile(), _httpRequest()
    └── Shared Library Loader: lib('turndown'), lib('...')
           ↑
    assets/js/lib/
           turndown.min.js
           (future libraries)
```

### Shared JS Library System

#### Library Storage

Shared libraries are bundled as app assets:

```
assets/js/lib/
    turndown.min.js
```

User-provided libraries (for advanced use) can also be placed at:

```
{app_internal}/js/lib/
```

App-bundled libraries take precedence on name conflict with user-provided ones.

#### Library Loading API

Inside any JS tool (both built-in and user-defined), a library is loaded via a `lib()` function injected into the QuickJS global scope:

```javascript
const TurndownService = lib('turndown');
const td = new TurndownService();
const markdown = td.turndown(htmlString);
```

The `lib()` function:
1. Looks up the library name in `assets/js/lib/` (then `{app_internal}/js/lib/`)
2. Evaluates the library script in a sandboxed scope
3. Returns the library's exported value (the last assigned `module.exports`, `exports`, or the last evaluated expression)
4. Caches the result -- subsequent `lib('turndown')` calls within the same runtime session return the cached instance

#### Library Compatibility

Libraries must be:
- **Self-contained**: No Node.js built-ins (`require('fs')`, `require('path')`, etc.)
- **QuickJS-compatible**: Standard ES5/ES6+ JS; no browser-only APIs
- **Pure-logic libraries**: Libraries that process data, not libraries that require a DOM or native Node APIs

Turndown meets all three criteria.

### Built-in Tool JS Migration

The following Kotlin tools are removed and replaced with JS equivalents:

| Tool | Kotlin Class (removed) | JS Asset (new) |
|------|------------------------|----------------|
| `get_current_time` | `GetCurrentTimeTool.kt` | `assets/js/tools/get_current_time.js` |
| `read_file` | `ReadFileTool.kt` | `assets/js/tools/read_file.js` |
| `write_file` | `WriteFileTool.kt` | `assets/js/tools/write_file.js` |
| `http_request` | `HttpRequestTool.kt` | `assets/js/tools/http_request.js` |

Each JS tool calls through to native bridges already established in FEAT-012:

```javascript
// get_current_time.js
function execute(params) {
    return { time: _time() };
}
```

```javascript
// http_request.js
function execute(params) {
    const response = _httpRequest(params.method, params.url, params.headers, params.body);
    return response;
}
```

The tool definitions (name, description, parameter schema) move from hardcoded Kotlin `ToolDefinition` objects to the same `.json` metadata format used by user JS tools (FEAT-012), stored alongside the `.js` files in `assets/js/tools/`.

### New Tool: `webfetch`

`webfetch` is a new built-in JS tool that fetches a URL and returns the page content as clean Markdown.

#### Tool Definition

| Field | Value |
|-------|-------|
| Name | `webfetch` |
| Description | Fetch a web page and return its content as Markdown |
| Parameters | `url` (string, required): The URL to fetch |
| Required Permissions | `INTERNET` (already required by `http_request`) |
| Timeout | 30 seconds |
| Returns | Markdown string of the page content |

#### Implementation

```javascript
// webfetch.js
const TurndownService = lib('turndown');

function execute(params) {
    const response = _httpRequest('GET', params.url, {}, null);
    if (response.error) {
        return { error: response.error };
    }
    const contentType = (response.headers['content-type'] || '').toLowerCase();
    if (!contentType.includes('text/html')) {
        return { content: response.body };
    }
    const td = new TurndownService({ headingStyle: 'atx', codeBlockStyle: 'fenced' });
    // Remove nav, header, footer, script, style before conversion
    const cleanedHtml = response.body
        .replace(/<(script|style|nav|header|footer)[^>]*>[\s\S]*?<\/\1>/gi, '');
    const markdown = td.turndown(cleanedHtml);
    return { content: markdown };
}
```

#### Relationship to `http_request`

`webfetch` and `http_request` are separate tools with distinct responsibilities:

| | `http_request` | `webfetch` |
|--|----------------|------------|
| Purpose | General-purpose HTTP calls | Human-readable web page content |
| Response | Raw HTTP response (body, status, headers) | Markdown string |
| Use case | APIs, JSON endpoints, custom headers | Documentation, articles, web pages |
| Turndown | No | Yes |

### Built-in JS Tool Loading

Built-in JS tools (from `assets/js/tools/`) are loaded at app startup alongside FEAT-012 user JS tools:

1. At startup, `JsToolLoader` scans `assets/js/tools/` for `*.json` + `*.js` pairs
2. Each pair is registered in `ToolRegistry` as a `JsTool` (same class as FEAT-012 user tools)
3. App-internal user tools and external user tools are loaded afterward (FEAT-012 behavior unchanged)
4. On name conflict: user tools override built-in tools (power users can replace any built-in tool)

### Native Bridge Requirements

FEAT-012 introduced QuickJS bridges. FEAT-015 requires the following bridges to be available (some may already exist from FEAT-012):

| Bridge Function | Description | Already in FEAT-012? |
|----------------|-------------|----------------------|
| `_time()` | Returns ISO 8601 current time string | Likely yes |
| `_readFile(path)` | Reads file content as string | Likely yes |
| `_writeFile(path, content)` | Writes string to file | Likely yes |
| `_httpRequest(method, url, headers, body)` | Makes HTTP request, returns `{status, body, headers, error}` | Likely yes |

If any bridge is missing from FEAT-012, it must be added as part of FEAT-015 implementation.

### User Interaction Flows

#### Using `webfetch` in a Conversation

```
1. User: "Summarize the content at https://example.com/article"
2. AI calls webfetch(url="https://example.com/article")
3. Tool fetches HTML, runs Turndown, returns Markdown
4. AI summarizes the Markdown content and responds to the user
5. Chat shows the webfetch tool call (URL → Markdown)
```

#### User JS Tool Using a Shared Library

```
1. User asks AI: "Create a tool that converts text to title case"
2. AI writes:
   - {tools}/title_case.js  (uses lib('some-case-library') or plain JS)
   - {tools}/title_case.json
3. Tool is immediately available in the registry
4. User calls the tool in the next message
```

## Acceptance Criteria

Must pass (all required):

- [ ] All four existing Kotlin built-in tools (`get_current_time`, `read_file`, `write_file`, `http_request`) are removed from Kotlin and replaced with JS equivalents
- [ ] Migrated tools behave identically to their Kotlin predecessors (same tool names, same parameter schemas, same return formats)
- [ ] Built-in JS tools are loaded from `assets/js/tools/` at app startup
- [ ] `lib()` function is available in the QuickJS global scope for all JS tools
- [ ] `lib('turndown')` correctly loads the bundled Turndown library and returns a usable `TurndownService` constructor
- [ ] `webfetch` tool is available and returns clean Markdown for a given URL
- [ ] `webfetch` handles non-HTML responses (returns raw body without Turndown processing)
- [ ] `webfetch` handles HTTP errors gracefully (returns error message)
- [ ] User-defined JS tools (FEAT-012) can also use `lib()` to access shared libraries
- [ ] User tools with the same name as a built-in tool override the built-in tool
- [ ] All existing Layer 1A tests for built-in tools continue to pass after migration

Optional (nice to have for V1):

- [ ] User can add custom libraries to `{app_internal}/js/lib/` and use them via `lib()`
- [ ] Library loading errors produce clear error messages naming the library that failed
- [ ] Built-in tool JS source files are viewable (as a reference) from Settings or file manager

## UI/UX Requirements

This feature has no new UI. The migration is transparent to users:
- Tool names and behaviors are identical
- `webfetch` appears in tool lists like any other tool
- Tool call display in chat is unchanged

## Feature Boundary

### Included

- JS Library Bundle System (`lib()` API, `assets/js/lib/` directory)
- Bundled Turndown library (`turndown.min.js`)
- Migration of `get_current_time`, `read_file`, `write_file`, `http_request` from Kotlin to JS
- New `webfetch` built-in JS tool
- Built-in JS tool loading from `assets/js/tools/`
- User tool override of built-in tools on name conflict

### Not Included (V1)

- Library version management or update mechanism
- Library marketplace or discovery
- Tree-shaking or bundling optimization for libraries
- Libraries that require a DOM (jsdom, etc.)
- HTML truncation / token-limit awareness in `webfetch` (deferred to a future feature)
- `webfetch` caching (deferred)
- PDF or non-HTML content extraction in `webfetch` (returns raw body)

## Business Rules

1. Built-in JS tools are read-only -- they cannot be edited or deleted by the user via in-app UI
2. A user tool with the same name as a built-in tool silently overrides the built-in tool
3. `lib()` only resolves libraries by name (not by path) -- arbitrary file access is not allowed
4. Libraries loaded via `lib()` are sandboxed within the same QuickJS instance -- they cannot access the Android API directly
5. `webfetch` only accepts HTTP and HTTPS URLs
6. `webfetch` follows redirects (up to 5 hops, consistent with `http_request` behavior)
7. `webfetch` strips `<script>`, `<style>`, `<nav>`, `<header>`, `<footer>` before Turndown conversion

## Non-Functional Requirements

### Performance

- `lib()` first load (cold): < 100ms for Turndown
- `lib()` subsequent calls (cached): < 1ms
- `webfetch` total round-trip (network excluded): < 200ms for Turndown processing
- App startup time increase from loading built-in JS tools: < 100ms

### Compatibility

- All migrated tools must be fully backward-compatible: same tool names, same parameter schemas, same return value structures

### Security

- `lib()` cannot load files outside `assets/js/lib/` and `{app_internal}/js/lib/`
- Libraries run in the same QuickJS sandbox as other JS tools -- no elevated access
- `webfetch` does not follow cross-origin redirects to `file://` or `content://` URIs

## Dependencies

### Depends On

- **FEAT-004 (Tool System)**: The tool interface, registry, and execution engine being replaced
- **FEAT-012 (JavaScript Tool Engine)**: QuickJS runtime, native bridges, JS tool loading infrastructure

### Depended On By

- No other features currently depend on FEAT-015

### External Dependencies

- **Turndown** (~20KB minified, no runtime dependencies): bundled as `assets/js/lib/turndown.min.js`

## Error Handling

### Error Scenarios

1. **Library not found**
   - Cause: `lib('unknown-lib')` called for a non-existent library
   - Handling: Throw `Error("Library 'unknown-lib' not found")` inside QuickJS -- caught by `ToolExecutionEngine` and returned as `ToolResult.error()`

2. **Library parse/evaluation error**
   - Cause: Corrupted library file
   - Handling: Throw descriptive error; tool returns error result

3. **`webfetch` network error**
   - Cause: URL unreachable, timeout, DNS failure
   - Handling: Return `{ error: "Network error: <message>" }`

4. **`webfetch` non-200 response**
   - Cause: 404, 500, etc.
   - Handling: Return `{ error: "HTTP <status>: <status text>", content: response.body }`

5. **Turndown conversion failure**
   - Cause: Malformed HTML that Turndown cannot process
   - Handling: Fall back to returning raw stripped text; log warning

6. **Built-in tool JS file missing from assets**
   - Cause: Build misconfiguration
   - Handling: Log error at startup; continue without registering the affected tool; surface in a startup diagnostics log

## Future Improvements

- [ ] **HTML truncation in `webfetch`**: Limit Markdown output to a configurable token budget to avoid overwhelming the model context
- [ ] **`webfetch` caching**: Cache fetched pages for a short TTL (e.g., 5 minutes) to avoid redundant network requests
- [ ] **More bundled libraries**: e.g., a Markdown parser, a CSV parser, a date/time utility
- [ ] **Library manifest**: A `lib-manifest.json` listing bundled libraries with name, version, and description -- surfaced in Settings
- [ ] **User library upload**: UI for adding custom libraries without using the file manager

## Test Points

### Functional Tests

- Verify `get_current_time` JS tool returns correct ISO 8601 time
- Verify `read_file` JS tool reads file content correctly
- Verify `write_file` JS tool writes and overwrites file content correctly
- Verify `http_request` JS tool makes GET/POST requests and returns status, body, headers
- Verify all four migrated tools have identical parameter schemas to their Kotlin predecessors
- Verify `lib('turndown')` returns a usable constructor
- Verify `lib('nonexistent')` returns a clear error
- Verify `webfetch` returns Markdown for an HTML page
- Verify `webfetch` strips `<script>` and `<nav>` tags before conversion
- Verify `webfetch` returns raw body for non-HTML content types
- Verify `webfetch` returns error for unreachable URLs
- Verify user JS tools can call `lib()` for shared libraries
- Verify user tool with same name as built-in overrides the built-in

### Edge Cases

- `webfetch` on a page with no body content (empty HTML)
- `webfetch` on a very large HTML page (>1MB)
- `lib()` called concurrently from multiple tools in the same session
- `http_request` with custom headers and POST body (same as before migration)
- Built-in tool directory exists but a `.json` or `.js` file is missing for one tool
- `webfetch` redirect chain exceeding 5 hops

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
