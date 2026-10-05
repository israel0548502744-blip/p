# SDK-less type check

Compiles every Kotlin source of the Android app **without the Android SDK or Google's Maven
repository** (useful on locked-down CI or when `dl.google.com` is unreachable):

```bash
cd android/tools/sdkless-check
gradle compileKotlin        # or ../../gradlew -p . compileKotlin
```

How: real AOSP framework classes come from Robolectric's `android-all` jar, Compose from JetBrains
Compose Multiplatform (same `androidx.compose.*` API), ONNX Runtime from its Android AAR — all on
Maven Central. The few Google-Maven-only APIs the app uses (activity, media3, core, lifecycle) are
signature stubs in `stubs/`, and `stubs/r/R.kt` stands in for the generated `R` class.

This is a compile check only. Build the real APK with the Android SDK (see the top-level README).
