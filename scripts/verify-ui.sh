#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${ANDROID_SERIAL:?Set ANDROID_SERIAL to your test emulator, e.g. emulator-5556}"
if [[ "$serial" != emulator-* ]]; then echo 'UI fixture is intended only for a disposable emulator.' >&2; exit 2; fi
adb_cli="${ADB:-adb}"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
"$adb_cli" -s "$serial" install -r -t app/build/outputs/apk/debug/app-debug.apk
"$adb_cli" -s "$serial" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
report="$(mktemp)"
trap 'rm -f "$report"' EXIT
"$adb_cli" -s "$serial" shell am instrument -w com.rummiclub.app.test/com.rummiclub.app.UiSmoke | tee "$report"
rg -q '^PASS' "$report"
if rg -q '^FAIL' "$report"; then exit 1; fi
mkdir -p artifacts/ui
for name in portrait warning landscape help; do
  "$adb_cli" -s "$serial" pull "/sdcard/Android/data/com.rummiclub.app/files/ui-$name.png" "artifacts/ui/$name.png"
done
