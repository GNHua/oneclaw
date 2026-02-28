# RFC-004: Tool System

## Document Information
- **RFC ID**: RFC-004
- **Related PRD**: [FEAT-004 (Tool System)](../../prd/features/FEAT-004-tool-system.md)
- **Related Design**: [UI Design Spec](../../design/ui-design-spec.md) (Tool Call Display in Chat Screen)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Created**: 2026-02-27
- **Last Updated**: 2026-02-27 (updated with implementation fixes from Layer 2 testing)
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
The Tool System is the framework that allows AI models to perform real actions beyond text generation -- reading files, making HTTP requests, checking the time, etc. This is what makes OneClawShadow an "AI Agent runtime" rather than just a chat app. The system defines a standard interface for tool registration, execution, and result formatting, along with Android permission handling and provider-specific format conversion.

This RFC covers the tool infrastructure and all 4 built-in tools. The actual tool call loop within a streaming conversation (model requests tool -> execute -> send result back -> model continues) is covered in RFC-001 (Chat Interaction). Tool selection UI in Agent configuration is covered in RFC-002 (Agent Management).

### Goals
1. Define the `Tool` interface and `ToolDefinition` data model
2. Implement the `ToolRegistry` for tool registration and lookup
3. Implement the `ToolExecutionEngine` with timeout, permission checking, and error handling
4. Implement `PermissionChecker` for Android runtime permissions
5. Implement 4 built-in tools: `get_current_time`, `read_file`, `write_file`, `http_request`
6. Define tool definition format conversion for each provider (OpenAI, Anthropic, Gemini)
7. Implement parameter validation against JSON Schema
8. Support parallel execution of multiple tool calls in a single model response

### Non-Goals
- Tool call loop within streaming conversation (RFC-001)
- Tool selection UI in Agent configuration (RFC-002)
- Tool call display in chat UI (RFC-001)
- User-defined custom tools
- Sandboxed tool execution (separate process)
- Tool execution approval/confirmation flow
- Streaming tool results

## Technical Design

### Architecture Overview

```
┌──────────────────────────────────────────────────────┐
│                     Chat Layer (RFC-001)               │
│  SendMessageUseCase                                    │
│       │                                                │
│       │  tool call request from model                  │
│       v                                                │
├──────────────────────────────────────────────────────┤
│                   Tool Execution Engine                │
│  ┌────────────────────────────────────────────────┐   │
│  │ executeTool(name, params, availableToolIds)     │   │
│  │      │                                          │   │
│  │      ├── 1. Lookup tool in ToolRegistry         │   │
│  │      ├── 2. Validate availability               │   │
│  │      ├── 3. Check permissions (PermissionChecker)│  │
│  │      ├── 4. Execute with timeout (coroutine)    │   │
│  │      └── 5. Return ToolResult                   │   │
│  └────────────────────────────────────────────────┘   │
│       │                                                │
│       v                                                │
│  ┌────────────────────────────────────────────────┐   │
│  │              ToolRegistry                       │   │
│  │  ┌──────────────────┐ ┌──────────────────┐     │   │
│  │  │ get_current_time │ │    read_file     │     │   │
│  │  └──────────────────┘ └──────────────────┘     │   │
│  │  ┌──────────────────┐ ┌──────────────────┐     │   │
│  │  │   write_file     │ │  http_request    │     │   │
│  │  └──────────────────┘ └──────────────────┘     │   │
│  └────────────────────────────────────────────────┘   │
├──────────────────────────────────────────────────────┤
│              Provider Adapter Layer (RFC-003)          │
│  formatToolDefinitions() converts ToolDefinition      │
│  to provider-specific format (OpenAI/Anthropic/Gemini)│
└──────────────────────────────────────────────────────┘
```

### Core Components

1. **Tool (interface)**
   - Responsibility: Define the contract all tools must implement
   - Properties: `definition: ToolDefinition`
   - Methods: `suspend fun execute(parameters: Map<String, Any?>): ToolResult`

2. **ToolDefinition (data class)**
   - Responsibility: Describe a tool's metadata (name, description, parameter schema, permissions, timeout)
   - Used by: ToolRegistry, Agent config, provider adapters

3. **ToolRegistry**
   - Responsibility: Store all registered tools, provide lookup by name, provide filtered lists by tool IDs
   - Singleton: One instance for the entire app

4. **ToolExecutionEngine**
   - Responsibility: Orchestrate tool execution -- lookup, permission check, timeout, error handling
   - Dependencies: ToolRegistry, PermissionChecker

5. **PermissionChecker**
   - Responsibility: Check and request Android runtime permissions
   - Mechanism: Suspending coroutine that waits for Activity permission callback

6. **ToolParameterValidator**
   - Responsibility: Validate tool call parameters against the tool's JSON Schema
   - Used by: ToolExecutionEngine (before executing)

## Data Model

### Domain Models

All defined in `core/model/`. `ToolDefinition` and `ToolResult` are already declared in RFC-000. This section provides the complete definitions.

#### ToolDefinition

```kotlin
data class ToolDefinition(
    val name: String,                          // Unique tool name, snake_case (e.g., "read_file")
    val description: String,                   // Human-readable description (one sentence)
    val parametersSchema: ToolParametersSchema, // Structured parameter schema
    val requiredPermissions: List<String>,      // Android permissions needed (e.g., "android.permission.READ_EXTERNAL_STORAGE")
    val timeoutSeconds: Int                    // Max execution time
)
```

**Change from RFC-000**: `parametersSchema` was a `String` (raw JSON Schema). It is now a typed `ToolParametersSchema` data class for easier manipulation in Kotlin code while still being serializable to JSON Schema for API calls.

#### ToolParametersSchema

```kotlin
data class ToolParametersSchema(
    val properties: Map<String, ToolParameter>,   // Parameter name -> definition
    val required: List<String> = emptyList()       // Names of required parameters
)

data class ToolParameter(
    val type: String,                  // "string", "integer", "number", "boolean", "object", "array"
    val description: String,           // Human-readable description
    val enum: List<String>? = null,    // Allowed values (if restricted)
    val default: Any? = null           // Default value (if optional)
)
```

