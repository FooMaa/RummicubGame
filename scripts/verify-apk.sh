#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "$#" -eq 0 ]; then
  echo 'Usage: ./scripts/verify-apk.sh path/to/app.apk [...]' >&2
  exit 2
fi

android_sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$android_sdk_dir" ] && [ -f local.properties ]; then
  android_sdk_dir="$(sed -n 's/^sdk\.dir=//p' local.properties | head -n 1)"
fi
android_build_tools="$android_sdk_dir/build-tools/34.0.0"
if [ ! -x "$android_build_tools/apksigner" ] || [ ! -x "$android_build_tools/zipalign" ]; then
  echo 'Android Build Tools 34.0.0 required. Set ANDROID_HOME or sdk.dir in local.properties.' >&2
  exit 2
fi

for apk_path in "$@"; do
  if [[ "$apk_path" != *.apk ]] || [ ! -s "$apk_path" ]; then
    echo "Not a non-empty APK: $apk_path" >&2
    exit 1
  fi
  echo "Checking $apk_path"
  unzip -tqq "$apk_path"
  "$android_build_tools/apksigner" verify --verbose "$apk_path"
  "$android_build_tools/zipalign" -c 4 "$apk_path"
done
