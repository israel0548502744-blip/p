#!/usr/bin/env bash
# Runs the instrumented end-to-end tests; always prints the relevant logcat so crashes are visible in the CI log.
./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace
status=$?
echo "================ logcat (filtered) ================"
adb logcat -d -t 4000 | grep -E "AndroidRuntime|FATAL|BlueShield|libc  |DEBUG  |onnxruntime|MediaCodec|ProcessingService|SIGSEGV|Fatal signal" | tail -400
exit $status