This is a simplified subset of JSON Schema that covers all V1 tool parameter needs. When serialized for API calls, it produces standard JSON Schema format:

```json
{
  "type": "object",
  "properties": {
    "path": { "type": "string", "description": "The absolute file path to read" }
  },
  "required": ["path"]
}
```

#### ToolResult

```kotlin
data class ToolResult(
    val status: ToolResultStatus,
    val result: String?,               // Result data (for success)
    val errorType: String?,            // Error type identifier (for error)
    val errorMessage: String?          // Human-readable error message (for error)
) {
    companion object {
        fun success(result: String): ToolResult = ToolResult(
            status = ToolResultStatus.SUCCESS,
            result = result,
            errorType = null,
            errorMessage = null
        )

        fun error(errorType: String, errorMessage: String): ToolResult = ToolResult(
            status = ToolResultStatus.ERROR,
            result = null,
            errorType = errorType,
            errorMessage = errorMessage
        )
    }
}

enum class ToolResultStatus {
    SUCCESS, ERROR
}
```

#### Serialization to JSON

The tool result is serialized to JSON when sent back to the model:

```kotlin
fun ToolResult.toJsonString(): String {
    return if (status == ToolResultStatus.SUCCESS) {
        Json.encodeToString(mapOf("status" to "success", "result" to result))
    } else {
        Json.encodeToString(mapOf(
            "status" to "error",
            "error_type" to errorType,
            "message" to errorMessage
        ))
    }
}
```

## Tool Interface

```kotlin
/**
 * Interface that all tools must implement.
 * Located in: tool/engine/Tool.kt
 */
interface Tool {

    /**
     * The tool's metadata: name, description, parameter schema, permissions, timeout.
     */
    val definition: ToolDefinition

    /**
     * Execute the tool with the given parameters.
     *
     * This method is called on a background dispatcher (Dispatchers.IO).
     * It should NOT switch dispatchers internally.
     *
     * @param parameters Key-value map of parameter name to value.
     *   Values are typed based on the parameter schema:
     *   - "string" -> String
     *   - "integer" -> Int or Long
     *   - "number" -> Double
     *   - "boolean" -> Boolean
     *   - "object" -> Map<String, Any?>
     *   - "array" -> List<Any?>
     *
     * @return ToolResult with success data or error information.
     */
    suspend fun execute(parameters: Map<String, Any?>): ToolResult
}
```

## Tool Registry

```kotlin
/**
 * Registry of all available tools. Singleton, created at app startup.
 * Located in: tool/engine/ToolRegistry.kt
 */
class ToolRegistry {

    private val tools = mutableMapOf<String, Tool>()

    /**
     * Register a tool. Throws IllegalArgumentException if a tool with the same name
     * is already registered.
     */
    fun register(tool: Tool) {
        val name = tool.definition.name
        require(!tools.containsKey(name)) {
            "Tool '$name' is already registered"
        }
        tools[name] = tool
    }

    /**
     * Get a tool by name. Returns null if not found.
     */
    fun getTool(name: String): Tool? = tools[name]

    /**
     * Get all registered tool definitions.
     */
    fun getAllToolDefinitions(): List<ToolDefinition> = tools.values.map { it.definition }

    /**
     * Get tool definitions for a specific set of tool names.
     * Used to get the tool set for a specific Agent.
     * Unknown names are silently ignored.
     */
    fun getToolDefinitionsByNames(names: List<String>): List<ToolDefinition> =
        names.mapNotNull { tools[it]?.definition }

    /**
     * Check if a tool name exists in the registry.
     */
    fun hasTool(name: String): Boolean = tools.containsKey(name)

    /**
     * Get all registered tool names.
     */
    fun getAllToolNames(): List<String> = tools.keys.toList()
}
```

## Tool Execution Engine

