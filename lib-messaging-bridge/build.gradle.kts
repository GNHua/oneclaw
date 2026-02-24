plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tomandy.oneclaw.bridge"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)

    // OkHttp (for Telegram + Discord API calls)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // JSON Serialization
    implementation(libs.kotlinx.serialization.json)

    // Security (encrypted credentials)
    implementation(libs.androidx.security.crypto)

    // NanoHTTPD (for WebChat server)
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.websocket)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
