# RFC-016: 聊天输入框重新设计

## 文档信息
- **RFC ID**: RFC-016
- **关联 PRD**: FEAT-016 (Chat Input Redesign)
- **依赖**: RFC-001 (Chat Interaction), RFC-014 (Agent Skill)
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景
当前 `ChatScreen.kt` 中的 `ChatInput` Composable 使用了一个包裹在 `tonalElevation = 2.dp` 的 `Surface` 中的 `OutlinedTextField`。操作按钮（技能、停止、发送）与文本输入框并排放置在同一个水平 `Row` 中。与 Google Gemini 等现代移动 AI 聊天界面相比，这种布局显得过时——现代界面在圆角填充容器内使用两层垂直布局。

### 目标
1. 将单行布局替换为两层垂直布局：文本框在上，操作按钮在下
2. 将 `OutlinedTextField` 替换为 `BasicTextField`，并放置在填充的圆角容器内（`surfaceContainerHigh`，`RoundedCornerShape(28.dp)`）
3. 添加水平外边距，使输入容器不再占满全宽
4. 保留所有现有行为：文本输入、发送、停止、技能按钮、斜杠命令、焦点管理、IME 内边距

### 非目标
- 对 `ChatViewModel`、`ChatUiState` 或数据层的任何修改
- 对 `SlashCommandPopup` 或 `SkillSelectionBottomSheet` 组件的任何修改
- 附件按钮功能（仅预留占位空间）
- 语音输入
- 文本框高度展开的自定义动画
- 超出 Material 3 自动提供范围的深色/浅色主题定制

## 技术方案

### 整体设计

这是一次**单文件、纯布局修改**，仅涉及 `ChatScreen.kt`。无需新建类、无需新建文件、无需修改 ViewModel 或状态。`ChatInput` Composable 的内部实现被重写，但函数签名保持不变。

```
┌─────────────────────────────────────────────────────┐
│                    ChatScreen.kt                     │
│                                                     │
│  ChatInput(text, onTextChange, onSend, onStop,      │
│            onSkillClick, isStreaming,                │
│            hasConfiguredProvider, focusRequester)    │
│                                                     │
│  Signature: UNCHANGED                               │
│  Internal layout: REWRITTEN                         │
│  ViewModel/State: UNCHANGED                         │
└─────────────────────────────────────────────────────┘
```

**修改文件数**: 1 (`ChatScreen.kt`)
**新增文件数**: 0
**删除文件数**: 0

### 新旧布局对比

#### 现有布局（基于 Row）

```
+------------------------------------------------------------------+
| Surface (tonalElevation = 2dp, full width)                       |
|                                                                  |
|  [Skill]  +---[OutlinedTextField]---+  [Stop?] [Send]            |
|    btn    | Message or /skill       |   btn     btn              |
|           +-------------------------+                            |
|                                                                  |
+------------------------------------------------------------------+
```

- 单个 `Row` 内所有元素并排排列
- `OutlinedTextField` 带边框描边，`MaterialTheme.shapes.extraLarge`
- 全宽 `Surface`，`tonalElevation = 2.dp`
- 按钮与文本框挤在同一行

#### 新布局（基于 Column，Gemini 风格）

```
   +------------------------------------------------------------+
   |  (surfaceContainerHigh, RoundedCornerShape(28.dp))          |
   |                                                             |
   |   Message or /skill                                         |
   |   (BasicTextField, auto-expanding 1-6 lines)                |
   |                                                             |
   |   [Skill]                               [Stop?] [Send]     |
   |    btn                                   btn     btn        |
   |                                                             |
   +------------------------------------------------------------+
       ^--- 12.dp margin from screen edges ---^
```

- `Column` 分两层：文本框区域 + 操作按钮行
- `BasicTextField` 无边框，背景透明
- `Surface` 使用 `surfaceContainerHigh` 填充色，`RoundedCornerShape(28.dp)`
- 距屏幕边缘 12.dp 水平外边距
- 操作按钮在文本框下方单独一行

### 详细实现

#### 新版 ChatInput Composable

```kotlin
@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSkillClick: () -> Unit = {},
    isStreaming: Boolean,
    hasConfiguredProvider: Boolean,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Layer 1: Text Field
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .padding(top = 12.dp, bottom = 4.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                minLines = 1,
                maxLines = 6,
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = "Message or /skill",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Layer 2: Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Skill button (left)
                IconButton(
                    onClick = onSkillClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = "Skills",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Stop button (right, conditional)
                if (isStreaming) {
                    IconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.size(40.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Send button (right)
                IconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank() && hasConfiguredProvider,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}
```