```kotlin
/**
 * Orchestrates tool execution: lookup, permission check, timeout, error handling.
 * Located in: tool/engine/ToolExecutionEngine.kt
 */
class ToolExecutionEngine(
    private val registry: ToolRegistry,
    private val permissionChecker: PermissionChecker
) {

    /**
     * Execute a single tool call.
     *
     * @param toolName The name of the tool to execute
     * @param parameters The parameters to pass to the tool
     * @param availableToolNames The tool names available to the current Agent
     * @return ToolResult with success or error
     */
    suspend fun executeTool(
        toolName: String,
        parameters: Map<String, Any?>,
        availableToolNames: List<String>
    ): ToolResult {
        // 1. Look up tool
        val tool = registry.getTool(toolName)
            ?: return ToolResult.error(
                "tool_not_found",
                "Tool '$toolName' not found"
            )

        // 2. Check availability (is this tool in the Agent's tool set?)
        if (toolName !in availableToolNames) {
            return ToolResult.error(
                "tool_not_available",
                "Tool '$toolName' is not available for this agent"
            )
        }

        // 3. Validate parameters
        val validationError = validateParameters(parameters, tool.definition.parametersSchema)
        if (validationError != null) {
            return ToolResult.error("validation_error", validationError)
        }

        // 4. Check Android permissions
        val missingPermissions = permissionChecker.getMissingPermissions(
            tool.definition.requiredPermissions
        )
        if (missingPermissions.isNotEmpty()) {
            val granted = permissionChecker.requestPermissions(missingPermissions)
            if (!granted) {
                return ToolResult.error(
                    "permission_denied",
                    "Required permissions were denied: ${missingPermissions.joinToString(", ")}"
                )
            }
        }

        // 5. Execute with timeout on IO dispatcher
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(tool.definition.timeoutSeconds * 1000L) {
                    tool.execute(parameters)
                }
            }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error(
                "timeout",
                "Tool execution timed out after ${tool.definition.timeoutSeconds}s"
            )
        } catch (e: CancellationException) {
            throw e  // Don't catch coroutine cancellation
        } catch (e: Exception) {
            ToolResult.error(
                "execution_error",
                "Tool execution failed: ${e.message ?: "Unknown error"}"
            )
        }
    }

    /**
     * Execute multiple tool calls in parallel.
     * Used when a single model response contains multiple tool call requests.
     *
     * @param toolCalls List of (toolName, parameters, toolCallId) triples
     * @param availableToolNames The tool names available to the current Agent
     * @return List of (toolCallId, ToolResult) pairs, in the same order as input
     */
    suspend fun executeToolsParallel(
        toolCalls: List<ToolCallRequest>,
        availableToolNames: List<String>
    ): List<ToolCallResponse> = coroutineScope {
        toolCalls.map { call ->
            async {
                val result = executeTool(call.toolName, call.parameters, availableToolNames)
                ToolCallResponse(
                    toolCallId = call.toolCallId,
                    toolName = call.toolName,
                    result = result,
                    durationMs = measureTimeMillis {
                        // Duration is measured inside executeTool; this is a simplified view.
                        // Actual timing is done below.
                    }
                )
            }
        }.map { deferred ->
            deferred.await()
        }
    }

    /**
     * Execute a single tool call and measure duration.
     */
    suspend fun executeToolTimed(
        toolName: String,
        parameters: Map<String, Any?>,
        availableToolNames: List<String>
    ): Pair<ToolResult, Long> {
        val startTime = System.currentTimeMillis()
        val result = executeTool(toolName, parameters, availableToolNames)
        val duration = System.currentTimeMillis() - startTime
        return Pair(result, duration)
    }

    /**
     * Validate parameters against the tool's schema.
     * Returns null if valid, or an error message string if invalid.
     */
    private fun validateParameters(
        parameters: Map<String, Any?>,
        schema: ToolParametersSchema
    ): String? {
        // Check required parameters are present
        for (requiredParam in schema.required) {
            if (!parameters.containsKey(requiredParam) || parameters[requiredParam] == null) {
                return "Missing required parameter: '$requiredParam'"
            }
        }

        // Check parameter types
        for ((name, value) in parameters) {
            if (value == null) continue  // Optional parameter not provided
            val paramDef = schema.properties[name] ?: continue  // Unknown param, ignore

            val typeError = validateType(name, value, paramDef.type)
            if (typeError != null) return typeError

            // Check enum constraint
            if (paramDef.enum != null && value is String && value !in paramDef.enum) {
                return "Parameter '$name' must be one of: ${paramDef.enum.joinToString(", ")}"
            }
        }

        return null  // Valid
    }

    private fun validateType(name: String, value: Any, expectedType: String): String? {
        val valid = when (expectedType) {
            "string" -> value is String
            "integer" -> value is Int || value is Long
            "number" -> value is Number
            "boolean" -> value is Boolean
            "object" -> value is Map<*, *>
            "array" -> value is List<*>
            else -> true  // Unknown type, allow
        }
        return if (!valid) {
            "Parameter '$name' expected type '$expectedType' but got ${value::class.simpleName}"
        } else null
    }
}

/**
 * Represents a tool call request from the model.
 */
data class ToolCallRequest(
    val toolCallId: String,            // Provider-assigned ID for this tool call
    val toolName: String,
    val parameters: Map<String, Any?>
)

/**
 * Represents the result of a tool call execution.
 */
data class ToolCallResponse(
    val toolCallId: String,
    val toolName: String,
    val result: ToolResult,
    val durationMs: Long
)
```

## Permission Checker

```kotlin
/**
 * Handles Android runtime permission checking and requesting.
 * Uses a suspending approach: when permissions need to be requested,
 * the coroutine suspends until the user responds to the permission dialog.
 *
 * Located in: tool/engine/PermissionChecker.kt
 */
class PermissionChecker(private val context: Context) {

    /**
     * Continuation waiting for permission result.
     * Only one permission request can be in-flight at a time.
     */
    private var pendingContinuation: CancellableContinuation<Boolean>? = null

    /**
     * The Activity must call this method to set up the permission result callback.
     * This should be called in onCreate of the Activity.
     */
    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null

    /**
     * Bind to an Activity's permission result launcher.
     * Called from MainActivity during initialization.
     */
    fun bindToActivity(launcher: ActivityResultLauncher<Array<String>>) {
        this.permissionLauncher = launcher
    }

    /**
     * Unbind when Activity is destroyed.
     */
    fun unbind() {
        this.permissionLauncher = null
        pendingContinuation?.cancel()
        pendingContinuation = null
    }

    /**
     * Check which of the given permissions are not yet granted.
     *
     * @param permissions List of Android permission strings
     * @return List of permissions that are NOT granted
     */
    fun getMissingPermissions(permissions: List<String>): List<String> {
        if (permissions.isEmpty()) return emptyList()
        return permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request the given permissions. Suspends until the user responds.
     *
     * @param permissions List of Android permission strings to request
     * @return true if ALL permissions were granted, false otherwise
     */
    suspend fun requestPermissions(permissions: List<String>): Boolean {
        val launcher = permissionLauncher
            ?: return false  // No Activity bound, cannot request

        return suspendCancellableCoroutine { continuation ->
            pendingContinuation = continuation

            continuation.invokeOnCancellation {
                pendingContinuation = null
            }

            // Launch the system permission dialog
            launcher.launch(permissions.toTypedArray())
        }
    }

    /**
     * Called by the Activity when the permission result is received.
     * This resumes the suspended coroutine.
     */
    fun onPermissionResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.values.all { it }
        pendingContinuation?.resume(allGranted)
        pendingContinuation = null
    }
}
```

### Activity Integration

```kotlin
// In MainActivity.kt
class MainActivity : ComponentActivity() {

    private val permissionChecker: PermissionChecker by inject()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionChecker.onPermissionResult(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionChecker.bindToActivity(permissionLauncher)
        // ... rest of onCreate
    }

    override fun onDestroy() {
        permissionChecker.unbind()
        super.onDestroy()
    }
}
```

## Built-in Tools

### 1. GetCurrentTimeTool

