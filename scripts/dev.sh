#!/usr/bin/env bash
# =============================================================================
# dev.sh —— 真机熄屏调试入口（项目约定，见 AGENTS.md / docs/device-debug.md）
#
#   scripts/dev.sh start          熄灭面板（设备保持唤醒），phone-mirror 继续出帧
#   scripts/dev.sh stop           点亮面板，结束调试
#   scripts/dev.sh status         设备 / 面板 / 唤醒 / 镜像状态
#   scripts/dev.sh shot [文件]    取当前画面（默认 .tmp/phone-shot.jpg）
#
# 环境变量：
#   DSH_WEB_URL                     镜像服务地址（默认 http://127.0.0.1:3080）
#   PHONE_MIRROR_SCRCPY / SCRCPY_BIN  scrcpy 路径（默认 PATH → outputs/tools/scrcpy-*/）
#   ADB_SERIAL                      多设备时指定
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/env.sh
source "$ROOT/scripts/env.sh"

GUI_URL="${DSH_WEB_URL:-http://127.0.0.1:3080}"
MIRROR_URL="$GUI_URL/phone-mirror"
WORK="$ROOT/.tmp"
HOLDER_PID="$WORK/phone-holder.pid"
HOLDER_LOG="$WORK/phone-holder.log"
mkdir -p "$WORK"

SERIAL_ARGS=()
if [ -n "${ADB_SERIAL:-}" ]; then
    SERIAL_ARGS=(-s "$ADB_SERIAL")
fi

log() { printf '%s\n' "$*"; }
die() { printf '✗ %s\n' "$*" >&2; exit 1; }

adbx() { adb "${SERIAL_ARGS[@]}" "$@"; }

need_device() {
    adbx get-state >/dev/null 2>&1 || die "没有可用的 adb 设备（先 adb devices 检查）"
}

# 物理面板的 HWC 电源状态：On / Off（比 DisplayManager 的 committedState 准）
panel_state() {
    local raw
    raw=$(adbx shell "dumpsys SurfaceFlinger | awk '/^Display /{p=1} /^Virtual Display /{p=0} p && /powerMode=/{print; exit}'" 2>/dev/null | tr -d '\r')
    printf '%s' "${raw##*powerMode=}"
}

wakefulness() {
    adbx shell dumpsys power 2>/dev/null | sed -n 's/.*mWakefulness=//p' | head -1 | tr -d '\r'
}

mirror_json=""
refresh_mirror() { mirror_json=$(curl -fsS --max-time 2 "$MIRROR_URL/status.json" 2>/dev/null || true); }
mirror_has() {
    case "$mirror_json" in
        *"$1"*) return 0 ;;
        *) return 1 ;;
    esac
}

wait_panel() { # wanted-state
    local want="$1" i
    for i in $(seq 1 40); do
        [ "$(panel_state)" = "$want" ] && return 0
        sleep 0.25
    done
    return 1
}

find_scrcpy() {
    local candidate
    if [ -n "${PHONE_MIRROR_SCRCPY:-}" ] && [ -x "$PHONE_MIRROR_SCRCPY" ]; then
        printf '%s' "$PHONE_MIRROR_SCRCPY"
        return 0
    fi
    if [ -n "${SCRCPY_BIN:-}" ] && [ -x "$SCRCPY_BIN" ]; then
        printf '%s' "$SCRCPY_BIN"
        return 0
    fi
    candidate=$(command -v scrcpy 2>/dev/null || true)
    if [ -n "$candidate" ]; then
        printf '%s' "$candidate"
        return 0
    fi
    for candidate in "$ROOT"/outputs/tools/scrcpy-*/scrcpy; do
        if [ -x "$candidate" ]; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    return 1
}

