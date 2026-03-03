# JavaScript 工具引擎

## 功能标识
- **功能ID**: FEAT-012
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: 草稿
- **优先级**: P1 (应该有)
- **负责人**: TBD
- **关联RFC**: RFC-012 (待定)

## 用户故事

**作为** OneClaw 的用户，
**我想要** 通过编写 JavaScript 脚本来扩展 AI Agent 的工具能力，
**以便** 无需修改应用 Kotlin 源码就能添加新工具，实现快速原型开发、社区共享和 AI 辅助工具创建。

### 典型场景

1. **AI 生成工具**：用户对 AI 说"创建一个把 Markdown 转换为纯文本的工具"。AI 通过 `write_file` 将 `.js` 和 `.json` 文件写入工具目录，工具立即可用。
2. **社区分享**：用户从论坛或 GitHub 下载 JS 工具包（`.js` + `.json`），放到工具目录，工具自动出现在工具列表中。
3. **数据处理**：用户需要一个解析 CSV 数据并提取特定列的工具。无需等待应用更新，直接添加一个 JS 工具即可。
4. **API 集成**：用户创建一个 JS 工具来调用特定 REST API（如天气服务），使用桥接的 `fetch()` 能力处理自定义认证和响应格式化。
5. **复合操作**：用户创建一个 JS 工具，读取文件、处理内容、将结果写入另一个文件 -- 在一次工具调用中组合多种能力。

## 功能描述

### 功能概述

JavaScript 工具引擎扩展了 FEAT-004（工具系统），允许通过嵌入式 QuickJS 运行时执行 JavaScript 文件来定义工具。每个 JS 工具由两个文件组成：包含工具逻辑的 `.js` 文件和定义工具元数据（名称、描述、参数 schema）的 `.json` 文件。JS 工具从指定目录加载，注册到现有的 ToolRegistry 中，与内置 Kotlin 工具并列，并通过相同的 ToolExecutionEngine 管道执行。

此功能弥补了当前"仅限内置工具"模式与完整插件系统之间的空白，提供了一种即时、轻量的可扩展机制。

### 架构原则

1. **无缝集成**：JS 工具是 ToolRegistry 中的一等公民 -- AI 模型、Agent 和执行引擎对它们的处理与内置 Kotlin 工具完全相同。
2. **约定优于配置**：通过扫描目录发现工具。文件命名约定（`name.js` + `name.json`）是唯一要求。
3. **直接桥接，而非编排**：JS 脚本通过注入 QuickJS 运行时的桥接函数访问宿主能力（网络、文件系统），而非间接调用其他工具。
4. **AI 作为工具作者**：主要的工具创建工作流是让 AI Agent 帮你编写工具，利用现有的 `write_file` 内置工具。
5. **故障安全**：有 bug 的 JS 工具不会导致应用崩溃。错误会被捕获并作为标准 `ToolResult.error()` 返回。

### 工具文件结构

工具存放在设备上的指定目录中：

```
/sdcard/OneClaw/tools/
  weather_lookup.js        -- 工具逻辑
  weather_lookup.json      -- 工具元数据
  csv_parser.js
  csv_parser.json
  markdown_to_text.js
  markdown_to_text.json
```

也可以存放在应用内部存储中：

```
{app_internal}/tools/
```

两个目录都会被扫描。名称冲突时，应用内部存储的工具优先。

### 元数据文件格式（`.json`）

每个工具的元数据文件遵循现有的 `ToolDefinition` schema：

```json
{
  "name": "weather_lookup",
  "description": "使用 OpenWeatherMap API 查询指定城市的当前天气",
  "parameters": {
    "properties": {
      "city": {
        "type": "string",
        "description": "城市名称（例如 'Tokyo'、'New York'）"
      },
      "units": {
        "type": "string",
        "description": "温度单位",
        "enum": ["metric", "imperial"],
        "default": "metric"
      }
    },
    "required": ["city"]
  },
  "requiredPermissions": [],
  "timeoutSeconds": 15
}
```

这与 Kotlin `ToolDefinition` 数据类的结构完全相同，序列化为 JSON。`name` 字段必须与文件名匹配（例如 `weather_lookup.json` 定义的 name 为 `weather_lookup`）。

### JavaScript 文件格式（`.js`）

每个 JS 文件定义一个 `execute` 函数，接收参数对象并返回结果：

