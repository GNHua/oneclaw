# OneClaw Shadow 项目设计文档

## 文档目的

本文档详细说明OneClaw Shadow项目的设计理念、工作流程和最佳实践。这是一个实验性项目，旨在探索AI时代的软件开发新模式。

## 核心理念

### 1. 文档是唯一真理来源

在传统开发中，代码是真理来源。但在AI辅助开发时代，我们提出：

**文档（PRD + RFC）是唯一真理来源，代码是文档的一种实现**

这意味着：
- 任何功能必须先有文档，再有代码
- 代码可以随时根据文档重新生成
- 文档的变更驱动代码的变更
- 理解系统应该读文档，而不是读代码

### 2. 纯文档驱动的AI开发

传统AI辅助开发：人写代码 → AI补全/优化代码  
新模式：人写文档 → AI根据文档生成代码

关键约束：
- **AI不看现有代码**：避免继承技术债务
- **AI只读PRD和RFC**：保证实现完全符合设计
- **可重现性**：任何时候都可以从零重新生成

优势：
- 强制保持文档和代码同步
- 便于重构和技术栈迁移
- 减少技术债务累积
- 新人通过文档即可理解系统

### 3. 自动化验证消除人工瓶颈

你在之前的开发中发现的问题：**人工测试成为瓶颈**

解决方案：
- 用结构化格式（YAML）描述测试场景
- AI根据测试场景生成自动化测试代码
- CI/CD自动运行所有测试
- 人只需要编写测试场景，不需要手动执行

### 4. 可追溯的需求-设计-实现-测试链路

建立完整的可追溯性：
```
FEAT-001 (PRD)
  ↓
RFC-001 (技术设计)
  ↓
代码实现
  ↓
TEST-001 (测试场景)
  ↓
自动化测试代码
```

每个环节都有明确的对应关系，可以：
- 从功能反查设计文档
- 从代码反查需求文档
- 从测试反查功能需求

## 项目结构详解

### 文档中心 (docs/)

这是项目的核心，所有的需求和设计都在这里。

#### PRD目录结构
```
docs/prd/
├── 00-overview.md              # 产品总览
├── _template.md                # PRD模板
├── features/                   # 功能模块PRD
│   ├── FEAT-001-auth.md
│   ├── FEAT-002-cloud-storage.md
│   └── FEAT-003-file-management.md
└── versions/                   # 版本规划
    ├── v1.0-mvp.md
    └── v1.1-iteration.md
```

**PRD编写原则**：
- 描述"做什么"和"为什么"，不描述"怎么做"
- 用户视角，关注用户价值
- 明确范围：包含什么，不包含什么
- 可验证：有明确的验收标准

#### RFC目录结构
```
docs/rfc/
├── _template.md                # RFC模板
├── architecture/               # 架构设计
│   ├── 001-overall-architecture.md
│   ├── 002-data-layer.md
│   └── 003-ui-layer.md
└── features/                   # 功能实现方案
    ├── RFC-001-auth-implementation.md
    ├── RFC-002-cloud-storage-implementation.md
    └── RFC-003-file-management-implementation.md
```

**RFC编写原则**：
- 描述"怎么做"，技术实现细节
- 包含数据模型、API设计、代码示例
- 足够详细，AI可以直接实现
- 考虑性能、安全、扩展性

#### ADR（架构决策记录）
```
docs/adr/
├── _template.md
├── 001-use-jetpack-compose.md
├── 002-use-clean-architecture.md
└── 003-use-room-database.md
```

记录重要的技术决策：
- 为什么做这个决定
- 有哪些备选方案
- 各方案的优缺点
- 决策的影响和风险

### 测试中心 (tests/)

完整的测试体系，消除人工测试瓶颈。

#### 测试目录结构
```
tests/
├── plans/                      # 测试计划
│   ├── integration-test-plan.md
│   └── e2e-test-plan.md
├── scenarios/                  # 测试场景（YAML）
│   ├── _template.yaml
│   ├── TEST-001-login-flow.yaml
│   ├── TEST-002-file-upload.yaml
│   └── TEST-003-error-handling.yaml
├── unit/                       # 单元测试（生成的代码）
│   └── [AI生成的单元测试]
├── integration/                # 集成测试（生成的代码）
│   └── [AI生成的集成测试]
└── e2e/                       # 端到端测试（生成的代码）
    └── [AI生成的E2E测试]
```

**测试场景的作用**：
- 用YAML描述测试步骤和预期结果
- 人写场景，AI生成测试代码
- 场景文件即是测试文档，也是测试规范
- 可以在没有代码的情况下先设计测试

#### 三层测试策略

