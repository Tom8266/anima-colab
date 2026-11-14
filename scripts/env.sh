#!/usr/bin/env bash
# 公共环境：Android SDK / JDK / platform-tools
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_PLATFORM_TOOLS="$ANDROID_HOME/platform-tools"
export PATH="$ANDROID_PLATFORM_TOOLS:$PATH"

if [ -z "${JAVA_HOME:-}" ]; then
    if [ -x /usr/lib/jvm/java-25-openjdk/bin/javac ]; then
        export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
    else
        JAVAC="$(command -v javac || true)"
        if [ -n "$JAVAC" ]; then
            export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVAC")")")"
        fi
    fi
fi

# Gradle 用户目录：
#   1. 项目内已有完整缓存（.gradle-user-home/caches）时优先用它——本项目的依赖、
#      代理配置和 transform 都在这里，避免 ~/.gradle 缺少 rc 版依赖时又去连 Google Maven；
#   2. 否则用可写的 ~/.gradle（复用系统缓存）；
#   3. home 不可写（agent 沙箱、只读挂载）时退回项目内目录。
# 想强制指定：export GRADLE_USER_HOME=...
if [ -z "${GRADLE_USER_HOME:-}" ]; then
    ANIMA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    if [ -d "$ANIMA_ROOT/.gradle-user-home/caches" ]; then
        export GRADLE_USER_HOME="$ANIMA_ROOT/.gradle-user-home"
    elif [ -w "${HOME:-/nonexistent}/.gradle" ] \
        || { [ ! -e "${HOME:-/nonexistent}/.gradle" ] && [ -w "${HOME:-/nonexistent}" ]; }; then
        export GRADLE_USER_HOME="$HOME/.gradle"
    else
        export GRADLE_USER_HOME="$ANIMA_ROOT/.gradle-user-home"
    fi
    unset ANIMA_ROOT
fi
