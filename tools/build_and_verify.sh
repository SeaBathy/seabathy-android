#!/usr/bin/env bash
# Full verification: unit tests, debug APK and Android lint.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "[1/3] Unit tests"
./gradlew testGithubDebugUnitTest

echo "[2/3] Debug APK"
./gradlew assembleGithubDebug

echo "[3/3] Android lint"
./gradlew lintGithubDebug

APK="$ROOT/app/build/outputs/apk/github/debug/app-github-debug.apk"
test -f "$APK"
ls -lh "$APK"
echo "SeaBathy verification passed."
