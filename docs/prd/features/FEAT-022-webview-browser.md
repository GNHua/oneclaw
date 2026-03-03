# WebView Browser Tool

## Feature Information
- **Feature ID**: FEAT-022
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P2 (Nice to Have)
- **Owner**: TBD
- **Related RFC**: RFC-022 (pending)

## User Story

**As** an AI agent using OneClaw,
**I want** a browser tool that can render web pages in a real browser environment, take screenshots, and extract content from dynamically-rendered pages (SPAs),
**so that** I can interact with modern web applications that rely on JavaScript for content rendering, and visually inspect pages when text extraction is insufficient.

### Typical Scenarios

1. The agent needs to check the visual layout of a web page. It calls `browser_screenshot` with a URL and receives a screenshot image that it can analyze visually.
2. The agent needs content from a React/Vue/Angular SPA. The `webfetch` tool returns mostly empty HTML because the content is rendered by JavaScript. The agent uses `browser_extract` to load the page in a real WebView, wait for JS rendering, and extract the final DOM content as Markdown.
3. The agent needs to verify that a web form looks correct. It takes a screenshot and confirms the layout, button placement, and text are as expected.
4. The agent needs to extract structured data from a page that loads content via AJAX after initial page load. `browser_extract` waits for the page to settle before extracting.

## Feature Description

### Overview

FEAT-022 adds a WebView-based browser tool that provides two capabilities to AI agents:

1. **`browser_screenshot`**: Render a web page in an off-screen Android WebView and capture a screenshot as an image.
2. **`browser_extract`**: Render a web page in a WebView, execute JavaScript in the browser context to extract content, and return the result as Markdown or structured text.

Unlike `webfetch` (FEAT-021), which fetches static HTML and parses it server-side, the browser tool uses a real browser engine (Android WebView / Chromium) that executes JavaScript, loads dynamic content, and renders the page visually. This enables interaction with modern SPAs and JavaScript-heavy pages.

### Architecture Overview

```
AI Model
    | tool call: browser_screenshot(url="...") or browser_extract(url="...")
    v
ToolExecutionEngine  (Kotlin, unchanged)
    |
    v
ToolRegistry
    |
    v
BrowserTool  [NEW - Kotlin built-in tool]
    |
    +-- WebViewManager [NEW - manages off-screen WebView lifecycle]
    |       |
    |       +-- WebView (off-screen, created on main thread)
    |       |     |
    |       |     +-- JavaScript execution (evaluateJavascript)
    |       |     +-- Page rendering (Chromium engine)
    |       |
    |       +-- ScreenshotCapture [NEW - captures WebView as bitmap]
    |       |     |
    |       |     +-- Canvas-based capture
    |       |     +-- Image compression (PNG/JPEG)
    |       |
    |       +-- ContentExtractor [NEW - JS-based DOM extraction]
    |             |
    |             +-- Built-in extraction script (Readability-style)
    |             +-- Optional Turndown (runs in WebView with real DOM)
    |
    +-- Output handling
          |
          +-- Screenshot: file path or base64
          +-- Extract: Markdown string
```

### Two Capabilities, One Tool

The browser tool exposes two modes via a single tool registration with a `mode` parameter:

#### `browser_screenshot`

Renders a page and captures a screenshot:

| Field | Value |
|-------|-------|
| Mode | `screenshot` |
| Description | Render a web page and capture a screenshot |
| Parameters | `url` (string, required): The URL to render |
| | `mode` (string, required): `"screenshot"` |
| | `width` (integer, optional): Viewport width in pixels. Default: 412 (Pixel-like) |
| | `height` (integer, optional): Viewport height in pixels. Default: 915 |
| | `wait_seconds` (number, optional): Seconds to wait after page load for JS rendering. Default: 2 |
| | `full_page` (boolean, optional): Capture full scrollable page, not just viewport. Default: false |
| Returns | Object with `image_path` (file path to saved screenshot) |

#### `browser_extract`

Renders a page and extracts content via JavaScript:

| Field | Value |
|-------|-------|
| Mode | `extract` |
| Description | Render a web page and extract content as Markdown |
| Parameters | `url` (string, required): The URL to render |
| | `mode` (string, required): `"extract"` |
| | `wait_seconds` (number, optional): Seconds to wait after page load. Default: 2 |
| | `max_length` (integer, optional): Maximum output length. Default: 50000 |
| | `javascript` (string, optional): Custom JS to execute. Must return a string. |
| Returns | Markdown string of the extracted content |

### Tool Definition

| Field | Value |
|-------|-------|
| Name | `browser` |
| Description | Render a web page in a browser, then take a screenshot or extract content |
| Parameters | `url` (string, required): The URL to load |
| | `mode` (string, required): `"screenshot"` or `"extract"` |
| | `width` (integer, optional): Viewport width. Default: 412 |
| | `height` (integer, optional): Viewport height. Default: 915 |
| | `wait_seconds` (number, optional): Wait time after load. Default: 2 |
| | `full_page` (boolean, optional): Full-page screenshot. Default: false |
| | `max_length` (integer, optional): Max output for extract mode. Default: 50000 |
| | `javascript` (string, optional): Custom JS for extract mode |
| Timeout | 60 seconds |
| Returns | Screenshot file path (screenshot mode) or Markdown string (extract mode) |

### Screenshot Capture

The screenshot mode uses Android's WebView rendering pipeline:

1. Create or reuse an off-screen WebView (not visible to the user)
2. Set viewport dimensions per parameters
3. Load the URL and wait for `onPageFinished` callback
4. Wait additional `wait_seconds` for dynamic content to settle
5. Capture the WebView content to a Bitmap:
   - **Viewport-only**: Draw the WebView to a Canvas at the viewport size
   - **Full-page**: Measure the full content height via `computeVerticalScrollRange()`, create an appropriately-sized Bitmap, scroll and capture sections
6. Compress the Bitmap to PNG
7. Save to app-internal cache directory
8. Return the file path

### Content Extraction

The extract mode uses `evaluateJavascript()` to run JavaScript in the WebView's browser context:

1. Load the page and wait for rendering (same as screenshot flow)
2. If `javascript` parameter is provided, execute that custom script
3. Otherwise, execute a built-in extraction script that:
   a. Finds the main content area (same heuristic as FEAT-021: article > main > body)
   b. Strips noise elements (script, style, nav, footer, etc.)
   c. Uses Turndown (loaded in the WebView context where DOM APIs are available) to convert HTML to Markdown
   d. Falls back to `innerText` extraction if Turndown fails
4. Return the extracted text, truncated to `max_length`

This is where Turndown finally works as originally intended by FEAT-015 -- running in a real browser environment with full DOM API access.

### WebView Lifecycle

Managing WebView instances requires care to avoid memory leaks:

- WebViews must be created on the main (UI) thread
- A single reusable WebView instance is maintained by `WebViewManager`
- The WebView is created lazily on first use
- After each tool call, the WebView is reset (`loadUrl("about:blank")`, clear cache)
- The WebView is destroyed when the app goes to background or on explicit cleanup
- A timeout ensures the WebView is destroyed if not used for 5 minutes

### Relationship to `webfetch`

`browser` and `webfetch` are complementary tools:

| | `webfetch` (FEAT-021) | `browser` (FEAT-022) |
|--|------------------------|----------------------|
| Engine | OkHttp + Jsoup | Android WebView (Chromium) |
| JavaScript | Not executed | Fully executed |
| Dynamic content | No (static HTML only) | Yes (SPAs, AJAX, etc.) |
| Screenshot | No | Yes |
| Speed | Fast (< 1s typical) | Slower (2-5s typical) |
| Memory | Low (~5MB peak) | High (~50-100MB for WebView) |
| Use case | Static pages, docs, articles | SPAs, JS-rendered pages, visual inspection |
| Recommended for | Most web fetching | When `webfetch` returns empty/incomplete content |

The AI model should prefer `webfetch` for most tasks and fall back to `browser` when content requires JavaScript rendering.

### User Interaction Flows

#### Screenshot Flow

```
1. User: "Show me what google.com looks like"
2. AI calls browser(url="https://google.com", mode="screenshot")
3. BrowserTool:
   a. Creates/reuses off-screen WebView
   b. Loads URL, waits for page load + 2s
   c. Captures screenshot to PNG
   d. Returns file path
4. AI returns the screenshot image to the user
5. Chat displays the screenshot inline
```

