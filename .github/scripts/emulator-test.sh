#!/usr/bin/env bash
# Runs the instrumented end-to-end tests; always prints the relevant logcat so crashes are visible in the CI log.
./gradlew :app:connectedEmulatorDebugAndroidTest --no-daemon --stacktrace
status=$?
echo "================ engines tried ================"
adb logcat -d | grep -E "BlueShield: (model: .*(unavailable|not used|loaded)|engines|timings)" | head -120
echo "================ logcat (filtered) ================"
adb logcat -d -t 4000 | grep -E "AndroidRuntime|FATAL|BlueShield|libc  |DEBUG  |onnxruntime|MediaCodec|ProcessingService|SIGSEGV|Fatal signal" | tail -400
exit $status
