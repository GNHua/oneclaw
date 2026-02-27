# 开发工作流指南

本文档提供详细的工作流程指导，帮助你按照文档驱动的方式开发OneClaw应用。

## 快速开始

### 第一次使用

```bash
# 1. 进入项目目录
cd oneclaw-shadow-1

# 2. 阅读项目设计文档
cat docs/00-project-design.md

# 3. 查看PRD总览
cat docs/prd/00-overview.md

# 4. 准备开始第一个功能
# 使用PRD模板
cp docs/prd/_template.md docs/prd/features/FEAT-001-your-feature.md
```

## 工作流详解

### 流程1：添加新功能

#### 阶段1：需求定义（人工 + AI辅助）

**输入**：功能想法  
**输出**：完整的PRD文档

**步骤**：

1. **创建PRD文档**
   ```bash
   # 使用模板创建新的PRD
   cp docs/prd/_template.md docs/prd/features/FEAT-XXX-功能名.md
   ```

2. **填写基本信息**
   - 功能ID：FEAT-XXX（按序号递增）
   - 功能名称：简短清晰的名称
   - 优先级：P0/P1/P2
   - 状态：草稿

3. **编写用户故事**
   ```markdown
   作为 [用户角色]
   我想要 [具体功能]
   以便 [达成目标]
   ```
   
   技巧：
   - 从用户视角思考
   - 关注价值而非实现
   - 一个用户故事解决一个问题

4. **编写功能描述**
   - 用1-2段话概述功能
   - 详细列出功能点
   - 描述用户交互流程
   
   技巧：
   - 使用"用户可以..."的句式
   - 避免技术术语
   - 可以包含简单的流程图

5. **定义验收标准**
   ```markdown
   - [ ] 用户可以通过XX方式完成YY
   - [ ] 当ZZ条件时，系统应该AA
   - [ ] 界面显示BB信息
   ```
   
   技巧：
   - 每条标准都可验证
   - 使用具体的、可观察的行为
   - 包含正常和异常情况

6. **描述UI/UX要求**
   - 界面布局
   - 交互方式
   - 视觉样式
   - （如有设计稿）添加链接
   
7. **明确功能边界**
   ```markdown
   包含：
   - ✅ 功能点A
   - ✅ 功能点B
   
   不包含：
   - ❌ 功能点C（留到下个版本）
   - ❌ 功能点D（不在产品范围内）
   ```

8. **（可选）AI辅助细化**
   - 把你的PRD草稿给AI
   - 让AI帮助：
     - 发现遗漏的场景
     - 补充边界情况
     - 完善验收标准
     - 检查逻辑一致性

9. **Review和批准**
   - 自己Review一遍
   - 确认需求清晰明确
   - 更新状态为"已批准"

**检查清单**：
- [ ] 功能ID已分配
- [ ] 用户故事清晰
- [ ] 验收标准完整且可验证
- [ ] 功能边界明确
- [ ] UI/UX要求清晰
- [ ] 依赖关系已标注

---

#### 阶段2：技术设计（人工 + AI辅助）

**输入**：已批准的PRD  
**输出**：完整的RFC文档

**步骤**：

1. **创建RFC文档**
   ```bash
   cp docs/rfc/_template.md docs/rfc/features/RFC-XXX-功能名.md
   ```

2. **填写基本信息**
   - RFC编号：RFC-XXX（与FEAT编号对应或独立编号）
   - 关联PRD：FEAT-XXX
   - 状态：草稿

3. **设计整体架构**
   - 画出架构图（ASCII或描述）
   - 列出核心组件
   - 说明组件之间的关系
   
   示例：
   ```
   UI层：LoginScreen + LoginViewModel
   领域层：LoginUseCase
   数据层：AuthRepository + RemoteDataSource
   ```

4. **设计数据模型**
   ```kotlin
   // 详细定义所有数据类
   data class User(
       val id: String,
       val email: String,
       val name: String,
       val createdAt: Long
   )
   ```
   
   技巧：
   - 包含所有字段和类型
   - 添加注释说明字段含义
   - 考虑可选字段和默认值

