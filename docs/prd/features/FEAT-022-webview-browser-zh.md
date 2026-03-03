# WebView 浏览器工具

## 功能信息
- **功能 ID**: FEAT-022
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **优先级**: P2（锦上添花）
- **负责人**: 待定
- **关联 RFC**: RFC-022（待定）

## 用户故事

**作为** 使用 OneClaw 的 AI agent，
**我希望** 有一个能够在真实浏览器环境中渲染网页、截图，并从动态渲染页面（SPA）中提取内容的浏览器工具，
**以便** 我可以与依赖 JavaScript 渲染内容的现代 Web 应用交互，并在文本提取不足时对页面进行可视化检查。

### 典型场景

1. Agent 需要检查网页的视觉布局。它携带 URL 调用 `browser_screenshot`，并收到一张可供视觉分析的截图。
2. Agent 需要获取 React/Vue/Angular SPA 的内容。由于内容由 JavaScript 渲染，`webfetch` 工具返回的 HTML 几乎为空。Agent 使用 `browser_extract` 在真实 WebView 中加载页面，等待 JS 渲染完成，然后将最终 DOM 内容提取为 Markdown。
3. Agent 需要验证某个 Web 表单的视觉效果是否正确。它截图并确认布局、按钮位置和文本是否符合预期。
4. Agent 需要从页面初始加载后通过 AJAX 异步加载内容的页面中提取结构化数据。`browser_extract` 会等待页面稳定后再进行提取。

## 功能描述

### 概述

FEAT-022 新增一个基于 WebView 的浏览器工具，为 AI agent 提供两种能力：

1. **`browser_screenshot`**：在离屏 Android WebView 中渲染网页，并将截图捕获为图像。
2. **`browser_extract`**：在 WebView 中渲染网页，在浏览器上下文中执行 JavaScript 提取内容，并以 Markdown 或结构化文本形式返回结果。

与 `webfetch`（FEAT-021）不同——后者获取静态 HTML 并在服务端解析——浏览器工具使用真实的浏览器引擎（Android WebView / Chromium），能够执行 JavaScript、加载动态内容，并对页面进行可视化渲染。这使得与现代 SPA 及 JavaScript 密集型页面的交互成为可能。

### 架构概览

```
AI 模型
    | 工具调用：browser_screenshot(url="...") 或 browser_extract(url="...")
    v
ToolExecutionEngine  （Kotlin，无变更）
    |
    v
ToolRegistry
    |
    v
BrowserTool  [新增 - Kotlin 内置工具]
    |
    +-- WebViewManager [新增 - 管理离屏 WebView 生命周期]
    |       |
    |       +-- WebView（离屏，在主线程创建）
    |       |     |
    |       |     +-- JavaScript 执行（evaluateJavascript）
    |       |     +-- 页面渲染（Chromium 引擎）
    |       |
    |       +-- ScreenshotCapture [新增 - 将 WebView 捕获为位图]
    |       |     |
    |       |     +-- 基于 Canvas 的捕获
    |       |     +-- 图像压缩（PNG/JPEG）
    |       |
    |       +-- ContentExtractor [新增 - 基于 JS 的 DOM 提取]
    |             |
    |             +-- 内置提取脚本（Readability 风格）
    |             +-- 可选 Turndown（在 WebView 上下文中运行，可访问真实 DOM）
    |
    +-- 输出处理
          |
          +-- 截图：文件路径或 base64
          +-- 提取：Markdown 字符串
```

### 两种能力，一个工具

浏览器工具通过单个工具注册，使用 `mode` 参数暴露两种模式：

#### `browser_screenshot`

渲染页面并捕获截图：

| 字段 | 值 |
|------|-----|
| 模式 | `screenshot` |
| 描述 | 渲染网页并捕获截图 |
| 参数 | `url`（string，必填）：要渲染的 URL |
| | `mode`（string，必填）：`"screenshot"` |
| | `width`（integer，可选）：视口宽度，单位像素。默认：412（类 Pixel 设备） |
| | `height`（integer，可选）：视口高度，单位像素。默认：915 |
| | `wait_seconds`（number，可选）：页面加载后等待 JS 渲染完成的秒数。默认：2 |
| | `full_page`（boolean，可选）：捕获完整可滚动页面而非仅视口。默认：false |
| 返回值 | 包含 `image_path`（截图保存路径）的对象 |

#### `browser_extract`

渲染页面并通过 JavaScript 提取内容：

