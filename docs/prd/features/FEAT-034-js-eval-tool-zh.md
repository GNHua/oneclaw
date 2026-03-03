# JavaScript Eval 工具

## 功能信息
- **Feature ID**: FEAT-034
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1（应当包含）
- **Owner**: TBD
- **Related RFC**: RFC-034（待定）

## 用户故事

**作为**使用 OneClaw 的 AI Agent，
**我希望**有一个工具能在沙盒化的 QuickJS 环境中执行我即时编写的任意 JavaScript 代码，
**以便**我可以在无需依赖预定义工具文件的情况下，执行计算、数据转换、算法任务和通用编程工作。

### 典型场景

1. 用户询问"第 50 个斐波那契数是多少？"—— Agent 编写 JS 函数进行计算并返回结果。
2. 用户粘贴一个 CSV 表格并要求 Agent 计算平均值 —— Agent 编写 JS 解析数据并计算统计结果。
3. Agent 需要按多个条件对列表进行排序 —— 它编写 JS 比较器并运行。
4. 用户要求将 Unix 时间戳转换为人类可读的日期 —— Agent 快速编写 JS Date 转换代码。
5. Agent 需要对大块文本进行正则提取 —— 它编写带正则逻辑的 JS 并返回匹配结果。
6. 用户要求对字符串进行 Base64 编码/解码、URL 编码或哈希计算 —— Agent 编写相应的 JS 代码。
7. Agent 需要从非结构化文本生成结构化 JSON 数据 —— 它编写 JS 解析逻辑。
8. 用户提出涉及公式的数学问题 —— Agent 编写使用 Math.* 函数的 JS 代码来计算答案。

## 功能描述

### 概述

FEAT-034 新增一个 Kotlin 内置工具 `js_eval`，该工具接受 JavaScript 源代码字符串参数，并在沙盒化的 QuickJS 环境中执行。该工具复用现有的 `JsExecutionEngine`（来自 RFC-004）来运行代码，使 AI 模型可以使用与 JS 工具相同的桥接函数（console、fs、fetch、time、lib）。最后一个表达式的结果（或若定义了 `execute()` 函数则返回其返回值）将返回给 AI 模型。

这与现有的 JS 工具不同——后者是从磁盘或资源目录加载的预定义文件。`js_eval` 允许 AI 模型动态编写代码，使其成为一个通用计算工具。

### 架构概述

```
AI Model
    | tool call: js_eval(code="return 2 + 2;")
    v
 ToolExecutionEngine  (Kotlin, unchanged)
    |
    v
 ToolRegistry
    |
    v
 JsEvalTool  [NEW - Kotlin built-in tool]
    |
    v
 JsExecutionEngine  (existing, reused)
    |
    +-- QuickJS sandbox
    |       |
    |       +-- Memory limit: 16MB
    |       +-- Stack limit: 1MB
    |       +-- Timeout enforcement
    |       +-- Bridge functions: console, fs, fetch, time, lib
    |       |
    |       +-- Evaluate code, return result
    |
    +-- Result formatting
            |
            +-- Return string/JSON result
            +-- Return error if execution fails
```

### 工具定义

| 字段 | 值 |
|-------|-------|
| Name | `js_eval` |
| Description | Execute JavaScript code in a sandboxed QuickJS environment and return the result. Useful for computation, data processing, and algorithmic tasks. |
| Parameters | `code` (string, required): The JavaScript source code to execute |
| | `timeout_seconds` (integer, optional): Maximum execution time in seconds. Default: 30 |
| Required Permissions | None |
| Timeout | Controlled by `timeout_seconds` parameter (max 120 seconds) |
| Returns | The result of the evaluation as a string, or error object |

### 代码执行模型

`code` 参数按如下方式被包装并执行：

1. 若代码定义了名为 `main()` 的函数，则调用该函数，其返回值即为结果。
2. 否则，整个代码块作为顶层脚本被求值，最后一个表达式的值为结果。
3. 字符串结果原样返回；对象/数组经 JSON 序列化后返回；`null`/`undefined` 返回空字符串。

