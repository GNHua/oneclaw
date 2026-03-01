# PDF 工具

## 功能信息
- **功能 ID**: FEAT-033
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **优先级**: P1（应有）
- **负责人**: 待定
- **关联 RFC**: RFC-033

## 用户故事

**作为** 使用 OneClawShadow 的 AI 智能体，
**我希望** 拥有能够读取 PDF 文件的工具——包括提取元数据、文本内容以及将页面渲染为图片，
**以便** 帮助用户理解、摘要和分析设备上存储的 PDF 文档。

### 典型场景

1. 用户共享一篇研究论文 PDF 并请智能体对其进行摘要。智能体调用 `pdf_info` 检查页数，再调用 `pdf_extract_text` 获取文本内容，最终生成摘要。
2. 用户有一个没有文本层的扫描版发票 PDF。智能体调用 `pdf_extract_text`，得到"未找到文本内容"的响应，随后调用 `pdf_render_page` 将页面渲染为图片以进行视觉检查。
3. 用户请智能体提取一份长篇 PDF 报告中的特定页面（例如"摘要第 10-15 页"）。智能体使用带 `pages` 参数的 `pdf_extract_text` 仅提取所需范围的内容。
4. 用户希望了解某份文档的作者和创建日期。智能体调用 `pdf_info` 获取文档元数据。

## 功能描述

### 概述

FEAT-033 以 Kotlin 内置工具的形式为 OneClawShadow 新增三个 PDF 相关工具，使 AI 智能体能够处理设备上存储的 PDF 文件。这三个工具移植自 OneClaw 1.0 的 `lib-pdf` 插件，并适配到 OneClawShadow 的工具架构中。

三个工具分别为：
- **`pdf_info`** -- 获取 PDF 元数据（页数、文件大小、标题、作者等）
- **`pdf_extract_text`** -- 从 PDF 文件中提取文本内容，支持可选的页面范围选择
- **`pdf_render_page`** -- 将 PDF 页面渲染为 PNG 图片以供视觉检查

### 架构概述

```
AI Model
    | tool call: pdf_info(path="...") / pdf_extract_text(...) / pdf_render_page(...)
    v
ToolExecutionEngine  (unchanged)
    |
    v
ToolRegistry
    |
    v
PdfInfoTool / PdfExtractTextTool / PdfRenderPageTool  [NEW - Kotlin built-in tools]
    |
    +-- PDFBox Android (text extraction, metadata reading)
    |
    +-- Android PdfRenderer (page rendering to bitmap)
    |
    +-- PdfToolUtils [NEW - shared path resolution, page range parsing]
```

### 工具定义

#### pdf_info

| 字段 | 值 |
|------|----|
| 名称 | `pdf_info` |
| 描述 | 获取 PDF 文件的元数据和基本信息 |
| 参数 | `path`（string，必填）：PDF 文件路径 |
| 所需权限 | `READ_EXTERNAL_STORAGE` |
| 超时时间 | 15 秒 |
| 返回值 | 包含页数、文件大小、标题、作者及其他文档属性的文本 |

#### pdf_extract_text

| 字段 | 值 |
|------|----|
| 名称 | `pdf_extract_text` |
| 描述 | 从 PDF 文件中提取文本内容 |
| 参数 | `path`（string，必填）：PDF 文件路径 |
| | `pages`（string，可选）：页面范围（例如 "1-5"、"3"、"1,3,5-7"）。省略则提取全部页面 |
| | `max_chars`（integer，可选）：最多返回的字符数。默认：50000 |
| 所需权限 | `READ_EXTERNAL_STORAGE` |
| 超时时间 | 30 秒 |
| 返回值 | 提取的文本，标头包含文件名、页面范围和总页数信息 |

#### pdf_render_page

| 字段 | 值 |
|------|----|
| 名称 | `pdf_render_page` |
| 描述 | 将 PDF 页面渲染为 PNG 图片 |
| 参数 | `path`（string，必填）：PDF 文件路径 |
| | `page`（integer，必填）：要渲染的页码（从 1 开始） |
| | `dpi`（integer，可选）：渲染分辨率，单位 DPI（默认 150，范围 72-300） |
| 所需权限 | `READ_EXTERNAL_STORAGE` |
| 超时时间 | 30 秒 |
| 返回值 | 包含输出文件路径、分辨率和文件大小的文本 |

