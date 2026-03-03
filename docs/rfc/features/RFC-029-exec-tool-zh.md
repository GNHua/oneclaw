# RFC-029: Shell Exec 工具

## 文档信息
- **RFC ID**: RFC-029
- **关联 PRD**: [FEAT-029 (Shell Exec 工具)](../../prd/features/FEAT-029-exec-tool.md)
- **关联架构**: [RFC-000 (整体架构)](../architecture/RFC-000-overall-architecture.md)
- **关联 RFC**: [RFC-004 (工具系统)](RFC-004-tool-system.md)
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景

OneClaw 的 AI agent 目前通过一组有限的内置工具（webfetch、browser、schedule_task 等）和基于 JS 的工具（文件读写、HTTP、时间）与设备进行交互。然而，目前没有通用工具可以在设备上执行任意 shell 命令。Shell 访问能力将使 agent 执行更广泛的任务：文件系统操作、系统诊断、包管理、网络测试、文本处理和自动化脚本。

Android 提供了 `Runtime.getRuntime().exec()`，允许应用在其沙箱内启动 shell 进程。这些进程以应用的 UID 运行，拥有与应用本身相同的权限——没有 root 访问权限，受 Android 安全模型约束。

### 目标

1. 在 `tool/builtin/` 中实现 `ExecTool.kt`，作为 Kotlin 内置工具
2. 通过 `Runtime.getRuntime().exec()` 配合 `sh -c` 包装来执行 shell 命令
3. 从子进程中捕获 stdout、stderr 和退出码
4. 强制执行可配置的超时，并在超时后强制终止进程
5. 支持可配置的工作目录和输出截断
6. 在 `ToolModule` 中注册该工具

### 非目标

- 具有持久状态的交互式 shell 会话
- Root 命令执行
- 命令白名单/黑名单过滤
- 将输出实时流式传输到 UI
- 每条命令的环境变量覆盖
- 后台进程管理

## 技术设计

### 架构概览

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

### 核心组件

**新增：**
1. `ExecTool` -- Kotlin 内置工具，执行 shell 命令并返回输出

**修改：**
2. `ToolModule` -- 将 `ExecTool` 注册为 Kotlin 内置工具

## 详细设计

### 目录结构（新增与变更文件）

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

### ToolModule 变更

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

### ExecTool 所需导入

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

## 实施计划

### 阶段一：ExecTool 核心实现

1. 在 `tool/builtin/` 中创建 `ExecTool.kt`
2. 通过 `Runtime.getRuntime().exec()` 实现命令执行
3. 实现使用并发读取器捕获 stdout/stderr
4. 使用 `Process.waitFor(timeout, unit)` 实现超时控制
5. 实现包含退出码的输出格式化

### 阶段二：集成

1. 更新 `ToolModule.kt` 以注册 `ExecTool`
2. 添加 `ExecTool` 导入及 Koin single 注册
3. 在 `ToolRegistry.apply` 块中添加注册

### 阶段三：测试

1. 创建 `ExecToolTest.kt` 并编写单元测试
2. 运行 Layer 1A 测试（`./gradlew test`）
3. 如有模拟器则运行 Layer 1B 测试
4. 在设备上对各类 shell 命令进行手动测试

## 数据模型

无数据模型变更。`ExecTool` 实现了已有的 `Tool` 接口。

## API 设计

### 工具接口

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

### 输出格式示例

**命令成功执行：**
```
[Exit Code: 0]

total 48
drwxr-xr-x  2 u0_a123 u0_a123 4096 2026-03-01 10:00 .
-rw-r--r--  1 u0_a123 u0_a123 1234 2026-03-01 09:30 file.txt
```

**命令执行出错：**
```
[Exit Code: 1]

[stderr]
ls: cannot access '/nonexistent': No such file or directory
```

**同时有 stdout 和 stderr 的命令：**
```
[Exit Code: 0]

Processing file1.txt
Processing file2.txt

[stderr]
Warning: file3.txt skipped (empty)
```

**命令超时：**
```
[Exit Code: -1 (timeout after 30s)]

partial output here...

[stderr]
Process killed after 30 seconds timeout.
```

## 进程生命周期

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

## 错误处理

| 错误 | 原因 | 错误类型 | 处理策略 |
|------|------|----------|----------|
| 命令为空 | `command` 参数为空或 null | `validation_error` | 立即返回并附带错误信息 |
| 工作目录无效 | 路径不存在或不是目录 | `validation_error` | 立即返回并附带错误信息 |
| 进程创建失败 | 系统资源限制、命令无效 | `execution_error` | 捕获 IOException，返回错误 |
| 权限被拒绝 | 命令需要应用不具备的权限 | `permission_error` | 捕获 SecurityException，返回错误 |
| 超时 | 命令执行时间超过 `timeout_seconds` | 不适用（成功） | 终止进程，返回带超时标识的部分输出 |
| I/O 错误 | 输出捕获期间流读取失败 | `execution_error` | 捕获 IOException，返回错误 |
| 未知错误 | 其他任何异常 | `execution_error` | 记录日志并返回通用错误信息 |

注意：超时不被视为错误——工具会以成功状态返回，并在输出中包含超时标识，因为部分输出对 AI 模型仍可能有价值。

## 安全注意事项

1. **Android 应用沙箱**：通过 `Runtime.exec()` 启动的进程以应用的 UID 运行。它们无法访问其他应用的数据、系统文件，也无法执行特权操作（除非设备已 root）。这由 Linux 内核强制执行，而非由本工具负责。

