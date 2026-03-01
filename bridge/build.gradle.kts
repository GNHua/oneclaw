plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.oneclaw.shadow.bridge"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Koin
    implementation(libs.koin.android)

    // OkHttp (HTTP + WebSocket)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // JSON Serialization
    implementation(libs.kotlinx.serialization.json)

    // Security (encrypted credentials)
    implementation(libs.security.crypto)

    // WorkManager (watchdog)
    implementation(libs.work.runtime.ktx)

    // NanoHTTPD (webhook + WebSocket servers)
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.websocket)

    // Commonmark (Markdown -> HTML for Telegram)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.strikethrough)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
