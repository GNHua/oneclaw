# RFC-009: Settings (设置)

## 文档信息
- **RFC ID**: RFC-009
- **关联 PRD**: [FEAT-009 (Settings)](../../prd/features/FEAT-009-settings.md)
- **关联架构**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **依赖**: 无 (Settings 页面已存在)
- **被依赖**: [RFC-006 (Token Tracking)](RFC-006-token-tracking.md), FEAT-007 (Data Sync)
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: Draft
- **作者**: TBD

## 概述

### 背景

Settings 页面目前只有两个扁平的入口："Manage Agents" 和 "Manage Providers"。随着应用功能增长（token 用量追踪、数据同步、外观自定义等），更多入口会被添加到 Settings 中。如果没有结构化的分组，页面会变成一个冗长且无序的列表。

本 RFC 为 Settings 页面添加"外观"区域（含主题控制），并将所有条目重新组织为带标签的分区。字体大小跟随 Android 系统设置（Compose 的 `sp` 单位自动处理）。V1 阶段仅支持英文。

现有的 `app_settings` 表（通过 `SettingsDao` 实现的键值存储）作为主题偏好的持久化层。现有的 `OneClawTheme` composable 已经支持 `darkTheme` 布尔参数。实现方案是在两者之间建立桥梁：在应用启动时读取存储的偏好，通过 `AppCompatDelegate.setDefaultNightMode()` 应用主题，同时提供 Compose 层面的 `darkTheme` 覆盖，使 `OneClawTheme` 无需 Activity 重建即可立即响应。

### 目标

1. 将 Settings 页面重新组织为分区：Appearance（外观）、Providers & Models、Agents、Usage、Data & Backup。
2. 添加主题设置，提供三个选项：跟随系统、浅色、深色。
3. 主题切换立即生效（无需重启应用）。
4. 主题偏好在应用重启后持久保存。
5. 字体大小跟随 Android 系统设置（无需代码更改 -- Compose 的 `sp` 单位自动处理）。

### 非目标

- 应用内字体大小控制（延后到未来版本）。
- 语言选择 / 本地化。
- 通知偏好设置。
- 关于 / 版本信息页面。
- 实现 Usage 或 Data & Backup 功能（属于 FEAT-006 和 FEAT-007 -- 本 RFC 仅添加占位入口）。

## 技术设计

### 架构概览

```
主题偏好流程：

1. 应用启动
   OneclawApplication.onCreate()
     -> SettingsRepository.getString("theme_mode")
     -> AppCompatDelegate.setDefaultNightMode(mode)
     -> ThemeManager.themeMode (StateFlow) 初始化

2. Compose 主题
   MainActivity.setContent
     -> 收集 ThemeManager.themeMode 作为 State
     -> OneClawTheme(darkTheme = 从 themeMode 解析)

3. 用户切换主题 (SettingsScreen)
   用户点击 Theme -> AlertDialog 展示 RadioButtons
     -> ThemeManager.setThemeMode(newMode)
       -> SettingsRepository.setString("theme_mode", newMode)
       -> AppCompatDelegate.setDefaultNightMode(mode)
       -> ThemeManager.themeMode 更新 -> Compose 重组
```

无需修改数据库 schema。`app_settings` 表已存在，提供键值存储。

### 变更 1：ThemeManager

一个单例，以 `StateFlow` 形式持有当前主题模式，并提供初始化和更新方法。它作为主题状态的唯一数据源。

新文件：`core/theme/ThemeManager.kt`

```kotlin
package com.oneclaw.shadow.core.theme

import androidx.appcompat.app.AppCompatDelegate
import com.oneclaw.shadow.core.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.find { it.key == key } ?: SYSTEM
    }
}

class ThemeManager(
    private val settingsRepository: SettingsRepository
) {
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    companion object {
        const val SETTINGS_KEY = "theme_mode"
    }

    /**
     * Called once during Application.onCreate() to read the persisted theme
     * and apply it via AppCompatDelegate.
     */
    suspend fun initialize() {
        val stored = settingsRepository.getString(SETTINGS_KEY)
        val mode = ThemeMode.fromKey(stored)
        _themeMode.value = mode
        applyNightMode(mode)
    }

    /**
     * Called from the Settings screen when the user selects a new theme.
     * Persists the choice, updates the StateFlow, and applies via AppCompatDelegate.
     */
    suspend fun setThemeMode(mode: ThemeMode) {
        settingsRepository.setString(SETTINGS_KEY, mode.key)
        _themeMode.value = mode
        applyNightMode(mode)
    }

    private fun applyNightMode(mode: ThemeMode) {
        val nightMode = when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
```

### 变更 2：在 DI 中注册 ThemeManager 并在应用启动时初始化

#### 2a. DI 注册

