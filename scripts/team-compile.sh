#!/usr/bin/env bash
# 团队编译检查（串行化）：多个人同时改代码时，用文件锁避免 Gradle 构建互相踩踏。
# 用法：scripts/team-compile.sh [额外 gradle 参数]
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

mkdir -p "$PWD/.tmp"

if command -v flock >/dev/null 2>&1; then
    exec 9>"$PWD/.tmp/gradle-compile.lock"
    flock 9
fi

cd android
./gradlew :app:compileDebugKotlin --console=plain "$@"
