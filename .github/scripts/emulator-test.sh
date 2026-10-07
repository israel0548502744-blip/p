#!/usr/bin/env bash
# Runs the instrumented end-to-end tests; always prints the relevant logcat so crashes are visible in the CI log.
./gradlew :app:connectedEmulatorDebugAndroidTest --no-daemon --stacktrace
status=$?
mkdir -p app/build/e2e-out
adb exec-out run-as com.blueshield.app cat files/e2e_output.mp4 > app/build/e2e-out/e2e_output.mp4 2>/dev/null || true
echo "================ engines tried ================"
adb logcat -d | grep -E "BlueShield: (model: .*(unavailable|not used|loaded)|engines|timings)" | head -120
echo "================ logcat (filtered) ================"
adb logcat -d -t 4000 | grep -E "AndroidRuntime|FATAL|BlueShield|libc  |DEBUG  |onnxruntime|MediaCodec|ProcessingService|SIGSEGV|Fatal signal" | tail -400
exit $status