#### Extract Flow

```
1. User: "Get the content from this React app: https://example-spa.com"
2. AI first tries webfetch -- gets mostly empty HTML (JS not executed)
3. AI calls browser(url="https://example-spa.com", mode="extract")
4. BrowserTool:
   a. Loads URL in WebView, waits for JS rendering
   b. Runs extraction script via evaluateJavascript()
   c. Returns Markdown content
5. AI summarizes the content for the user
```

#### Custom JavaScript Flow

```
1. User: "Get the price from this product page"
2. AI calls browser(url="https://shop.example.com/product/123", mode="extract",
     javascript="document.querySelector('.product-price')?.textContent || 'Price not found'")
3. BrowserTool:
   a. Loads URL, waits for rendering
   b. Executes custom JS
   c. Returns the price text
4. AI reports the price to the user
```

## Acceptance Criteria

Must pass (all required):

- [ ] `browser` tool is registered in `ToolRegistry` with both `screenshot` and `extract` modes
- [ ] `mode` parameter is required and validated (only `"screenshot"` or `"extract"` accepted)
- [ ] Screenshot mode renders a page and saves a PNG file
- [ ] Screenshot file is accessible by the AI model (returned as file path)
- [ ] Extract mode loads a page, executes JavaScript, and returns text content
- [ ] Extract mode handles SPA pages that render content via JavaScript
- [ ] Default extraction script finds main content and converts to Markdown
- [ ] Custom `javascript` parameter is executed and its return value is used
- [ ] `wait_seconds` parameter controls the wait time after page load
- [ ] WebView is created on the main thread and properly managed
- [ ] WebView is cleaned up after each tool call (no state leaks between calls)
- [ ] URL validation rejects non-HTTP schemes
- [ ] Tool timeout (60s) prevents hanging on pages that never finish loading
- [ ] All Layer 1A tests pass

Optional (nice to have for V1):

- [ ] `full_page` screenshot captures the entire scrollable page
- [ ] Viewport size is configurable via `width` and `height` parameters
- [ ] WebView reuse across multiple tool calls in the same session
- [ ] Screenshot format selection (PNG vs JPEG)

## UI/UX Requirements

This feature has no new UI for the user. The WebView is off-screen and invisible:
- Screenshots are saved to cache and displayed in chat as image results
- Extract results are displayed as text tool results
- No browser window or WebView is shown to the user

## Feature Boundary

### Included

- Off-screen WebView management (`WebViewManager`)
- Screenshot capture via Canvas/Bitmap
- Content extraction via `evaluateJavascript()`
- Built-in extraction script with Turndown in WebView
- Custom JavaScript execution in extract mode
- Configurable wait time for dynamic content
- Output truncation for extract mode
- WebView lifecycle management (create, reuse, cleanup, destroy)

### Not Included (V1)

- Interactive browsing (clicking, scrolling, form filling)
- Multi-page navigation within a single tool call
- Cookie/session persistence across tool calls
- Authentication (login flows)
- PDF rendering or download
- Video/audio content capture
- Browser DevTools or network inspection
- Headless Chrome or external browser integration
- Accessibility tree extraction

## Business Rules

1. `browser` only accepts HTTP and HTTPS URLs
2. WebView JavaScript execution is enabled (required for SPA support)
3. Custom `javascript` parameter executes in the page's context (has access to page DOM)
4. Screenshots are saved as PNG files in the app's cache directory
5. Screenshot files are temporary and may be cleaned up by the system
6. WebView does not persist cookies, localStorage, or session data between tool calls
7. Tool timeout is 60 seconds (longer than `webfetch` due to rendering time)
8. The WebView is not visible to the user at any time

## Non-Functional Requirements

### Performance

- Page load + screenshot: 3-8 seconds typical (depends on page complexity)
- Page load + extraction: 3-6 seconds typical
- WebView creation (cold start): ~500ms
- WebView reuse (warm): < 100ms overhead
- Screenshot PNG compression: < 200ms for viewport-size images

### Memory

- WebView process: ~50-100MB (managed by Android system, separate process)
- Screenshot bitmap: ~5-15MB (viewport size, ARGB_8888)
- Full-page screenshot: potentially larger (capped at 10,000px height)
- WebView is destroyed when not in use to free memory