| 字段 | 值 |
|------|-----|
| 模式 | `extract` |
| 描述 | 渲染网页并以 Markdown 形式提取内容 |
| 参数 | `url`（string，必填）：要渲染的 URL |
| | `mode`（string，必填）：`"extract"` |
| | `wait_seconds`（number，可选）：页面加载后的等待秒数。默认：2 |
| | `max_length`（integer，可选）：最大输出长度。默认：50000 |
| | `javascript`（string，可选）：要执行的自定义 JS，必须返回字符串。 |
| 返回值 | 提取内容的 Markdown 字符串 |

### 工具定义

| 字段 | 值 |
|------|-----|
| 名称 | `browser` |
| 描述 | 在浏览器中渲染网页，然后截图或提取内容 |
| 参数 | `url`（string，必填）：要加载的 URL |
| | `mode`（string，必填）：`"screenshot"` 或 `"extract"` |
| | `width`（integer，可选）：视口宽度。默认：412 |
| | `height`（integer，可选）：视口高度。默认：915 |
| | `wait_seconds`（number，可选）：加载后的等待时间。默认：2 |
| | `full_page`（boolean，可选）：全页截图。默认：false |
| | `max_length`（integer，可选）：提取模式的最大输出长度。默认：50000 |
| | `javascript`（string，可选）：提取模式的自定义 JS |
| 超时 | 60 秒 |
| 返回值 | 截图文件路径（截图模式）或 Markdown 字符串（提取模式） |

### 截图捕获

截图模式使用 Android 的 WebView 渲染管线：

1. 创建或复用一个离屏 WebView（用户不可见）
2. 根据参数设置视口尺寸
3. 加载 URL，等待 `onPageFinished` 回调
4. 额外等待 `wait_seconds` 秒，使动态内容稳定
5. 将 WebView 内容捕获为位图：
   - **仅视口**：按视口尺寸将 WebView 绘制到 Canvas
   - **全页**：通过 `computeVerticalScrollRange()` 获取完整内容高度，创建适当大小的位图，分段滚动捕获
6. 将位图压缩为 PNG
7. 保存至应用内部缓存目录
8. 返回文件路径

### 内容提取

提取模式使用 `evaluateJavascript()` 在 WebView 的浏览器上下文中运行 JavaScript：

1. 加载页面并等待渲染完成（流程与截图相同）
2. 若提供了 `javascript` 参数，则执行该自定义脚本
3. 否则执行内置提取脚本，该脚本将：
   a. 定位主内容区域（与 FEAT-021 相同的启发式规则：article > main > body）
   b. 剥离干扰元素（script、style、nav、footer 等）
   c. 使用 Turndown（在 WebView 上下文中加载，可访问完整 DOM API）将 HTML 转换为 Markdown
   d. 若 Turndown 失败，则回退到 `innerText` 提取
4. 返回提取文本，截断至 `max_length`

这正是 Turndown 按 FEAT-015 原始设计运行的场景——在具有完整 DOM API 访问权限的真实浏览器环境中执行。

### WebView 生命周期

管理 WebView 实例需要谨慎处理，以避免内存泄漏：

- WebView 必须在主线程（UI 线程）上创建
- `WebViewManager` 维护单个可复用的 WebView 实例
- WebView 在首次使用时惰性创建
- 每次工具调用结束后，重置 WebView（`loadUrl("about:blank")`，清除缓存）
- 应用进入后台时或显式清理时销毁 WebView
- 设置超时机制：若 5 分钟内未使用，则销毁 WebView

### 与 `webfetch` 的关系

`browser` 与 `webfetch` 是互补工具：

| | `webfetch`（FEAT-021） | `browser`（FEAT-022） |
|--|------------------------|----------------------|
| 引擎 | OkHttp + Jsoup | Android WebView（Chromium） |
| JavaScript | 不执行 | 完整执行 |
| 动态内容 | 不支持（仅静态 HTML） | 支持（SPA、AJAX 等） |
| 截图 | 不支持 | 支持 |
| 速度 | 快（通常 < 1 秒） | 较慢（通常 2-5 秒） |
| 内存 | 低（峰值约 5MB） | 高（WebView 约 50-100MB） |
| 适用场景 | 静态页面、文档、文章 | SPA、JS 渲染页面、视觉检查 |
| 推荐用于 | 大多数网页获取任务 | 当 `webfetch` 返回空内容或内容不完整时 |

AI 模型应优先使用 `webfetch` 处理大多数任务，仅当内容需要 JavaScript 渲染时才回退到 `browser`。

### 用户交互流程

#### 截图流程

```
1. 用户："给我看看 google.com 是什么样子的"
2. AI 调用 browser(url="https://google.com", mode="screenshot")
3. BrowserTool：
   a. 创建或复用离屏 WebView
   b. 加载 URL，等待页面加载 + 2 秒
   c. 将截图保存为 PNG
   d. 返回文件路径
4. AI 将截图图像返回给用户
5. 聊天界面内联展示截图
```

