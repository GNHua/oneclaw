plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tomandy.palmclaw.agent"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties",
                "mozilla/public-suffix-list.txt",
            )
        }
    }
}

dependencies {
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Networking (for LLM clients)
    api(libs.retrofit)
    api(libs.retrofit.kotlinx.serialization)
    api(libs.okhttp)
    api(libs.okhttp.logging)

    // Google Generative AI (official java-genai SDK)
    implementation(libs.google.genai)

    // Anthropic Claude SDK
    implementation(libs.anthropic.java)

    // Plugin runtime (for Plugin interface & tool definitions)
    api(project(":plugin-runtime"))

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.mockwebserver)
}
