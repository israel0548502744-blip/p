# BlueShield for Android

Standalone, on-device version of BlueShield (Kotlin · Jetpack Compose · MediaCodec · OpenGL ES · ONNX Runtime).
See the top-level README (sections 2–6) for build instructions, architecture and limitations.

```bash
./gradlew assemblePhoneDebug       # → app/build/outputs/apk/phone/debug/app-phone-debug.apk  (needs the Android SDK)
gradle -p core test                # pipeline unit + integration tests on the JVM (no SDK needed; uses ffmpeg for test frames)
gradle -p tools/sdkless-check compileKotlin   # type-check the whole app without the SDK
```

| Module | Contents |
|---|---|
| `core/` | Platform-neutral pipeline (same algorithms as desktop): models (ONNX Runtime), person/region tracking, gender votes, optical flow, temporal fusion, ownership mask composition, mask store. |
| `app/` | Android app: `engine/` (MediaCodec decode, GL compositor, encoder + muxer, audio, MediaStore/FileProvider), `service/` (foreground processing), `ui/` (Compose screens). |
| `core/.../ml/EngineCheck.kt` | Which processor each model may run on (video only; photos always use the plain CPU engine): an AI chip / GPU / XNNPACK engine is used for a model only if it computes the same skin mask, boxes, gender / age and outlines as the CPU on a real bundled photo (`core/src/main/resources/blueshield/engine_probe.jpg`, CC-BY-4.0 Intel); the outline encoders never run on 16-bit engines. |
| `tools/sdkless-check/` | Compile check against AOSP framework classes when the Android SDK is unavailable. |
