# Shell Exec 工具

## 功能信息
- **功能 ID**: FEAT-029
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **优先级**: P1（应具备）
- **负责人**: TBD
- **关联 RFC**: RFC-029（待定）

## 用户故事

**作为** 使用 OneClaw 的 AI 智能体，
**我希望** 拥有一个能在 Android 设备上执行 shell 命令的工具，
**以便** 我可以与设备操作系统进行交互——列出文件、查看系统信息、运行脚本、管理应用包，以及执行需要直接命令行访问的自动化任务。

### 典型场景

1. 智能体运行 `ls -la /sdcard/Download/` 列出用户下载目录中的文件，然后汇总内容。
2. 智能体运行 `pm list packages` 枚举设备上已安装的应用。
3. 智能体运行 `cat /proc/cpuinfo` 查看设备的硬件规格。
4. 智能体链式执行 `find /sdcard -name "*.pdf" -mtime -7` 定位最近修改的 PDF 文件。
5. 智能体运行 `ping -c 3 google.com` 诊断网络连通性。
6. 智能体运行 `getprop ro.build.version.release` 查看 Android 版本。

## 功能描述

### 概述

FEAT-029 新增一个 Kotlin 内置 `exec` 工具，通过 `Runtime.getRuntime().exec()` 在 Android 设备上运行 shell 命令。该工具捕获 stdout、stderr 和退出码，以结构化文本的形式返回给 AI 模型。这使 AI 智能体能够直接访问设备的命令行环境，从而实现文件系统操作、系统检查、进程管理和通用自动化。

该工具在非交互式 shell 中运行命令，并通过可配置的超时机制防止进程失控。输出会被捕获并截断至可配置的字符上限，以避免超出 AI 模型的上下文窗口。

### 架构概览

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

### 工具定义

| 字段 | 值 |
|-------|-------|
| 名称 | `exec` |
| 描述 | 在设备上执行 shell 命令并返回其输出 |
| 参数 | `command`（string，必填）：要执行的 shell 命令 |
| | `timeout_seconds`（integer，可选）：最大执行时间（秒）。默认值：30 |
| | `working_directory`（string，可选）：命令的工作目录。默认值：应用数据目录 |
| | `max_length`（integer，可选）：最大输出长度（字符数）。默认值：50000 |
| 所需权限 | 无（在应用沙箱内运行） |
| 超时 | 由 `timeout_seconds` 参数控制（最大 120 秒） |
| 返回值 | 含退出码的 stdout/stderr 合并输出，或错误对象 |

### 命令执行

- 命令通过 `Runtime.getRuntime().exec(arrayOf("sh", "-c", command))` 执行
- shell 进程以应用的 UID 和权限运行（Android 应用沙箱）
- 除非设备已 root 且应用拥有 root 权限，否则无法获取 root 访问权限
- 命令在非交互式 shell 中运行——无 stdin，无 TTY
- 环境变量继承自应用进程

### 输出格式

该工具返回结构化文本输出：

```
[Exit Code: 0]

<stdout content here>
```

如果 stderr 不为空：

```
[Exit Code: 1]

<stdout content here>

[stderr]
<stderr content here>
```

如果命令超时：

```
[Exit Code: -1 (timeout)]

<partial stdout if any>

[stderr]
Process killed after 30 seconds timeout.
```

### 用户交互流程

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

## 验收标准

必须通过（全部必填）：

- [ ] `exec` 工具已在 `ToolRegistry` 中注册为 Kotlin 内置工具
- [ ] 工具接受 `command` 字符串参数，并通过 `Runtime.getRuntime().exec()` 执行
- [ ] Shell 命令使用 `sh -c` 包装器运行，以确保正确的 shell 解析
- [ ] stdout 被捕获并包含在结果中
- [ ] stderr 在非空时被捕获并包含在结果中
- [ ] 退出码包含在输出中
- [ ] `timeout_seconds` 参数控制最大执行时间（默认：30 秒，最大：120 秒）
- [ ] 超时到期时进程被强制终止
- [ ] `working_directory` 参数设置进程的工作目录
- [ ] `max_length` 参数控制输出截断（默认：50000）
- [ ] 输出截断在行边界处整洁地发生
- [ ] 没有输出的命令返回成功，并附带空输出说明
- [ ] 无效命令返回包含 shell 错误信息的错误
- [ ] 所有 Layer 1A 测试通过

可选（锦上添花）：

