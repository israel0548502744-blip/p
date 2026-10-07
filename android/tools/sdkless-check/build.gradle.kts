// Type-checks the whole Android app (UI, ViewModel, service, engine) on a machine WITHOUT the
// Android SDK / Google Maven:
//   • Android framework  = Robolectric "android-all" jar (real AOSP classes, Maven Central)
//   • ONNX Runtime        = onnxruntime-android AAR classes (Maven Central)
//   • Compose             = JetBrains Compose Multiplatform 1.7 (same androidx.compose API, Maven Central)
//   • activity/media3/core/lifecycle (Google-Maven-only) = signature stubs in stubs/
// It only compiles; it does not produce an APK. Use it when the real toolchain is unavailable.
import java.io.File
import java.net.URI
import java.util.zip.ZipFile

plugins { kotlin("jvm"); kotlin("plugin.compose") }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
sourceSets["main"].kotlin.setSrcDirs(listOf("../../app/src/main/java", "stubs"))

val libs = layout.buildDirectory.dir("libs")
val fetchFrameworkJars by tasks.registering {
    val out = libs
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.apply { mkdirs() }
        fun get(url: String, dest: File) { if (!dest.exists()) URI(url).toURL().openStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } } }
        val v = "15-robolectric-13954326"
        get("https://repo.maven.apache.org/maven2/org/robolectric/android-all/$v/android-all-$v.jar", dir.resolve("android-all.jar"))
        val ort = "1.29.0"
        val aar = dir.resolve("ort-$ort.aar")
        get("https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ort/onnxruntime-android-$ort.aar", aar)
        ZipFile(aar).use { z -> z.getInputStream(z.getEntry("classes.jar")).use { i -> dir.resolve("ort-classes.jar").outputStream().use { o -> i.copyTo(o) } } }
    }
}
tasks.named("compileKotlin") { dependsOn(fetchFrameworkJars) }

dependencies {
    compileOnly(files(libs.map { it.file("android-all.jar") }, libs.map { it.file("ort-classes.jar") }))
    implementation("com.blueshield:blueshield-core")
    implementation("org.jetbrains.compose.ui:ui-desktop:1.7.3")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:1.7.3")
    implementation("org.jetbrains.compose.material3:material3-desktop:1.7.3")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