5. **设计API接口**
   
   内部API（应用内）：
   ```kotlin
   interface AuthRepository {
       suspend fun login(email: String, password: String): Result<User>
       suspend fun logout(): Result<Unit>
   }
   ```
   
   外部API（后端）：
   ```
   POST /api/auth/login
   Request: { "email": "...", "password": "..." }
   Response: { "token": "...", "user": {...} }
   ```
   
   技巧：
   - 定义完整的请求和响应格式
   - 包含错误响应
   - 说明认证方式

6. **设计UI层**
   ```kotlin
   // 定义UiState
   data class LoginUiState(
       val email: String = "",
       val password: String = "",
       val isLoading: Boolean = false,
       val error: String? = null
   )
   
   // 定义ViewModel
   class LoginViewModel : ViewModel() {
       // 详细的实现逻辑
   }
   ```

7. **规划实现步骤**
   ```markdown
   Phase 1: 数据层
   1. [ ] 创建User数据类
   2. [ ] 创建AuthRepository接口
   3. [ ] 实现RemoteDataSource
   4. [ ] 实现AuthRepositoryImpl
   
   Phase 2: 领域层
   1. [ ] 创建LoginUseCase
   
   Phase 3: UI层
   1. [ ] 创建LoginUiState
   2. [ ] 创建LoginViewModel
   3. [ ] 创建LoginScreen
   ```

8. **定义错误处理策略**
   ```kotlin
   sealed class Result<out T> {
       data class Success<T>(val data: T) : Result<T>()
       data class Error(val exception: Exception) : Result<Nothing>()
   }
   ```

9. **考虑非功能性需求**
   - 性能：响应时间、并发处理
   - 安全：数据加密、权限控制
   - 可测试性：如何mock依赖

10. **（可选）AI辅助设计**
    - 给AI看PRD和初步的RFC
    - 让AI：
      - 建议技术方案
      - 指出潜在问题
      - 优化数据结构
      - 补充实现细节

11. **Review和批准**
    - 检查设计的完整性
    - 确认AI可以直接实现
    - 更新状态为"已批准"

**检查清单**：
- [ ] RFC编号已分配
- [ ] 关联PRD已标注
- [ ] 架构设计清晰
- [ ] 数据模型完整
- [ ] API设计明确
- [ ] UI层设计详细
- [ ] 实现步骤清晰
- [ ] 错误处理完善
- [ ] 技术选型有依据

---

#### 阶段3：测试设计（人工）

**输入**：已批准的PRD和RFC  
**输出**：测试场景YAML文件

**步骤**：

1. **创建测试场景文件**
   ```bash
   cp tests/scenarios/_template.yaml tests/scenarios/TEST-XXX-场景名.yaml
   ```

2. **填写元信息**
   ```yaml
   metadata:
     scenario_id: TEST-XXX
     feature_id: FEAT-XXX
     rfc_id: RFC-XXX
     name: "用户登录流程"
     type: e2e  # unit/integration/e2e
     priority: P0
   ```

3. **定义测试数据**
   ```yaml
   test_data:
     valid_user:
       email: "test@example.com"
       password: "Test123456"
     invalid_user:
       email: "wrong@example.com"
       password: "wrongpass"
   ```

4. **编写主场景（Happy Path）**
   ```yaml
   scenarios:
     - name: "正常登录流程"
       steps:
         - step_id: 1
           action: "打开应用"
           expected:
             - "显示登录界面"
           verification:
             - type: "screen_visible"
               target: "LoginScreen"
         
         - step_id: 2
           action: "输入用户名和密码"
           data: "${test_data.valid_user}"
           expected:
             - "输入框显示内容"
         
         - step_id: 3
           action: "点击登录按钮"
           expected:
             - "成功跳转到主页"
           verification:
             - type: "screen_visible"
               target: "HomeScreen"
   ```

