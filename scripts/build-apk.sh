#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :core:test :server:test :app:lintDebug :app:assembleDebug
mkdir -p artifacts
cp app/build/outputs/apk/debug/app-debug.apk artifacts/RummiClub-debug.apk
sha256sum artifacts/RummiClub-debug.apk > artifacts/SHA256SUMS
echo "APK: $(pwd)/artifacts/RummiClub-debug.apk"