这同时支持简单单行表达式和结构化多函数脚本：

**简单表达式：**
```javascript
// code: "2 + 2"
// result: "4"
```

**基于函数：**
```javascript
// code:
function main() {
  const fib = (n) => n <= 1 ? n : fib(n-1) + fib(n-2);
  return fib(10);
}
// result: "55"
```

**数据处理：**
```javascript
// code:
function main() {
  const data = [3, 1, 4, 1, 5, 9, 2, 6];
  const sorted = data.sort((a, b) => a - b);
  const sum = data.reduce((a, b) => a + b, 0);
  return JSON.stringify({ sorted, sum, avg: sum / data.length });
}
// result: '{"sorted":[1,1,2,3,4,5,6,9],"sum":31,"avg":3.875}'
```

### 可用桥接函数

代码可访问与 JS 工具相同的桥接函数：

| 桥接 | 函数 | 描述 |
|--------|-----------|-------------|
| `console` | `log()`, `warn()`, `error()` | 日志输出（输出至 Android logcat） |
| `fs` | `readFile()`, `writeFile()`, `listDir()`, 等 | 文件系统访问 |
| `fetch` | `fetch(url, options)` | HTTP 请求 |
| `_time` | `now()`, `format()` | 时间工具 |
| `lib()` | `lib(name)` | 加载 JS 库 |

### 输出格式

工具以字符串形式直接返回结果：

- 基本类型值（number、boolean、string）转换为其字符串表示
- 对象和数组经 JSON 序列化后返回
- `null` 或 `undefined` 返回空字符串
- 若发生错误，返回携带错误信息的 `ToolResult.error()`

### 用户交互流程

```
1. User: "Calculate the compound interest on $10,000 at 5% for 10 years"
2. AI writes JS code to compute compound interest
3. AI calls js_eval(code="function main() { ... }")
4. JsEvalTool:
   a. Passes code to JsExecutionEngine.executeFromSource()
   b. QuickJS evaluates the code in a sandboxed context
   c. Returns the computed result
5. AI receives "16288.95", formats a response for the user
6. Chat shows the js_eval tool call and result
```

## 验收标准

必须通过（全部必须满足）：

- [ ] `js_eval` 工具已作为 Kotlin 内置工具注册至 `ToolRegistry`
- [ ] 工具接受包含 JavaScript 源代码的 `code` 字符串参数
- [ ] 代码通过现有 `JsExecutionEngine` 在 QuickJS 沙盒中执行
- [ ] 简单表达式返回其求值结果（例如 `2+2` 返回 `"4"`）
- [ ] 定义了 `main()` 函数的代码会自动调用该函数并返回结果
- [ ] 对象/数组在结果中经 JSON 序列化
- [ ] `timeout_seconds` 参数控制最大执行时间（默认：30秒，最大：120秒）
- [ ] 内存限制被强制执行（16MB 堆，1MB 栈 —— 现有 QuickJS 限制）
- [ ] 空或空白的 `code` 返回验证错误
- [ ] JS 语法错误返回携带解析错误信息的错误
- [ ] JS 运行时错误（例如未定义变量）返回携带错误信息的错误
- [ ] 桥接函数（console、fs、fetch、time、lib）在沙盒中可用
- [ ] 全部 Layer 1A 测试通过

可选（锦上添花）：

- [ ] 控制台输出被捕获并包含在结果元数据中
- [ ] 支持以参数形式传入结构化输入数据

## UI/UX 需求

本功能无新增 UI。工具以透明方式运行：
- 在聊天中与其他工具相同的工具调用展示方式
- 输出显示在工具结果区域
- V1 阶段无需额外的设置界面

## 功能边界

### 包含范围

- 封装 `JsExecutionEngine` 的 Kotlin `JsEvalTool` 实现
- 同时支持表达式求值和 `main()` 函数调用
- 可配置超时，最大值限制为 120 秒
- 复用所有现有 QuickJS 桥接（console、fs、fetch、time、lib）
- 注册至 `ToolModule`