5. **编写异常场景**
   - 错误的输入
   - 网络错误
   - 边界条件
   - 并发情况
   
   每个PRD中的验收标准应该对应至少一个测试场景

6. **定义性能标准**
   ```yaml
   performance_criteria:
     - metric: "login_response_time"
       threshold: 2000  # ms
   ```

7. **Review测试场景**
   - 是否覆盖所有验收标准
   - 是否包含异常情况
   - 是否可以自动化

**检查清单**：
- [ ] 测试场景ID已分配
- [ ] 关联FEAT和RFC
- [ ] 测试数据完整
- [ ] 主流程覆盖
- [ ] 异常情况覆盖
- [ ] 验证方式明确
- [ ] 性能标准定义

---

#### 阶段4：AI开发实现

**输入**：已批准的RFC  
**输出**：实现代码

**步骤**：

1. **准备AI提示词**
   ```
   我需要你根据RFC文档实现代码。
   
   重要约束：
   - 只根据RFC实现，不要参考任何现有代码
   - 严格遵循RFC中的数据模型和接口定义
   - 使用RFC中指定的技术栈
   - 遵循Clean Architecture + MVVM架构
   
   RFC文档：
   [粘贴RFC-XXX的完整内容]
   
   请按照RFC中的实现步骤，逐步实现代码。
   ```

2. **分阶段让AI实现**
   
   不要一次性让AI实现所有代码，而是：
   
   ```
   Phase 1: 先实现数据层
   请实现：
   - User数据类
   - AuthRepository接口
   - RemoteDataSource
   - AuthRepositoryImpl
   ```
   
   Review代码后，继续：
   
   ```
   Phase 2: 实现领域层
   请实现：
   - LoginUseCase
   ```
   
   最后：
   
   ```
   Phase 3: 实现UI层
   请实现：
   - LoginUiState
   - LoginViewModel
   - LoginScreen
   ```

3. **Review生成的代码**
   
   重点检查：
   - [ ] 是否遵循RFC的设计
   - [ ] 是否使用正确的架构分层
   - [ ] 是否有适当的错误处理
   - [ ] 是否有必要的注释
   - [ ] 代码风格是否一致

4. **运行代码**
   ```bash
   ./gradlew build
   ./gradlew installDebug
   ```

5. **如果有问题**
   - 不要直接让AI改代码
   - 而是回到RFC，看是否需要更新RFC
   - 更新RFC后，重新生成代码

---

#### 阶段5：AI生成测试代码

**输入**：测试场景YAML + 实现代码  
**输出**：自动化测试代码

**步骤**：

1. **让AI生成单元测试**
   ```
   请根据以下测试场景生成单元测试代码：
   
   [粘贴TEST-XXX.yaml的内容]
   
   要求：
   - 使用JUnit 5
   - 使用MockK进行mock
   - 测试ViewModel和UseCase
   - 覆盖正常和异常情况
   ```

2. **让AI生成集成测试**
   ```
   请生成集成测试，测试数据层的功能：
   - 使用Room的测试工具
   - 使用MockWebServer测试API调用
   ```

3. **让AI生成UI测试**
   ```
   请根据测试场景生成UI测试：
   - 使用Jetpack Compose Test
   - 测试完整的用户流程
   ```

4. **Review测试代码**
   - [ ] 测试是否覆盖所有场景
   - [ ] 断言是否正确
   - [ ] Mock是否合理

5. **运行测试**
   ```bash
   # 单元测试
   ./gradlew test
   
   # 集成测试
   ./gradlew connectedAndroidTest
   
   # 查看覆盖率
   ./gradlew jacocoTestReport
   open app/build/reports/jacoco/html/index.html
   ```

---

#### 阶段6：验证和交付

**步骤**：

1. **运行所有测试**
   ```bash
   ./scripts/run-tests.sh
   ```

2. **检查测试覆盖率**
   - 目标：> 80%
   - 重点覆盖业务逻辑

