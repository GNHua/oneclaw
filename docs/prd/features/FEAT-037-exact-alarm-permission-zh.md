# 精确闹钟权限对话框

## 功能信息
- **功能 ID**: FEAT-037
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: Draft
- **优先级**: P0（阻塞项 -- Play Store 上架）
- **负责人**: TBD
- **关联 RFC**: [RFC-037（精确闹钟权限）](../../rfc/features/RFC-037-exact-alarm-permission.md)
- **依赖功能**: [FEAT-019（定时任务）](FEAT-019-scheduled-tasks.md)

## 用户故事

**作为** OneClaw 的用户，
**我希望** 应用引导我完成精确闹钟权限的授权，
**以便** 定时任务能在我设定的精确时间触发，并且应用符合 Google Play 政策要求。

### 典型场景

1. 用户在 Android 13+ 设备上创建第一个定时任务。应用检测到精确闹钟权限未授权，弹出对话框说明权限用途，并提供按钮跳转至系统设置。
2. 用户在对话框中点击"前往设置"。系统精确闹钟权限设置页面打开，用户授权后返回应用。定时任务创建成功，闹钟完成注册。
3. 用户在对话框中点击"取消"。任务仍会保存，但闹钟不会注册。应用显示警告，提示该任务将无法在精确设定的时间触发。
4. 用户在应用运行期间通过系统设置撤销精确闹钟权限。系统广播权限状态变更。应用检测到撤销事件，暂停精确闹钟调度，直至用户重新授权。
5. 用户此前已拒绝过该权限。再次编辑或创建定时任务时，对话框会再次出现。
6. 设备重启。`BootCompletedReceiver` 在重新调度闹钟前先检查 `canScheduleExactAlarms()`。若权限未授权，则不注册闹钟。
7. AI Agent 通过对话调用 `schedule_task`。工具检查权限，若未授权，则返回错误信息，提示用户通过应用设置授予权限。

## 功能描述

### 概述

从 Android 12（API 31）起，精确闹钟调度需要用户显式授权。Android 13+（API 33）进一步收紧了限制，不再默认授予 `SCHEDULE_EXACT_ALARM` 权限。替代方案 `USE_EXACT_ALARM` 虽可自动授权，但 Google Play 政策将其限制为仅适用于闹钟、计时器和日历类应用。由于 OneClaw 是 AI Agent 运行时，必须使用 `SCHEDULE_EXACT_ALARM` 并引导用户通过系统设置完成授权。

### 当前问题

应用目前在 manifest 中同时声明了 `SCHEDULE_EXACT_ALARM`（maxSdkVersion=32）和 `USE_EXACT_ALARM`。在 Android 13+ 上，应用依赖 `USE_EXACT_ALARM`，这可能导致 Play Store 审核拒绝，因为本应用并非闹钟类应用。此外，代码在调用 `setExactAndAllowWhileIdle()` 时未检查权限是否已授权，在 Android 12+ 设备上权限被拒绝时会发生崩溃。

### 解决方案

1. **Manifest**：将 `USE_EXACT_ALARM` 替换为 `SCHEDULE_EXACT_ALARM`（去除 maxSdkVersion 限制）。
2. **运行时检查**：在任何调用 `setExactAndAllowWhileIdle()` 之前，先检查 `AlarmManager.canScheduleExactAlarms()`。
3. **UI 对话框**：当 UI 上下文（任务编辑页面）检查失败时，显示 Material 3 AlertDialog，说明权限用途并提供跳转系统设置的选项。
4. **工具上下文**：当工具上下文（AI Agent 创建任务）检查失败时，返回错误信息，指引用户授权。
5. **权限状态接收器**：监听 `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`，在权限被授予时重新调度闹钟。

### 权限流程

```
用户保存任务 / 工具创建任务
         |
         v
  canScheduleExactAlarms()?
     /           \
   是             否
    |              |
    v              v
 注册闹钟     UI 上下文？
            /        \
           是          否（工具）
            |           |
            v           v
       显示对话框    返回错误信息
            |
            v
    用户点击"前往设置"
            |
            v
    系统设置页面
            |
            v
    用户授予权限
            |
            v
    ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
            |
            v
    重新调度所有已启用任务
```

## 验收标准

### TEST-037-01: 调度前权限检查
- **前提** 应用运行于 Android 12+ 且精确闹钟权限**未**授权
- **操作** 用户在编辑页面保存新定时任务
- **预期** 显示精确闹钟权限对话框，而非静默失败或崩溃

### TEST-037-02: 对话框内容
- **前提** 精确闹钟权限对话框已显示
- **操作** 用户阅读对话框内容
- **预期** 对话框说明该权限用于定时任务精确触发，并显示"前往设置"和"取消"两个按钮

### TEST-037-03: 前往设置
- **前提** 精确闹钟权限对话框已显示
- **操作** 用户点击"前往设置"
- **预期** 精确闹钟权限的系统设置页面打开（`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`）

### TEST-037-04: 设置页面授权后
- **前提** 用户在系统设置中授予权限后返回应用
- **操作** 接收到权限状态变更广播
- **预期** 所有已启用的定时任务重新注册闹钟

### TEST-037-05: 取消对话框
- **前提** 精确闹钟权限对话框已显示
- **操作** 用户点击"取消"
- **预期** 任务已保存，但显示警告：该任务将无法在精确设定的时间触发

### TEST-037-06: 工具上下文错误
- **前提** 精确闹钟权限**未**授权
- **操作** AI Agent 调用 `schedule_task`
- **预期** 工具返回错误信息："Exact alarm permission is not granted. Please go to Settings > Apps > OneClaw > Alarms & reminders to enable it."

### TEST-037-07: 启动接收器检查
- **前提** 设备重启且精确闹钟权限**未**授权
- **操作** `BootCompletedReceiver` 触发
- **预期** 闹钟**不**被注册，且不发生崩溃

### TEST-037-08: Android 12 以下免操作
- **前提** 应用运行于 Android 11 或更低版本
- **操作** 创建定时任务
- **预期** 不进行权限检查，不显示对话框，直接注册闹钟

### TEST-037-09: Manifest 合规性
- **前提** 应用提交至 Google Play
- **操作** 审核 manifest 文件
- **预期** 仅声明 `SCHEDULE_EXACT_ALARM`（无 `USE_EXACT_ALARM`），满足 Play Store 对非闹钟类应用的政策要求

## 非功能性需求

- 在 Android 12 以下设备上，权限检查的开销必须为零（不使用反射，仅通过简单的 `Build.VERSION.SDK_INT` 判断）。
- 对话框必须遵循 Material 3 设计规范，并与应用现有主题保持一致。
- 权限状态接收器必须轻量化——仅重新调度闹钟，不执行任何重型初始化操作。

## 超出范围

- 应用启动时主动显示权限对话框（仅在用户尝试创建或编辑定时任务时显示）。
- 权限被拒绝时降级使用非精确闹钟（任务仅不注册闹钟）。
- 应用内精确闹钟设置开关（完全通过系统设置管理）。