### Compatibility

- Requires Android API 24+ (WebView `evaluateJavascript` and modern features)
- WebView version depends on user's system WebView update
- Most modern web pages render correctly in Android WebView

### Security

- WebView JavaScript is enabled but sandboxed by the Android WebView security model
- `WebViewClient` does not expose Android interfaces to web content (`addJavascriptInterface` is NOT used for page scripts)
- Custom `javascript` parameter runs in the same sandbox as page scripts
- No file:// or content:// URLs allowed
- WebView cache is cleared after each use

## Dependencies

### Depends On

- **FEAT-004 (Tool System)**: Tool interface, registry, execution engine
- **FEAT-021 (Kotlin Webfetch)**: Establishes the static HTML fetching baseline; browser tool complements it

### Depended On By

- No other features currently depend on FEAT-022

### External Dependencies

- **Android WebView**: System component, no additional dependency needed
- **Turndown.js** (~20KB): Already bundled as `assets/js/lib/turndown.min.js` (from FEAT-015). Loaded into WebView for DOM-to-Markdown conversion.

## Error Handling

### Error Scenarios

1. **Invalid URL**
   - Cause: Malformed URL or non-HTTP scheme
   - Handling: Return `ToolResult.error("Invalid URL: <message>")`

2. **Invalid mode**
   - Cause: `mode` is not `"screenshot"` or `"extract"`
   - Handling: Return `ToolResult.error("Invalid mode. Use 'screenshot' or 'extract'")`

3. **Page load failure**
   - Cause: Network error, DNS failure, SSL error
   - Handling: `WebViewClient.onReceivedError` captures the error; return `ToolResult.error("Page load failed: <message>")`

4. **Page load timeout**
   - Cause: Page never finishes loading within tool timeout
   - Handling: Cancel loading, return partial result or error

5. **JavaScript execution error**
   - Cause: Custom JS throws an exception or returns undefined
   - Handling: Return `ToolResult.error("JavaScript error: <message>")`

6. **Screenshot capture failure**
   - Cause: WebView not rendered, bitmap allocation failure
   - Handling: Return `ToolResult.error("Screenshot capture failed: <message>")`

7. **WebView unavailable**
   - Cause: System WebView not installed or disabled (rare)
   - Handling: Return `ToolResult.error("WebView not available on this device")`

8. **Out of memory**
   - Cause: Full-page screenshot of extremely long page
   - Handling: Cap full-page height at 10,000px; fall back to viewport-only

## Future Improvements

- [ ] **Interactive mode**: Support clicking, scrolling, and form filling for multi-step web interactions
- [ ] **Session persistence**: Allow the AI to maintain a browser session across multiple tool calls
- [ ] **Network inspection**: Capture XHR/fetch requests and responses for debugging
- [ ] **Accessibility tree**: Extract the accessibility tree for structured page understanding
- [ ] **Element screenshot**: Screenshot a specific CSS selector rather than the full page
- [ ] **Video/GIF capture**: Record short animations or page transitions
- [ ] **Multiple tabs**: Support opening and switching between multiple pages

## Test Points

### Functional Tests

- Verify screenshot mode produces a valid PNG file
- Verify screenshot file path is returned in the tool result
- Verify extract mode returns Markdown content
- Verify extract mode handles JS-rendered content (mock SPA page)
- Verify custom `javascript` parameter is executed and result returned
- Verify `wait_seconds` parameter delays extraction
- Verify URL validation rejects non-HTTP schemes
- Verify `mode` parameter validation
- Verify WebView cleanup after tool call (no state leaks)
- Verify tool timeout works (60s)

### Edge Cases

- Page with infinite scroll (screenshot should capture viewport only by default)
- Page that never finishes loading (timeout handling)
- Page with SSL certificate error
- Page with HTTP Basic Auth prompt
- `javascript` parameter that returns a very large string
- `javascript` parameter that takes longer than wait_seconds
- Concurrent browser tool calls (should queue or reject)
- App goes to background during browser tool execution
- WebView process crash during rendering
- Page with `<meta http-equiv="refresh">` redirect

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