#### 提取流程

```
1. 用户："获取这个 React 应用的内容：https://example-spa.com"
2. AI 先尝试 webfetch——得到的 HTML 几乎为空（JS 未执行）
3. AI 调用 browser(url="https://example-spa.com", mode="extract")
4. BrowserTool：
   a. 在 WebView 中加载 URL，等待 JS 渲染完成
   b. 通过 evaluateJavascript() 运行提取脚本
   c. 返回 Markdown 内容
5. AI 向用户总结内容
```

#### 自定义 JavaScript 流程

```
1. 用户："获取这个商品页面的价格"
2. AI 调用 browser(url="https://shop.example.com/product/123", mode="extract",
     javascript="document.querySelector('.product-price')?.textContent || 'Price not found'")
3. BrowserTool：
   a. 加载 URL，等待渲染完成
   b. 执行自定义 JS
   c. 返回价格文本
4. AI 向用户报告价格
```

## 验收标准

必须通过（全部必填）：

- [ ] `browser` 工具已在 `ToolRegistry` 中注册，支持 `screenshot` 和 `extract` 两种模式
- [ ] `mode` 参数为必填且经过验证（仅接受 `"screenshot"` 或 `"extract"`）
- [ ] 截图模式能渲染页面并保存 PNG 文件
- [ ] 截图文件可被 AI 模型访问（以文件路径形式返回）
- [ ] 提取模式能加载页面、执行 JavaScript 并返回文本内容
- [ ] 提取模式能处理通过 JavaScript 渲染内容的 SPA 页面
- [ ] 默认提取脚本能找到主内容并转换为 Markdown
- [ ] 自定义 `javascript` 参数被执行，其返回值被使用
- [ ] `wait_seconds` 参数能控制页面加载后的等待时间
- [ ] WebView 在主线程上创建并得到妥善管理
- [ ] 每次工具调用后 WebView 被清理（调用之间无状态泄漏）
- [ ] URL 验证拒绝非 HTTP 协议
- [ ] 工具超时（60 秒）能防止页面加载无限挂起
- [ ] 所有 Layer 1A 测试通过

可选（V1 中锦上添花）：

- [ ] `full_page` 截图能捕获完整可滚动页面
- [ ] 视口尺寸可通过 `width` 和 `height` 参数配置
- [ ] 同一会话中多次工具调用可复用 WebView
- [ ] 截图格式可选（PNG 或 JPEG）

## UI/UX 要求

本功能不为用户新增任何 UI。WebView 处于离屏且不可见状态：
- 截图保存至缓存，并在聊天中以图像结果形式展示
- 提取结果以文本工具结果形式展示
- 不向用户展示任何浏览器窗口或 WebView

## 功能边界

### 包含

- 离屏 WebView 管理（`WebViewManager`）
- 通过 Canvas/Bitmap 捕获截图
- 通过 `evaluateJavascript()` 提取内容
- 在 WebView 中使用 Turndown 的内置提取脚本
- 提取模式中的自定义 JavaScript 执行
- 可配置的动态内容等待时间
- 提取模式的输出截断
- WebView 生命周期管理（创建、复用、清理、销毁）

### 不包含（V1）

- 交互式浏览（点击、滚动、填写表单）
- 单次工具调用内的多页面导航
- 跨工具调用的 Cookie/会话持久化
- 身份验证（登录流程）
- PDF 渲染或下载
- 视频/音频内容捕获
- 浏览器 DevTools 或网络检查
- 无头 Chrome 或外部浏览器集成
- 无障碍树提取

## 业务规则

1. `browser` 仅接受 HTTP 和 HTTPS URL
2. WebView 的 JavaScript 执行已启用（SPA 支持所需）
3. 自定义 `javascript` 参数在页面上下文中执行（可访问页面 DOM）
4. 截图以 PNG 文件形式保存在应用缓存目录中
5. 截图文件为临时文件，系统可能对其进行清理
6. WebView 在工具调用之间不持久化 Cookie、localStorage 或会话数据
7. 工具超时为 60 秒（由于渲染耗时，长于 `webfetch`）
8. WebView 在任何时候对用户均不可见

## 非功能性要求

### 性能

- 页面加载 + 截图：通常 3-8 秒（取决于页面复杂度）
- 页面加载 + 提取：通常 3-6 秒
- WebView 创建（冷启动）：约 500 毫秒
- WebView 复用（热启动）：< 100 毫秒额外开销
- 截图 PNG 压缩：视口尺寸图像 < 200 毫秒

### 内存