```kotlin
/**
 * Located in: tool/builtin/GetCurrentTimeTool.kt
 */
class GetCurrentTimeTool : Tool {

    override val definition = ToolDefinition(
        name = "get_current_time",
        description = "Get the current date and time",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "timezone" to ToolParameter(
                    type = "string",
                    description = "Timezone identifier (e.g., 'America/New_York', 'Asia/Shanghai'). Defaults to device timezone.",
                    default = null
                ),
                "format" to ToolParameter(
                    type = "string",
                    description = "Output format: 'iso8601' or 'human_readable'. Defaults to 'iso8601'.",
                    enum = listOf("iso8601", "human_readable"),
                    default = "iso8601"
                )
            ),
            required = emptyList()
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 5
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val timezoneId = parameters["timezone"] as? String
            val format = parameters["format"] as? String ?: "iso8601"

            val zone = if (timezoneId != null) {
                try {
                    ZoneId.of(timezoneId)
                } catch (e: Exception) {
                    return ToolResult.error(
                        "validation_error",
                        "Invalid timezone: '$timezoneId'. Use IANA timezone format (e.g., 'America/New_York')."
                    )
                }
            } else {
                ZoneId.systemDefault()
            }

            val now = ZonedDateTime.now(zone)

            val result = when (format) {
                "human_readable" -> {
                    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm:ss a z")
                    now.format(formatter)
                }
                else -> { // iso8601
                    now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                }
            }

            ToolResult.success(result)
        } catch (e: Exception) {
            ToolResult.error("execution_error", "Failed to get current time: ${e.message}")
        }
    }
}
```

### 2. ReadFileTool

```kotlin
/**
 * Located in: tool/builtin/ReadFileTool.kt
 *
 * Uses direct file path access. Requires MANAGE_EXTERNAL_STORAGE permission
 * on Android 11+ for accessing files outside app-specific directories.
 */
class ReadFileTool : Tool {

    companion object {
        private const val MAX_FILE_SIZE = 1024 * 1024  // 1MB max for text file reading
    }

    override val definition = ToolDefinition(
        name = "read_file",
        description = "Read the contents of a file from local storage",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "path" to ToolParameter(
                    type = "string",
                    description = "The absolute file path to read (e.g., '/storage/emulated/0/Documents/notes.txt')"
                ),
                "encoding" to ToolParameter(
                    type = "string",
                    description = "File encoding. Defaults to 'UTF-8'.",
                    default = "UTF-8"
                )
            ),
            required = listOf("path")
        ),
        requiredPermissions = buildFileReadPermissions(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val path = parameters["path"] as? String
            ?: return ToolResult.error("validation_error", "Parameter 'path' is required")
        val encoding = parameters["encoding"] as? String ?: "UTF-8"

        // Security: prevent access to app-internal files
        val normalizedPath = File(path).canonicalPath
        if (isRestrictedPath(normalizedPath)) {
            return ToolResult.error(
                "permission_denied",
                "Access denied: cannot read app-internal or system files"
            )
        }

        val file = File(normalizedPath)

        if (!file.exists()) {
            return ToolResult.error("file_not_found", "File not found: $path")
        }

        if (!file.isFile) {
            return ToolResult.error("validation_error", "Path is a directory, not a file: $path")
        }

        if (file.length() > MAX_FILE_SIZE) {
            return ToolResult.error(
                "file_too_large",
                "File is too large (${file.length()} bytes). Maximum supported size is ${MAX_FILE_SIZE} bytes (1MB)."
            )
        }

        return try {
            val charset = try {
                Charset.forName(encoding)
            } catch (e: Exception) {
                return ToolResult.error("validation_error", "Unsupported encoding: '$encoding'")
            }

            val content = file.readText(charset)
            ToolResult.success(content)
        } catch (e: SecurityException) {
            ToolResult.error("permission_denied", "Permission denied: cannot read $path")
        } catch (e: Exception) {
            ToolResult.error("execution_error", "Failed to read file: ${e.message}")
        }
    }

    private fun isRestrictedPath(canonicalPath: String): Boolean {
        val restricted = listOf(
            "/data/data/",           // App-internal storage
            "/data/user/",           // App-internal storage (alternate path)
            "/system/",              // System files
            "/proc/",               // Process info
            "/sys/"                  // Kernel info
        )
        return restricted.any { canonicalPath.startsWith(it) }
    }
}

/**
 * Build the appropriate file read permissions based on Android version.
 */
private fun buildFileReadPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Android 11+: need MANAGE_EXTERNAL_STORAGE for broad file access
        listOf(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE)
    } else {
        listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
```

**Note on MANAGE_EXTERNAL_STORAGE**: This is a special permission that cannot be granted through the standard permission dialog. It requires navigating to system settings. The `PermissionChecker` needs special handling for this:

```kotlin
// In PermissionChecker, special case for MANAGE_EXTERNAL_STORAGE
fun hasManageExternalStoragePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true // Not needed on older versions
    }
}

/**
 * Open the system settings page for MANAGE_EXTERNAL_STORAGE.
 * Returns immediately -- the user must manually toggle the permission.
 * The tool will check again on the next execution attempt.
 */
fun requestManageExternalStorage(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }
}
```

For V1, when `MANAGE_EXTERNAL_STORAGE` is not granted, the tool returns an error message guiding the user to enable it in Settings. This is standard behavior for apps requiring this permission.

### 3. WriteFileTool

```kotlin
/**
 * Located in: tool/builtin/WriteFileTool.kt
 */
class WriteFileTool : Tool {

    override val definition = ToolDefinition(
        name = "write_file",
        description = "Write contents to a file on local storage",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "path" to ToolParameter(
                    type = "string",
                    description = "The absolute file path to write (e.g., '/storage/emulated/0/Documents/output.txt')"
                ),
                "content" to ToolParameter(
                    type = "string",
                    description = "The content to write to the file"
                ),
                "mode" to ToolParameter(
                    type = "string",
                    description = "Write mode: 'overwrite' (replace file) or 'append' (add to end). Defaults to 'overwrite'.",
                    enum = listOf("overwrite", "append"),
                    default = "overwrite"
                )
            ),
            required = listOf("path", "content")
        ),
        requiredPermissions = buildFileWritePermissions(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val path = parameters["path"] as? String
            ?: return ToolResult.error("validation_error", "Parameter 'path' is required")
        val content = parameters["content"] as? String
            ?: return ToolResult.error("validation_error", "Parameter 'content' is required")
        val mode = parameters["mode"] as? String ?: "overwrite"

        // Security: prevent writing to app-internal or system paths
        val normalizedPath = File(path).canonicalPath
        if (isRestrictedPath(normalizedPath)) {
            return ToolResult.error(
                "permission_denied",
                "Access denied: cannot write to app-internal or system paths"
            )
        }

        val file = File(normalizedPath)

        return try {
            // Create parent directories if they don't exist
            file.parentFile?.mkdirs()

            when (mode) {
                "append" -> file.appendText(content)
                else -> file.writeText(content)  // overwrite
            }

            val bytesWritten = content.toByteArray().size
            ToolResult.success(
                "Successfully wrote $bytesWritten bytes to $path (mode: $mode)"
            )
        } catch (e: SecurityException) {
            ToolResult.error("permission_denied", "Permission denied: cannot write to $path")
        } catch (e: Exception) {
            ToolResult.error("execution_error", "Failed to write file: ${e.message}")
        }
    }

    private fun isRestrictedPath(canonicalPath: String): Boolean {
        val restricted = listOf(
            "/data/data/",
            "/data/user/",
            "/system/",
            "/proc/",
            "/sys/"
        )
        return restricted.any { canonicalPath.startsWith(it) }
    }
}

private fun buildFileWritePermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        listOf(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE)
    } else {
        listOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}
```

