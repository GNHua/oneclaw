# Shell Exec Tool

## Feature Information
- **Feature ID**: FEAT-029
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-029 (pending)

## User Story

**As** an AI agent using OneClaw,
**I want** a tool that executes shell commands on the Android device,
**so that** I can interact with the device's operating system -- listing files, checking system info, running scripts, managing packages, and performing automation tasks that require direct command-line access.

### Typical Scenarios

1. The agent runs `ls -la /sdcard/Download/` to list files in the user's download directory, then summarizes the contents.
2. The agent runs `pm list packages` to enumerate installed apps on the device.
3. The agent runs `cat /proc/cpuinfo` to check the device's hardware specifications.
4. The agent chains commands like `find /sdcard -name "*.pdf" -mtime -7` to locate recently modified PDF files.
5. The agent runs `ping -c 3 google.com` to diagnose network connectivity.
6. The agent runs `getprop ro.build.version.release` to check the Android version.

## Feature Description

### Overview

FEAT-029 adds a Kotlin built-in `exec` tool that runs shell commands on the Android device using `Runtime.getRuntime().exec()`. The tool captures stdout, stderr, and the exit code, returning them as structured text to the AI model. This gives the AI agent direct access to the device's command-line environment, enabling file system operations, system inspection, process management, and general-purpose automation.

The tool runs commands in a non-interactive shell with a configurable timeout to prevent runaway processes. Output is captured and truncated to a configurable character limit to avoid overwhelming the AI model's context window.

### Architecture Overview

```
AI Model
    | tool call: exec(command="ls -la /sdcard/")
    v
 ToolExecutionEngine  (Kotlin, unchanged)
    |
    v
 ToolRegistry
    |
    v
 ExecTool  [NEW - Kotlin built-in tool]
    |
    +-- ProcessBuilder / Runtime.exec()
    |       |
    |       +-- stdout capture
    |       +-- stderr capture
    |       +-- exit code
    |       +-- timeout enforcement
    |
    +-- Output formatting
            |
            +-- Combine stdout + stderr
            +-- Truncate to max_length
            +-- Return with exit code
```

### Tool Definition

| Field | Value |
|-------|-------|
| Name | `exec` |
| Description | Execute a shell command on the device and return its output |
| Parameters | `command` (string, required): The shell command to execute |
| | `timeout_seconds` (integer, optional): Maximum execution time in seconds. Default: 30 |
| | `working_directory` (string, optional): Working directory for the command. Default: app data directory |
| | `max_length` (integer, optional): Maximum output length in characters. Default: 50000 |
| Required Permissions | None (runs within app sandbox) |
| Timeout | Controlled by `timeout_seconds` parameter (max 120 seconds) |
| Returns | Combined stdout/stderr with exit code, or error object |

### Command Execution

- Commands are executed via `Runtime.getRuntime().exec(arrayOf("sh", "-c", command))`
- The shell process runs with the app's UID and permissions (Android app sandbox)
- No root access unless the device is rooted and the app has root permissions
- Commands run in a non-interactive shell -- no stdin, no TTY
- Environment variables inherit from the app process

### Output Format

The tool returns a structured text output:

```
[Exit Code: 0]

<stdout content here>
```

If stderr is non-empty:

```
[Exit Code: 1]

<stdout content here>

[stderr]
<stderr content here>
```

If the command times out:

```
[Exit Code: -1 (timeout)]

<partial stdout if any>

[stderr]
Process killed after 30 seconds timeout.
```

### User Interaction Flow

```
1. User: "What files are in my Downloads folder?"
2. AI calls exec(command="ls -la /sdcard/Download/")
3. ExecTool:
   a. Creates a process via Runtime.exec()
   b. Captures stdout and stderr in separate threads
   c. Waits for completion (up to timeout)
   d. Formats output with exit code
4. AI receives the directory listing, summarizes for the user
5. Chat shows the exec tool call result
```

## Acceptance Criteria

Must pass (all required):

- [ ] `exec` tool is registered as a Kotlin built-in tool in `ToolRegistry`
- [ ] Tool accepts a `command` string parameter and executes it via `Runtime.getRuntime().exec()`
- [ ] Shell commands run with `sh -c` wrapper for proper shell interpretation
- [ ] stdout is captured and returned in the result
- [ ] stderr is captured and included in the result when non-empty
- [ ] Exit code is included in the output
- [ ] `timeout_seconds` parameter controls maximum execution time (default: 30s, max: 120s)
- [ ] Process is forcibly killed when timeout expires
- [ ] `working_directory` parameter sets the process working directory
- [ ] `max_length` parameter controls output truncation (default: 50000)
- [ ] Output truncation happens cleanly at a line boundary
- [ ] Commands that produce no output return success with empty output indication
- [ ] Invalid commands return an error with the shell's error message
- [ ] All Layer 1A tests pass

