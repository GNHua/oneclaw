pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "OneClaw"
include(":app")
include(":plugin-runtime")
include(":core-agent")
include(":lib-scheduler")
include(":plugin-manager")
include(":lib-workspace")
include(":skill-engine")
include(":lib-device-control")
include(":lib-web")
include(":lib-qrcode")
include(":lib-location")
include(":lib-notification-media")
include(":lib-pdf")
include(":lib-camera")
include(":lib-sms-phone")
include(":lib-voice-memo")
include(":lib-messaging-bridge")
