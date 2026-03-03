# Getting Started

This guide covers setting up the development environment, building, and testing OneClaw.

## Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or later
- **JDK 17** (required by the project)
- **Android SDK** with compileSdk 35 and minSdk 26
- **Git**
- **Android emulator or physical device** (for instrumented tests)

## Clone and Build

```bash
git clone <repository-url>
cd oneclaw-1

# Build debug APK
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Project Setup in Android Studio

1. Open Android Studio
2. File > Open > select the `oneclaw-1` directory
3. Wait for Gradle sync to complete
4. Select the `app` run configuration
5. Run on an emulator or connected device

## Running Tests

Tests are organized in layers and should be run in order after implementing changes:

### Layer 1A: JVM Unit Tests

Fast tests that run on the JVM without an Android device.

```bash
# Run all unit tests
./gradlew test

# Run a specific test class
./gradlew test --tests "com.oneclaw.shadow.data.repository.ProviderRepositoryImplTest"

# Run a specific test method
./gradlew test --tests "com.oneclaw.shadow.data.repository.ProviderRepositoryImplTest.someTestMethod"
```

Uses JUnit 5 with the vintage engine for JUnit 4 compatibility (needed by Robolectric).

### Layer 1B: Instrumented Tests

Tests that run on an Android device or emulator.

```bash
# Requires a running emulator on port 5554
ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest
```

### Layer 1C: Roborazzi Screenshot Tests

Visual regression tests using Robolectric and Roborazzi.

```bash
# Record baseline screenshots
./gradlew recordRoborazziDebug

# Verify against baselines
./gradlew verifyRoborazziDebug
```

### Compile Checks Only

Quick compilation checks without running tests:

```bash
./gradlew compileDebugUnitTestKotlin
./gradlew compileDebugAndroidTestKotlin
```

## Key Files to Understand First

Start with these files to understand the overall structure:

| File | Purpose |
|------|---------|
| `CLAUDE.md` | Project conventions and build commands |
| `app/build.gradle.kts` | Dependencies and build config |
| `app/src/main/kotlin/.../MainActivity.kt` | App entry point |
| `app/src/main/kotlin/.../OneclawApplication.kt` | Application class (Koin init) |
| `app/src/main/kotlin/.../di/` | All 8 Koin DI modules |
| `app/src/main/kotlin/.../navigation/Routes.kt` | All screen routes |
| `app/src/main/kotlin/.../navigation/NavGraph.kt` | Navigation graph |
| `app/src/main/kotlin/.../core/model/` | All domain models |
| `app/src/main/kotlin/.../core/repository/` | Repository interfaces |
| `app/src/main/kotlin/.../tool/engine/Tool.kt` | Tool interface |

## Tech Stack

| Category | Technology | Version |
|----------|-----------|---------|
| Language | Kotlin | 2.0.21 |
| UI | Jetpack Compose + Material 3 | BOM 2024.12.01 |
| DI | Koin | 3.5.6 |
| Database | Room | 2.6.1 |
| Network | OkHttp + Retrofit | 4.12.0 / 2.11.0 |
| Serialization | kotlinx.serialization | 1.7.3 |
| Navigation | Navigation Compose | 2.8.5 |
| Security | EncryptedSharedPreferences | 1.1.0-alpha06 |
| JS Engine | QuickJS (quickjs-kt) | 1.0.0-alpha13 |
| PDF | PDFBox Android | 2.0.27.0 |
| HTML Parsing | Jsoup | 1.18.3 |
| Image Loading | Coil | 3.0.4 |
| Markdown | multiplatform-markdown-renderer | 0.28.0 |
| Build | Android Gradle Plugin | 8.7.3 |
| Test | JUnit 5, MockK, Turbine, Roborazzi | Various |

## Build Variants

- **debug** -- Development build with debugging enabled
- **release** -- Minified build with ProGuard/R8 and release signing

Release builds require a `signing.properties` file at the project root with keystore configuration.
