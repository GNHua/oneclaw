# RFC-009: Settings

## Document Information
- **RFC ID**: RFC-009
- **Related PRD**: [FEAT-009 (Settings)](../../prd/features/FEAT-009-settings.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Depends On**: None (Settings screen already exists)
- **Depended On By**: [RFC-006 (Token Tracking)](RFC-006-token-tracking.md), FEAT-007 (Data Sync)
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

The Settings screen currently has two flat entries: "Manage Agents" and "Manage Providers". As the app grows, more features (token tracking, data sync, appearance customization) will add their own entries to Settings. Without structure, the screen becomes a long unsorted list.

This RFC adds an Appearance section with theme control and reorganizes the Settings screen into labeled sections. Font size follows the Android system setting automatically (Compose `sp` units). Language is English only in V1.

The existing `app_settings` table (key-value store via `SettingsDao`) is the persistence layer for theme preference. The existing `OneClawTheme` composable already supports a `darkTheme` boolean parameter. The implementation bridges these two: read the stored preference at app start, apply it via `AppCompatDelegate.setDefaultNightMode()`, and provide a Compose-level `darkTheme` override so that `OneClawTheme` responds immediately without requiring an Activity recreation.

### Goals

1. Reorganize the Settings screen into labeled sections: Appearance, Providers & Models, Agents, Usage, Data & Backup.
2. Add a Theme setting with three options: System default, Light, Dark.
3. Theme change takes effect immediately (no app restart).
4. Theme preference persists across app restarts.
5. Font size follows the Android system setting (no code changes needed -- Compose `sp` units handle this).

### Non-Goals

- In-app font size control (deferred to future version).
- Language selection / localization.
- Notification preferences.
- About / version info screen.
- Implementing the Usage or Data & Backup features (those are FEAT-006 and FEAT-007 -- this RFC only adds placeholder entries).

## Technical Design

### Architecture Overview

```
Theme preference flow:

1. App Launch
   OneclawApplication.onCreate()
     -> SettingsRepository.getString("theme_mode")
     -> AppCompatDelegate.setDefaultNightMode(mode)
     -> ThemeManager.themeMode (StateFlow) initialized

2. Compose Theme
   MainActivity.setContent
     -> ThemeManager.themeMode collected as State
     -> OneClawTheme(darkTheme = resolved from themeMode)

3. User Changes Theme (SettingsScreen)
   User taps Theme -> AlertDialog with RadioButtons
     -> ThemeManager.setThemeMode(newMode)
       -> SettingsRepository.setString("theme_mode", newMode)
       -> AppCompatDelegate.setDefaultNightMode(mode)
       -> ThemeManager.themeMode updated -> Compose recomposes
```

No schema changes. The `app_settings` table already exists with key-value storage.

### Change 1: ThemeManager

A singleton that holds the current theme mode as a `StateFlow` and provides methods to initialize and update it. It acts as the single source of truth for the theme state.

New file: `core/theme/ThemeManager.kt`

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

### Change 2: Register ThemeManager in DI and Initialize at App Start

#### 2a. DI Registration

In `AppModule.kt` (or the appropriate DI module where singletons live), register `ThemeManager` as a singleton:

```kotlin
// In the existing appModule or a new themeModule
single { ThemeManager(get()) }
```

#### 2b. Initialize in OneclawApplication

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

        // RFC-009: Initialize theme from persisted setting
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            get<ThemeManager>(ThemeManager::class.java).initialize()
        }
    }
}
```

### Change 3: Wire ThemeManager into Compose Theme

Update `MainActivity` to collect the `ThemeManager.themeMode` flow and pass the resolved `darkTheme` value to `OneClawTheme`:

```kotlin
class MainActivity : ComponentActivity() {

    private val permissionChecker: PermissionChecker by inject()
    private val themeManager: ThemeManager by inject()

    // ... permissionLauncher unchanged ...

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