2. **不进行 Root 提权**：本工具不使用 `su`，也不尝试提升权限。如果用户设备已 root 且 `su` 在 PATH 中，AI 模型理论上可以调用 `su -c "..."`，但这受设备 root 管理应用（如 Magisk）的约束，后者会提示用户进行授权确认。

3. **V1 不进行命令过滤**：本工具不对命令进行黑名单或白名单过滤。理由如下：
   - Android 沙箱已限制了进程的操作范围
   - 命令过滤很容易被绕过（编码、间接调用、脚本）
   - AI 模型已有文件读写工具，具备类似的访问能力
   - 安装此 agent 应用的用户已接受 AI 驱动设备交互的风险

4. **资源限制**：超时控制可防止进程失控。输出截断可防止内存耗尽。进程始终在 `finally` 块中被销毁。

5. **无网络数据泄漏风险**：本工具仅将输出返回给 AI 模型，而 AI 模型已在应用进程内。不会暴露额外的网络攻击面。

## 性能

| 操作 | 预期时间 | 备注 |
|------|----------|------|
| 进程创建 | ~50-100ms | Fork + exec 开销 |
| stdout/stderr 捕获 | 取决于命令 | 缓冲 I/O，并发读取器 |
| 超时控制 | 精度约 ~1s | 使用 `Process.waitFor(long, TimeUnit)` |
| 进程清理 | < 100ms | `destroy()` + `destroyForcibly()` |

内存使用：
- stdout 和 stderr 缓冲在 StringBuilder 中（上限为 `max_length`）
- 进程句柄和流在 `finally` 块中关闭
- 调用之间无持久状态

## 测试策略

### 单元测试

**ExecToolTest.kt：**
- `testExecute_simpleCommand` -- `echo hello` 返回 "hello" 且退出码为 0
- `testExecute_commandWithExitCode` -- `exit 42` 返回退出码 42
- `testExecute_commandWithStderr` -- 产生 stderr 输出的命令
- `testExecute_commandWithPipes` -- `echo hello | tr a-z A-Z` 返回 "HELLO"
- `testExecute_timeout` -- `sleep 60` 配合 timeout_seconds=2 触发超时
- `testExecute_workingDirectory` -- `pwd` 配合自定义 working_directory
- `testExecute_emptyCommand` -- 空命令返回校验错误
- `testExecute_invalidWorkingDir` -- 不存在的目录返回校验错误
- `testExecute_maxLength` -- 大量输出被截断
- `testExecute_noOutput` -- 无 stdout/stderr 的命令
- `testExecute_timeoutClamped` -- timeout_seconds > 120 被限制在最大值
- `testDefinition` -- 工具定义包含正确的名称和参数

### 集成测试（Layer 1B）

- 在设备上执行 `ls` 并验证输出包含预期目录
- 执行 `getprop ro.build.version.sdk` 并验证返回一个数字
- 执行访问 `/sdcard/` 的命令（如已授予存储权限）

### 手动测试（Layer 2）

- 运行 `pm list packages` 并验证应用包列表输出
- 运行 `cat /proc/cpuinfo` 并验证硬件信息输出
- 运行包含管道和重定向的命令链
- 运行超过超时时间的命令并验证可以干净地终止
- 在指定工作目录中运行命令

## 备选方案

### 1. 使用 ProcessBuilder 替代 Runtime.exec()

**方案**：使用 `ProcessBuilder`，它提供更多控制（将 stderr 重定向到 stdout、配置环境变量）。
**决策**：按用户指定使用 `Runtime.getRuntime().exec()`。在内部，`Runtime.exec()` 本身也会创建 `ProcessBuilder`。上述实现直接使用 `Runtime.exec()`。在未来迭代中，如果需要合并 stderr 或按命令覆盖环境变量，可以切换到 `ProcessBuilder`。

### 2. 使用持久化 Shell 会话

**方案**：维护一个长期运行的 `sh` 进程，并通过 stdin 向其传送命令。
**V1 阶段拒绝**：增加了复杂性（shell 状态管理、提示符检测、输出边界标记）。每条命令作为独立进程更简单、更可预测。可作为未来增强功能添加。

### 3. 命令黑名单

**方案**：维护一份危险命令列表（rm -rf、reboot 等）并拒绝执行。
**V1 阶段拒绝**：容易被绕过（命令编码、脚本、别名）。Android 沙箱已防止真正危险的操作。AI agent 应用的用户接受相应风险。可在后续作为可选安全层添加。

## 依赖关系

### 外部依赖

无。仅使用 Android 平台 API：
- `java.lang.Runtime`
- `java.lang.Process`
- `java.io.BufferedReader` / `java.io.InputStreamReader`
- `java.util.concurrent.TimeUnit`

### 内部依赖

- `tool/engine/` 中的 `Tool` 接口
- `core/model/` 中的 `ToolResult`、`ToolDefinition`、`ToolParametersSchema`、`ToolParameter`
- Android 提供的 `Context`（用于默认工作目录）

## 未来扩展

- **持久化 shell 会话**：维护一个运行中的 `sh` 进程，支持有状态的命令序列（cd、环境变量）
- **命令审批**：在执行命令前显示可选的 UI 确认对话框
- **命令黑名单/白名单**：可配置的命令安全过滤器
- **环境变量**：按命令覆盖环境变量
- **流式输出**：在聊天 UI 中实时显示 stdout/stderr
- **多 shell 支持**：允许选择 `bash`、`zsh` 或其他可用的 shell
- **后台进程**：支持定期回报状态的长期运行进程

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|----------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