- WebView 进程：约 50-100MB（由 Android 系统管理，独立进程）
- 截图位图：约 5-15MB（视口尺寸，ARGB_8888）
- 全页截图：可能更大（高度上限为 10,000 像素）
- 不使用时销毁 WebView 以释放内存

### 兼容性

- 需要 Android API 24+（WebView 的 `evaluateJavascript` 及现代功能）
- WebView 版本取决于用户的系统 WebView 更新状态
- 大多数现代网页在 Android WebView 中可正常渲染

### 安全性

- WebView 的 JavaScript 已启用，但受 Android WebView 安全模型沙箱化
- `WebViewClient` 不向 Web 内容暴露 Android 接口（页面脚本不使用 `addJavascriptInterface`）
- 自定义 `javascript` 参数与页面脚本在同一沙箱中运行
- 不允许 file:// 或 content:// URL
- 每次使用后清除 WebView 缓存

## 依赖关系

### 依赖于

- **FEAT-004（工具系统）**：工具接口、注册表、执行引擎
- **FEAT-021（Kotlin Webfetch）**：建立静态 HTML 获取基线；浏览器工具对其进行补充

### 被依赖于

- 目前没有其他功能依赖 FEAT-022

### 外部依赖

- **Android WebView**：系统组件，无需额外依赖
- **Turndown.js**（约 20KB）：已作为 `assets/js/lib/turndown.min.js` 打包（来自 FEAT-015）。加载到 WebView 中用于 DOM 到 Markdown 的转换。

## 错误处理

### 错误场景

1. **无效 URL**
   - 原因：URL 格式错误或非 HTTP 协议
   - 处理：返回 `ToolResult.error("Invalid URL: <message>")`

2. **无效 mode**
   - 原因：`mode` 不是 `"screenshot"` 或 `"extract"`
   - 处理：返回 `ToolResult.error("Invalid mode. Use 'screenshot' or 'extract'")`

3. **页面加载失败**
   - 原因：网络错误、DNS 解析失败、SSL 错误
   - 处理：`WebViewClient.onReceivedError` 捕获错误；返回 `ToolResult.error("Page load failed: <message>")`

4. **页面加载超时**
   - 原因：页面在工具超时时间内始终未加载完成
   - 处理：取消加载，返回部分结果或错误

5. **JavaScript 执行错误**
   - 原因：自定义 JS 抛出异常或返回 undefined
   - 处理：返回 `ToolResult.error("JavaScript error: <message>")`

6. **截图捕获失败**
   - 原因：WebView 未渲染、位图分配失败
   - 处理：返回 `ToolResult.error("Screenshot capture failed: <message>")`

7. **WebView 不可用**
   - 原因：系统 WebView 未安装或已禁用（罕见）
   - 处理：返回 `ToolResult.error("WebView not available on this device")`

8. **内存溢出**
   - 原因：对超长页面进行全页截图
   - 处理：将全页高度上限设为 10,000 像素；回退到仅视口捕获

## 未来改进

- [ ] **交互模式**：支持点击、滚动和表单填写，实现多步骤 Web 交互
- [ ] **会话持久化**：允许 AI 在多次工具调用之间维持浏览器会话
- [ ] **网络检查**：捕获 XHR/fetch 请求和响应，用于调试
- [ ] **无障碍树**：提取无障碍树，实现对页面结构的深度理解
- [ ] **元素截图**：对特定 CSS 选择器而非整个页面进行截图
- [ ] **视频/GIF 捕获**：录制短暂动画或页面过渡效果
- [ ] **多标签页**：支持打开并切换多个页面

## 测试要点

### 功能测试

- 验证截图模式能生成有效的 PNG 文件
- 验证截图文件路径在工具结果中正确返回
- 验证提取模式能返回 Markdown 内容
- 验证提取模式能处理 JS 渲染的内容（模拟 SPA 页面）
- 验证自定义 `javascript` 参数被执行且结果被返回
- 验证 `wait_seconds` 参数能延迟提取操作
- 验证 URL 验证拒绝非 HTTP 协议
- 验证 `mode` 参数验证逻辑
- 验证工具调用后 WebView 得到清理（无状态泄漏）
- 验证工具超时生效（60 秒）

### 边界情况

- 含无限滚动的页面（截图默认仅捕获视口）
- 始终未加载完成的页面（超时处理）
- 含 SSL 证书错误的页面
- 含 HTTP Basic Auth 提示的页面
- 返回超大字符串的 `javascript` 参数
- 执行时间超过 wait_seconds 的 `javascript` 参数
- 并发浏览器工具调用（应排队或拒绝）
- 浏览器工具执行期间应用进入后台
- 渲染过程中 WebView 进程崩溃
- 含 `<meta http-equiv="refresh">` 重定向的页面

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|---------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
