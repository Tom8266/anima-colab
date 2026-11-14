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
