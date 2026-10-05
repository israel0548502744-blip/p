plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val ortVersion = "1.20.0"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Same `ai.onnxruntime` API as onnxruntime-android; the app supplies the Android build at runtime.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

// The shared spec + models live at the repository root.
val repoRoot = rootDir.resolve(if (rootDir.name == "core") "../.." else "..").normalize()
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/spec"))
val copySpec by tasks.registering(Copy::class) {
    from(repoRoot.resolve("shared/pipeline.json"))
    into(layout.buildDirectory.dir("generated/spec/blueshield"))
}
tasks.named("processResources") { dependsOn(copySpec) }

tasks.test {
    useJUnit()
    systemProperty("blueshield.repo", repoRoot.absolutePath)
    maxHeapSize = "2g"
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
}