Optional (nice to have):

- [ ] Command history tracking for debugging
- [ ] Blocklist for dangerous commands (rm -rf /, reboot, etc.)

## UI/UX Requirements

This feature has no new UI. The tool operates transparently:
- Same tool call display in chat as other tools
- Output shown in the tool result area
- No additional settings screen needed for V1

## Feature Boundary

### Included

- Kotlin `ExecTool` implementation using `Runtime.getRuntime().exec()`
- stdout and stderr capture with separate reader threads
- Exit code reporting
- Configurable timeout with process kill
- Configurable working directory
- Output truncation with configurable limit
- Registration in `ToolModule`

### Not Included (V1)

- Interactive shell sessions (persistent shell with stdin)
- Root command execution support
- Command blocklist or allowlist
- Environment variable configuration per-command
- Background/long-running process management
- Streaming output (real-time stdout/stderr)
- Shell selection (bash, zsh, etc.) -- uses `sh` only
- Command approval UI (user confirmation before execution)

## Business Rules

1. Commands are always wrapped with `sh -c` for proper shell interpretation (pipes, redirects, etc.)
2. Default timeout is 30 seconds; maximum allowed timeout is 120 seconds
3. If `timeout_seconds` exceeds 120, it is clamped to 120
4. Timed-out processes are destroyed via `Process.destroyForcibly()`
5. Default working directory is the app's data directory (`context.filesDir`)
6. Output truncation defaults to 50,000 characters if `max_length` is not specified
7. The process inherits the app's environment variables
8. No stdin is provided to the process (non-interactive)

## Non-Functional Requirements

### Performance

- Process creation: < 100ms
- Output capture: Real-time via buffered reader threads
- Timeout enforcement: Accurate to within 1 second

### Memory

- stdout and stderr are buffered in StringBuilder (in-memory)
- Large outputs are truncated to `max_length` to prevent OOM
- Process resources are cleaned up after execution

### Compatibility

- Works on all supported Android versions (API 26+)
- `Runtime.getRuntime().exec()` is available on all Android versions
- Available shell commands depend on the device's Android version and ROM

## Dependencies

### Depends On

- **FEAT-004 (Tool System)**: Tool interface, registry, execution engine

### Depended On By

- None currently

### External Dependencies

- None (uses Android platform APIs only)

## Error Handling

### Error Scenarios

1. **Empty command**
   - Cause: `command` parameter is empty or blank
   - Handling: Return `ToolResult.error("validation_error", "Parameter 'command' is required and cannot be empty")`

2. **Invalid working directory**
   - Cause: Specified `working_directory` does not exist
   - Handling: Return `ToolResult.error("validation_error", "Working directory does not exist: <path>")`

3. **Process creation failure**
   - Cause: System cannot create a new process (resource limits)
   - Handling: Return `ToolResult.error("execution_error", "Failed to start process: <message>")`

4. **Timeout**
   - Cause: Command exceeds the timeout limit
   - Handling: Kill the process, return partial output with timeout indication

5. **I/O error during output capture**
   - Cause: Stream read failure
   - Handling: Return `ToolResult.error("execution_error", "Failed to read process output: <message>")`

## Test Points

### Functional Tests

- Verify `exec` executes a simple command and returns stdout
- Verify `exec` captures stderr when command produces error output
- Verify exit code is correctly reported (0 for success, non-zero for failure)
- Verify commands with pipes work (`echo hello | tr a-z A-Z`)
- Verify commands with redirects work
- Verify `timeout_seconds` parameter kills long-running commands
- Verify `working_directory` changes the process working directory
- Verify `max_length` truncates long output at line boundaries
- Verify empty command returns validation error
- Verify non-existent command returns shell error message

### Edge Cases

- Command that produces very large output (>1MB)
- Command that produces output on both stdout and stderr
- Command that takes exactly the timeout duration
- Command that produces binary output
- Command with special characters in arguments
- Command with very long argument string
- `timeout_seconds` set to 0 or negative value
- `timeout_seconds` set above 120 (should be clamped)
- `working_directory` pointing to a non-existent path
- `max_length` set to 0 or negative value
- Command that forks a background process

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
