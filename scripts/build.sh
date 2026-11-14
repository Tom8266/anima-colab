#!/usr/bin/env bash
# 构建 APK：默认 debug，传 release 构建 release（未签名）
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
cd android

VARIANT="${1:-debug}"
case "$VARIANT" in
    debug|release) ;;
    *) echo "usage: scripts/build.sh [debug|release]" >&2; exit 2 ;;
esac

TASK=":app:assemble$(printf '%s' "${VARIANT^}")"
./gradlew "$TASK" --console=plain "${@:2}"

if [ "$VARIANT" = "debug" ]; then
    ls -lh app/build/outputs/apk/debug/app-debug.apk
else
    ls -lh app/build/outputs/apk/release/*.apk 2>/dev/null || true
fi
