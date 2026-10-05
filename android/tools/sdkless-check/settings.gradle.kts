// See README.md in this folder.
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins { kotlin("jvm") version "2.2.21"; kotlin("plugin.compose") version "2.2.21"; kotlin("plugin.serialization") version "2.2.21" }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "sdkless-check"
includeBuild("../../core")
