#!/usr/bin/env bash
# 安装 debug APK 并启动 App（需要手机已连接 adb）
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

APK="android/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.tom.animacolab"
if [ ! -f "$APK" ]; then
    echo "APK 不存在，先执行 scripts/build.sh" >&2
    exit 1
fi

mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ "${#DEVICES[@]}" -eq 0 ]; then
    echo "没有检测到设备。请 USB 连接并开启调试，或 adb pair/connect 无线调试后重试：" >&2
    adb devices >&2
    exit 1
fi
echo "设备: ${DEVICES[*]}"

adb install -r "$APK"
adb shell am start -n "$PKG/.MainActivity"
echo "已安装并启动 $PKG"
