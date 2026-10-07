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
        // CI passes -PbuildNumber=<run number>: every build gets a higher versionCode, so it installs as an update
        val build = (findProperty("buildNumber") as String?)?.toIntOrNull()
        versionCode = if (build != null) 100 + build else 6
        versionName = "1.5" + if (build != null) ".$build" else ".0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // "phone": the APK people install — 64-bit ARM, ONNX Runtime built with every accelerator (Qualcomm QNN for
    // Snapdragon NPUs/GPUs, WebGPU for any Vulkan GPU, NNAPI, XNNPACK). "emulator": x86_64 for the emulator
    // tests, plain ONNX Runtime (accelerators are absent there, so the fallback to the CPU is what gets tested).
    // Same code in both; only the native runtime differs.
    flavorDimensions += "runtime"
    productFlavors {
        create("phone") {
            dimension = "runtime"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("emulator") {
            dimension = "runtime"
            ndk { abiFilters += "x86_64" }
        }
    }

    // CI passes -PsigningStore=<keystore> (kept in the repository's private Actions cache, never in the code):
    // every build is signed with the same key, so a new version installs over the old one
    signingConfigs {
        (findProperty("signingStore") as String?)?.let { path ->
            create("ci") {
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        debug { signingConfigs.findByName("ci")?.let { signingConfig = it } }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // CI keeps one debug key across builds (see android-apk.yml)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("onnx") }
    packaging {
        resources { excludes += listOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
        jniLibs {
            // the Qualcomm libraries must be extracted to disk for the AI chip to load them (and compress well)
            useLegacyPackaging = true
            // DSP libraries for chips older than any phone that runs this app's models in reasonable time
            excludes += listOf("**/libQnnDsp*.so")
        }
    }

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
    "phoneImplementation"("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
    "emulatorImplementation"("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

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