### 4. HttpRequestTool

```kotlin
/**
 * Located in: tool/builtin/HttpRequestTool.kt
 */
class HttpRequestTool(private val okHttpClient: OkHttpClient) : Tool {

    companion object {
        private const val MAX_RESPONSE_SIZE = 100 * 1024  // 100KB max response body
    }

    override val definition = ToolDefinition(
        name = "http_request",
        description = "Make an HTTP request to a URL",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "url" to ToolParameter(
                    type = "string",
                    description = "The URL to request"
                ),
                "method" to ToolParameter(
                    type = "string",
                    description = "HTTP method: GET, POST, PUT, DELETE. Defaults to GET.",
                    enum = listOf("GET", "POST", "PUT", "DELETE"),
                    default = "GET"
                ),
                "headers" to ToolParameter(
                    type = "object",
                    description = "Key-value pairs of HTTP headers (optional)"
                ),
                "body" to ToolParameter(
                    type = "string",
                    description = "Request body for POST/PUT requests (optional)"
                )
            ),
            required = listOf("url")
        ),
        requiredPermissions = emptyList(),  // INTERNET permission is granted by default
        timeoutSeconds = 30
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val url = parameters["url"] as? String
            ?: return ToolResult.error("validation_error", "Parameter 'url' is required")
        val method = (parameters["method"] as? String ?: "GET").uppercase()
        val headers = parameters["headers"] as? Map<*, *>
        val body = parameters["body"] as? String

        // Validate URL
        val httpUrl = url.toHttpUrlOrNull()
            ?: return ToolResult.error("validation_error", "Invalid URL: $url")

        // Build request
        val requestBuilder = Request.Builder().url(httpUrl)

        // Add headers
        headers?.forEach { (key, value) ->
            if (key is String && value is String) {
                requestBuilder.addHeader(key, value)
            }
        }

        // Set method and body
        val requestBody = body?.toRequestBody("application/json".toMediaTypeOrNull())
        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(requestBody ?: "".toRequestBody(null))
            "PUT" -> requestBuilder.put(requestBody ?: "".toRequestBody(null))
            "DELETE" -> {
                if (requestBody != null) {
                    requestBuilder.delete(requestBody)
                } else {
                    requestBuilder.delete()
                }
            }
            else -> return ToolResult.error("validation_error", "Unsupported HTTP method: $method")
        }

        return try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()

            val responseBody = response.body?.let { responseBody ->
                val bytes = responseBody.bytes()
                if (bytes.size > MAX_RESPONSE_SIZE) {
                    val truncated = String(bytes, 0, MAX_RESPONSE_SIZE, Charsets.UTF_8)
                    "$truncated\n\n(Response truncated. Showing first ${MAX_RESPONSE_SIZE / 1024}KB of ${bytes.size / 1024}KB total.)"
                } else {
                    String(bytes, Charsets.UTF_8)
                }
            } ?: "(empty response body)"

            // Build response headers summary (only important ones)
            val responseHeaders = buildString {
                response.header("Content-Type")?.let { append("Content-Type: $it\n") }
                response.header("Content-Length")?.let { append("Content-Length: $it\n") }
            }.trimEnd()

            val result = buildString {
                appendLine("HTTP ${response.code} ${response.message}")
                if (responseHeaders.isNotEmpty()) {
                    appendLine(responseHeaders)
                }
                appendLine()
                append(responseBody)
            }

            ToolResult.success(result)
        } catch (e: java.net.UnknownHostException) {
            ToolResult.error("network_error", "Cannot resolve host: ${httpUrl.host}")
        } catch (e: java.net.SocketTimeoutException) {
            ToolResult.error("timeout", "HTTP request timed out")
        } catch (e: java.net.ConnectException) {
            ToolResult.error("network_error", "Connection refused: $url")
        } catch (e: Exception) {
            ToolResult.error("execution_error", "HTTP request failed: ${e.message}")
        }
    }
}
```

## Tool Definition Format Conversion

Different AI providers expect tool definitions in different formats. The conversion is handled in the provider adapter layer (from RFC-003). This section defines how `ToolDefinition` is converted to each provider's format.

### JSON Schema Serialization Helper

```kotlin
/**
 * Serialize ToolParametersSchema to a JSON Schema map.
 * Used by all provider adapters.
 *
 * Located in: tool/engine/ToolSchemaSerializer.kt
 */
object ToolSchemaSerializer {

    fun toJsonSchemaMap(schema: ToolParametersSchema): Map<String, Any> {
        val properties = schema.properties.map { (name, param) ->
            val paramMap = mutableMapOf<String, Any>(
                "type" to param.type,
                "description" to param.description
            )
            param.enum?.let { paramMap["enum"] = it }
            name to paramMap
        }.toMap()

        val result = mutableMapOf<String, Any>(
            "type" to "object",
            "properties" to properties
        )
        if (schema.required.isNotEmpty()) {
            result["required"] = schema.required
        }
        return result
    }
}
```

**Critical serialization note (from Layer 2 bug fix):**