    // ... onDestroy unchanged ...
}
```

This means theme changes via `ThemeManager.setThemeMode()` immediately update the `StateFlow`, which triggers a recomposition, which applies the new color scheme -- no Activity restart needed.

### Change 4: Reorganize SettingsScreen with Sections and Theme Dialog

The current `SettingsScreen` is a flat list. Replace it with a sectioned layout and add a theme selection dialog.

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

            // -- Data & Backup (placeholder for FEAT-007) --
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

    // Theme selection dialog
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
                            onClick = null // handled by Row selectable
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

Key design decisions:
- The dialog dismisses on radio button selection (immediate apply). The "Cancel" button is for dismissing without changing.
- `SectionHeader` uses `labelLarge` style with `primary` color, consistent with the PRD specification.
- The `SettingsItem` composable is reused from the existing code, unchanged.
- The screen is wrapped in `verticalScroll` to handle content that may exceed the viewport.
- Usage and Data & Backup entries are placeholder -- they call empty lambdas or `TODO` blocks until FEAT-006 and FEAT-007 are wired.

### Change 5: Update NavGraph

Pass the `onUsageStatistics` callback in the NavGraph:

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

Note: The `Route.UsageStatistics` route and its composable destination are defined in RFC-006. If RFC-006 is not yet implemented, `onUsageStatistics` can remain an empty lambda `{}` until then.

## Implementation Steps

### Step 1: Create ThemeManager
- File: `app/src/main/kotlin/com/oneclaw/shadow/core/theme/ThemeManager.kt` (new)
- Add `ThemeMode` enum with `SYSTEM`, `LIGHT`, `DARK` and `fromKey()` companion function
- Add `ThemeManager` class with `StateFlow<ThemeMode>`, `initialize()`, and `setThemeMode()` methods
- `initialize()` reads from `SettingsRepository` and calls `AppCompatDelegate.setDefaultNightMode()`
- `setThemeMode()` persists to `SettingsRepository`, updates StateFlow, and calls `AppCompatDelegate.setDefaultNightMode()`

### Step 2: Register ThemeManager in DI
- File: `app/src/main/kotlin/com/oneclaw/shadow/di/AppModule.kt` (existing)
- Add `single { ThemeManager(get()) }` to the module

### Step 3: Initialize theme at app start
- File: `app/src/main/kotlin/com/oneclaw/shadow/OneclawApplication.kt` (existing)
- In `onCreate()`, after Koin initialization, launch a coroutine to call `ThemeManager.initialize()`

### Step 4: Wire ThemeManager into MainActivity
- File: `app/src/main/kotlin/com/oneclaw/shadow/MainActivity.kt` (existing)
- Inject `ThemeManager` via Koin
- Collect `themeManager.themeMode` as Compose state
- Resolve `darkTheme` boolean from `ThemeMode` and pass to `OneClawTheme(darkTheme = ...)`

### Step 5: Reorganize SettingsScreen with sections and theme dialog
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/provider/SettingsScreen.kt` (existing)
- Add `SectionHeader` composable (`labelLarge`, `primary` color)
- Reorganize existing items under "Providers & Models" and "Agents" section headers
- Add "Appearance" section with "Theme" entry that opens `ThemeSelectionDialog`
- Add "Usage" section with "Usage Statistics" placeholder entry
- Add "Data & Backup" section with placeholder entries for Google Drive Sync, Export, Import
- Add `ThemeSelectionDialog` composable with `AlertDialog`, `RadioButton` for each `ThemeMode`
- Inject `ThemeManager` via `koinInject()`, collect `themeMode` to show current selection
- On theme selection: call `themeManager.setThemeMode()` in a coroutine scope, dismiss dialog
- Wrap content in `verticalScroll` to handle overflow

### Step 6: Update NavGraph (if RFC-006 is implemented)
- File: `app/src/main/kotlin/com/oneclaw/shadow/navigation/NavGraph.kt` (existing)
- Pass `onUsageStatistics` callback to `SettingsScreen` that navigates to `Route.UsageStatistics`

## Test Strategy

### Layer 1A -- Unit Tests

**`ThemeModeTest`** (`app/src/test/kotlin/.../core/theme/`):
- Test: `ThemeMode.fromKey("system")` returns `ThemeMode.SYSTEM`
- Test: `ThemeMode.fromKey("light")` returns `ThemeMode.LIGHT`
- Test: `ThemeMode.fromKey("dark")` returns `ThemeMode.DARK`
- Test: `ThemeMode.fromKey(null)` returns `ThemeMode.SYSTEM` (default)
- Test: `ThemeMode.fromKey("invalid")` returns `ThemeMode.SYSTEM` (fallback)

**`ThemeManagerTest`** (`app/src/test/kotlin/.../core/theme/`):
- Test: `initialize()` with no stored value sets `themeMode` to `SYSTEM`
- Test: `initialize()` with stored `"dark"` sets `themeMode` to `DARK`
- Test: `initialize()` with stored `"light"` sets `themeMode` to `LIGHT`
- Test: `setThemeMode(DARK)` persists `"dark"` to `SettingsRepository` and updates `themeMode` flow to `DARK`
- Test: `setThemeMode(LIGHT)` after `initialize()` updates the flow from the initial value to `LIGHT`
- Test: `initialize()` calls `AppCompatDelegate.setDefaultNightMode()` with the correct mode constant