1. **单元测试**（Unit Tests）
   - 测试目标：独立的类和函数
   - 覆盖范围：ViewModel、UseCase、Repository
   - 覆盖率目标：> 80%
   - 执行速度：快（毫秒级）

2. **集成测试**（Integration Tests）
   - 测试目标：多个组件的交互
   - 覆盖范围：数据库操作、API调用、数据流
   - 执行速度：中等（秒级）

3. **端到端测试**（E2E Tests）
   - 测试目标：完整的用户流程
   - 覆盖范围：关键业务流程
   - 执行速度：慢（分钟级）
   - 工具：Jetpack Compose UI Test / Espresso

### 代码目录 (app/)

Android应用代码，由AI根据RFC生成。

采用Clean Architecture + MVVM：
```
app/src/main/kotlin/com/example/oneclaw/
├── data/           # 数据层
│   ├── local/      # 本地数据源（Room）
│   ├── remote/     # 远程数据源（Retrofit）
│   └── repository/ # Repository实现
├── domain/         # 领域层
│   ├── model/      # 领域模型
│   ├── repository/ # Repository接口
│   └── usecase/    # 用例
└── ui/             # UI层
    ├── theme/      # 主题
    ├── components/ # 通用组件
    └── features/   # 功能模块（Screen + ViewModel）
```

**代码组织原则**：
- 严格的分层架构
- 单向依赖：UI → Domain → Data
- 按功能模块化
- 高内聚低耦合

## 完整工作流

### 添加新功能的完整流程

#### 第1步：需求阶段
```
1. 创建PRD文档：docs/prd/features/FEAT-XXX-功能名.md
2. 使用PRD模板填写：
   - 用户故事
   - 功能描述
   - 验收标准
   - UI/UX要求
3. (可选) 让AI帮助细化PRD
4. Review PRD，确认需求明确
```

#### 第2步：设计阶段
```
1. 创建RFC文档：docs/rfc/features/RFC-XXX-功能名.md
2. 使用RFC模板填写：
   - 架构设计
   - 数据模型
   - API设计
   - 实现步骤
3. (可选) 让AI提供技术方案建议
4. Review RFC，确认设计可行
5. (如有重大技术决策) 创建ADR文档
```

#### 第3步：测试设计阶段
```
1. 创建测试场景：tests/scenarios/TEST-XXX-场景名.yaml
2. 描述测试步骤和预期结果
3. 包含正常流程和异常流程
4. Review测试场景，确保覆盖完整
```

#### 第4步：开发阶段
```
1. 给AI提供RFC文档
2. 明确告诉AI：只根据RFC实现，不参考现有代码
3. AI生成代码
4. Review代码的架构和关键逻辑
5. 提交代码
```

#### 第5步：测试实现阶段
```
1. 给AI提供测试场景YAML文件
2. AI根据场景生成测试代码：
   - 单元测试
   - 集成测试
   - UI测试
3. Review测试代码
4. 提交测试代码
```

#### 第6步：自动化验证阶段
```
1. 运行单元测试：./gradlew test
2. 运行集成测试：./gradlew connectedAndroidTest
3. 运行E2E测试：./scripts/run-e2e-tests.sh
4. 检查测试覆盖率：./gradlew jacocoTestReport
5. 所有测试通过 → 功能完成
```

#### 第7步：迭代和重构
```
如果需要修改：
1. 修改PRD或RFC文档
2. 给AI新的文档
3. AI重新生成代码
4. 运行自动化测试验证
5. 对比新旧实现的差异
```

### 重构整个应用的流程

这是你最关心的场景：根据文档重新生成整个应用

```
场景：现有应用技术债务太多，需要重构

步骤：
1. 确保PRD和RFC文档完整且最新
2. 创建新的代码分支或新目录
3. 给AI提供所有RFC文档
4. 明确告诉AI：从零开始实现，不看旧代码
5. AI生成全新的代码
6. 运行所有测试，验证功能完整性
7. 对比新旧实现：
   - 代码质量
   - 性能指标
   - 测试覆盖率
8. 决定是否采用新实现

优势：
- 完全消除技术债务
- 应用最新的技术栈
- 代码质量有保证（基于明确的RFC）
- 功能完整性有保证（自动化测试验证）
```

## ID和命名规范

### 功能ID规范
- 格式：`FEAT-XXX`
- 编号：从001开始，连续递增
- 示例：`FEAT-001`, `FEAT-002`

### RFC ID规范
- 格式：`RFC-XXX`
- 编号：从001开始，连续递增
- 命名：`RFC-XXX-简短描述.md`
- 示例：`RFC-001-auth-implementation.md`

### ADR ID规范
- 格式：`ADR-XXX`
- 编号：从001开始，连续递增
- 命名：`XXX-决策简述.md`
- 示例：`001-use-jetpack-compose.md`