`toJsonSchemaMap()` returns `Map<String, Any>`. When this map is embedded into a `buildJsonObject { }` (kotlinx.serialization), the nested `Map` and `List` values must be converted to `JsonElement` — they cannot be passed as raw Kotlin objects. Using `.toString()` on a `Map<String, Any>` produces Kotlin syntax (`{key=value}`) instead of JSON.

Every adapter must use an `anyToJsonElement()` helper when embedding the schema map into the request body:

```kotlin
@Suppress("UNCHECKED_CAST")
private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> buildJsonObject {
        (value as Map<String, Any?>).forEach { (k, v) -> put(k, anyToJsonElement(v)) }
    }
    is List<*> -> buildJsonArray {
        value.forEach { add(anyToJsonElement(it)) }
    }
    else -> JsonPrimitive(value.toString())
}
```

Usage in adapters:

```kotlin
// AnthropicAdapter — in buildAnthropicRequest()
put("input_schema", anyToJsonElement(
    ToolSchemaSerializer.toJsonSchemaMap(tool.parametersSchema)
))

// OpenAiAdapter — in buildOpenAiRequest()
put("parameters", anyToJsonElement(
    ToolSchemaSerializer.toJsonSchemaMap(tool.parametersSchema)
))

// GeminiAdapter — in buildGeminiRequest()
put("parameters", anyToJsonElement(
    toGeminiSchemaMap(tool.parametersSchema)
))
```

### OpenAI Format

OpenAI's function calling format is the baseline. Our internal format is designed to align with it.

```json
{
  "type": "function",
  "function": {
    "name": "read_file",
    "description": "Read the contents of a file from local storage",
    "parameters": {
      "type": "object",
      "properties": {
        "path": {
          "type": "string",
          "description": "The absolute file path to read"
        },
        "encoding": {
          "type": "string",
          "description": "File encoding. Defaults to 'UTF-8'."
        }
      },
      "required": ["path"]
    }
  }
}
```

```kotlin
// In OpenAiAdapter
fun formatToolDefinitions(tools: List<ToolDefinition>): List<Map<String, Any>> {
    return tools.map { tool ->
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to ToolSchemaSerializer.toJsonSchemaMap(tool.parametersSchema)
            )
        )
    }
}
```

### Anthropic Format

Anthropic's tool use format is slightly different: no `"type": "function"` wrapper, and the schema key is `input_schema` instead of `parameters`.

```json
{
  "name": "read_file",
  "description": "Read the contents of a file from local storage",
  "input_schema": {
    "type": "object",
    "properties": {
      "path": {
        "type": "string",
        "description": "The absolute file path to read"
      },
      "encoding": {
        "type": "string",
        "description": "File encoding. Defaults to 'UTF-8'."
      }
    },
    "required": ["path"]
  }
}
```

```kotlin
// In AnthropicAdapter
fun formatToolDefinitions(tools: List<ToolDefinition>): List<Map<String, Any>> {
    return tools.map { tool ->
        mapOf(
            "name" to tool.name,
            "description" to tool.description,
            "input_schema" to ToolSchemaSerializer.toJsonSchemaMap(tool.parametersSchema)
        )
    }
}
```

### Gemini Format

Gemini uses `function_declarations` within a `tools` array, and uses `parameters` with a slightly different schema structure.

```json
{
  "function_declarations": [
    {
      "name": "read_file",
      "description": "Read the contents of a file from local storage",
      "parameters": {
        "type": "OBJECT",
        "properties": {
          "path": {
            "type": "STRING",
            "description": "The absolute file path to read"
          },
          "encoding": {
            "type": "STRING",
            "description": "File encoding. Defaults to 'UTF-8'."
          }
        },
        "required": ["path"]
      }
    }
  ]
}
```

Note: Gemini uses uppercase type names (`STRING`, `OBJECT`, etc.).

```kotlin
// In GeminiAdapter
fun formatToolDefinitions(tools: List<ToolDefinition>): Map<String, Any> {
    val declarations = tools.map { tool ->
        mapOf(
            "name" to tool.name,
            "description" to tool.description,
            "parameters" to toGeminiSchemaMap(tool.parametersSchema)
        )
    }
    return mapOf("function_declarations" to declarations)
}

private fun toGeminiSchemaMap(schema: ToolParametersSchema): Map<String, Any> {
    val properties = schema.properties.map { (name, param) ->
        val paramMap = mutableMapOf<String, Any>(
            "type" to param.type.uppercase(),
            "description" to param.description
        )
        param.enum?.let { paramMap["enum"] = it }
        name to paramMap
    }.toMap()

    val result = mutableMapOf<String, Any>(
        "type" to "OBJECT",
        "properties" to properties
    )
    if (schema.required.isNotEmpty()) {
        result["required"] = schema.required
    }
    return result
}
```

### Tool Result Format Conversion

Tool results also need to be formatted differently per provider when sent back to the model.

**OpenAI**: Tool results are sent as messages with `role: "tool"`:
```json
{
  "role": "tool",
  "tool_call_id": "call_abc123",
  "content": "{\"status\": \"success\", \"result\": \"file contents here\"}"
}
```

**Anthropic**: Tool results are sent as `tool_result` content blocks:
```json
{
  "role": "user",
  "content": [
    {
      "type": "tool_result",
      "tool_use_id": "toolu_abc123",
      "content": "{\"status\": \"success\", \"result\": \"file contents here\"}"
    }
  ]
}
```

**Gemini**: Tool results are sent as `functionResponse` parts:
```json
{
  "role": "function",
  "parts": [
    {
      "functionResponse": {
        "name": "read_file",
        "response": {
          "status": "success",
          "result": "file contents here"
        }
      }
    }
  ]
}
```

These format conversions will be implemented in each provider adapter's `sendMessageStream()` method (RFC-001). This RFC documents the expected formats so RFC-001 can reference them.

## Koin Dependency Injection

```kotlin
// ToolModule.kt
val toolModule = module {

    // Tool Registry - singleton, all tools registered at startup
    single {
        ToolRegistry().apply {
            register(GetCurrentTimeTool())
            register(ReadFileTool())
            register(WriteFileTool())
            register(HttpRequestTool(get()))  // get() = OkHttpClient
        }
    }

    // Permission Checker - singleton, bound to Activity lifecycle
    single { PermissionChecker(androidContext()) }

    // Tool Execution Engine - singleton
    single { ToolExecutionEngine(get(), get()) }  // ToolRegistry, PermissionChecker
}
```