在 `AppModule.kt`（或存放单例的对应 DI 模块）中，将 `ThemeManager` 注册为单例：

```kotlin
// 在现有的 appModule 或新建的 themeModule 中
single { ThemeManager(get()) }
```

#### 2b. 在 OneclawApplication 中初始化

```kotlin
class OneclawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@OneclawApplication)
            modules(
                appModule,
                databaseModule,
                networkModule,
                repositoryModule,
                toolModule,
                featureModule
            )
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            get<CleanupSoftDeletedUseCase>(CleanupSoftDeletedUseCase::class.java)()
        }

        // RFC-009: 从持久化设置中初始化主题
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            get<ThemeManager>(ThemeManager::class.java).initialize()
        }
    }
}
```

### 变更 3：将 ThemeManager 接入 Compose 主题

更新 `MainActivity`，收集 `ThemeManager.themeMode` flow，并将解析后的 `darkTheme` 值传递给 `OneClawTheme`：

```kotlin
class MainActivity : ComponentActivity() {

    private val permissionChecker: PermissionChecker by inject()
    private val themeManager: ThemeManager by inject()

    // ... permissionLauncher 不变 ...

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionChecker.bindToActivity(permissionLauncher)
        enableEdgeToEdge()
        setContent {
            val themeMode by themeManager.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            OneClawTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    AppNavGraph(navController = navController)
                }
            }
        }
    }

    // ... onDestroy 不变 ...
}
```

这意味着通过 `ThemeManager.setThemeMode()` 更改主题会立即更新 `StateFlow`，触发重组，应用新的配色方案 -- 无需 Activity 重启。

### 变更 4：重组 SettingsScreen，添加分区和主题选择对话框

当前 `SettingsScreen` 是扁平列表。将其替换为分区布局，并添加主题选择对话框。

```kotlin
package com.oneclaw.shadow.feature.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.oneclaw.shadow.core.theme.ThemeManager
import com.oneclaw.shadow.core.theme.ThemeMode
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onManageProviders: () -> Unit,
    onManageAgents: () -> Unit = {},
    onUsageStatistics: () -> Unit = {},
    themeManager: ThemeManager = koinInject()
) {
    val currentTheme by themeManager.themeMode.collectAsState()
    val scope = rememberCoroutineScope()
    var showThemeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // -- Appearance --
            SectionHeader("Appearance")
            SettingsItem(
                title = "Theme",
                subtitle = when (currentTheme) {
                    ThemeMode.SYSTEM -> "System default"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                },
                onClick = { showThemeDialog = true }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // -- Providers & Models --
            SectionHeader("Providers & Models")
            SettingsItem(
                title = "Manage Providers",
                subtitle = "Add API keys, configure models",
                onClick = onManageProviders
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // -- Agents --
            SectionHeader("Agents")
            SettingsItem(
                title = "Manage Agents",
                subtitle = "Create and configure agents",
                onClick = onManageAgents
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // -- Usage --
            SectionHeader("Usage")
            SettingsItem(
                title = "Usage Statistics",
                subtitle = "View token usage by model",
                onClick = onUsageStatistics
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // -- Data & Backup (FEAT-007 占位) --
            SectionHeader("Data & Backup")
            SettingsItem(
                title = "Google Drive Sync",
                subtitle = "Not connected",
                onClick = { /* TODO: FEAT-007 */ }
            )
            SettingsItem(
                title = "Export Backup",
                subtitle = "Save all data to a file",
                onClick = { /* TODO: FEAT-007 */ }
            )
            SettingsItem(
                title = "Import Backup",
                subtitle = "Restore from a backup file",
                onClick = { /* TODO: FEAT-007 */ }
            )
        }
    }

    // 主题选择对话框
    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentTheme = currentTheme,
            onThemeSelected = { mode ->
                scope.launch { themeManager.setThemeMode(mode) }
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThemeSelectionDialog(
    currentTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme") },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = mode == currentTheme,
                                onClick = { onThemeSelected(mode) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == currentTheme,
                            onClick = null // 由 Row selectable 处理
                        )
                        Text(
                            text = when (mode) {
                                ThemeMode.SYSTEM -> "System default"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
```

关键设计决策：
- 对话框在选择单选按钮时立即关闭（即时应用）。"Cancel" 按钮用于不做更改直接关闭。
- `SectionHeader` 使用 `labelLarge` 样式和 `primary` 颜色，与 PRD 规格一致。
- `SettingsItem` composable 复用现有代码，保持不变。
- 页面内容包裹在 `verticalScroll` 中，以处理内容可能超出视口的情况。
- Usage 和 Data & Backup 条目为占位 -- 调用空 lambda 或 `TODO` 代码块，待 FEAT-006 和 FEAT-007 实现后接入。

### 变更 5：更新 NavGraph

在 NavGraph 中传递 `onUsageStatistics` 回调：