```javascript
// weather_lookup.js

async function execute(params) {
  const city = params.city;
  const units = params.units || "metric";
  const apiKey = params._env?.OPENWEATHER_API_KEY || "";

  const url = `https://api.openweathermap.org/data/2.5/weather?q=${encodeURIComponent(city)}&units=${units}&appid=${apiKey}`;

  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`API 返回 ${response.status}: ${response.statusText}`);
  }

  const data = await response.json();
  return `${data.name} 天气: ${data.weather[0].description}，温度: ${data.main.temp}°${units === "metric" ? "C" : "F"}，湿度: ${data.main.humidity}%`;
}
```

契约：
- 文件必须定义一个 `execute` 函数（全局作用域）。
- `execute` 接收一个与 JSON schema 匹配的 `params` 对象。
- `execute` 返回一个字符串（成功结果）或抛出一个 Error（错误结果）。
- `execute` 可以是 `async` 的（返回 Promise），以使用桥接的异步 API 如 `fetch()`。

### 桥接宿主 API

QuickJS 运行时注入以下桥接 API：

#### 网络：`fetch()`
Web Fetch API 的子集：
```javascript
const response = await fetch(url, {
  method: "GET" | "POST" | "PUT" | "DELETE",
  headers: { "Content-Type": "application/json" },
  body: "string body"
});

