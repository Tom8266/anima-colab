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

# Gradle 用户目录：默认用 ~/.gradle（复用已下载的 Gradle/依赖）；若 home 不可写
# （例如 agent 沙箱、只读挂载），退回项目内的 .gradle-user-home，保证构建可用。
if [ -z "${GRADLE_USER_HOME:-}" ]; then
    ANIMA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    if [ -w "${HOME:-/nonexistent}/.gradle" ] \
        || { [ ! -e "${HOME:-/nonexistent}/.gradle" ] && [ -w "${HOME:-/nonexistent}" ]; }; then
        export GRADLE_USER_HOME="$HOME/.gradle"
    else
        export GRADLE_USER_HOME="$ANIMA_ROOT/.gradle-user-home"
    fi
    unset ANIMA_ROOT
fi