### Layer 1C -- Screenshot Tests

- `SettingsScreen` with sections visible: verify section headers ("Appearance", "Providers & Models", "Agents", "Usage", "Data & Backup") are rendered with `labelLarge` style and primary color
- `SettingsScreen` with theme set to "System default": verify "Theme" row shows "System default" subtitle
- `SettingsScreen` with theme set to "Dark": verify "Theme" row shows "Dark" subtitle
- `ThemeSelectionDialog` open with "System default" selected: verify three radio options, "System default" is checked
- `ThemeSelectionDialog` open with "Dark" selected: verify "Dark" radio is checked
- `SettingsScreen` in dark mode: verify dark color scheme is applied
- `SettingsScreen` in light mode: verify light color scheme is applied

### Layer 2 -- adb Visual Verification

**Flow 9-1: Change theme to Dark**
1. Open Settings
2. Verify "Appearance" section header is visible
3. Tap "Theme" -- verify dialog opens with "System default" pre-selected
4. Select "Dark" -- verify dialog dismisses and app switches to dark theme immediately
5. Navigate back to Chat screen -- verify dark theme is applied
6. Force-stop and relaunch the app -- verify app launches in dark mode (preference persisted)

**Flow 9-2: Change theme to Light**
1. Open Settings > tap "Theme"
2. Select "Light" -- verify app switches to light theme
3. Change Android system to dark mode (via system Settings) -- verify app stays in light mode (user override)

**Flow 9-3: System default theme**
1. Open Settings > tap "Theme", select "System default"
2. Change Android system from light to dark mode -- verify app switches to dark mode automatically
3. Change Android system back to light mode -- verify app switches back to light mode

**Flow 9-4: Settings screen sections**
1. Open Settings
2. Verify all section headers visible: Appearance, Providers & Models, Agents, Usage, Data & Backup
3. Tap "Manage Providers" -- verify navigation to provider list
4. Go back, tap "Manage Agents" -- verify navigation to agent list
5. Go back, verify "Usage Statistics", "Google Drive Sync", "Export Backup", "Import Backup" entries are visible

**Flow 9-5: System font size**
1. In Android system Settings, set font size to "Largest"
2. Open the app
3. Verify all text (settings labels, section headers, dialog text) renders at the larger font size
4. Set system font size back to default -- verify app text returns to normal size

## Data Flow

### Theme Initialization (App Launch)

```
OneclawApplication.onCreate()
  -> ThemeManager.initialize()
  -> SettingsRepository.getString("theme_mode")
  -> SettingsDao.getString("theme_mode")
  -> SQL: SELECT value FROM app_settings WHERE key = 'theme_mode'
  -> returns null (fresh install) or "system" / "light" / "dark"
  -> ThemeMode.fromKey(stored) -> ThemeMode enum value
  -> _themeMode.value = mode (StateFlow updated)
  -> AppCompatDelegate.setDefaultNightMode(mapped mode constant)
```

### Theme Change (User Action)

```
User taps Theme in SettingsScreen
  -> showThemeDialog = true
  -> ThemeSelectionDialog renders with currentTheme pre-selected
  -> User taps "Dark" radio button
  -> onThemeSelected(ThemeMode.DARK) called
  -> scope.launch { themeManager.setThemeMode(ThemeMode.DARK) }
    -> SettingsRepository.setString("theme_mode", "dark")
    -> SettingsDao.set(SettingsEntity(key = "theme_mode", value = "dark"))
    -> _themeMode.value = DARK (StateFlow updated)
    -> AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES)
  -> showThemeDialog = false (dialog dismisses)
  -> MainActivity: themeMode collected as State -> darkTheme = true
  -> OneClawTheme(darkTheme = true) recomposes with darkScheme
```

### Compose Theme Resolution

```
MainActivity.setContent
  -> val themeMode by themeManager.themeMode.collectAsState()
  -> when (themeMode):
       SYSTEM -> isSystemInDarkTheme() (delegates to Android system)
       LIGHT  -> false
       DARK   -> true
  -> OneClawTheme(darkTheme = resolved)
  -> MaterialTheme uses lightScheme or darkScheme accordingly
```

## Change History

| Date | Version | Change | Author |
|------|---------|--------|--------|
| 2026-02-28 | 0.1 | Initial draft | TBD |
