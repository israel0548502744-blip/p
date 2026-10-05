// Lets the pure-Kotlin core build and test on its own (no Android SDK needed):
//   gradle -p android/core test
// When built as part of android/, this file is ignored and versions come from android/settings.gradle.kts.
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins {
        kotlin("jvm") version "2.2.21"
        kotlin("plugin.serialization") version "2.2.21"
    }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "blueshield-core"
