# 数据存储与同步

## 功能信息
- **Feature ID**: FEAT-007
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: TBD
- **Related Feature**: [FEAT-005 (Session Management)](FEAT-005-session.md)

## 用户故事

**作为** OneClaw 的用户，
**我希望** 将数据备份到 Google Drive 并导出到本地，
**以便** 在更换设备或数据丢失时恢复我的对话和设置。

### 典型场景

1. 用户设置 Google Drive 同步。应用每小时自动将变更数据上传到用户的 Google Drive。如果用户更换了新手机，可以恢复所有数据。
2. 用户在进行重大更改前希望进行本地备份。点击"导出备份"即可获得一个可保存或分享的文件。
3. 用户在第二台设备上安装应用，登录 Google Drive，即可恢复会话、Agent 和设置。

## 功能描述

### 概述

数据存储与同步提供两种备份机制：自动 Google Drive 同步（增量同步，每小时一次）和手动本地导出（完整备份文件）。所有数据以本地优先 -- 同步是用于备份和跨设备使用的附加功能。API key 永远不会被同步。

### Google Drive 同步

#### 设置
- 用户从设置页面登录 Google 账号。
- 登录后，同步自动启用并开始运行。
- 同步状态在设置中可见（上次同步时间、同步状态）。

#### 同步范围
除 API key 外的所有应用数据：
- 会话和消息历史
- Agent 配置（内置和自定义）
- Provider 配置（provider 名称、API base URL、模型 -- 但不包括 API key）
- 应用设置/偏好
- Token 用量数据（存储在消息中）

#### 不同步的内容
- API key（永远不会离开设备，除非用户在新设备上重新输入）
- Roborazzi 截图或本地临时文件
- 应用缓存

#### 同步行为
- **频率**：每 1 小时自动同步一次。仅在应用处于前台或近期使用过时运行。
- **增量同步**：仅上传自上次同步以来发生变更的记录（基于 `updatedAt` 时间戳或变更日志）。
- **冲突解决**：以最后写入为准（last-write-wins）。如果同一条记录在两台设备上被修改，最近修改的版本将覆盖另一个。
- **方向**：双向同步。设备上的变更推送到 Drive；Drive 上的变更拉取到本地。
- **手动触发**：用户可在设置中点击"立即同步"以强制执行即时同步。
- **后台运行**：同步在后台协程中运行，不会阻塞 UI。

#### 恢复流程
- 在新设备上，用户打开设置，登录 Google Drive。
- 应用检测到 Drive 上已有备份数据。
- 用户确认"从备份恢复"。
- 所有数据被下载并合并到本地数据库。
- API key 为空 -- 用户需要为每个 provider 重新输入。

### 本地导出

- 用户在设置中点击"导出备份"。
- 应用生成一个文件（JSON 或 ZIP），包含所有应用数据（与 Google Drive 同步范围相同，不包括 API key）。
- 系统分享面板打开，用户可以保存文件、通过邮件发送等。
- 导入：用户点击"导入备份"并选择之前导出的文件。数据被恢复（在确认提示后覆盖本地数据）。

## 验收标准

必须通过（全部必需）：
- [ ] 用户可以在设置中登录 Google Drive
- [ ] 登录后，同步每小时自动开始
- [ ] 同步是增量的（仅上传变更数据）
- [ ] 设置中显示上次同步时间
- [ ] "立即同步"按钮触发即时同步
- [ ] API key 永远不包含在同步或导出的数据中
- [ ] 新设备可以从 Google Drive 备份恢复
- [ ] 恢复后，API key 为空（用户需要重新输入）
- [ ] 用户可以导出本地备份文件
- [ ] 用户可以导入本地备份文件（覆盖前有确认提示）
- [ ] 同步不阻塞 UI
- [ ] 冲突解决使用 last-write-wins 策略

可选（锦上添花）：
- [ ] 大量同步时显示同步进度指示器
- [ ] 同步反复失败时显示错误通知

## UI/UX 要求

### 设置入口

在设置页面中，添加"数据与备份"区域：

```
Data & Backup
  Google Drive Sync         Connected (user@gmail.com)
                            Last synced: 5 minutes ago
                            [Sync Now]

  Export Backup             Save all data to a file
  Import Backup             Restore from a backup file
```

未登录时：

```
Data & Backup
  Google Drive Sync         Not connected
                            [Sign in with Google]

  Export Backup             Save all data to a file
  Import Backup             Restore from a backup file
```

### 恢复确认对话框

从 Google Drive 恢复或导入本地备份时：

```
Restore from Backup?

This will replace all current data with the backup.
API keys will not be restored -- you will need to
re-enter them in provider settings.

[Cancel]  [Restore]
```

### 同步状态指示器

- 同步进行中：同步状态文字旁显示小型 `CircularProgressIndicator`
- 同步错误：红色错误文字，附带重试选项
- 同步完成：绿色勾选标记或"上次同步：X 分钟前"

## 功能边界

