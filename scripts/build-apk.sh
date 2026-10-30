#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :core:test :server:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
mkdir -p artifacts
cp app/build/outputs/apk/debug/app-debug.apk artifacts/Rummikub-debug.apk
./scripts/verify-apk.sh artifacts/Rummikub-debug.apk
sha256sum artifacts/Rummikub-debug.apk > artifacts/SHA256SUMS
echo "APK: $(pwd)/artifacts/Rummikub-debug.apk"
