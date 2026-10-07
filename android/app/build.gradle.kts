plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "com.blueshield.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blueshield.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 64-bit phones + x86_64 emulators; keeps the APK small (ONNX Runtime native libs are per-ABI).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // replace with your own key for distribution
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("onnx") }
    packaging { resources { excludes += listOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") } }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/modelAssets"))
    // short fixture clips (woman + man, four men) used by the emulator end-to-end test
    sourceSets["androidTest"].assets.srcDir(rootDir.resolve("../tests/fixtures"))
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// The ONNX models are committed at <repo>/models/onnx and copied into the APK's assets.
val copyModels by tasks.registering(Copy::class) {
    from(rootDir.resolve("../models/onnx")) { include("*.onnx") }
    into(layout.buildDirectory.dir("generated/modelAssets/models"))
}
tasks.named("preBuild") { dependsOn(copyModels) }

dependencies {
    implementation(project(":core"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
