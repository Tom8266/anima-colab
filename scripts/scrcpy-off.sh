#!/usr/bin/env bash
# =============================================================================
# scrcpy-off.sh —— 熄屏调试：把手机画面镜像到电脑，手机面板保持熄灭
#
# 原理：scrcpy 的 --turn-screen-off 只关闭显示面板电源，设备保持唤醒，
#       画面继续合成/镜像，鼠标键盘可直接操作 App；适合长时间调 UI，避免 AMOLED 常亮。
#
# 用法：
#   scripts/scrcpy-off.sh              # 默认参数启动
#   scripts/scrcpy-off.sh --max-fps 15 # 额外参数原样传给 scrcpy
#   ADB_SERIAL=xxx scripts/scrcpy-off.sh
#
# 运行时：MOD+o 熄屏 / MOD+Shift+o 亮屏 / MOD+p 电源键（MOD 默认左 Alt 或 Super）
#
# scrcpy 来源（按顺序）：
#   1. outputs/tools/scrcpy-*/scrcpy（本机解压的官方 release）
#   2. PATH 里的 scrcpy（sudo dnf install scrcpy）
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/env.sh
source "$ROOT/scripts/env.sh"

SCRCPY=""
for candidate in "$ROOT"/outputs/tools/scrcpy-*/scrcpy; do
    if [ -x "$candidate" ]; then
        SCRCPY="$candidate"
        break
    fi
done
if [ -z "$SCRCPY" ]; then
    SCRCPY="$(command -v scrcpy || true)"
fi
if [ -z "$SCRCPY" ]; then
    cat >&2 <<'EOF'
找不到 scrcpy。二选一：
  sudo dnf install scrcpy
  # 或者下载官方 release 解压到 outputs/tools/：
  # https://github.com/Genymobile/scrcpy/releases
EOF
    exit 1
fi

if ! adb devices | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit !found }'; then
    echo "没有已授权的 adb 设备：" >&2
    adb devices >&2
    exit 1
fi

ARGS=()
if [ -n "${ADB_SERIAL:-}" ]; then
    ARGS+=(-s "$ADB_SERIAL")
fi

echo "scrcpy: $SCRCPY"
echo "熄屏镜像中……关闭窗口（或 Ctrl+C）即退出；运行中 MOD+o 可开关手机屏幕。"
exec "$SCRCPY" "${ARGS[@]}" \
    --turn-screen-off --stay-awake --no-audio --max-fps 30 "$@"