- [ ] 用于调试的命令历史记录
- [ ] 危险命令黑名单（rm -rf /、reboot 等）

## UI/UX 要求

本功能无新 UI。工具透明运行：
- 与其他工具一样在聊天中显示工具调用
- 输出显示在工具结果区域
- V1 无需额外的设置界面

## 功能边界

### 包含

- 使用 `Runtime.getRuntime().exec()` 实现的 Kotlin `ExecTool`
- 使用独立读取线程捕获 stdout 和 stderr
- 退出码上报
- 可配置超时与进程终止
- 可配置工作目录
- 带可配置上限的输出截断
- 在 `ToolModule` 中注册

### 不包含（V1）

- 交互式 shell 会话（带 stdin 的持久 shell）
- root 命令执行支持
- 命令黑名单或白名单
- 按命令配置环境变量
- 后台/长时间运行进程管理
- 流式输出（实时 stdout/stderr）
- Shell 选择（bash、zsh 等）——仅使用 `sh`
- 命令审批 UI（执行前需用户确认）

## 业务规则

1. 命令始终使用 `sh -c` 包装，以确保正确的 shell 解析（管道、重定向等）
2. 默认超时为 30 秒；允许的最大超时为 120 秒
3. 若 `timeout_seconds` 超过 120，则被截断至 120
4. 超时进程通过 `Process.destroyForcibly()` 销毁
5. 默认工作目录为应用的数据目录（`context.filesDir`）
6. 若未指定 `max_length`，输出截断默认为 50,000 个字符
7. 进程继承应用的环境变量
8. 进程不提供 stdin（非交互式）

## 非功能性需求

### 性能

- 进程创建：< 100ms
- 输出捕获：通过缓冲读取线程实时进行
- 超时执行：精度在 1 秒以内

### 内存

- stdout 和 stderr 在 StringBuilder 中缓冲（内存中）
- 大量输出被截断至 `max_length` 以防止 OOM
- 执行完成后清理进程资源

### 兼容性

- 支持所有受支持的 Android 版本（API 26+）
- `Runtime.getRuntime().exec()` 在所有 Android 版本上均可用
- 可用的 shell 命令取决于设备的 Android 版本和 ROM

## 依赖关系

### 依赖于

- **FEAT-004（工具系统）**：Tool 接口、注册表、执行引擎

### 被依赖于

- 目前无

### 外部依赖

- 无（仅使用 Android 平台 API）

## 错误处理

### 错误场景

1. **命令为空**
   - 原因：`command` 参数为空或仅含空白字符
   - 处理：返回 `ToolResult.error("validation_error", "Parameter 'command' is required and cannot be empty")`

2. **无效的工作目录**
   - 原因：指定的 `working_directory` 不存在
   - 处理：返回 `ToolResult.error("validation_error", "Working directory does not exist: <path>")`

3. **进程创建失败**
   - 原因：系统无法创建新进程（资源限制）
   - 处理：返回 `ToolResult.error("execution_error", "Failed to start process: <message>")`

4. **超时**
   - 原因：命令超过超时限制
   - 处理：终止进程，返回带超时说明的部分输出

5. **输出捕获时发生 I/O 错误**
   - 原因：流读取失败
   - 处理：返回 `ToolResult.error("execution_error", "Failed to read process output: <message>")`

## 测试要点

### 功能测试

- 验证 `exec` 执行简单命令并返回 stdout
- 验证 `exec` 在命令产生错误输出时捕获 stderr
- 验证退出码被正确上报（成功为 0，失败为非零）
- 验证带管道的命令可正常工作（`echo hello | tr a-z A-Z`）
- 验证带重定向的命令可正常工作
- 验证 `timeout_seconds` 参数终止长时间运行的命令
- 验证 `working_directory` 更改进程工作目录
- 验证 `max_length` 在行边界处截断长输出
- 验证空命令返回验证错误
- 验证不存在的命令返回 shell 错误信息

### 边界情况

- 产生非常大输出（>1MB）的命令
- 同时在 stdout 和 stderr 上产生输出的命令
- 执行时间恰好等于超时时长的命令
- 产生二进制输出的命令
- 参数中含有特殊字符的命令
- 参数字符串非常长的命令
- `timeout_seconds` 设置为 0 或负值
- `timeout_seconds` 设置超过 120（应被截断）
- `working_directory` 指向不存在的路径
- `max_length` 设置为 0 或负值
- 派生后台进程的命令

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | 初始版本 | - |