```kotlin
composable(Route.Settings.path) {
    SettingsScreen(
        onNavigateBack = { navController.popBackStack() },
        onManageProviders = { navController.navigate(Route.ProviderList.path) },
        onManageAgents = { navController.navigate(Route.AgentList.path) },
        onUsageStatistics = { navController.navigate(Route.UsageStatistics.path) }
    )
}
```

注意：`Route.UsageStatistics` 路由及其 composable 目标定义在 RFC-006 中。如果 RFC-006 尚未实现，`onUsageStatistics` 可以暂时保持为空 lambda `{}`。

## 实现步骤

### 步骤 1：创建 ThemeManager
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/core/theme/ThemeManager.kt`（新建）
- 添加 `ThemeMode` 枚举，包含 `SYSTEM`、`LIGHT`、`DARK` 以及 `fromKey()` 伴生函数
- 添加 `ThemeManager` 类，包含 `StateFlow<ThemeMode>`、`initialize()` 和 `setThemeMode()` 方法
- `initialize()` 从 `SettingsRepository` 读取并调用 `AppCompatDelegate.setDefaultNightMode()`
- `setThemeMode()` 持久化到 `SettingsRepository`，更新 StateFlow，并调用 `AppCompatDelegate.setDefaultNightMode()`

### 步骤 2：在 DI 中注册 ThemeManager
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/di/AppModule.kt`（已有）
- 在模块中添加 `single { ThemeManager(get()) }`

### 步骤 3：在应用启动时初始化主题
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/OneclawApplication.kt`（已有）
- 在 `onCreate()` 中，Koin 初始化之后，启动协程调用 `ThemeManager.initialize()`

### 步骤 4：将 ThemeManager 接入 MainActivity
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/MainActivity.kt`（已有）
- 通过 Koin 注入 `ThemeManager`
- 将 `themeManager.themeMode` 作为 Compose State 收集
- 从 `ThemeMode` 解析 `darkTheme` 布尔值，传递给 `OneClawTheme(darkTheme = ...)`

### 步骤 5：重组 SettingsScreen，添加分区和主题对话框
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/provider/SettingsScreen.kt`（已有）
- 添加 `SectionHeader` composable（`labelLarge`、`primary` 颜色）
- 将现有条目重新组织到 "Providers & Models" 和 "Agents" 分区标题下
- 添加 "Appearance" 分区，包含打开 `ThemeSelectionDialog` 的 "Theme" 入口
- 添加 "Usage" 分区，包含 "Usage Statistics" 占位入口
- 添加 "Data & Backup" 分区，包含 Google Drive Sync、Export、Import 占位入口
- 添加 `ThemeSelectionDialog` composable，使用 `AlertDialog` 和每个 `ThemeMode` 对应的 `RadioButton`
- 通过 `koinInject()` 注入 `ThemeManager`，收集 `themeMode` 以显示当前选择
- 选择主题时：在协程作用域中调用 `themeManager.setThemeMode()`，关闭对话框
- 内容包裹在 `verticalScroll` 中以处理溢出

### 步骤 6：更新 NavGraph（如果 RFC-006 已实现）
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/navigation/NavGraph.kt`（已有）
- 向 `SettingsScreen` 传递 `onUsageStatistics` 回调，导航至 `Route.UsageStatistics`

## 测试策略

### Layer 1A -- 单元测试

**`ThemeModeTest`** (`app/src/test/kotlin/.../core/theme/`):
- 测试：`ThemeMode.fromKey("system")` 返回 `ThemeMode.SYSTEM`
- 测试：`ThemeMode.fromKey("light")` 返回 `ThemeMode.LIGHT`
- 测试：`ThemeMode.fromKey("dark")` 返回 `ThemeMode.DARK`
- 测试：`ThemeMode.fromKey(null)` 返回 `ThemeMode.SYSTEM`（默认值）
- 测试：`ThemeMode.fromKey("invalid")` 返回 `ThemeMode.SYSTEM`（兜底值）

**`ThemeManagerTest`** (`app/src/test/kotlin/.../core/theme/`):
- 测试：`initialize()` 在无存储值时将 `themeMode` 设为 `SYSTEM`
- 测试：`initialize()` 在存储值为 `"dark"` 时将 `themeMode` 设为 `DARK`
- 测试：`initialize()` 在存储值为 `"light"` 时将 `themeMode` 设为 `LIGHT`
- 测试：`setThemeMode(DARK)` 将 `"dark"` 持久化到 `SettingsRepository` 并将 `themeMode` flow 更新为 `DARK`
- 测试：在 `initialize()` 之后调用 `setThemeMode(LIGHT)` 将 flow 从初始值更新为 `LIGHT`
- 测试：`initialize()` 使用正确的模式常量调用 `AppCompatDelegate.setDefaultNightMode()`