response.ok        // boolean
response.status    // number
response.statusText // string
await response.text()  // string
await response.json()  // 解析后的对象
```

实现：委托给 Kotlin 端的 OkHttpClient。

#### 文件系统：`fs`
```javascript
const content = fs.readFile("/sdcard/Documents/notes.txt");        // string (UTF-8)
fs.writeFile("/sdcard/Documents/output.txt", "content");           // void
const exists = fs.exists("/sdcard/Documents/notes.txt");           // boolean
```

实现：委托给 Kotlin 端的 Java File I/O。与 `ReadFileTool` / `WriteFileTool` 相同的路径限制（阻止系统路径、大小限制）。

#### 控制台：`console`
```javascript
console.log("调试信息");     // 输出到 Android Logcat（tag: "JSTool:{tool_name}"）
console.warn("警告");
console.error("错误");
```

仅用于调试。输出不会返回给 AI 模型。

#### 环境变量：`params._env`
一个只读对象，包含用户配置的环境变量（存储在应用设置中）。用于 JS 工具需要的 API 密钥：
```javascript
const apiKey = params._env?.MY_API_KEY || "";
```

避免在 JS 工具文件中硬编码 API 密钥。

### 工具发现与加载

1. **应用启动时**：`JsToolLoader` 扫描工具目录中的 `.json` 文件。
2. **对每个 `.json` 文件**：
   a. 解析并验证元数据。
   b. 检查对应的 `.js` 文件是否存在。
   c. 创建一个 `JsTool` 实例，封装元数据和 JS 文件路径。
   d. 将 `JsTool` 注册到 `ToolRegistry` 中。
3. **名称冲突时**：如果 JS 工具与内置 Kotlin 工具同名，跳过 JS 工具并记录警告。
4. **热重载**（可选，P2）：当工具目录中的文件发生变化时，重新扫描并更新注册表。V1 版本中，在设置中提供手动"重新加载工具"操作即可。

### 工具执行流程

当 AI 模型调用 JS 工具时：

```
1. ToolExecutionEngine 收到工具调用（与其他工具相同）
2. 调用 JsTool.execute(parameters)
3. JsTool 创建一个 QuickJS 运行时实例（或复用池中的实例）
4. 将桥接函数（fetch、fs、console）注入 JS 上下文
5. 加载并执行工具的 .js 文件
6. 使用参数调用 execute(params) 函数
7. 如果 execute 返回字符串：ToolResult.success(result)
8. 如果 execute 抛出异常：ToolResult.error("execution_error", error.message)
9. 如果执行超过超时时间：终止 QuickJS 上下文，ToolResult.error("timeout", ...)
10. 清理 QuickJS 上下文
```

### 用户交互流程

#### 通过 AI 添加工具
```
1. 用户："创建一个摄氏度和华氏度之间转换温度的工具"
2. AI 调用 write_file 创建 /sdcard/OneClaw/tools/temperature_convert.json
3. AI 调用 write_file 创建 /sdcard/OneClaw/tools/temperature_convert.js
4. AI 通知用户工具已创建
5. 用户在设置中点击"重新加载工具"（或重启应用）
6. 工具出现在工具列表中，可以分配给 Agent
7. 用户（或 AI）可以使用该工具："把 100°F 转换成摄氏度"
```

#### 通过文件导入添加工具
```
1. 用户从外部来源下载工具文件
2. 用户将文件放入 /sdcard/OneClaw/tools/
3. 用户在设置中点击"重新加载工具"（或重启应用）
4. 工具出现在工具列表中
```

### 设置界面新增内容

在设置（FEAT-009）中新增一个区域：
- **JS 工具**区域显示：
  - 已加载的 JS 工具数量
  - "重新加载工具"按钮，重新扫描工具目录
  - 已加载的 JS 工具列表，显示名称和状态（已加载 / 错误）
  - 点击工具可查看元数据详情和加载错误信息
- **环境变量**区域：
  - 键值对，注入为所有 JS 工具执行时的 `params._env`
  - 用于 JS 工具需要的 API 密钥和配置
  - 值以加密方式存储（与 API 密钥相同，使用 EncryptedSharedPreferences）

## 验收标准

必须满足（全部要求）：
- [ ] QuickJS 引擎嵌入应用中并能执行 JavaScript 代码
- [ ] 启动时通过扫描工具目录发现 JS 工具
- [ ] `.json` 元数据文件根据 ToolDefinition schema 进行解析和验证
- [ ] `.js` 文件在 QuickJS 运行时中加载和执行
- [ ] `execute(params)` 函数契约有效（接收参数，返回字符串或抛出异常）
- [ ] JS 工具注册到 ToolRegistry 中，与内置 Kotlin 工具并列
- [ ] JS 工具在 Agent 工具选择界面中与内置工具显示方式相同
- [ ] JS 工具通过相同的 ToolExecutionEngine 管道执行（超时、错误处理）
- [ ] 桥接：`fetch()` 支持 GET 和 POST 请求
- [ ] 桥接：`fs.readFile()` 读取文件，与 ReadFileTool 相同的限制
- [ ] 桥接：`fs.writeFile()` 写入文件，与 WriteFileTool 相同的限制
- [ ] 桥接：`console.log/warn/error` 输出到 Logcat
- [ ] 环境变量可通过 `params._env` 注入
- [ ] JS 工具错误被捕获并作为 ToolResult.error 返回（应用不崩溃）
- [ ] JS 工具超时被强制执行（终止 QuickJS 上下文）
- [ ] 与内置工具的名称冲突被正确处理（跳过 JS 工具，记录警告）
- [ ] 设置中的"重新加载工具"操作重新扫描并更新注册表
- [ ] AI 可以使用现有的 `write_file` 工具创建 JS 工具

可选（锦上添花）：
- [ ] 热重载：文件系统监听器自动检测工具目录中的变更
- [ ] 工具验证：设置中的"测试工具"按钮，用示例参数运行工具
- [ ] 通过分享意图导入 JS 工具（从其他应用接收 `.js` + `.json` 文件）

## UI/UX 要求

### 设置界面新增内容

#### JS 工具区域
- 标题："JavaScript 工具"
- 副标题："已加载 {N} 个工具"
- "重新加载"按钮（图标按钮或文字按钮）
- 工具列表：每行显示工具名称、描述（截断）和状态指示器
  - 绿色圆点：加载成功
  - 红色圆点：加载错误（点击查看错误详情）
- 点击工具显示详情对话框，包含完整元数据和 JS 文件路径

#### 环境变量区域
- 标题："环境变量"
- 键值对列表，支持添加/编辑/删除
- 值默认隐藏（如密码字段），点击可显示
- "添加变量"按钮
- 用于 JS 工具通过 `params._env` 引用的 API 密钥

### 视觉设计
- 遵循现有 Material 3 + 金色/琥珀色强调色风格
- JS 工具在 Agent 配置工具列表中与内置工具视觉上无差异（没有特殊标记 -- 它们是一等公民）
- 在设置中，JS 工具区域的工具名称使用微妙的等宽字体

## 功能边界

### 包含的功能
- QuickJS 运行时集成
- 从文件系统发现 JS 工具（扫描目录）
- JSON 元数据解析和验证
- JS `execute()` 函数调用
- 宿主桥接：`fetch()`、`fs.readFile()`、`fs.writeFile()`、`fs.exists()`、`console.*`
- JS 工具的环境变量
- 设置中的"重新加载工具"操作
- JS 工具作为 ToolRegistry 的一等公民

### 不包含的功能
- 应用内 JavaScript 代码编辑器
- 可视化工具构建器 / 无代码工具创建
- npm / Node.js 模块系统（没有 `require()`，不能从 npm `import`）
- TypeScript 支持
- 调试器 / 单步执行
- 工具版本管理或更新机制
- 工具市场 / 商店
- 单次执行中的 JS 到 JS 工具链式调用（一个 JS 工具调用另一个 JS 工具）
- 沙箱进程隔离（JS 通过 QuickJS 在进程内运行）

## 业务规则

### 工具规则
1. 工具名称在内置工具和 JS 工具之间必须唯一
2. 名称冲突时，内置 Kotlin 工具始终优先
3. 工具名称必须与文件名匹配（例如 `my_tool.json` 必须定义 `"name": "my_tool"`）
4. 工具名称遵循 snake_case 约定（加载时验证）
5. 只有 `.js` 和 `.json` 文件都存在且有效时，工具才会被加载
6. 无效的工具被跳过并记录警告（不阻止其他工具加载）

### 执行规则
1. JS 工具在 `Dispatchers.IO` 上运行，与 Kotlin 工具相同
2. 每次 JS 工具执行获得一个新的（或池化的）QuickJS 上下文
3. `.json` 元数据中的超时时间被强制执行
4. 如果 `execute()` 返回非字符串值，通过 `JSON.stringify()` 转换为字符串
5. 如果 `execute()` 返回 `undefined` 或 `null`，结果为空字符串
6. 桥接函数错误（例如 fetch 网络错误）作为 JS 异常抛出，工具可以捕获或让其传播

### 安全规则
1. `fs` 桥接与内置文件工具相同的路径限制（阻止系统路径）
2. `fs` 桥接强制执行相同的文件大小限制
3. 环境变量值以加密方式存储
4. JS 工具不能访问应用内部存储或数据库
5. JS 工具不能直接访问 Android API（只能通过提供的桥接）
6. `fetch()` 桥接与 HttpRequestTool 相同的响应大小限制

## 非功能性需求

### 性能要求
- QuickJS 引擎初始化：< 50ms
- 工具目录扫描和加载：50 个工具 < 500ms
- 单个 JS 工具执行开销（不包括实际工作）：< 20ms
- QuickJS 上下文创建：< 10ms
- 内存：QuickJS 运行时增加约 2MB 应用内存占用
- 每个 JS 上下文堆限制为 16MB

### 可靠性
- QuickJS 崩溃（原生层）被捕获，不会导致应用崩溃
- JS 中的无限循环通过超时终止
- JS 上下文内存耗尽触发错误，而非应用崩溃

### 安全性
- 工具执行上下文之外不使用 `eval()` 执行任意代码
- JS 工具不能逃逸 QuickJS 沙箱访问 JVM/Kotlin 对象
- 桥接函数是 JS 和宿主之间唯一的通信通道

### 兼容性
- QuickJS 库：使用 quickjs-android 或类似的活跃维护包装库
- 最低 API 级别：与应用相同（API 26）
- ABI 支持：arm64-v8a、armeabi-v7a、x86_64（模拟器用）

## 依赖关系

### 依赖的功能
- **FEAT-004（工具系统）**：JS 工具集成到现有的 ToolRegistry 和 ToolExecutionEngine
- **FEAT-009（设置）**：JS 工具管理和环境变量的 UI
- **QuickJS 原生库**：用于 JavaScript 执行的第三方依赖

### 被依赖的功能
- **FEAT-002（Agent 管理）**：Agent 可以在工具配置中选择 JS 工具
- **FEAT-001（聊天交互）**：JS 工具调用在聊天中与其他工具的显示方式相同

### 外部依赖
- QuickJS Android 库（如 `aspect-build/aspect-quickjs-android` 或类似库）

## 错误处理

### 错误场景

1. **缺少 `.js` 文件**
   - 原因：`.json` 元数据存在但对应的 `.js` 文件缺失
   - 处理：跳过工具，记录警告，在设置中显示错误状态

2. **无效的 JSON 元数据**
   - 原因：`.json` 文件有语法错误或缺少必填字段
   - 处理：跳过工具，记录警告，在设置中显示解析错误详情

3. **JS 语法错误**
   - 原因：`.js` 文件包含无效的 JavaScript
   - 处理：工具加载但在首次执行时失败，给出清晰的错误信息

4. **缺少 `execute` 函数**
   - 原因：`.js` 文件评估成功但未定义 `execute`
   - 处理：返回 `ToolResult.error("execution_error", "JS tool does not define an execute() function")`

5. **JS 运行时错误**
   - 原因：`execute()` 中的未处理异常（TypeError、ReferenceError 等）
   - 处理：捕获，返回 `ToolResult.error("execution_error", error.message)`

6. **fetch() 网络错误**
   - 原因：网络不可达、DNS 失败、超时
   - 处理：抛出 JS 异常，如果未被捕获则传播为执行错误

7. **fs 桥接权限拒绝**
   - 原因：尝试访问被阻止的路径
   - 处理：抛出 JS 异常："Access denied: path is restricted"

8. **超时**
   - 原因：JS 执行超过配置的超时时间
   - 处理：中断/终止 QuickJS 上下文，`ToolResult.error("timeout", ...)`

9. **内存耗尽**
   - 原因：JS 代码分配过多内存（超过 16MB 堆限制）
   - 处理：QuickJS 触发 OOM，作为执行错误捕获

10. **与内置工具名称冲突**
    - 原因：JS 工具与 Kotlin 内置工具同名
    - 处理：跳过 JS 工具，记录警告

## 测试要点

### 功能测试
- 验证 QuickJS 引擎初始化并能执行基本 JavaScript
- 验证工具目录扫描找到 `.js` + `.json` 文件对
- 验证 JSON 元数据正确解析为 ToolDefinition
- 验证 `execute(params)` 使用正确参数调用
- 验证成功返回值封装在 ToolResult.success() 中
- 验证抛出的错误封装在 ToolResult.error() 中
- 验证 `fetch()` 桥接正确发起 HTTP 请求（GET、POST）
- 验证 `fs.readFile()` 正确读取文件
- 验证 `fs.writeFile()` 正确写入文件
- 验证 `fs.exists()` 返回正确的布尔值
- 验证 `console.log()` 输出到 Logcat
- 验证 `params._env` 包含配置的环境变量
- 验证超时强制终止 JS 执行
- 验证名称冲突处理（内置优先）
- 验证"重新加载工具"重新扫描目录并更新注册表
- 验证 JS 工具出现在 Agent 工具选择中
- 验证 AI 可以通过 write_file 创建 JS 工具并在重新加载后加载

### 边界测试
- 工具目录不存在（应自动创建）
- 空工具目录（不加载 JS 工具，无错误）
- `.json` 文件无对应 `.js` 文件
- `.js` 文件无对应 `.json` 文件
- 有语法错误的 JS 文件
- 没有 `execute` 函数的 JS 文件
- `execute` 返回非字符串（对象、数字、数组）
- `execute` 返回 null/undefined
- JS 中的无限循环（必须触发超时）
- execute 返回超大字符串
- fetch() 使用无效 URL 调用
- fs 操作不存在的文件
- 加载多个工具，其中一个无效（其他工具仍应正常加载）
- JS 文件和参数中的 Unicode 内容

### 性能测试
- QuickJS 初始化时间
- 1、10、50 个工具的加载时间
- JS 工具执行延迟开销
- 加载 QuickJS 后的内存占用

### 安全测试
- 验证 fs 桥接阻止系统路径
- 验证 JS 不能访问应用内部存储
- 验证 JS 不能逃逸 QuickJS 沙箱
- 验证环境变量静态加密

## 数据需求

### 工具目录
| 数据项 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `{name}.json` | 文件 | 是 | ToolDefinition JSON 格式的工具元数据 |
| `{name}.js` | 文件 | 是 | 包含 `execute(params)` 函数的工具逻辑 |

### 环境变量（存储在 EncryptedSharedPreferences 中）
| 数据项 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| Key | String | 是 | 变量名称（例如 `OPENWEATHER_API_KEY`） |
| Value | String | 是 | 变量值（静态加密） |

### 无需新增 Room 实体
JS 工具元数据从文件读取，不存储在数据库中。环境变量存储在 EncryptedSharedPreferences 中。

## 开放问题

- [ ] 使用哪个 QuickJS Android 库？需要评估各选项的稳定性、维护状态和 API 易用性。
- [ ] JS 工具上下文应该池化以提升性能，还是每次执行创建新的以确保隔离？
- [ ] `fetch()` 桥接是否应支持流式响应，还是仅支持缓冲？
- [ ] 是否应限制可加载的 JS 工具最大数量？
- [ ] 环境变量应该按工具配置还是全局配置？

## 参考资料

- [QuickJS JavaScript 引擎](https://bellard.org/quickjs/)
- [FEAT-004 工具系统 PRD](FEAT-004-tool-system.md)
- [RFC-004 工具系统](../../rfc/features/RFC-004-tool-system.md)

## 变更历史

| 日期 | 版本 | 变更内容 | 变更人 |
|------|------|----------|--------|
| 2026-02-28 | 0.1 | 初始版本 | - |