### 用户交互流程

```
1. 用户："帮我摘要这份 PDF"
   （用户已通过文件附件共享 PDF，或文件位于可访问的存储路径中）
2. AI 调用 pdf_info(path="/sdcard/Documents/report.pdf")
3. pdf_info 返回："Pages: 42, Title: Annual Report 2025, ..."
4. AI 调用 pdf_extract_text(path="/sdcard/Documents/report.pdf", max_chars=80000)
5. pdf_extract_text 返回：提取的文本内容
6. AI 根据提取的文本生成摘要
```

```
1. 用户："这份扫描版 PDF 的第 3 页是什么内容？"
2. AI 调用 pdf_extract_text(path="...", pages="3")
3. 返回："No text content found. This may be a scanned document."
4. AI 调用 pdf_render_page(path="...", page=3, dpi=200)
5. 返回："Page 3 rendered and saved to: pdf-renders/document-page3.png"
6. AI 利用渲染后的图片进行视觉分析
```

## 验收标准

必须通过（全部必填）：

- [ ] TEST-033-01：`pdf_info` 作为 Kotlin 内置工具注册到 `ToolRegistry`
- [ ] TEST-033-02：`pdf_extract_text` 作为 Kotlin 内置工具注册到 `ToolRegistry`
- [ ] TEST-033-03：`pdf_render_page` 作为 Kotlin 内置工具注册到 `ToolRegistry`
- [ ] TEST-033-04：`pdf_info` 返回页数、文件大小及可用的元数据字段
- [ ] TEST-033-05：`pdf_extract_text` 从基于文本的 PDF 中返回文本内容
- [ ] TEST-033-06：`pdf_extract_text` 支持页面范围选择（"1-5"、"3"、"1,3,5-7"）
- [ ] TEST-033-07：`pdf_extract_text` 对无文本层的扫描版 PDF 返回友好提示信息
- [ ] TEST-033-08：`pdf_extract_text` 在 `max_chars` 处截断输出并附带截断说明
- [ ] TEST-033-09：`pdf_render_page` 将指定页面渲染为 PNG 图片
- [ ] TEST-033-10：`pdf_render_page` 将输出保存到应用内部的 `pdf-renders/` 目录
- [ ] TEST-033-11：`pdf_render_page` 遵守 `dpi` 参数（限制在 72-300 范围内）
- [ ] TEST-033-12：三个工具对文件缺失、无效参数及页码超范围情况均返回适当的错误信息
- [ ] TEST-033-13：所有 Layer 1A 测试通过

可选（锦上添花）：

- [ ] `pdf_extract_text` 支持从受密码保护的 PDF 中提取文本（通过 `password` 参数）
- [ ] `pdf_render_page` 支持一次渲染多个页面

## UI/UX 要求

本功能不新增任何 UI 界面。工具注册到工具系统后可供 AI 智能体使用：
- 工具名称显示在工具管理页面（FEAT-017）
- 工具调用结果显示在聊天视图中（FEAT-001）

## 功能边界

### 包含

- 三个 Kotlin 内置工具：`PdfInfoTool`、`PdfExtractTextTool`、`PdfRenderPageTool`
- 共享工具类：`PdfToolUtils`（路径解析、页面范围解析）
- 在 `build.gradle.kts` 中新增 PDFBox Android 依赖
- 在 `OneclawApplication` 或 Koin 模块中初始化 PDFBox
- 更新 `ToolModule` 以注册三个工具
- 渲染页面的 PNG 输出目录管理

### 不包含（V1）

- PDF 创建或编辑
- PDF 批注
- 扫描版 PDF 的 OCR（渲染后的图片可由具有视觉能力的模型处理）
- PDF 表单填写
- PDF 签名或加密
- 受密码保护的 PDF 支持
- PDF 转 Markdown（超出原始文本提取范围）

## 业务规则

1. 三个工具均需要指向现有 PDF 文件的有效路径
2. `pdf_extract_text` 若省略 `pages` 参数，默认提取全部页面
3. `pdf_extract_text` 若未指定 `max_chars`，默认字符限制为 50,000
4. `pdf_render_page` 使用从 1 开始的页码编号（第 1 页为第一页）
5. `pdf_render_page` 的 DPI 无论输入值如何，均限制在 72-300 范围内
6. 渲染后的 PNG 文件保存到应用内部存储（`filesDir/pdf-renders/`）
7. 渲染后的 PNG 文件名遵循 `{basename}-page{N}.png` 格式