cmd_start() {
    need_device
    refresh_mirror
    if mirror_has '"screenOff":true' && [ "$(panel_state)" = "Off" ]; then
        log "✓ 面板已熄灭（phone-mirror 持有；设备 $(wakefulness)）"
        return 0
    fi
    if [ -n "$mirror_json" ]; then
        log "通过 phone-mirror 熄灭面板…"
        curl -fsS --max-time 15 -X POST "$MIRROR_URL/screen?state=off" >/dev/null || true
    else
        log "phone-mirror 不可达，启动 scrcpy holder…"
        local bin pid
        bin=$(find_scrcpy) || die "找不到 scrcpy（PATH 或 outputs/tools/scrcpy-*/，也可设 PHONE_MIRROR_SCRCPY）"
        pid=""
        if [ -f "$HOLDER_PID" ]; then pid=$(cat "$HOLDER_PID" 2>/dev/null || true); fi
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            log "holder 已在运行（pid $pid）"
        else
            nohup "$bin" "${SERIAL_ARGS[@]}" --no-window --turn-screen-off --stay-awake --no-audio >"$HOLDER_LOG" 2>&1 &
            printf '%s\n' "$!" >"$HOLDER_PID"
            disown 2>/dev/null || true
        fi
    fi
    if wait_panel Off; then
        log "✓ 面板已熄灭（设备 $(wakefulness)）；画面：侧边栏「手机屏幕」或 $MIRROR_URL/stream.mjpg"
    else
        die "面板没有熄灭（看 phone-mirror 状态或 $HOLDER_LOG）"
    fi
}

cmd_stop() {
    need_device
    refresh_mirror
    if mirror_has '"screenOff":true'; then
        log "通过 phone-mirror 点亮面板…"
        curl -fsS --max-time 15 -X POST "$MIRROR_URL/screen?state=on" >/dev/null || true
    fi
    if [ -f "$HOLDER_PID" ]; then
        local pid
        pid=$(cat "$HOLDER_PID" 2>/dev/null || true)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            log "结束 scrcpy holder（pid $pid）…"
            kill "$pid" 2>/dev/null || true
        fi
        rm -f "$HOLDER_PID"
    fi
    if ! wait_panel On 5; then
        log "面板仍是 Off，尝试唤醒键…"
        adbx shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
        wait_panel On 5 || die "无法点亮面板（可能需要手动解锁）"
    fi
    log "✓ 面板已点亮（设备 $(wakefulness)）"
}

cmd_status() {
    need_device
    local serial model
    serial=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')
    model=$(adbx shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    log "设备  : $serial  $model"
    log "面板  : $(panel_state)    唤醒: $(wakefulness)"
    refresh_mirror
    if [ -n "$mirror_json" ]; then
        log "镜像  : $mirror_json"
    else
        log "镜像  : 不可达（$MIRROR_URL，侧边栏没开？）"
    fi
    if mirror_has '"screenOff":true'; then
        log "holder: phone-mirror 插件（$MIRROR_URL）"
    elif [ -f "$HOLDER_PID" ] && kill -0 "$(cat "$HOLDER_PID" 2>/dev/null || echo 0)" 2>/dev/null; then
        log "holder: pid $(cat "$HOLDER_PID")（日志 $HOLDER_LOG）"
    else
        log "holder: 无"
    fi
}

cmd_shot() {
    local out="${1:-$WORK/phone-shot.jpg}"
    need_device
    refresh_mirror
    if mirror_has '"running":true'; then
        curl -fsS --max-time 8 -o "$out" "$MIRROR_URL/snapshot.jpg" || die "取快照失败"
        log "$out"
        return 0
    fi
    [ "$(panel_state)" = "On" ] || die "镜像未运行且面板已熄：先 scripts/dev.sh start，或打开侧边栏"
    adbx exec-out screencap -p >"$out" || die "screencap 失败"
    log "$out"
}

usage() { sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

case "${1:-status}" in
    start) cmd_start ;;
    stop) cmd_stop ;;
    status) cmd_status ;;
    shot) shift; cmd_shot "${1:-}" ;;
    -h|--help|help) usage ;;
    *) die "用法: scripts/dev.sh {start|stop|status|shot [file]}" ;;
esac
