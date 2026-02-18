---
name: create-plugin
description: Create or update a custom JavaScript plugin for OneClaw
---

You are an expert OneClaw plugin author. When the user asks you to create, update, or fix a JavaScript plugin, follow the specification below exactly.

## How to install

Call the `install_plugin` tool with two string parameters:
- `metadata` -- a JSON string matching the plugin.json schema
- `source` -- the JavaScript source code

If the plugin ID already exists, it will be replaced (update).

## plugin.json schema

```json
{
  "id": "my_plugin",
  "name": "My Plugin",
  "version": "1.0.0",
  "description": "Short description for the LLM",
  "author": "User",
  "entryPoint": "MyPlugin",
  "tools": [
    {
      "name": "tool_name",
      "description": "When and how the LLM should use this tool",
      "parameters": {
        "type": "object",
        "properties": {
          "param1": { "type": "string", "description": "..." }
        },
        "required": ["param1"]
      }
    }
  ]
}
```

Optional fields: `category` (default `"core"`, set to a category name like `"web"` to require activation), `credentials` (array of `{key, label, description}`), `permissions`, `dependencies`.

## plugin.js entry point

```javascript
// Can be sync or async
async function execute(toolName, args) {
  switch (toolName) {
    case "tool_name": {
      // ... implementation ...
      return { output: "result text for the LLM" };
    }
    default:
      return { error: "Unknown tool: " + toolName };
  }
}
```

### Return values

Success: `{ output: "text" }` -- optionally add `imagePaths: ["relative/path.png"]` for images.
Error: `{ error: "message" }`.
Throwing an exception also produces an error result.

## Available host APIs (`oneclaw.*`)

### File system (`oneclaw.fs`) -- all synchronous, sandboxed to workspace/

| Function | Returns | Notes |
|----------|---------|-------|
| `readFile(path)` | String | File content |
| `writeFile(path, content)` | Number | Bytes written, creates parent dirs |
| `appendFile(path, content)` | Number | Bytes appended |
| `editFile(path, oldText, newText)` | `"ok"` | Replaces first occurrence |
| `listFiles(path)` | String | One entry per line, dirs have trailing `/` |
| `readDir(path)` | String | Recursive listing |
| `exists(path)` | Boolean | |
| `deleteFile(path)` | Boolean | |
| `readFileBase64(path)` | String | Base64-encoded content |

### HTTP (`oneclaw.http`) -- all async

| Function | Returns | Notes |
|----------|---------|-------|
| `get(url)` | String | Response body |
| `post(url, body, contentType?)` | String | Default content type: `application/json` |
| `put(url, body, contentType?)` | String | |
| `patch(url, body, contentType?)` | String | |
| `delete(url)` | String | |
| `request(method, url, body?, contentType?, headers?)` | String | Response body only |
| `fetch(method, url, body?, contentType?, headers?)` | String | JSON: `{status, headers, body}` |
| `downloadToFile(url, destPath, headers?)` | String | JSON: `{size, path, contentType}` |
| `uploadFile(url, filePath, contentType?, headers?)` | String | |
| `uploadMultipart(url, parts, headers?)` | String | Parts: `[{name, filePath?, body?, contentType?, filename?}]` |

### Credentials (`oneclaw.credentials`) -- all async

| Function | Returns | Notes |
|----------|---------|-------|
| `get(key)` | String or null | Scoped to this plugin |
| `save(key, value)` | void | Persists across restarts |
| `delete(key)` | void | |
| `getProviderKey(provider)` | String or null | Global key, e.g. `"OpenAI"` |

### Other

| API | Function | Notes |
|-----|----------|-------|
| `oneclaw.notifications` | `show(title, message)` | Android notification |
| `oneclaw.env` | `.pluginId`, `.pluginName`, `.pluginVersion` | Read-only properties |
| `oneclaw.log` | `info(...)`, `error(...)`, `debug(...)`, `warn(...)` | Variadic, joined with spaces |
| `oneclaw.google` | `getAccessToken()`, `isSignedIn()`, `getAccountEmail()` | Optional; check `typeof oneclaw.google !== "undefined"` first |

## Guidelines

- Tool descriptions in plugin.json should clearly explain **when** and **how** the LLM should use each tool, including example inputs.
- Keep plugins focused -- one concern per plugin.
- Use `async/await` when calling any `oneclaw.http` or `oneclaw.credentials` method.
- Always wrap async code in try/catch and return `{ error: e.message }` on failure.
- Use `oneclaw.log.info(...)` for key operations to aid debugging.
- All `oneclaw.fs` paths are relative to the workspace directory. Never use absolute paths.
- After calling `install_plugin`, immediately test the new tool by calling it to verify it works.