## Data Flow

### Flow: Model Requests a Single Tool Call

```
1. Model response (via streaming) includes a tool call:
   tool_name: "read_file", parameters: {"path": "/storage/emulated/0/notes.txt"}
   (Parsing of the streaming tool call is handled by RFC-001)

2. SendMessageUseCase (RFC-001) receives the parsed tool call
   -> Creates a TOOL_CALL message in the database (status: PENDING)
   -> UI shows "Calling read_file..." in compact mode

3. SendMessageUseCase calls ToolExecutionEngine.executeToolTimed(
     toolName = "read_file",
     parameters = {"path": "/storage/emulated/0/notes.txt"},
     availableToolNames = agent.toolIds
   )

4. ToolExecutionEngine:
   a. Looks up "read_file" in ToolRegistry -> found
   b. Checks "read_file" is in agent's tool set -> yes
   c. Validates parameters -> path is present, valid
   d. Checks permissions -> MANAGE_EXTERNAL_STORAGE
      - If not granted -> returns error guiding user to Settings
      - If granted -> continues
   e. Executes ReadFileTool.execute() on Dispatchers.IO with 10s timeout
   f. ReadFileTool reads the file, returns ToolResult.success(fileContents)

5. ToolExecutionEngine returns (ToolResult.success, durationMs=45)

6. SendMessageUseCase:
   -> Updates TOOL_CALL message status to SUCCESS, stores duration
   -> Creates TOOL_RESULT message with the result content
   -> Sends tool result back to model (formatted for the provider)
   -> Model processes and continues

7. UI updates:
   -> Tool call card shows "read_file - Done (45ms)"
   -> Model's next response streams in
```

### Flow: Model Requests Multiple Tool Calls (Parallel)

```
1. Model response includes 2 tool calls:
   - tool_call_1: get_current_time, parameters: {}
   - tool_call_2: http_request, parameters: {"url": "https://api.example.com/data"}

2. SendMessageUseCase creates 2 TOOL_CALL messages (both PENDING)

3. SendMessageUseCase calls ToolExecutionEngine.executeToolsParallel([
     ToolCallRequest("call_1", "get_current_time", {}),
     ToolCallRequest("call_2", "http_request", {"url": "..."})
   ], agent.toolIds)

4. ToolExecutionEngine launches both in parallel via coroutineScope + async:
   - async { executeTool("get_current_time", ...) }  -> completes in 2ms
   - async { executeTool("http_request", ...) }       -> completes in 500ms

5. Both results collected. Total wall time: ~500ms (not 502ms).

6. SendMessageUseCase processes both results:
   -> Updates both TOOL_CALL messages
   -> Creates 2 TOOL_RESULT messages
   -> Sends both results back to model
```

## Error Handling

### Error Scenarios and Behavior

| Scenario | Error Type | Error Message | What Happens |
|----------|-----------|---------------|--------------|
| Tool name not found | `tool_not_found` | "Tool 'xyz' not found" | Model informed, can try alternative |
| Tool not in agent's set | `tool_not_available` | "Tool 'xyz' is not available for this agent" | Model informed |
| Missing required param | `validation_error` | "Missing required parameter: 'path'" | Model can retry with correct params |
| Wrong param type | `validation_error` | "Parameter 'path' expected type 'string' but got Int" | Model can retry |
| Permission denied | `permission_denied` | "Required permissions were denied: ..." | Model informs user |
| MANAGE_EXTERNAL_STORAGE not granted | `permission_denied` | "File access requires 'All files access' permission. Please enable it in Settings > Apps > OneClawShadow > Permissions." | Model guides user |
| File not found | `file_not_found` | "File not found: /path/to/file" | Model can inform user or try another path |
| File too large | `file_too_large` | "File is too large (X bytes). Maximum: 1MB." | Model can inform user |
| Restricted path | `permission_denied` | "Access denied: cannot read app-internal or system files" | Security boundary enforced |
| HTTP DNS failure | `network_error` | "Cannot resolve host: example.com" | Model can inform user |
| HTTP timeout | `timeout` | "HTTP request timed out" | Model can retry or inform user |
| Tool execution timeout | `timeout` | "Tool execution timed out after Xs" | Model can retry or inform user |
| Tool crash (unhandled exception) | `execution_error` | "Tool execution failed: [exception message]" | App stays alive, model informed |

### Error Result Flow

All errors follow the same path:
1. ToolExecutionEngine catches the error
2. Returns `ToolResult.error(type, message)`
3. SendMessageUseCase stores it as a TOOL_RESULT message with error status
4. Sends it back to the model as a tool result
5. Model decides how to proceed (retry, inform user, try different approach)
6. UI shows the error in the tool call card

The app never crashes from a tool error. The model always gets a result (success or error) for every tool call it makes.

## Implementation Steps

### Phase 1: Core Infrastructure
1. [ ] Define `ToolParametersSchema` and `ToolParameter` in `core/model/`
2. [ ] Update `ToolDefinition` to use `ToolParametersSchema` instead of `String`
3. [ ] Add `ToolResult.success()` and `ToolResult.error()` companion methods
4. [ ] Implement `ToolSchemaSerializer` (to JSON Schema map)
5. [ ] Implement `Tool` interface in `tool/engine/`
6. [ ] Implement `ToolRegistry` in `tool/engine/`
7. [ ] Implement parameter validation logic in `ToolExecutionEngine`
8. [ ] Implement `ToolCallRequest` and `ToolCallResponse` data classes

### Phase 2: Permission System
9. [ ] Implement `PermissionChecker` with suspending permission request
10. [ ] Implement `MANAGE_EXTERNAL_STORAGE` special case handling
11. [ ] Integrate `PermissionChecker` with `MainActivity` (bind/unbind lifecycle)

