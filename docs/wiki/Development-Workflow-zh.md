# 开发工作流

OneClaw 遵循文档驱动的开发方式。文档是唯一的事实来源——代码由 RFC 生成，随时可以完整重新生成。

## 核心理念

1. **文档即事实来源** -- 代码可以重写；需求文档和设计文档持久保存
2. **纯文档驱动 AI 开发** -- AI 根据 PRD 和 RFC 生成代码，不参考已有实现
3. **自动化验证** -- 全面的测试保障质量
4. **可复现性** -- 应用可以从文档重新生成

## 工作流：PRD -> RFC -> 代码 -> 测试

### 1. 需求阶段（PRD）

在 `docs/prd/features/FEAT-XXX-feature-name.md` 创建产品需求文档。

PRD 定义：
- 功能 ID 与元数据
- 用户故事（作为……，我希望……，以便……）
- 典型使用场景
- 功能描述（概述 + 详细规格）
- 验收标准

### 2. 设计阶段（RFC）

在 `docs/rfc/features/RFC-XXX-feature-name.md` 创建技术设计文档。

RFC 定义：
- 技术方案与架构
- API 接口与数据结构
- 数据库 Schema 变更
- UI 组件规格
- 足以用于代码生成的实现细节

### 3. 开发阶段（代码）

基于 RFC 生成代码。RFC 应包含足够的细节，使 AI 无需查看现有代码即可完成实现。

关键约定：
- 遵循 Clean Architecture 分层（core -> data -> feature）
- 对可能失败的操作使用 `AppResult<T>`
- 将新组件注册到对应的 Koin 模块
- 为新页面添加导航路由

### 4. 测试阶段

实现 RFC 后，执行测试流程：

1. **Layer 1A** -- `./gradlew test`（所有 JVM 测试必须通过）
2. **Layer 1B** -- `ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest`（无模拟器时跳过）
3. **Layer 1C** -- `./gradlew verifyRoborazziDebug`（UI 变更时执行）
4. **Layer 2** -- 手动 adb 验证流程
5. **编写测试报告** -- `docs/testing/reports/RFC-XXX-<name>-report.md`
6. **更新手动测试指南** -- `docs/testing/manual-test-guide.md`

## ID 体系

| 前缀 | 用途 | 示例 |
|------|------|------|
| `FEAT-XXX` | 产品需求 | `FEAT-001`（对话交互） |
| `RFC-XXX` | 技术设计 | `RFC-001`（对话交互） |
| `ADR-XXX` | 架构决策 | `ADR-001` |
| `TEST-XXX` | 测试场景 | `TEST-001` |

功能 ID 与 RFC ID 通常共用同一编号（FEAT-001 对应 RFC-001）。

## 双语文档

所有文档必须同时提供两种语言版本：
- **英文：** `filename.md`
- **中文：** `filename-zh.md`

工作流：先撰写英文版本，再翻译为中文。两个版本必须保持同步。

## 新增功能

1. 分配下一个可用的 `FEAT-XXX` ID
2. 撰写 `docs/prd/features/FEAT-XXX-feature-name.md`（英文）
3. 翻译为 `docs/prd/features/FEAT-XXX-feature-name-zh.md`（中文）
4. 撰写 `docs/rfc/features/RFC-XXX-feature-name.md`（英文）
5. 翻译为 `docs/rfc/features/RFC-XXX-feature-name-zh.md`（中文）
6. 基于 RFC 实现代码
7. 执行测试流程
8. 编写测试报告（英文 + 中文）

## 修改已有功能

1. 更新 PRD 中的新需求
2. 更新 RFC 中的修订技术设计
3. 重新生成或修改代码
4. 执行测试流程
5. 更新测试报告

## 文档模板

模板位于：
- PRD：`docs/prd/_template.md`
- RFC：`docs/rfc/_template.md`
- ADR：`docs/adr/_template.md`

## 目录结构

```
docs/
├── prd/
│   ├── _template.md
│   ├── 00-overview.md
│   └── features/
│       ├── FEAT-001-chat.md
│       ├── FEAT-001-chat-zh.md
│       └── ...
├── rfc/
│   ├── _template.md
│   ├── architecture/
│   │   └── RFC-000-*.md
│   └── features/
│       ├── RFC-001-chat-interaction.md
│       ├── RFC-001-chat-interaction-zh.md
│       └── ...
├── adr/
│   └── ADR-*.md
├── testing/
│   ├── strategy.md
│   ├── manual-test-guide.md
│   └── reports/
│       └── RFC-XXX-*-report.md
└── wiki/
    └── （本文档）
```
