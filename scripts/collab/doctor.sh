#!/usr/bin/env bash
# 协作环境自检：两个人应该看到同样的 PASS，才算环境一致。
# 用法：scripts/collab/doctor.sh [--offline]
set -uo pipefail

OFFLINE=0
[ "${1:-}" = "--offline" ] && OFFLINE=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

FAIL=0
WARN=0
pass() { printf '  \033[32mPASS\033[0m  %s\n' "$*"; }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$*"; WARN=$((WARN + 1)); }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; FAIL=$((FAIL + 1)); }
section() { printf '\n\033[1m%s\033[0m\n' "$*"; }

major_of() { # 从 "21.0.12" / "1.8.0_491" 里取主版本
    local v="${1#\"}"; v="${v%%\"*}"
    local first="${v%%.*}"
    if [ "$first" = "1" ]; then v="${v#1.}"; first="${v%%.*}"; fi
    echo "$first"
}

section "1. Git"
if command -v git >/dev/null 2>&1; then
    pass "git $(git --version | awk '{print $3}')"
else
    fail "没有 git"
fi

if git rev-parse --git-dir >/dev/null 2>&1; then
    pass "当前目录是 git 仓库"
    remote="$(git remote get-url origin 2>/dev/null || true)"
    case "$remote" in
        *anima-colab*)
            pass "origin = $remote"
            case "$remote" in
                git@*) pass "使用 SSH remote（推荐）" ;;
                *) warn "origin 是 HTTPS，网络不稳时建议换成 SSH" ;;
            esac
            ;;
        "") fail "没有 origin remote" ;;
        *) warn "origin 不是 anima-colab：$remote" ;;
    esac
    if [ -f .git/hooks/pre-push ] && grep -q "collab" .git/hooks/pre-push 2>/dev/null; then
        pass "pre-push 钩子已安装（防误推 main）"
    else
        warn "未安装 pre-push 钩子，运行 scripts/collab/install-hooks.sh"
    fi
else
    fail "不在仓库根目录（当前：$ROOT）"
fi

section "2. JDK（CI 用 21）"
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    pass "JAVA_HOME = $JAVA_HOME"
elif [ -n "${JAVA_HOME:-}" ]; then
    fail "JAVA_HOME 指向不存在的目录：$JAVA_HOME"
fi
if command -v java >/dev/null 2>&1; then
    jv="$(java -version 2>&1 | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
    jm="$(major_of "$jv")"
    if [ "${jm:-0}" -ge 21 ] && [ "${jm:-0}" -le 25 ]; then
        pass "java $jv"
    elif [ "${jm:-0}" -ge 17 ] && [ "${jm:-0}" -lt 21 ]; then
        warn "java $jv —— 能编，但和 CI（21）不一致"
    elif [ "${jm:-0}" -gt 25 ]; then
        warn "java $jv —— 过新，AGP / Gradle 未验证，建议装 JDK 21"
    else
        fail "java $jv —— 低于 17，编不过"
    fi
else
    fail "没有 java（装 JDK 21）"
fi

section "3. Android SDK（compileSdk 37 / build-tools 37）"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
    # 常见位置兜底
    for c in "$HOME/Android/Sdk" "/e/Android/Sdk" "/c/Android/Sdk"; do
        [ -d "$c" ] && SDK="$c" && break
    done
fi
if [ -n "$SDK" ] && [ -d "$SDK" ]; then
    pass "SDK = $SDK"
    if ls -d "$SDK/platforms/android-37"* >/dev/null 2>&1; then
        pass "platforms/android-37"
    else
        fail "缺 platforms;android-37（sdkmanager \"platforms;android-37.0\"）"
    fi
    if ls -d "$SDK/build-tools/37"* >/dev/null 2>&1; then
        pass "build-tools 37"
    else
        fail "缺 build-tools;37.0.0（sdkmanager \"build-tools;37.0.0\"）"
    fi
    if [ -x "$SDK/platform-tools/adb" ] || [ -x "$SDK/platform-tools/adb.exe" ]; then
        pass "platform-tools/adb"
    else
        warn "缺 platform-tools（真机安装才需要）"
    fi
else
    fail "没有找到 Android SDK（设 ANDROID_HOME）"
fi
[ -f android/local.properties ] || warn "android/local.properties 不存在（首次构建会自动用 ANDROID_HOME）"

section "4. Python"
if command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1; then
    PY="$(command -v python3 || command -v python)"
    pv="$("$PY" -V 2>&1 | awk '{print $2}')"
    pm="$(major_of "$pv")"
    if [ "${pm:-0}" -ge 3 ]; then
        pass "python $pv"
    else
        fail "python $pv 太旧（要 3.10+）"
    fi
else
    fail "没有 python3"
fi

section "5. Gradle wrapper"
if [ -x android/gradlew ]; then
    pass "android/gradlew 存在"
    if [ "${OFFLINE}" = "1" ]; then
        warn "跳过 wrapper 版本检查（--offline）"
    else
        gv="$(cd android && JAVA_HOME="${JAVA_HOME:-}" timeout 120 ./gradlew --version 2>/dev/null | awk '/^Gradle /{print $2}')"
        if [ -n "$gv" ]; then
            pass "Gradle $gv"
        else
            warn "gradlew 跑不起来（首次需联网下 Gradle 9.9.0；代理环境见 docs/COLLAB.md 第 5 节）"
        fi
    fi
else
    fail "android/gradlew 缺失或没有执行权限"
fi

section "结果"
if [ "$FAIL" -eq 0 ]; then
    if [ "$WARN" -eq 0 ]; then
        printf '\033[32m环境就绪。\033[0m 下一步：scripts/collab/new-task.sh feat 你的任务\n'
    else
        printf '\033[32m可以使用\033[0m，但有 %d 个 WARN 建议处理。\n' "$WARN"
    fi
    exit 0
else
    printf '\033[31m有 %d 项必须修复\033[0m（另有 %d 个 WARN）。修完重跑本脚本。\n' "$FAIL" "$WARN"
    exit 1
fi
