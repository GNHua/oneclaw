# 入门指南

本指南介绍如何搭建开发环境、构建并测试 OneClaw。

## 前置要求

- **Android Studio** Hedgehog (2023.1.1) 或更高版本
- **JDK 17**（项目要求）
- **Android SDK**，compileSdk 35，minSdk 26
- **Git**
- **Android 模拟器或实体设备**（用于仪器化测试）

## 克隆与构建

```bash
git clone <repository-url>
cd oneclaw-1

# 构建 debug APK
./gradlew assembleDebug
```

debug APK 输出路径为 `app/build/outputs/apk/debug/app-debug.apk`。

## 在 Android Studio 中配置项目

1. 打开 Android Studio
2. 依次点击 File > Open，选择 `oneclaw-1` 目录
3. 等待 Gradle 同步完成
4. 选择 `app` 运行配置
5. 在模拟器或已连接的设备上运行

## 运行测试

测试按层级组织，实现变更后应按顺序依次执行：

### Layer 1A：JVM 单元测试

在 JVM 上运行的快速测试，无需 Android 设备。

```bash
# 运行所有单元测试
./gradlew test

# 运行指定测试类
./gradlew test --tests "com.oneclaw.shadow.data.repository.ProviderRepositoryImplTest"

# 运行指定测试方法
./gradlew test --tests "com.oneclaw.shadow.data.repository.ProviderRepositoryImplTest.someTestMethod"
```

使用 JUnit 5 并附带 vintage 引擎以兼容 JUnit 4（Robolectric 所需）。

### Layer 1B：仪器化测试

在 Android 设备或模拟器上运行的测试。

```bash
# 需要在 5554 端口上运行模拟器
ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest
```

### Layer 1C：Roborazzi 截图测试

基于 Robolectric 和 Roborazzi 的视觉回归测试。

```bash
# 录制基准截图
./gradlew recordRoborazziDebug

# 与基准截图进行对比验证
./gradlew verifyRoborazziDebug
```

### 仅编译检查

在不运行测试的情况下快速进行编译检查：

```bash
./gradlew compileDebugUnitTestKotlin
./gradlew compileDebugAndroidTestKotlin
```

## 建议优先阅读的关键文件

从以下文件入手，了解项目的整体结构：

| 文件 | 说明 |
|------|---------|
| `CLAUDE.md` | 项目规范与构建命令 |
| `app/build.gradle.kts` | 依赖项与构建配置 |
| `app/src/main/kotlin/.../MainActivity.kt` | 应用入口 |
| `app/src/main/kotlin/.../OneclawApplication.kt` | Application 类（Koin 初始化） |
| `app/src/main/kotlin/.../di/` | 全部 8 个 Koin DI 模块 |
| `app/src/main/kotlin/.../navigation/Routes.kt` | 所有页面路由 |
| `app/src/main/kotlin/.../navigation/NavGraph.kt` | 导航图 |
| `app/src/main/kotlin/.../core/model/` | 所有领域模型 |
| `app/src/main/kotlin/.../core/repository/` | Repository 接口 |
| `app/src/main/kotlin/.../tool/engine/Tool.kt` | Tool 接口 |

## 技术栈

| 分类 | 技术 | 版本 |
|----------|-----------|---------|
| 语言 | Kotlin | 2.0.21 |
| UI | Jetpack Compose + Material 3 | BOM 2024.12.01 |
| 依赖注入 | Koin | 3.5.6 |
| 数据库 | Room | 2.6.1 |
| 网络 | OkHttp + Retrofit | 4.12.0 / 2.11.0 |
| 序列化 | kotlinx.serialization | 1.7.3 |
| 导航 | Navigation Compose | 2.8.5 |
| 安全 | EncryptedSharedPreferences | 1.1.0-alpha06 |
| JS 引擎 | QuickJS (quickjs-kt) | 1.0.0-alpha13 |
| PDF | PDFBox Android | 2.0.27.0 |
| HTML 解析 | Jsoup | 1.18.3 |
| 图片加载 | Coil | 3.0.4 |
| Markdown | multiplatform-markdown-renderer | 0.28.0 |
| 构建 | Android Gradle Plugin | 8.7.3 |
| 测试 | JUnit 5, MockK, Turbine, Roborazzi | 各版本不同 |

## 构建变体

- **debug** -- 启用调试的开发构建
- **release** -- 经 ProGuard/R8 混淆压缩并使用正式签名的发布构建

release 构建需要在项目根目录下提供包含 keystore 配置的 `signing.properties` 文件。