### Phase 3: Execution Engine
12. [ ] Implement `ToolExecutionEngine.executeTool()` (single tool)
13. [ ] Implement `ToolExecutionEngine.executeToolTimed()` (with duration)
14. [ ] Implement `ToolExecutionEngine.executeToolsParallel()` (parallel)
15. [ ] Add timeout handling with `withTimeout`
16. [ ] Add error catching (all exceptions -> ToolResult.error)

### Phase 4: Built-in Tools
17. [ ] Implement `GetCurrentTimeTool`
18. [ ] Implement `ReadFileTool` with path security checks
19. [ ] Implement `WriteFileTool` with path security checks
20. [ ] Implement `HttpRequestTool` with response truncation

### Phase 5: Provider Format Conversion
21. [ ] Implement `formatToolDefinitions()` in `OpenAiAdapter`
22. [ ] Implement `formatToolDefinitions()` in `AnthropicAdapter`
23. [ ] Implement `formatToolDefinitions()` in `GeminiAdapter`
24. [ ] Document tool result format for each provider (for RFC-001)

### Phase 6: DI & Integration
25. [ ] Set up `ToolModule` in Koin
26. [ ] Register all built-in tools in `ToolRegistry`
27. [ ] Add `MANAGE_EXTERNAL_STORAGE` and `INTERNET` to AndroidManifest.xml
28. [ ] Unit test all tools with mock data
29. [ ] Integration test: tool registration -> execution -> result

## Testing Strategy

### Unit Tests
- `ToolRegistry`: register, lookup, duplicate name rejection, getByNames
- `ToolExecutionEngine.validateParameters()`: required params, type checking, enum validation
- `ToolExecutionEngine.executeTool()`: mock tool, verify timeout, verify error catching
- `GetCurrentTimeTool`: default timezone, specified timezone, invalid timezone, both formats
- `ReadFileTool`: file exists, file not found, restricted path, too large, encoding
- `WriteFileTool`: overwrite, append, restricted path, parent dir creation
- `HttpRequestTool`: GET/POST, headers, response truncation, network errors
- `ToolSchemaSerializer`: verify JSON Schema output matches expected format
- Format conversion: verify OpenAI/Anthropic/Gemini tool definition formats

### Integration Tests (Instrumented)
- Permission flow: request -> grant -> tool executes
- Permission flow: request -> deny -> error returned
- File read/write on actual device storage
- HTTP request to a test server
- Parallel tool execution timing

### Edge Cases
- Tool called with empty parameters map
- Tool called with extra unexpected parameters (should be ignored)
- Very large HTTP response body (verify truncation)
- File read on a binary file
- Write file to a path where parent directory doesn't exist
- Permission request while Activity is in background
- Concurrent permission requests (should be serialized)
- Tool execution cancelled (coroutine cancellation)

## Security Considerations

1. **File access boundaries**: `ReadFileTool` and `WriteFileTool` block access to `/data/data/`, `/data/user/`, `/system/`, `/proc/`, `/sys/` via path canonicalization and prefix checking.
2. **No arbitrary code execution**: Tools are compiled into the app. V1 does not support user-defined or downloaded tools.
3. **HTTP tool**: No restrictions on HTTP vs HTTPS, since users may intentionally access local/HTTP services. The tool does not add any credentials -- users control what URLs the AI accesses.
4. **Tool results in database**: Tool results (including file contents and HTTP responses) are stored in the messages table. They have the same security level as other messages. Sensitive data in tool results is the user's responsibility.
5. **API key isolation**: Tool execution never has access to API keys. Tools cannot read EncryptedSharedPreferences.

## Dependencies

### Depends On
- **RFC-000 (Overall Architecture)**: Core models (ToolDefinition, ToolResult), project structure
- **RFC-003 (Provider Management)**: Provider adapters (for format conversion methods)
- Android APIs: File I/O, OkHttp (for HTTP tool), java.time (for time tool), Permission system

### Depended On By
- **RFC-001 (Chat Interaction)**: Tool call loop in streaming conversation, tool call UI data
- **RFC-002 (Agent Management)**: Agent's tool set references tool names from ToolRegistry

## Open Questions

- [ ] **HTTP response body improvement**: V1 uses simple truncation at 100KB. Future improvement: for `text/html` responses, strip `<script>`, `<style>`, `<nav>`, `<footer>` tags and convert to plain text before truncating. This would greatly improve the usefulness of web page fetching. This requires an HTML parsing library (e.g., Jsoup) and is deferred post-V1.
- [ ] **File size limit**: 1MB max for `read_file`. Should this be configurable? For V1, hardcoded is fine.
- [ ] **MANAGE_EXTERNAL_STORAGE on Play Store**: If publishing to Play Store, this permission requires justification. The app's use case (AI agent runtime with file access) is a valid justification, similar to file managers.

## Future Improvements

- [ ] **HTML-aware HTTP response processing**: Strip scripts/styles, extract main content, convert to clean text before truncating. Would make `http_request` much more useful for web browsing tasks.
- [ ] **Sandboxed tool execution**: Run tools in a separate process for better isolation.
- [ ] **User-defined custom tools**: Allow users to create tools via scripting (e.g., JavaScript/Lua).
- [ ] **Tool-specific UI**: File picker for `read_file`, rich display for image/media tools.
- [ ] **Streaming tool results**: For long-running tools, return partial results progressively.
- [ ] **More built-in tools**: Clipboard, device info, app launcher, notifications, calendar, contacts.
- [ ] **Tool execution approval mode**: Optional confirmation dialog before executing sensitive tools.

## References

- [FEAT-004 PRD](../../prd/features/FEAT-004-tool-system.md) -- Functional requirements
- [RFC-000 Overall Architecture](../architecture/RFC-000-overall-architecture.md) -- Tool interface and engine outline
- [RFC-003 Provider Management](RFC-003-provider-management.md) -- Provider adapters for format conversion
- [OpenAI Function Calling](https://platform.openai.com/docs/guides/function-calling)
- [Anthropic Tool Use](https://docs.anthropic.com/en/docs/build-with-claude/tool-use)
- [Gemini Function Calling](https://ai.google.dev/gemini-api/docs/function-calling)
- [Android MANAGE_EXTERNAL_STORAGE](https://developer.android.com/training/data-storage/manage-all-files)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-27 | 0.1 | Initial version | - |
