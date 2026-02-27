# 测试报告：RFC-XXX — [RFC 名称]

## 报告信息

| 字段 | 内容 |
|------|------|
| RFC | RFC-XXX |
| 关联 FEAT | FEAT-XXX |
| Commit | `xxxxxxx` |
| 日期 | YYYY-MM-DD |
| 测试人 | AI (OpenCode) |
| 状态 | 通过 / 部分通过 / 失败 |

## 摘要

简要说明本次 RFC 实现了哪些功能，测试覆盖了哪些范围。

| 层级 | 步骤 | 结果 | 备注 |
|------|------|------|------|
| 1A | JVM 单元测试 | 通过 / 失败 / 跳过 | X 个测试 |
| 1B | 设备 DAO 测试 | 通过 / 失败 / 跳过 | X 个测试 |
| 1B | 设备 UI 测试 | 通过 / 失败 / 跳过 | X 个测试 |
| 1C | Roborazzi 截图测试 | 通过 / 失败 / 跳过 | X 张截图 |
| 2 | adb 视觉验证 | 通过 / 失败 / 跳过 | Flow X、Y、Z |

## 第一层 A：JVM 单元测试

**命令：** `./gradlew test`

**结果：** 通过

**测试数量：** X 个测试，0 个失败

本次 RFC 新增或修改的测试类：
- `SomeUseCaseTest` — X 个测试
- `SomeViewModelTest` — X 个测试

## 第一层 B：设备端测试

**命令：** `ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest`

**结果：** 通过 / 跳过

**跳过原因（如适用）：** _例：模拟器不可用_

**测试数量：** X 个测试，0 个失败

## 第一层 C：Roborazzi 截图测试

**命令：**
```bash
./gradlew recordRoborazziDebug
./gradlew verifyRoborazziDebug
```

**结果：** 通过 / 跳过

**跳过原因（如适用）：** _例：本次 RFC 没有 UI 变更_

### 截图

#### [屏幕名称] — [变体]

![ScreenName](screenshots/RFC-XXX_ScreenName.png)

视觉检查：[描述截图内容，确认符合预期]

## 第二层：adb 视觉验证

**结果：** 通过 / 跳过

**跳过原因（如适用）：** _例：未设置 API key；Chat 功能尚未实现_

### Flow 1：[流程名称]

执行步骤：
1. [操作] — [结果]
2. [操作] — [结果]

截图：
![flow1-step3](../../screenshots/layer2/flow1-step3.png)

判定：通过 / 失败 — [说明]

## 发现的问题

_列出测试过程中发现的 Bug、异常行为或与 RFC 规范的偏差。_

| # | 描述 | 严重程度 | 状态 |
|---|------|----------|------|
| 1 | | | |

_如无问题：「未发现问题。」_

## 变更历史

| 日期 | 变更内容 |
|------|----------|
| YYYY-MM-DD | 初始版本 |
