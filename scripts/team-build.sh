#!/usr/bin/env bash
# 团队构建（串行化）：assembleDebug，输出 APK 路径。与 team-compile.sh 共用同一把锁。
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
mkdir -p "$PWD/.tmp"
if command -v flock >/dev/null 2>&1; then
    exec 9>"$PWD/.tmp/gradle-compile.lock"
    flock 9
fi
cd android
./gradlew :app:assembleDebug --console=plain "$@"
ls -lh app/build/outputs/apk/debug/app-debug.apk
