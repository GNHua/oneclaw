# RFC-029: Shell Exec Tool

## Document Information
- **RFC ID**: RFC-029
- **Related PRD**: [FEAT-029 (Shell Exec Tool)](../../prd/features/FEAT-029-exec-tool.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

OneClaw's AI agent currently interacts with the device through a limited set of built-in tools (webfetch, browser, schedule_task, etc.) and JS-based tools (file read/write, HTTP, time). However, there is no general-purpose tool for executing arbitrary shell commands on the device. Shell access would enable the agent to perform a wide range of tasks: file system operations, system diagnostics, package management, network testing, text processing, and automation scripting.

Android provides `Runtime.getRuntime().exec()` which allows apps to spawn shell processes within their sandbox. These processes run with the app's UID and have the same permissions as the app itself -- no root access, bounded by Android's security model.

### Goals

1. Implement `ExecTool.kt` as a Kotlin built-in tool in `tool/builtin/`
2. Execute shell commands via `Runtime.getRuntime().exec()` with `sh -c` wrapping
3. Capture stdout, stderr, and exit code from the child process
4. Enforce configurable timeout with forced process termination
5. Support configurable working directory and output truncation
6. Register the tool in `ToolModule`

### Non-Goals

- Interactive shell sessions with persistent state
- Root command execution
- Command allowlist/blocklist filtering
- Streaming output to the UI in real-time
- Per-command environment variable overrides
- Background process management

## Technical Design

### Architecture Overview

```
+-----------------------------------------------------------------+
|                     Chat Layer (RFC-001)                          |
|  SendMessageUseCase                                              |
|       |                                                          |
|       |  tool call: exec(command="ls -la /sdcard/")              |
|       v                                                          |
+------------------------------------------------------------------+
|                   Tool Execution Engine (RFC-004)                  |
|  executeTool(name, params, availableToolIds)                      |
|       |                                                           |
|       v                                                           |
|  +--------------------------------------------------------------+ |
|  |                    ToolRegistry                                | |
|  |  +-------------------+                                        | |
|  |  |       exec        |  Kotlin built-in [NEW]                 | |
|  |  |  (ExecTool.kt)    |                                       | |
|  |  +--------+----------+                                        | |
|  |           |                                                    | |
|  |           v                                                    | |
|  |  +------------------------------------------------------+    | |
|  |  |                  ExecTool                              |    | |
|  |  |  1. Validate parameters                               |    | |
|  |  |  2. Create ProcessBuilder("sh", "-c", command)         |    | |
|  |  |  3. Set working directory                              |    | |
|  |  |  4. Start process                                      |    | |
|  |  |  5. Read stdout/stderr in coroutines                   |    | |
|  |  |  6. Wait with timeout                                  |    | |
|  |  |  7. Format and return result                           |    | |
|  |  +------------------------------------------------------+    | |
|  +--------------------------------------------------------------+ |
+-------------------------------------------------------------------+
```

### Core Components

**New:**
1. `ExecTool` -- Kotlin built-in tool that executes shell commands and returns output

**Modified:**
2. `ToolModule` -- Register `ExecTool` as a Kotlin built-in tool

## Detailed Design

### Directory Structure (New & Changed Files)

```
app/src/main/
├── kotlin/com/oneclaw/shadow/
│   ├── tool/
│   │   └── builtin/
│   │       ├── ExecTool.kt               # NEW
│   │       ├── WebfetchTool.kt           # unchanged
│   │       ├── BrowserTool.kt            # unchanged
│   │       ├── LoadSkillTool.kt          # unchanged
│   │       ├── CreateScheduledTaskTool.kt # unchanged
│   │       └── CreateAgentTool.kt        # unchanged
│   └── di/
│       └── ToolModule.kt                 # MODIFIED

app/src/test/kotlin/com/oneclaw/shadow/
    └── tool/
        └── builtin/
            └── ExecToolTest.kt            # NEW
```

### ExecTool

```kotlin
/**
 * Located in: tool/builtin/ExecTool.kt
 *
 * Kotlin built-in tool that executes shell commands on the Android device
 * using Runtime.getRuntime().exec(). Captures stdout, stderr, and exit code.
 */
class ExecTool(
    private val context: Context
) : Tool {

    companion object {
        private const val TAG = "ExecTool"
        private const val DEFAULT_TIMEOUT_SECONDS = 30
        private const val MAX_TIMEOUT_SECONDS = 120
        private const val DEFAULT_MAX_LENGTH = 50_000
    }

    override val definition = ToolDefinition(
        name = "exec",
        description = "Execute a shell command on the device and return its output",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "command" to ToolParameter(
                    type = "string",
                    description = "The shell command to execute"
                ),
                "timeout_seconds" to ToolParameter(
                    type = "integer",
                    description = "Maximum execution time in seconds. Default: 30, Max: 120"
                ),
                "working_directory" to ToolParameter(
                    type = "string",
                    description = "Working directory for the command. Default: app data directory"
                ),
                "max_length" to ToolParameter(
                    type = "integer",
                    description = "Maximum output length in characters. Default: 50000"
                )
            ),
            required = listOf("command")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = MAX_TIMEOUT_SECONDS + 5  // Extra buffer beyond process timeout
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        // 1. Parse and validate parameters
        val command = parameters["command"]?.toString()?.trim()
        if (command.isNullOrBlank()) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'command' is required and cannot be empty"
            )
        }

        val timeoutSeconds = parseIntParam(parameters["timeout_seconds"])
            ?.coerceIn(1, MAX_TIMEOUT_SECONDS)
            ?: DEFAULT_TIMEOUT_SECONDS

        val maxLength = parseIntParam(parameters["max_length"])
            ?.coerceAtLeast(1)
            ?: DEFAULT_MAX_LENGTH

        val workingDir = parameters["working_directory"]?.toString()?.let { path ->
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                return ToolResult.error(
                    "validation_error",
                    "Working directory does not exist: $path"
                )
            }
            dir
        } ?: context.filesDir

        // 2. Execute the command
        return try {
            executeCommand(command, workingDir, timeoutSeconds, maxLength)
        } catch (e: SecurityException) {
            ToolResult.error("permission_error", "Permission denied: ${e.message}")
        } catch (e: IOException) {
            ToolResult.error("execution_error", "Failed to start process: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error executing command", e)
            ToolResult.error("execution_error", "Error: ${e.message}")
        }
    }

    private suspend fun executeCommand(
        command: String,
        workingDir: File,
        timeoutSeconds: Int,
        maxLength: Int
    ): ToolResult = withContext(Dispatchers.IO) {
        val process = Runtime.getRuntime().exec(
            arrayOf("sh", "-c", command),
            null,  // inherit environment
            workingDir
        )

        try {
            // 3. Capture stdout and stderr concurrently
            val stdoutDeferred = async {
                readStream(process.inputStream, maxLength)
            }
            val stderrDeferred = async {
                readStream(process.errorStream, maxLength)
            }

            // 4. Wait for process completion with timeout
            val completed = process.waitFor(
                timeoutSeconds.toLong(),
                TimeUnit.SECONDS
            )

            if (!completed) {
                // Timeout: kill the process
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)  // Brief wait for cleanup

                val stdout = stdoutDeferred.await()
                val stderr = stderrDeferred.await()

                return@withContext ToolResult.success(
                    formatOutput(
                        exitCode = -1,
                        stdout = stdout,
                        stderr = stderr,
                        timedOut = true,
                        timeoutSeconds = timeoutSeconds
                    )
                )
            }

            val exitCode = process.exitValue()
            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()

            ToolResult.success(
                formatOutput(
                    exitCode = exitCode,
                    stdout = stdout,
                    stderr = stderr,
                    timedOut = false,
                    timeoutSeconds = timeoutSeconds
                )
            )
        } finally {
            process.destroy()
        }
    }

    /**
     * Read an InputStream into a String, truncating at maxLength.
     */
    private fun readStream(stream: InputStream, maxLength: Int): String {
        val reader = BufferedReader(InputStreamReader(stream))
        val sb = StringBuilder()
        var totalRead = 0

        reader.use {
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count == -1) break

                val remaining = maxLength - totalRead
                if (remaining <= 0) break

                val toAppend = minOf(count, remaining)
                sb.append(buffer, 0, toAppend)
                totalRead += toAppend

                if (totalRead >= maxLength) break
            }
        }

        return sb.toString()
    }

    /**
     * Format the output with exit code, stdout, stderr, and timeout info.
     */
    private fun formatOutput(
        exitCode: Int,
        stdout: String,
        stderr: String,
        timedOut: Boolean,
        timeoutSeconds: Int
    ): String {
        val sb = StringBuilder()

        if (timedOut) {
            sb.appendLine("[Exit Code: -1 (timeout after ${timeoutSeconds}s)]")
        } else {
            sb.appendLine("[Exit Code: $exitCode]")
        }

        if (stdout.isNotEmpty()) {
            sb.appendLine()
            sb.append(stdout)
            if (!stdout.endsWith("\n")) sb.appendLine()
        }

        if (stderr.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[stderr]")
            sb.append(stderr)
            if (!stderr.endsWith("\n")) sb.appendLine()
        }

        if (timedOut) {
            if (stderr.isEmpty()) {
                sb.appendLine()
                sb.appendLine("[stderr]")
            }
            sb.appendLine("Process killed after ${timeoutSeconds} seconds timeout.")
        }

        if (stdout.isEmpty() && stderr.isEmpty() && !timedOut) {
            sb.appendLine()
            sb.appendLine("(no output)")
        }

        return sb.toString().trimEnd()
    }

    private fun parseIntParam(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}
```

### ToolModule Changes

```kotlin
// In ToolModule.kt

val toolModule = module {
    // ... existing registrations ...

    // RFC-029: exec built-in tool
    single { ExecTool(androidContext()) }

    single {
        ToolRegistry().apply {
            // ... existing tool registrations ...

            try {
                register(get<ExecTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register exec: ${e.message}")
            }

            // ... rest of initialization ...
        }
    }
}
```

### Imports Required for ExecTool

```kotlin
import android.content.Context
import android.util.Log
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
```

## Implementation Plan

### Phase 1: ExecTool Core Implementation

1. Create `ExecTool.kt` in `tool/builtin/`
2. Implement command execution via `Runtime.getRuntime().exec()`
3. Implement stdout/stderr capture with concurrent readers
4. Implement timeout enforcement with `Process.waitFor(timeout, unit)`
5. Implement output formatting with exit code

### Phase 2: Integration

1. Update `ToolModule.kt` to register `ExecTool`
2. Add `ExecTool` import and Koin single registration
3. Add registration in the `ToolRegistry.apply` block

### Phase 3: Testing

1. Create `ExecToolTest.kt` with unit tests
2. Run Layer 1A tests (`./gradlew test`)
3. Run Layer 1B tests if emulator available
4. Manual testing with various shell commands on device

## Data Model

No data model changes. `ExecTool` implements the existing `Tool` interface.

## API Design

### Tool Interface

```
Tool Name: exec
Parameters:
  - command: string (required) -- The shell command to execute
  - timeout_seconds: integer (optional, default: 30, max: 120) -- Timeout
  - working_directory: string (optional, default: app data dir) -- CWD
  - max_length: integer (optional, default: 50000) -- Max output chars

Returns on success:
  Formatted text with exit code, stdout, stderr

Returns on error:
  ToolResult.error with descriptive message
```

### Output Format Examples

**Successful command:**
```
[Exit Code: 0]

total 48
drwxr-xr-x  2 u0_a123 u0_a123 4096 2026-03-01 10:00 .
-rw-r--r--  1 u0_a123 u0_a123 1234 2026-03-01 09:30 file.txt
```

**Command with error:**
```
[Exit Code: 1]

[stderr]
ls: cannot access '/nonexistent': No such file or directory
```

**Command with both stdout and stderr:**
```
[Exit Code: 0]

Processing file1.txt
Processing file2.txt

[stderr]
Warning: file3.txt skipped (empty)
```

**Timed-out command:**
```
[Exit Code: -1 (timeout after 30s)]

partial output here...

[stderr]
Process killed after 30 seconds timeout.
```

## Process Lifecycle

```
execute() called
    |
    v
Validate parameters (command, timeout, working_directory)
    |
    v
Runtime.getRuntime().exec(["sh", "-c", command], null, workingDir)
    |
    +-- Process spawned
    |
    +-- async: read stdout into StringBuilder
    +-- async: read stderr into StringBuilder
    |
    v
process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    |
    +-- true (completed) -----> Get exitValue(), await stdout/stderr
    |                               |
    |                               v
    |                           formatOutput(exitCode, stdout, stderr)
    |                               |
    |                               v
    |                           ToolResult.success(formatted)
    |
    +-- false (timeout) -----> process.destroyForcibly()
                                    |
                                    v
                                await stdout/stderr (partial)
                                    |
                                    v
                                formatOutput(-1, stdout, stderr, timedOut=true)
                                    |
                                    v
                                ToolResult.success(formatted)
```

## Error Handling

| Error | Cause | Error Type | Handling |
|-------|-------|------------|----------|
| Empty command | Blank or null `command` param | `validation_error` | Return immediately with error message |
| Invalid working directory | Path does not exist or is not a directory | `validation_error` | Return immediately with error message |
| Process creation failure | System resource limits, invalid command | `execution_error` | Catch IOException, return error |
| Permission denied | Command requires permissions the app doesn't have | `permission_error` | Catch SecurityException, return error |
| Timeout | Command exceeds `timeout_seconds` | N/A (success) | Kill process, return partial output with timeout indicator |
| I/O error | Stream read failure during output capture | `execution_error` | Catch IOException, return error |
| Unexpected error | Any other exception | `execution_error` | Log and return generic error |

Note: Timeout is not treated as an error -- the tool returns success with the timeout indicator in the output, because partial output may still be useful to the AI model.

## Security Considerations

1. **Android App Sandbox**: Processes spawned via `Runtime.exec()` run with the app's UID. They cannot access other apps' data, system files, or perform privileged operations unless the device is rooted. This is enforced by the Linux kernel, not by the tool.

2. **No Root Escalation**: The tool does not use `su` or attempt to escalate privileges. If the user's device is rooted and `su` is in PATH, the AI model could theoretically call `su -c "..."`, but this is constrained by the device's root management app (e.g., Magisk) which prompts the user for approval.

3. **No Command Filtering (V1)**: The tool does not blocklist or allowlist commands. The rationale:
   - The Android sandbox already limits what the process can do
   - Command filtering is easily bypassed (encoding, indirection, scripts)
   - The AI model already has file read/write tools with similar access
   - Users who install this agent app accept the risk of AI-driven device interaction

4. **Resource Limits**: Timeout enforcement prevents runaway processes. Output truncation prevents memory exhaustion. The process is always destroyed in the `finally` block.

5. **No Network Exfiltration Risk**: The tool only returns output to the AI model, which is already in the app's process. No additional network surface is exposed.

## Performance

| Operation | Expected Time | Notes |
|-----------|--------------|-------|
| Process creation | ~50-100ms | Fork + exec overhead |
| stdout/stderr capture | Depends on command | Buffered I/O, concurrent readers |
| Timeout enforcement | Accurate to ~1s | Uses `Process.waitFor(long, TimeUnit)` |
| Process cleanup | < 100ms | `destroy()` + `destroyForcibly()` |

Memory usage:
- stdout and stderr buffered in StringBuilder (capped at `max_length`)
- Process handle and streams are closed in `finally` block
- No persistent state between calls

## Testing Strategy

### Unit Tests

**ExecToolTest.kt:**
- `testExecute_simpleCommand` -- `echo hello` returns "hello" with exit code 0
- `testExecute_commandWithExitCode` -- `exit 42` returns exit code 42
- `testExecute_commandWithStderr` -- Command producing stderr output
- `testExecute_commandWithPipes` -- `echo hello | tr a-z A-Z` returns "HELLO"
- `testExecute_timeout` -- `sleep 60` with timeout_seconds=2 triggers timeout
- `testExecute_workingDirectory` -- `pwd` with custom working_directory
- `testExecute_emptyCommand` -- Blank command returns validation error
- `testExecute_invalidWorkingDir` -- Non-existent directory returns validation error
- `testExecute_maxLength` -- Large output is truncated
- `testExecute_noOutput` -- Command with no stdout/stderr
- `testExecute_timeoutClamped` -- timeout_seconds > 120 is clamped
- `testDefinition` -- Tool definition has correct name and parameters

### Integration Tests (Layer 1B)

- Execute `ls` on the device and verify output contains expected directories
- Execute `getprop ro.build.version.sdk` and verify it returns a number
- Execute a command that accesses `/sdcard/` (if storage permission granted)

### Manual Testing (Layer 2)

- Run `pm list packages` and verify app package listing
- Run `cat /proc/cpuinfo` and verify hardware info output
- Run a command chain with pipes and redirects
- Run a command that exceeds timeout and verify clean termination
- Run a command in a specific working directory

## Alternatives Considered

### 1. Use ProcessBuilder Instead of Runtime.exec()

**Approach**: Use `ProcessBuilder` which offers more control (redirect stderr to stdout, environment configuration).
**Decision**: Use `Runtime.getRuntime().exec()` as the user specified. Internally, `Runtime.exec()` creates a `ProcessBuilder` anyway. The implementation above uses `Runtime.exec()` directly. In future iterations, we could switch to `ProcessBuilder` if we need stderr merging or per-command environment variables.

### 2. Use a Persistent Shell Session

**Approach**: Maintain a long-running `sh` process and pipe commands to its stdin.
**Rejected for V1**: Adds complexity (shell state management, prompt detection, output boundary markers). Each command being an independent process is simpler and more predictable. Can be added as a future enhancement.

### 3. Command Blocklist

**Approach**: Maintain a list of dangerous commands (rm -rf, reboot, etc.) and reject them.
**Rejected for V1**: Easily bypassed (command encoding, scripts, aliases). The Android sandbox already prevents truly dangerous operations. Users of an AI agent app accept the risk. Can be added as an optional safety layer later.

## Dependencies

### External Dependencies

None. Uses only Android platform APIs:
- `java.lang.Runtime`
- `java.lang.Process`
- `java.io.BufferedReader` / `java.io.InputStreamReader`
- `java.util.concurrent.TimeUnit`

### Internal Dependencies

- `Tool` interface from `tool/engine/`
- `ToolResult`, `ToolDefinition`, `ToolParametersSchema`, `ToolParameter` from `core/model/`
- `Context` from Android (for default working directory)

## Future Extensions

- **Persistent shell session**: Maintain a running `sh` process for stateful command sequences (cd, environment variables)
- **Command approval**: Optional UI confirmation dialog before executing commands
- **Command blocklist/allowlist**: Configurable safety filter for commands
- **Environment variables**: Per-command environment variable overrides
- **Streaming output**: Real-time stdout/stderr display in the chat UI
- **Multiple shell support**: Allow selecting `bash`, `zsh`, or other shells if available
- **Background processes**: Support for long-running processes that report back periodically

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