### 包含
- Google Drive 自动同步（每小时，增量）
- 手动"立即同步"
- 本地导出（完整备份文件）
- 本地导入（从文件恢复）
- 在新设备上从 Google Drive 恢复
- 设置中的同步状态显示

### 不包含
- 选择性同步（选择同步内容）
- 其他云服务商（Dropbox、OneDrive）
- API key 同步（明确排除）
- 同步调度配置（固定为 1 小时）
- 同步数据的端到端加密（依赖 Google Drive 内置加密）
- 合并冲突 UI（始终使用 last-write-wins，不提示用户）

## 业务规则

1. API key 永远不包含在任何同步或导出操作中。
2. 同步是双向的：本地变更推送到 Drive，Drive 变更拉取到本地。
3. 冲突解决基于记录的 `updatedAt` 时间戳，采用 last-write-wins 策略。
4. 同步仅在应用近期活跃时运行（不是持久后台服务）。
5. 本地导出包含与 Google Drive 同步相同范围的所有数据。
6. 导入完全覆盖本地数据（不是合并）。用户必须在操作前确认。
7. 从备份恢复后，应用正常运行，但 API key 为空。

## 依赖关系

### 依赖于
- **FEAT-005 (Session Management)**：会话和消息是需要同步的主要数据。
- **FEAT-002 (Agent Management)**：Agent 配置需要同步。
- **FEAT-003 (Provider Management)**：Provider 配置（不含 API key）需要同步。
- **FEAT-009 (Settings)**：同步设置位于设置页面中。

### 被依赖于
- 无

## 测试要点

### 功能测试 -- 用户操作应用

#### 测试 1：Google Drive 登录与首次同步

| 步骤 | 用户操作 | 预期结果 |
|------|----------|----------|
| 1 | 进入 Settings > Data & Backup | "Google Drive Sync" 显示 "Not connected"。 |
| 2 | 点击 "Sign in with Google" | Google 登录流程打开。选择一个账号。 |
| 3 | 完成登录 | 状态变为 "Connected (user@gmail.com)"。同步开始。 |
| 4 | 等待同步完成 | 显示 "Last synced: just now"。 |

#### 测试 2：增量同步

| 步骤 | 用户操作 | 预期结果 |
|------|----------|----------|
| 1 | 在同步启用的状态下，在聊天中发送几条消息 | 消息被保存到本地。 |
| 2 | 点击 "Sync Now" | 同步运行。仅上传新消息（而非全部历史记录）。 |
| 3 | 观察同步状态 | "Last synced: just now" 更新。 |

#### 测试 3：在新设备上恢复

| 步骤 | 用户操作 | 预期结果 |
|------|----------|----------|
| 1 | 在新设备上安装应用 | 全新安装，显示设置向导。 |
| 2 | 完成设置，进入 Settings > Data & Backup | "Google Drive Sync" 显示 "Not connected"。 |
| 3 | 使用相同的 Google 账号登录 | 应用检测到 Drive 上已有备份。 |
| 4 | 在提示时点击 "Restore" | 确认对话框出现。点击 "Restore"。 |
| 5 | 等待恢复完成 | 所有会话、Agent、设置均已恢复。 |
| 6 | 检查 provider 设置 | API key 为空 -- 用户需要重新输入。 |
| 7 | 重新输入 API key 并发送一条消息 | 聊天正常工作，历史记录已恢复。 |

#### 测试 4：本地导出与导入

| 步骤 | 用户操作 | 预期结果 |
|------|----------|----------|
| 1 | 进入 Settings > Data & Backup | "Export Backup" 选项可见。 |
| 2 | 点击 "Export Backup" | 文件已生成。系统分享面板打开。 |
| 3 | 保存文件（例如保存到 Downloads） | 文件保存成功。 |
| 4 | 清除应用数据：`adb shell pm clear com.oneclaw.shadow` | 应用重置为初始状态。 |
| 5 | 完成设置向导，然后进入 Settings > Data & Backup | "Import Backup" 选项可见。 |
| 6 | 点击 "Import Backup"，选择已保存的文件 | 确认对话框："This will replace all current data." |
| 7 | 点击 "Restore" | 数据已恢复。会话、Agent、设置均已恢复。API key 为空。 |

#### 测试 5：API key 未被同步

| 步骤 | 用户操作 | 预期结果 |
|------|----------|----------|
| 1 | 配置一个带有 API key 的 provider | Provider 显示 "Connected"。 |
| 2 | 导出本地备份 | 文件已生成。 |
| 3 | 检查导出的文件（用文本编辑器打开） | 文件中不存在 API key 值。 |

### 边界情况

- 无网络时同步：同步优雅失败，下一小时重试
- 空应用同步（无会话、无自定义 Agent）：同步成功，数据量极小
- 超大历史记录（1000+ 会话）：同步完成且不崩溃，可能耗时较长
- 退出 Google Drive 登录：同步停止，现有本地数据不受影响
- 导入来自旧版应用的备份：优雅处理（跳过未知字段，不崩溃）

## 变更历史

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | 初始版本 | TBD |