### 测试ID规范
- 格式：`TEST-XXX`
- 编号：从001开始，连续递增
- 命名：`TEST-XXX-场景描述.yaml`
- 示例：`TEST-001-login-flow.yaml`

## 文档编写最佳实践

### PRD最佳实践

✅ **好的PRD**：
```markdown
## 用户故事
作为一个用户，我想要能够登录应用，以便访问我的个人数据。

## 验收标准
- [ ] 用户可以使用邮箱和密码登录
- [ ] 错误的密码会显示错误提示
- [ ] 登录成功后跳转到主页
- [ ] 登录状态持久保存
```

❌ **不好的PRD**：
```markdown
实现一个登录功能，使用JWT token，保存在SharedPreferences里。
```
（这是技术实现细节，应该在RFC中）

### RFC最佳实践

✅ **好的RFC**：
```kotlin
// 明确的数据模型
data class User(
    val id: String,
    val email: String,
    val name: String
)

// 明确的接口定义
interface AuthRepository {
    suspend fun login(email: String, password: String): Result<User>
}

// 明确的实现步骤
1. 创建LoginViewModel
2. 创建LoginUseCase
3. 创建AuthRepository实现
4. 创建LoginScreen UI
```

❌ **不好的RFC**：
```markdown
实现登录功能，具体实现参考现有代码。
```
（不够具体，AI无法实现）

### 测试场景最佳实践

✅ **好的测试场景**：
```yaml
steps:
  - action: "输入邮箱"
    target: "email_field"
    data: "test@example.com"
    expected:
      - "邮箱输入框显示test@example.com"
    verification:
      - type: "text_equals"
        target: "email_field"
        value: "test@example.com"
```

❌ **不好的测试场景**：
```yaml
steps:
  - action: "测试登录"
    expected: "能登录"
```
（不够具体，无法自动化）

## 工具和脚本

### 开发脚本 (scripts/)

```
scripts/
├── setup.sh              # 项目初始化
├── build.sh              # 构建应用
├── run-tests.sh          # 运行所有测试
├── generate-docs.sh      # 生成文档
└── check-coverage.sh     # 检查测试覆盖率
```

### 文档工具 (tools/)

```
tools/
├── validate-docs.py      # 验证文档格式和完整性
├── generate-test.py      # 从YAML生成测试代码
└── doc-graph.py          # 生成文档依赖关系图
```

## CI/CD集成

### GitHub Actions工作流

```yaml
# .github/workflows/ci.yml
name: CI

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
      - name: Setup JDK
      - name: Run unit tests
      - name: Run integration tests
      - name: Generate coverage report
      - name: Upload coverage to Codecov
  
  validate-docs:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
      - name: Validate PRD/RFC format
      - name: Check traceability
```

## 度量和改进

### 关键度量指标

1. **文档质量**
   - PRD完整性：是否所有必填项都填写
   - RFC详细度：是否足够AI实现
   - 文档覆盖率：有多少功能有PRD和RFC

2. **测试质量**
   - 代码覆盖率：> 80%
   - 测试场景覆盖率：关键流程是否都有测试
   - 自动化率：多少测试是自动化的

3. **开发效率**
   - 从PRD到可用功能的时间
   - 重构时间：根据文档重构的时间
   - Bug率：生产环境Bug数量

4. **可重现性**
   - 能否根据文档重新生成代码
   - 新人理解系统的时间

### 持续改进

定期Review：
- PRD模板是否需要调整
- RFC模板是否足够详细
- 测试场景是否易于编写
- AI生成的代码质量如何

## 实验目标

这个项目的实验目标：

1. **验证纯文档驱动开发的可行性**
   - 能否仅凭文档让AI实现完整应用
   - 文档需要多详细AI才能理解

2. **验证重构的便利性**
   - 能否快速根据文档重构应用
   - 新旧实现的质量对比

3. **验证自动化测试的效果**
   - 自动化测试能否替代人工测试
   - 测试场景是否易于编写和维护

4. **总结最佳实践**
   - 文档编写的最佳实践
   - AI协作的最佳方式
   - 工作流的优化建议

## 下一步

1. 分析现有OneClaw项目，提取核心功能
2. 为每个核心功能编写PRD
3. 为每个核心功能编写RFC
4. 设计测试场景
5. 让AI根据RFC实现代码
6. 对比新旧实现

## 参考资料

- [Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)
- [Android Architecture Guide](https://developer.android.com/topic/architecture)
- [RFC Process](https://en.wikipedia.org/wiki/Request_for_Comments)
- [Architecture Decision Records](https://adr.github.io/)