## 非功能性需求

### 性能

- `pdf_info`：典型 PDF（< 50MB）< 500ms
- `pdf_extract_text`：典型 PDF（< 100 页）< 2s
- `pdf_render_page`：150 DPI 下每页 < 3s
- 内存：PDDocument 对象在使用后立即关闭，防止内存泄漏

### 安全性

- 文件路径校验，防止访问受限制的系统目录
- PDF 渲染输出限定在应用内部存储中
- 无网络访问——所有操作均为本地文件操作
- PDFBox 解析器可安全处理恶意 PDF（不执行脚本）

### 兼容性

- 需要 Android API 21+（PdfRenderer 需要 API 21）
- PDFBox Android 兼容所有受支持的 Android 版本
- 同时支持基于文本和扫描版的 PDF（对扫描版提供优雅降级）

## 依赖关系

### 依赖于

- **FEAT-004（Tool System）**：工具接口、注册表、执行引擎
- **FEAT-025（File Browsing）**：文件系统访问模式
- **FEAT-026（File Attachments）**：用户可将 PDF 文件共享至应用

### 被依赖于

- 目前没有其他功能依赖 FEAT-033

### 外部依赖

- **PDFBox Android**（`com.tom-roush:pdfbox-android:2.0.27.0`）：PDF 文本提取和元数据读取。Apache 2.0 许可证。
- **Android PdfRenderer**：内置于 Android 框架（API 21+）。用于将页面渲染为位图。

## 错误处理

### 错误场景

1. **文件未找到**
   - 原因：路径指向不存在的文件
   - 处理：返回 `ToolResult.error("file_not_found", "File not found: <path>")`

2. **无效 PDF**
   - 原因：文件存在但不是有效的 PDF 文档
   - 处理：返回 `ToolResult.error("invalid_pdf", "Failed to read PDF: <message>")`

3. **页码超出范围**
   - 原因：请求的页码超过文档总页数
   - 处理：返回 `ToolResult.error("invalid_page", "Page N out of range (document has M pages)")`

4. **无效页面范围**
   - 原因：页面范围字符串格式错误（例如 "abc"、"5-2"）
   - 处理：返回 `ToolResult.error("invalid_page_range", "Invalid page range: <spec>")`

5. **权限被拒绝**
   - 原因：应用缺少 READ_EXTERNAL_STORAGE 权限
   - 处理：ToolExecutionEngine 在执行前请求权限

6. **内存不足**
   - 原因：PDF 文件过大或 DPI 渲染分辨率过高
   - 处理：由异常处理器捕获；返回错误并建议使用较低 DPI

## 测试要点

### 功能测试

- 验证 `pdf_info` 对已知 PDF 返回正确的页数
- 验证 `pdf_info` 返回可用的元数据字段（标题、作者等）
- 验证 `pdf_extract_text` 从基于文本的 PDF 中提取正确文本
- 验证 `pdf_extract_text` 带 `pages="1-3"` 时仅提取第 1-3 页
- 验证 `pdf_extract_text` 带 `pages="2"` 时仅提取第 2 页
- 验证 `pdf_extract_text` 带 `pages="1,3,5-7"` 时提取指定页面
- 验证 `pdf_extract_text` 在 `max_chars` 处截断并附带截断说明
- 验证 `pdf_extract_text` 对纯图片 PDF 返回扫描文档提示信息
- 验证 `pdf_render_page` 在预期路径创建 PNG 文件
- 验证 `pdf_render_page` 遵守 DPI 参数
- 验证 `pdf_render_page` 将 DPI 限制在 72-300 范围内

### 边界情况

- 页数为 0 的 PDF（文件损坏）
- 页数超过 1000 页的 PDF
- 无元数据字段的 PDF
- 大于 100MB 的 PDF
- 含复杂 Unicode 文本的 PDF（CJK、阿拉伯语等）
- 页面范围 "1-1"（单页范围）
- 含空格的页面范围 "1 - 5"
- `max_chars` 设为 0
- DPI 设为 0 或负数
- DPI 设为 1000（应限制到 300）
- 含空格和特殊字符的文件路径

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|---------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
