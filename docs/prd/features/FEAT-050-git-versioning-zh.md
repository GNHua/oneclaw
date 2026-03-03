# FEAT-050: 基于 Git 的文件版本控制

## 文档信息
- **Feature ID**: FEAT-050
- **Status**: Draft
- **Created**: 2026-03-02
- **Last Updated**: 2026-03-02

## 背景

OneClaw 将用户相关文件——记忆日志、每日对话摘要、AI 生成的报告以及 Markdown 文档——存储在应用内部的 `filesDir` 目录中。目前唯一的版本控制机制是对 `MEMORY.md` 进行简单的轮转备份（最多保留 5 份带时间戳的副本）。这种方式存在以下问题：

- 仅覆盖 `MEMORY.md`，不涵盖每日日志或 AI 生成的文件
- 最多保留 5 个快照，无细粒度的变更历史
- 无法查看版本之间的具体差异
- AI Agent 无法检索或恢复历史内容

## 目标

1. 用一个完整的 git 仓库替代现有的备份机制，追踪 `filesDir` 下所有文本文件
2. 对记忆文件和 AI 生成的 Markdown 文件的每次写入自动提交，形成连续的变更历史
3. 向 AI Agent 暴露一组 git 工具，使其能够查询历史、查看差异并恢复文件
4. 定期调度 `git gc` 以控制仓库体积
5. 允许用户创建可移植的 git bundle 以进行手动归档（例如，通过现有 Google 工具上传到 Google Drive）

## 非目标

- 不远程同步至 GitHub 或任何 git 托管服务
- 不进行分支管理，所有提交均进入默认分支（`main`）
- 不提供浏览 git 历史的应用内 UI（推迟至未来功能）
- 不对二进制文件（图片、视频、PDF、数据库文件）进行版本控制

## 用户故事

**US-1: 记忆的自动版本控制**
作为用户，我希望对 `MEMORY.md` 和每日日志的每次修改都能自动保存为一个 git 提交，这样我就不会丢失任何历史版本的记忆内容。

**US-2: AI 辅助的历史检索**
作为用户，我希望能够向 AI 提问"展示我上周记忆中发生了哪些变化"或"恢复昨天的每日日志"，并由 AI 执行相应的 git 命令来完成请求。

**US-3: 通过 bundle 进行手动备份**
作为用户，我希望能够调度一个任务，将整个 git 仓库打包成单个文件并上传到 Google Drive，从而在不将数据暴露给任何第三方 git 托管服务的前提下实现设备外备份。

**US-4: AI 生成文件的版本控制**
作为用户，我希望 AI 生成的 Markdown 报告和笔记能够被自动提交，以便我能恢复任何 AI 产出文档的历史版本。

## 功能需求

### FR-1: Git 仓库初始化
- 在首次启动时（或从不含此功能的版本升级时），在 `context.filesDir` 处初始化一个 git 仓库
- 创建包含所有现有符合条件文件的初始提交
- 写入 `.gitignore` 文件以排除二进制文件和内部文件（见 FR-2）

### FR-2: Gitignore 配置
仓库根目录下的 `.gitignore` 必须排除以下内容：
```
attachments/
bridge_images/
*.jpg
*.jpeg
*.png
*.gif
*.webp
*.bmp
*.mp4
*.onnx
*.pdf
*.db
*.db-shm
*.db-wal
*.bundle
*.zip
```

### FR-3: 自动提交触发条件
以下每种操作完成后自动创建提交：
- 写入 `MEMORY.md`（通过 `LongTermMemoryManager`）
- 追加内容到每日日志文件（通过 `MemoryFileStorage.appendToDailyLog`）
- 通过 AI `write_file` 工具写入任意文件
- 通过 AI `delete_file` 工具或文件 UI 删除文件

提交信息格式如下：
| 触发条件 | 提交信息 |
|---|---|
| 首次初始化 | `init: initialize memory repository` |
| MEMORY.md 更新 | `memory: update MEMORY.md` |
| 每日日志追加 | `log: add daily log YYYY-MM-DD` |
| AI write_file | `file: write <relative-path>` |
| 文件删除 | `file: delete <relative-path>` |
| 定期 gc | `gc: repository maintenance` |

### FR-4: 移除旧版备份机制
从 `MemoryFileStorage` 中移除 `createBackup()`、`pruneOldBackups()`、`restoreFromBackup()` 和 `listBackups()`。`MAX_BACKUPS` 常量以及 `BACKUP_PREFIX`、`BACKUP_SUFFIX`、`BACKUP_TIMESTAMP_FORMAT` 伴生对象也一并移除。

### FR-5: AI Git 工具
向 AI Agent 暴露以下工具：

| 工具 | 描述 |
|---|---|
| `git_log` | 列出提交记录，可按文件路径过滤并限制数量 |
| `git_show` | 通过 SHA 显示特定提交的内容和差异 |
| `git_diff` | 显示两个提交之间，或提交与工作树之间的差异 |
| `git_restore` | 将特定文件恢复至给定提交 SHA 时的状态 |
| `git_bundle` | 在指定输出路径创建 git bundle 文件，用于手动归档 |

### FR-6: 定期仓库维护
- 调度一个每月执行的 WorkManager 任务来运行 `git gc`
- 任务在压缩完成后创建一条消息为 `gc: repository maintenance` 的提交
- 在 IO dispatcher 上运行；若仓库不存在则跳过

## 非功能需求

- 所有 git 操作均在 IO dispatcher 上运行（绝不在主线程上执行）
- 使用 JGit 作为 git 实现（纯 Java，无需原生二进制文件）
- git 操作不得阻塞或延迟 UI；每次写入后以"即发即忘"方式执行
- `.git/` 目录须从所有 Room 数据库查询和文件浏览器列表中排除

## 范围之外

- 多设备同步
- 冲突解决
- 标签或发布管理
