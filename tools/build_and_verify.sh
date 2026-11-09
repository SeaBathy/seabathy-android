#!/usr/bin/env bash
# Full verification: unit tests, debug APK and Android lint.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "[1/3] Unit tests"
./gradlew testDebugUnitTest

echo "[2/3] Debug APK"
./gradlew assembleDebug

echo "[3/3] Android lint"
./gradlew lintDebug

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
test -f "$APK"
ls -lh "$APK"
echo "SeaBathy verification passed."