#### 新增导入语句

以下导入需添加到 `ChatScreen.kt`：

```kotlin
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
```

以下导入需删除：

```kotlin
// Remove: import androidx.compose.material3.OutlinedTextField
// (only if no other usage exists in the file -- currently OutlinedTextField is only used in ChatInput)
```

### 布局规格

#### 外层容器（Surface）
| 属性 | 值 |
|------|-----|
| 背景色 | `MaterialTheme.colorScheme.surfaceContainerHigh` |
| 形状 | `RoundedCornerShape(28.dp)` |
| 水平外边距 | 距屏幕两侧各 12.dp |
| 底部外边距 | 8.dp（通过 `padding(vertical = 8.dp)`） |
| 内部内边距 | 水平 12.dp，垂直 8.dp |

#### 文本框（BasicTextField）
| 属性 | 值 |
|------|-----|
| 类型 | `BasicTextField`（无边框，无描边） |
| 背景 | 透明（继承容器背景） |
| 文字样式 | `MaterialTheme.typography.bodyLarge` |
| 文字颜色 | `MaterialTheme.colorScheme.onSurface` |
| 光标颜色 | `MaterialTheme.colorScheme.primary` |
| 占位文本 | "Message or /skill" |
| 占位文本颜色 | `MaterialTheme.colorScheme.onSurfaceVariant` |
| 最小行数 | 1 |
| 最大行数 | 6（超出后可滚动） |
| 顶部内边距 | 12.dp |
| 底部内边距 | 4.dp（操作行之上） |

#### 操作按钮行
| 属性 | 值 |
|------|-----|
| 高度 | 40.dp |
| 顶部内边距 | 4.dp |
| 布局 | 技能按钮居左，弹性间距，停止/发送按钮居右 |

#### 按钮
| 按钮 | 尺寸 | 容器颜色 | 图标/内容 |
|------|------|---------|---------|
| Skill | 40.dp | 无（普通图标按钮） | `Icons.Default.AutoAwesome`，着色 `onSurfaceVariant` |
| Send | 40.dp | `primary`（填充） | `Icons.Default.Send`，着色 `onPrimary` |
| Send（禁用） | 40.dp | 静音色（默认禁用状态） | `Icons.Default.Send` |
| Stop | 40.dp | `errorContainer`（填充） | `CircularProgressIndicator` 18.dp，`error` 颜色 |

### 内边距与安全区域处理

外层 `Surface` 的 modifier 链为：

```kotlin
Modifier
    .fillMaxWidth()
    .imePadding()              // Push above keyboard
    .navigationBarsPadding()   // Respect navigation bar
    .padding(horizontal = 12.dp, vertical = 8.dp)  // Container margins
```

**顺序至关重要**：`imePadding()` 和 `navigationBarsPadding()` 必须在 `padding()` 之前，这样系统内边距先作用于全宽 Surface，随后再在安全区域内应用外边距。

在当前实现中，`imePadding()` 位于 `Surface` 上，`navigationBarsPadding()` 位于内层 `Row` 上。新实现将两者统一放置在 `Surface` 的 modifier 上，由于水平外边距的存在，Surface 不再是全宽，但视觉效果相同，代码更简洁。

### 行为保留检查清单

| 行为 | 实现机制 | 是否保留 |
|------|---------|---------|
| 文本输入 | `onValueChange = onTextChange` | 是 |
| 点击按钮发送 | `onClick = onSend` | 是 |
| 发送禁用（空文本/无提供商） | `enabled = text.isNotBlank() && hasConfiguredProvider` | 是 |
| 流式传输时显示停止按钮 | `if (isStreaming) { ... }` | 是 |
| 技能按钮 | `onClick = onSkillClick` | 是 |
| 斜杠命令弹窗 | 由 `ChatViewModel` 根据文本以"/"开头触发——弹窗在 `ChatScreen` 的 `bottomBar` 列中、`ChatInput` 上方渲染 | 是（无需修改） |
| FocusRequester | `Modifier.focusRequester(focusRequester)` 作用于 `BasicTextField` | 是 |
| IME 内边距 | `Modifier.imePadding()` 作用于 `Surface` | 是 |
| 导航栏内边距 | `Modifier.navigationBarsPadding()` 作用于 `Surface` | 是 |
| 自动展开 1-6 行 | `BasicTextField` 的 `minLines = 1, maxLines = 6` | 是 |
| 超出 6 行后可滚动 | `maxLines = 6` 允许文本框内部滚动 | 是 |
| 占位文本 | `decorationBox` 中条件渲染 `Text("Message or /skill")` | 是 |