3. **手动验证关键流程**
   - 虽然有自动化测试，但关键流程还是手动测一次
   - 主要看UI/UX是否符合预期

4. **更新文档状态**
   - PRD状态 → "已完成"
   - RFC状态 → "已完成"
   - 添加完成日期

5. **提交代码**
   ```bash
   git add .
   git commit -m "feat: 实现XXX功能 (FEAT-XXX)"
   git push
   ```

---

### 流程2：修改现有功能

当需求变更或发现问题时：

1. **更新PRD**
   - 修改功能描述或验收标准
   - 在变更历史中记录修改
   - 更新版本号

2. **更新RFC**
   - 根据PRD的变更调整技术方案
   - 在变更历史中记录修改

3. **更新测试场景**
   - 添加新的测试场景
   - 修改现有场景

4. **让AI重新生成代码**
   - 给AI新的RFC
   - 明确告诉AI：重新实现这个功能
   - 不要增量修改，而是重新生成

5. **运行测试验证**
   - 所有测试应该仍然通过
   - 新的测试场景应该通过

6. **对比新旧实现**
   ```bash
   # 如果需要对比
   git diff old-branch new-branch -- path/to/feature
   ```

---

### 流程3：重构整个应用

这是你最感兴趣的场景：根据文档完全重写应用

**场景**：现有代码技术债务太多，或者想换技术栈

**步骤**：

1. **确保文档完整**
   ```bash
   # 检查所有功能是否都有PRD和RFC
   ls docs/prd/features/
   ls docs/rfc/features/
   ```
   
   如果有功能缺文档：
   - 先补充PRD和RFC
   - 可以从现有代码反向工程

2. **创建新的实现分支或目录**
   ```bash
   # 方案A：新分支
   git checkout -b refactor-v2
   
   # 方案B：新目录（推荐用于实验）
   mkdir app-v2
   ```

3. **准备AI提示词**
   ```
   我需要你根据一系列RFC文档，从零实现一个Android应用。
   
   重要约束：
   - 不要参考任何现有代码
   - 严格按照RFC实现
   - 使用最新的技术栈和最佳实践
   
   项目结构：
   [粘贴目标目录结构]
   
   技术栈：
   - Kotlin 1.9.x
   - Jetpack Compose
   - Clean Architecture + MVVM
   - [其他技术栈]
   
   我会逐步提供每个功能的RFC，请依次实现。
   ```

4. **按模块实现**
   
   ```
   Step 1: 实现基础设施
   RFC: docs/rfc/architecture/001-overall-architecture.md
   请实现：
   - 项目结构
   - 依赖注入配置
   - 网络层配置
   - 数据库配置
   ```
   
   ```
   Step 2: 实现认证模块
   RFC: docs/rfc/features/RFC-001-auth-implementation.md
   请实现完整的认证模块
   ```
   
   ```
   Step 3: 实现云存储模块
   RFC: docs/rfc/features/RFC-002-cloud-storage-implementation.md
   请实现完整的云存储模块
   ```
   
   以此类推...

5. **每个模块实现后立即测试**
   ```bash
   # 生成该模块的测试
   # 运行测试
   ./gradlew test
   ```

6. **所有模块完成后运行完整测试**
   ```bash
   ./scripts/run-tests.sh
   ```

7. **对比新旧实现**
   
   创建对比报告：
   ```markdown
   # 重构对比报告
   
   ## 代码质量
   - 旧版本：XXX行代码，YYY个文件
   - 新版本：AAA行代码，BBB个文件
   - 代码减少/增加：ZZ%
   
   ## 测试覆盖率
   - 旧版本：XX%
   - 新版本：YY%
   
   ## 性能对比
   - 启动时间：旧XXms vs 新YYms
   - 内存占用：旧XXmb vs 新YYmb
   
   ## 技术债务
   - 旧版本：列出主要问题
   - 新版本：已解决的问题
   
   ## 结论
   是否采用新实现：[ ] 是 [ ] 否
   原因：...
   ```