### 不包含范围（V1）

- 多次调用间的持久化 JS 上下文（每次调用相互隔离）
- 按调用注入自定义桥接函数
- 结构化输入参数（除代码字符串外）
- 代码大小限制（QuickJS 内存限制已足够）
- 代码审批 UI（执行前的用户确认）
- npm/node 模块支持
- TypeScript 支持

## 业务规则

1. 每次 `js_eval` 调用创建一个全新的 QuickJS 上下文 —— 调用间不保留任何状态
2. 默认超时为 30 秒；允许的最大超时为 120 秒
3. 若 `timeout_seconds` 超过 120，则被限制为 120
4. 内存限制为 16MB 堆和 1MB 栈（现有 QuickJS 引擎限制）
5. 若代码定义了 `main()` 函数，则自动调用；否则返回最后一个表达式的值
6. 所有现有 JS 桥接均被注入至上下文中
7. 工具名称为 `js_eval`，以区别于预定义的 JS 工具

## 非功能性需求

### 性能

- 上下文创建：< 50ms（QuickJS 轻量级）
- 简单计算：< 10ms
- 桥接注入：< 20ms
- 每次调用总开销：< 100ms（不含代码执行时间）

### 内存

- 每次执行 QuickJS 堆限制为 16MB
- 每次执行 QuickJS 栈限制为 1MB
- 每次执行后上下文被销毁 —— 无内存泄漏

### 兼容性

- 支持所有受支持的 Android 版本（API 26+）
- QuickJS 库已作为项目依赖引入
- 无需额外的原生库

## 依赖关系

### 依赖于

- **FEAT-004（工具系统）**：工具接口、注册表、执行引擎
- **RFC-004 JS 执行**：JsExecutionEngine、QuickJS 桥接

### 被以下依赖

- 暂无

### 外部依赖

- QuickJS Android 库（已为项目依赖）

## 错误处理

### 错误场景

1. **代码为空**
   - 原因：`code` 参数为空或空白
   - 处理：返回 `ToolResult.error("validation_error", "Parameter 'code' is required and cannot be empty")`

2. **语法错误**
   - 原因：无效的 JavaScript 语法
   - 处理：返回 `ToolResult.error("execution_error", "JS syntax error: <message>")`

3. **运行时错误**
   - 原因：执行过程中发生 ReferenceError、TypeError 等
   - 处理：返回 `ToolResult.error("execution_error", "JS runtime error: <message>")`

4. **超时**
   - 原因：代码超过超时限制（例如无限循环）
   - 处理：返回 `ToolResult.error("timeout", "Execution timed out after <N>s")`

5. **内存超限**
   - 原因：代码分配超过 16MB
   - 处理：QuickJS 抛出错误，以 `ToolResult.error("execution_error", "...")` 形式返回

## 测试要点

### 功能测试

- 验证 `js_eval` 对简单算术表达式求值并返回结果
- 验证 `js_eval` 在定义了 `main()` 时调用它并返回其结果
- 验证 `js_eval` 返回经 JSON 序列化的对象
- 验证 `js_eval` 返回经 JSON 序列化的数组
- 验证 `js_eval` 对 `null`/`undefined` 返回空字符串
- 验证 `js_eval` 正确处理字符串结果
- 验证 `timeout_seconds` 参数终止长时间运行的代码
- 验证空代码返回验证错误
- 验证 JS 语法错误返回适当的错误
- 验证 JS 运行时错误返回适当的错误
- 验证 console.log 在沙盒中可用
- 验证桥接函数（fs、fetch、time）可用

### 边界情况

- 产生非常长字符串结果的代码
- 包含无限循环的代码（应超时）
- 分配过多内存的代码（应触发 QuickJS 限制）
- 使用 async/await 配合 fetch 的代码
- 包含 Unicode 字符的代码
- 使用 QuickJS 支持的 ES2020+ 特性的代码
- `timeout_seconds` 设置为 0 或负值
- `timeout_seconds` 设置超过 120（应被限制）

## 变更历史

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