### 主题规范

所有颜色和排版均来自 `MaterialTheme`：

| 用途 | Token |
|------|-------|
| 容器背景 | `colorScheme.surfaceContainerHigh` |
| 输入文字 | `colorScheme.onSurface` |
| 占位文字 | `colorScheme.onSurfaceVariant` |
| 光标 | `colorScheme.primary` |
| 技能图标着色 | `colorScheme.onSurfaceVariant` |
| 发送按钮容器 | `colorScheme.primary` |
| 发送按钮图标 | `colorScheme.onPrimary`（通过 `filledIconButtonColors`） |
| 停止按钮容器 | `colorScheme.errorContainer` |
| 停止进度指示器 | `colorScheme.error` |
| 文字样式 | `typography.bodyLarge` |

无硬编码颜色值。通过 Material 3 颜色方案自动支持浅色和深色主题。

## 实现步骤

本次为单步骤实现：

### 步骤 1：重写 ChatInput Composable
1. [ ] 在 `ChatScreen.kt` 中添加 `BasicTextField` 和 `SolidColor` 导入
2. [ ] 替换 `ChatInput` Composable 的函数体（当前代码第 336-401 行）：
   - 将 `Surface(tonalElevation = 2.dp)` 替换为 `Surface(color = surfaceContainerHigh, shape = RoundedCornerShape(28.dp))`
   - 将内层 `Row` 替换为包含两层的 `Column`
   - 将 `OutlinedTextField` 替换为 `BasicTextField` + `decorationBox`
   - 将技能按钮从文本框左侧移至操作行（左侧）
   - 将停止和发送按钮移至操作行（右侧）
   - 将 `imePadding()` 和 `navigationBarsPadding()` 合并到 `Surface` 的 modifier 上
   - 添加水平和垂直内边距作为容器外边距
3. [ ] 删除未使用的 `OutlinedTextField` 导入（如文件中无其他用法）
4. [ ] 验证 Composable 函数签名未发生变化

## 测试策略

### Layer 1A：JVM 编译检查
```bash
./gradlew compileDebugKotlin
./gradlew test
```
- 验证布局修改无编译错误
- 所有现有单元测试必须通过（无功能性变更）

### Layer 1C：Roborazzi 截图测试
- 由于输入区域外观已变更，需更新 `ChatScreen` Composable 的截图基线
- 需为以下状态录制基线：
  - 空输入状态（显示占位文本，发送按钮禁用）
  - 已输入文本状态（发送按钮启用）
  - 多行文本状态（输入框展开）
  - 流式传输状态（显示停止按钮）

```bash
./gradlew recordRoborazziDebug     # record new baselines
./gradlew verifyRoborazziDebug     # verify against new baselines
```

### Layer 2：视觉验证（adb）
在已连接设备上进行手动验证：

| 流程 | 步骤 | 预期结果 |
|------|------|---------|
| TC-016-01: 填充背景 | 打开聊天界面 | 输入区域有填充背景，无描边边框，圆角，有水平外边距 |
| TC-016-02: 自动展开 | 输入短文本，再输入长多行文本 | 输入框从 1 行扩展至最多 6 行，超出后可滚动 |
| TC-016-03: 操作按钮行 | 观察空状态，输入文本，清除文本 | 技能按钮在左下角，发送按钮在右下角，启用/禁用状态正确 |
| TC-016-04: 发送 | 输入文本，点击发送 | 消息发出，输入框清空 |
| TC-016-05: 停止 | 发送消息，观察流式传输 | 停止按钮伴随进度指示器出现，点击停止生成 |
| TC-016-06: 技能按钮 | 点击技能按钮 | 底部弹窗打开，技能选择正常，斜杠命令插入成功 |
| TC-016-07: 斜杠命令 | 输入"/" | 输入框上方弹出提示，过滤功能正常 |
| TC-016-08: 键盘内边距 | 点击输入框唤起键盘 | 输入框移至键盘上方，顶部栏保持可见 |
| TC-016-09: 主题切换 | 切换浅色/深色主题 | 颜色正确适配 |

## 依赖关系

### 依赖于
- **RFC-001 (Chat Interaction)**：`ChatInput` 是 RFC-001 定义的聊天界面的组成部分
- **RFC-014 (Agent Skill)**：技能按钮与斜杠命令集成

### 被依赖于
- 无

### 外部依赖
- 无（纯 Compose UI 修改，无新增库）

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|---------|--------|
| 2026-02-28 | 0.1 | 初始版本 | - |