8. **决策**
   - 如果新实现更好：合并到主分支
   - 如果有问题：修改RFC，重新生成部分代码
   - 保留旧实现作为参考

---

## AI协作技巧

### 给AI的最佳提示词模板

#### 实现功能
```
我需要你根据RFC实现代码。

约束条件：
- 只根据RFC实现，不参考现有代码
- 严格遵循RFC中的设计
- 使用RFC指定的技术栈
- 遵循Clean Architecture

RFC内容：
---
[粘贴完整RFC]
---

请实现以下部分：
1. [具体要实现的组件]
2. [具体要实现的组件]

每个组件请：
- 完整实现，不要省略
- 添加必要的注释
- 包含错误处理
```

#### 生成测试
```
请根据测试场景生成自动化测试代码。

测试场景：
---
[粘贴YAML内容]
---

要求：
- 使用JUnit 5 / Jetpack Compose Test
- 使用MockK进行mock
- 覆盖所有场景
- 添加清晰的注释

被测试的代码：
---
[粘贴相关代码]
---
```

#### 细化PRD
```
我写了一个PRD草稿，请帮我：
1. 发现遗漏的场景
2. 补充边界条件
3. 完善验收标准
4. 指出不明确的地方

PRD内容：
---
[粘贴PRD]
---

请保持产品视角，不要涉及技术实现。
```

#### 设计RFC
```
我有一个PRD，请帮我设计技术方案。

PRD内容：
---
[粘贴PRD]
---

技术栈：
- Kotlin + Jetpack Compose
- Clean Architecture + MVVM
- Room + Retrofit

请提供：
1. 架构设计
2. 数据模型
3. API设计
4. 实现步骤

RFC应该足够详细，可以直接用于实现。
```

### 常见问题

**Q: AI生成的代码不符合RFC？**
A: 
1. 检查RFC是否足够详细
2. 在提示词中强调"严格遵循RFC"
3. 把RFC中的代码示例直接粘贴给AI参考

**Q: AI总是参考现有代码？**
A:
1. 明确告诉AI"不要看现有代码"
2. 不要在对话中提到现有代码
3. 只提供RFC文档

**Q: 测试覆盖率不够？**
A:
1. 在测试场景中添加更多case
2. 让AI针对每个公开方法生成测试
3. 使用覆盖率报告识别未覆盖的代码

**Q: 重构后测试失败？**
A:
1. 检查RFC是否与PRD一致
2. 检查测试场景是否需要更新
3. 不要修改测试来适应代码，而是修改代码符合测试

---

## 工具使用

### 验证文档完整性
```bash
python tools/validate-docs.py
```

会检查：
- 所有FEAT是否有对应的RFC
- 所有RFC是否有对应的TEST
- 文档格式是否正确
- ID是否有冲突

### 生成文档依赖图
```bash
python tools/doc-graph.py
```

生成类似：
```
FEAT-001 (用户认证)
  ├─ RFC-001 (认证实现)
  │   └─ ADR-001 (选择JWT)
  └─ TEST-001 (登录流程)
  └─ TEST-002 (登出流程)

FEAT-002 (云存储)
  ├─ RFC-002 (存储实现)
  └─ TEST-003 (文件上传)
  └─ TEST-004 (文件下载)
```

### 运行所有测试
```bash
./scripts/run-tests.sh

# 输出：
# ✓ 单元测试: 45/45 通过
# ✓ 集成测试: 12/12 通过
# ✓ UI测试: 8/8 通过
# ✓ 覆盖率: 87%
```

---

## 下一步

根据你的情况，建议：

1. **如果从零开始**
   - 从最核心的功能开始
   - 写第一个PRD（比如用户认证）
   - 写对应的RFC
   - 让AI实现
   - 建立流程

2. **如果重构现有应用**
   - 先列出所有核心功能
   - 逐个功能反向工程PRD和RFC
   - 用新流程重新实现
   - 对比新旧版本

你想从哪里开始？