### Layer 1C -- 截图测试

- `SettingsScreen` 分区可见：验证分区标题（"Appearance"、"Providers & Models"、"Agents"、"Usage"、"Data & Backup"）以 `labelLarge` 样式和 primary 颜色渲染
- `SettingsScreen` 主题设为 "System default"：验证 "Theme" 行显示 "System default" 副标题
- `SettingsScreen` 主题设为 "Dark"：验证 "Theme" 行显示 "Dark" 副标题
- `ThemeSelectionDialog` 打开且选中 "System default"：验证三个单选选项，"System default" 处于选中状态
- `ThemeSelectionDialog` 打开且选中 "Dark"：验证 "Dark" 单选按钮处于选中状态
- `SettingsScreen` 深色模式下：验证应用了深色配色方案
- `SettingsScreen` 浅色模式下：验证应用了浅色配色方案

### Layer 2 -- adb 视觉验证

**Flow 9-1：切换主题为深色**
1. 打开 Settings
2. 验证 "Appearance" 分区标题可见
3. 点击 "Theme" -- 验证对话框打开，"System default" 预选中
4. 选择 "Dark" -- 验证对话框关闭，应用立即切换为深色主题
5. 返回 Chat 页面 -- 验证深色主题已应用
6. 强制停止并重新启动应用 -- 验证应用以深色模式启动（偏好已持久化）

**Flow 9-2：切换主题为浅色**
1. 打开 Settings > 点击 "Theme"
2. 选择 "Light" -- 验证应用切换为浅色主题
3. 将 Android 系统切换为深色模式（通过系统设置） -- 验证应用保持浅色模式（用户覆盖）

**Flow 9-3：跟随系统主题**
1. 打开 Settings > 点击 "Theme"，选择 "System default"
2. 将 Android 系统从浅色切换为深色模式 -- 验证应用自动切换为深色模式
3. 将 Android 系统切换回浅色模式 -- 验证应用切换回浅色模式

**Flow 9-4：Settings 页面分区**
1. 打开 Settings
2. 验证所有分区标题可见：Appearance、Providers & Models、Agents、Usage、Data & Backup
3. 点击 "Manage Providers" -- 验证导航到 provider 列表
4. 返回，点击 "Manage Agents" -- 验证导航到 agent 列表
5. 返回，验证 "Usage Statistics"、"Google Drive Sync"、"Export Backup"、"Import Backup" 条目可见

**Flow 9-5：系统字体大小**
1. 在 Android 系统设置中，将字体大小设为"最大"
2. 打开应用
3. 验证所有文本（设置标签、分区标题、对话框文本）以更大的字体渲染
4. 将系统字体大小恢复为默认 -- 验证应用文本恢复正常大小

## 数据流

### 主题初始化（应用启动）

```
OneclawApplication.onCreate()
  -> ThemeManager.initialize()
  -> SettingsRepository.getString("theme_mode")
  -> SettingsDao.getString("theme_mode")
  -> SQL: SELECT value FROM app_settings WHERE key = 'theme_mode'
  -> 返回 null（全新安装）或 "system" / "light" / "dark"
  -> ThemeMode.fromKey(stored) -> ThemeMode 枚举值
  -> _themeMode.value = mode（StateFlow 更新）
  -> AppCompatDelegate.setDefaultNightMode(映射后的模式常量)
```

### 主题切换（用户操作）

```
用户在 SettingsScreen 中点击 Theme
  -> showThemeDialog = true
  -> ThemeSelectionDialog 渲染，currentTheme 预选中
  -> 用户点击 "Dark" 单选按钮
  -> onThemeSelected(ThemeMode.DARK) 被调用
  -> scope.launch { themeManager.setThemeMode(ThemeMode.DARK) }
    -> SettingsRepository.setString("theme_mode", "dark")
    -> SettingsDao.set(SettingsEntity(key = "theme_mode", value = "dark"))
    -> _themeMode.value = DARK（StateFlow 更新）
    -> AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES)
  -> showThemeDialog = false（对话框关闭）
  -> MainActivity: themeMode 作为 State 收集 -> darkTheme = true
  -> OneClawTheme(darkTheme = true) 使用 darkScheme 重组
```

### Compose 主题解析

```
MainActivity.setContent
  -> val themeMode by themeManager.themeMode.collectAsState()
  -> when (themeMode):
       SYSTEM -> isSystemInDarkTheme()（委托给 Android 系统）
       LIGHT  -> false
       DARK   -> true
  -> OneClawTheme(darkTheme = 解析后的值)
  -> MaterialTheme 相应地使用 lightScheme 或 darkScheme
```

## 变更历史

| 日期 | 版本 | 变更 | 作者 |
|------|------|------|------|
| 2026-02-28 | 0.1 | 初始草稿 | TBD |
