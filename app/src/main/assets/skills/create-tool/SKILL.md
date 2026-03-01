---
name: create-tool
display_name: Create Tool
description: Guide the user through creating a custom JavaScript tool
version: "1.0"
tools_required:
  - create_js_tool
parameters:
  - name: idea
    type: string
    required: false
    description: Brief description of what the tool should do
---

# Create Tool

You are helping the user create a custom JavaScript tool for the OneClawShadow AI agent.

## Your Workflow

1. **Understand Requirements**: Ask the user what the tool should do. If they provided
   an {{idea}}, start from that. Clarify:
   - What data does the tool work with? (inputs)
   - What should it return? (outputs)
   - Does it need network access? (fetch API)
   - Does it need file system access? (fs API)
   - Are there any edge cases to handle?

2. **Design the Tool**: Based on the requirements, design:
   - A clear, descriptive tool name (snake_case, e.g., `parse_csv`, `fetch_weather`)
   - A description that helps the AI know when to use this tool
   - Parameters with types and descriptions
   - The JavaScript implementation

3. **Show for Review**: Present the complete tool to the user:
   - Tool name and description
   - Parameters table
   - Full JavaScript code
   - Ask: "Should I create this tool?"

4. **Create**: Only after the user confirms, call `create_js_tool` with:
   - name
   - description
   - parameters_schema (JSON string)
   - js_code
   - required_permissions (if needed)
   - timeout_seconds (if non-default)

5. **Verify**: After creation, suggest the user try the tool.

## Available JavaScript APIs

The following APIs are available to the tool's JavaScript code:

### HTTP Requests
```javascript
// Async fetch (like browser fetch API)
const response = await fetch(url, {
    method: "GET", // or "POST", "PUT", "DELETE"
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data) // for POST/PUT
});
const text = await response.text();
const json = await response.json();
// response.ok, response.status, response.statusText, response.headers
```

### File System
```javascript
fs.readFile(path)              // Returns file content as string
fs.writeFile(path, content)    // Write content to file (overwrite)
fs.appendFile(path, content)   // Append content to file
fs.exists(path)                // Returns true/false
// Note: restricted paths blocked (/data/data/, /system/, /proc/, /sys/)
// File size limit: 1MB per file
```

### Time
```javascript
_time(timezone, format)
// timezone: IANA format (e.g., "America/New_York"), empty for device timezone
// format: "iso8601" (default) or "human_readable"
```

### Console (for debugging)
```javascript
console.log("debug info")     // Logs to Android Logcat
console.warn("warning")
console.error("error")
```

### Libraries
```javascript
const TurndownService = lib('turndown'); // HTML to Markdown
```

## Tool Code Template

```javascript
// Synchronous tool
function execute(params) {
    // params contains all parameters defined in the schema
    var result = "...";
    return result; // Return a string or object
}

// Asynchronous tool (for HTTP requests)
async function execute(params) {
    var response = await fetch(params.url);
    var data = await response.text();
    return data;
}
```

## Important Rules

- ALWAYS show the code to the user and wait for confirmation before creating
- Tool names must be lowercase letters, numbers, and underscores (2-50 chars)
- The function must be named `execute` and accept a `params` argument
- Return a string for simple results, or an object that will be JSON-serialized
- Handle errors gracefully -- return descriptive error messages
- Keep tools focused -- one tool should do one thing well
- Add helpful parameter descriptions so the AI knows how to use the tool
