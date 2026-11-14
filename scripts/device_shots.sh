#!/usr/bin/env bash
# =============================================================================
# device_shots.sh —— Anima Colab 真机截图流水线（mock gateway + adb + uiautomator）
#
# 用途：不依赖真实 Colab 会话，在本机起假网关（scripts/mock_gateway.py），
#       通过 `adb reverse` 让手机上的 App 连到它，然后自动走一遍 App 各个页面
#       并截图，用于视觉 QA / 设计验收（Phase A 验证工具链，Phase B 出验收图）。
#
# 用法：
#   scripts/device_shots.sh                     # 用已安装的 APK 截图（不重新构建）
#   scripts/device_shots.sh --install           # 先 scripts/team-build.sh 构建 + adb install -r
#   scripts/device_shots.sh --install --phase full   # Phase B：全量截图（01-15，含空态/错误/生成中/结果）
#   scripts/device_shots.sh --phase a --outdir /tmp/shots
#
# 参数：
#   --install          构建并安装 debug APK（内部调用 scripts/team-build.sh，带 flock 串行锁）
#   --phase a|full     a = 01-10 基础截图（默认）；full = a + 空图库/连接失败/生成中/生成结果
#   --outdir DIR       截图输出目录（默认 docs/screenshots/redesign）
#   --serial ID        设备序列号（默认取 $ADB_SERIAL 或 ef9ca6f9）
#   --no-light         跳过浅色主题截图（09/10）
#   --dim              把手机面板亮度压到最低（截图取的是帧缓冲，画质不受影响；只为护 AMOLED），退出时恢复
#   -h, --help         显示帮助
#
# 环境变量（一般不用改）：
#   ADB_SERIAL / MOCK_PORT(8091) / MOCK_TOKEN(devtoken) / SHOT_SETTLE(0.8)
#   TAB_Y(2280) 底部导航栏点击 y；GALLERY_CARD_X/Y 图库第一张卡片中心坐标
#   （坐标以 1080x2400 / 440dpi 校准，换机时可用环境变量覆盖）
#
# 前置条件：
#   - 已连接真机（USB 调试已授权），`source scripts/env.sh` 提供 adb
#   - App 已安装（或用 --install 自动构建安装）；debug 包允许明文 HTTP
#   - 电脑装有 python3（mock gateway 只用标准库）、curl
#
# 输出：docs/screenshots/redesign/
#   01-generate.png                生成页（已连接 T4）—— 品牌深色（dynamic_color=false）
#   02-params-expanded.png         生成页-参数卡展开
#   03-gallery.png                 图库（mock 6 条历史 = 6 张图）
#   04-viewer.png                  大图查看器（点开第一张）
#   05-explore-tags.png            速查-Tag 模式
#   06-explore-templates.png       速查-模板模式
#   07-settings.png                设置页顶部
#   08-settings-manual-pairing.png 设置-手动配对展开
#   09-light-generate.png          浅色主题生成页（品牌色板，dynamic_color=false）
#   10-light-settings.png          浅色主题设置页
#   full 额外：
#   11-empty-gallery.png           空图库空态
#   12-error-generate.png          连接失败错误态
#   13-generate-running.png        生成中（进度卡，mock 模拟 WS progress）
#   14-generate-result.png         生成成功（结果卡，mock 出图）
#   15-dynamic-color.png           Material You 动态取色（dark + dynamic_color=true 对照）
#
# 调试：日志/UI dump 在 .tmp/device_qa/；失败时先看 .tmp/device_qa/mock_gateway.log
# 示例：
#   scripts/device_shots.sh --install --phase full
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# shellcheck source=scripts/env.sh
source "$ROOT/scripts/env.sh"

# ---------------------------------------------------------------- 配置
PACKAGE="com.tom.animacolab"
ACTIVITY=".MainActivity"
SERIAL="${ADB_SERIAL:-ef9ca6f9}"
PORT="${MOCK_PORT:-8091}"
BASE_URL="http://127.0.0.1:${PORT}"
TOKEN="${MOCK_TOKEN:-devtoken}"
OUTDIR="docs/screenshots/redesign"
PHASE="a"
INSTALL=0
SKIP_LIGHT=0
DIM=0
ORIG_BRIGHTNESS=""
ORIG_BRIGHTNESS_MODE=""
SHOT_SETTLE="${SHOT_SETTLE:-0.8}"
TAB_Y="${TAB_Y:-2280}"
GALLERY_CARD_X="${GALLERY_CARD_X:-270}"
GALLERY_CARD_Y="${GALLERY_CARD_Y:-700}"

WORK=".tmp/device_qa"
UI_XML="$WORK/ui.xml"
PREFS_FILE="$WORK/anima_config.xml"
GATEWAY_PID=""
MISSING=()
WARNINGS=()
STEP_NO=0

# ---------------------------------------------------------------- 日志
c_red='\033[31m'; c_yel='\033[33m'; c_grn='\033[32m'; c_dim='\033[2m'; c_off='\033[0m'
log()  { printf '%b\n' "  $*"; }
step() { STEP_NO=$((STEP_NO + 1)); printf '\n%b▶ [%02d] %s%b\n' "$c_grn" "$STEP_NO" "$*" "$c_off"; }
warn() { printf '%b\n' "  ${c_yel}⚠ $*${c_off}" >&2; WARNINGS+=("$*"); }
err()  { printf '%b\n' "  ${c_red}✗ $*${c_off}" >&2; MISSING+=("$*"); }
die()  { printf '%b\n' "  ${c_red}✗ $*${c_off}" >&2; exit 1; }

usage() { awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"; }

# ---------------------------------------------------------------- 参数
while [ $# -gt 0 ]; do
    case "$1" in
        --install)   INSTALL=1 ;;
        --phase)     PHASE="${2:-}"; shift ;;
        --phase=*)   PHASE="${1#*=}" ;;
        --outdir)    OUTDIR="${2:-}"; shift ;;
        --serial)    SERIAL="${2:-}"; shift ;;
        --no-light)  SKIP_LIGHT=1 ;;
        --dim)       DIM=1 ;;
        -h|--help)   usage; exit 0 ;;
        *) printf '未知参数: %s\n\n' "$1" >&2; usage; exit 2 ;;
    esac
    shift
done
case "$PHASE" in
    a|full) ;;
    *) die "--phase 只能是 a 或 full（收到: $PHASE）" ;;
esac

mkdir -p "$WORK" "$OUTDIR"

# ---------------------------------------------------------------- adb 包装
adbx() { adb -s "$SERIAL" "$@"; }

preflight() {
    step "预检（adb 设备 / python3 / 包安装 / 明文 HTTP）"
    command -v adb >/dev/null || die "找不到 adb，先 source scripts/env.sh"
    command -v python3 >/dev/null || die "找不到 python3"
    command -v curl >/dev/null || die "找不到 curl"
    local devices
    devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    if ! printf '%s\n' "$devices" | grep -qx "$SERIAL"; then
        die "设备 $SERIAL 未连接/未授权。当前在线设备: $(printf '%s' "$devices" | tr '\n' ' ')"
    fi
    log "设备: $SERIAL ($(adbx shell getprop ro.product.model 2>/dev/null | tr -d '\r'))  " \
        "$(adbx shell wm size 2>/dev/null | tr -d '\r')"
    if [ "$INSTALL" -eq 0 ]; then
        adbx shell pm path "$PACKAGE" >/dev/null 2>&1 \
            || die "App 未安装；加 --install 或先安装 debug APK"
        log "App 已安装: $(adbx shell dumpsys package "$PACKAGE" 2>/dev/null | sed -n 's/.*versionName=//p' | head -1)"
    fi
    # run-as 可用性（SharedPreferences 注入需要 debuggable 包）
    if ! adbx shell run-as "$PACKAGE" true >/dev/null 2>&1; then
        die "run-as $PACKAGE 不可用：需要 debug 包且已安装"
    fi
    log "run-as 可用（debug 包）"
    wake_device
    dim_screen
}

# ---------------------------------------------------------------- 面板亮度（护 AMOLED）
# --dim：把面板亮度调到最低，减少长时间亮屏对 OLED 的损耗。
# screencap 抓的是 SurfaceFlinger 的帧缓冲，与面板亮度无关，所以截图观感不变。
dim_screen() {
    [ "$DIM" -eq 1 ] || return 0
    ORIG_BRIGHTNESS=$(adbx shell settings get system screen_brightness 2>/dev/null | tr -d '\r')
    ORIG_BRIGHTNESS_MODE=$(adbx shell settings get system screen_brightness_mode 2>/dev/null | tr -d '\r')
    if adbx shell settings put system screen_brightness_mode 0 >/dev/null 2>&1 \
        && adbx shell settings put system screen_brightness 1 >/dev/null 2>&1; then
        log "面板亮度 → 1（原 ${ORIG_BRIGHTNESS:-?} / mode ${ORIG_BRIGHTNESS_MODE:-?}），结束自动恢复"
    else
        warn "--dim：设置亮度失败（部分 ROM 需要系统权限），继续按原亮度截图"
    fi
}

restore_brightness() {
    [ "$DIM" -eq 1 ] || return 0
    case "${ORIG_BRIGHTNESS:-}" in
        ''|null) ;;
        *) adbx shell settings put system screen_brightness "$ORIG_BRIGHTNESS" >/dev/null 2>&1 || true ;;
    esac
    case "${ORIG_BRIGHTNESS_MODE:-}" in
        ''|null) ;;
        *) adbx shell settings put system screen_brightness_mode "$ORIG_BRIGHTNESS_MODE" >/dev/null 2>&1 || true ;;
    esac
}

# ---------------------------------------------------------------- 设备状态
wake_device() {
    if adbx shell dumpsys power 2>/dev/null | grep -q 'mWakefulness=Asleep'; then
        adbx shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
        sleep 0.4
    fi
    if adbx shell dumpsys window 2>/dev/null | grep -q 'mDreamingLockscreen=true'; then
        adbx shell wm dismiss-keyguard >/dev/null 2>&1
        sleep 0.4
    fi
}

# ---------------------------------------------------------------- uiautomator
ui_dump() {
    local i
    for i in 1 2 3; do
        adbx shell uiautomator dump /sdcard/device_qa_ui.xml >/dev/null 2>&1
        if adbx pull /sdcard/device_qa_ui.xml "$UI_XML" >/dev/null 2>&1 && [ -s "$UI_XML" ]; then
            return 0
        fi
        sleep 0.4
    done
    return 1
}

# 在 UI dump 里找 text / content-desc，输出 "x y"（点击中心点），找不到输出空行。
# 用法: ui_find_xy <文案> [ymin] [ymax] [exact|auto]
#   exact = 只匹配完全相同的文案（点按钮时用它，避免「生成」误中「生成时前缀」）
#   auto  = 先精确匹配、再子串匹配（默认）
ui_find_xy() {
    local needle="$1" ymin="${2:-0}" ymax="${3:-10000}" mode="${4:-auto}"
    [ -s "$UI_XML" ] || return 0
    python3 - "$UI_XML" "$needle" "$ymin" "$ymax" "$mode" <<'PY'
import sys
import xml.etree.ElementTree as ET

path, needle, ymin, ymax = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
mode = sys.argv[5] if len(sys.argv) > 5 else 'auto'
try:
    root = ET.parse(path).getroot()
except Exception:
    print('')
    raise SystemExit

def bounds(node):
    raw = node.get('bounds') or ''
    try:
        left, rest = raw.split('][')
        x1, y1 = [int(v) for v in left.strip('[').split(',')]
        x2, y2 = [int(v) for v in rest.strip(']').split(',')]
        return x1, y1, x2, y2
    except Exception:
        return None

def collect(match):
    out = []
    for order, node in enumerate(root.iter('node')):
        texts = [node.get('text') or '', node.get('content-desc') or '']
        if not any(t and match(t.strip()) for t in texts):
            continue
        b = bounds(node)
        if not b:
            continue
        x1, y1, x2, y2 = b
        cy = (y1 + y2) // 2
        cx = (x1 + x2) // 2
        if x2 <= x1 or y2 <= y1 or not (ymin <= cy <= ymax):
            continue
        out.append(((x2 - x1) * (y2 - y1), order, cx, cy))
    out.sort()
    return out

cands = collect(lambda t: t == needle)
if not cands and mode != 'exact':
    cands = collect(lambda t: needle in t)
if cands:
    _, _, cx, cy = cands[0]
    print('%d %d' % (cx, cy))
else:
    print('')
PY
}

tap_xy() { adbx shell input tap "$1" "$2" >/dev/null 2>&1; sleep "${TAP_SETTLE:-0.45}"; }

# tap_text TEXT [ymin] [ymax]
tap_text() {
    local text="$1" ymin="${2:-0}" ymax="${3:-10000}" xy
    ui_dump || return 1
    xy=$(ui_find_xy "$text" "$ymin" "$ymax")
    [ -n "$xy" ] || return 1
    # shellcheck disable=SC2086
    tap_xy $xy
    log "tap 「$text」→ $xy"
}

# 依次尝试多个候选文案
tap_text_any() {
    local text
    for text in "$@"; do
        if tap_text "$text" 0 2180; then return 0; fi
    done
    return 1
}

tap_tab() { # 生成 / 图库 / 速查 / 设置 —— 先用文案定位，失败退回固定坐标
    local label="$1" x
    case "$label" in
        生成) x=135 ;;
        图库) x=405 ;;
        速查) x=675 ;;
        设置) x=945 ;;
        *) x=135 ;;
    esac
    if tap_text "$label" 2050 2400; then return 0; fi
    tap_xy "$x" "$TAB_Y"
    log "tap tab 「$label」→ ($x,$TAB_Y) 固定坐标"
}

swipe_up_content() {
    adbx shell input swipe 540 1850 540 750 260 >/dev/null 2>&1
    sleep 0.6
}

scroll_until_text() { # TEXT [max_scrolls]
    local text="$1" max="${2:-5}" i
    for i in $(seq 1 "$max"); do
        ui_dump || { swipe_up_content; continue; }
        if [ -n "$(ui_find_xy "$text" 0 2180)" ]; then return 0; fi
        swipe_up_content
    done
    ui_dump || true
    [ -n "$(ui_find_xy "$text" 0 2180)" ]
}

tap_text_scrolled() { # TEXT [max_scrolls]
    local text="$1" max="${2:-5}" i
    if tap_text "$text" 0 2180; then return 0; fi
    for i in $(seq 1 "$max"); do
        swipe_up_content
        ui_dump || continue
        if tap_text "$text" 0 2180; then return 0; fi
    done
    return 1
}

# 精确滚动：把 TEXT 元素的中心滚到屏幕 target_y 附近（px，默认 420）
scroll_to_y() {
    local text="$1" target="${2:-420}" xy y delta chunk
    ui_dump || return 1
    xy=$(ui_find_xy "$text" 0 2180)
    [ -n "$xy" ] || return 1
    y=${xy##* }
    delta=$((y - target))
    while [ "$delta" -gt 60 ]; do
        if [ "$delta" -gt 1000 ]; then chunk=1000; else chunk=$delta; fi
        adbx shell input swipe 540 1800 540 $((1800 - chunk)) 400 >/dev/null 2>&1
        sleep 0.45
        delta=$((delta - chunk))
    done
    sleep 0.2
}

dismiss_system_prompts() {
    local i
    for i in 1 2; do
        ui_dump || return 0
        if [ -n "$(ui_find_xy "允许" 0 10000)" ]; then
            tap_text "允许" 0 10000 || true
            sleep 0.4
        elif [ -n "$(ui_find_xy "Allow" 0 10000)" ]; then
            tap_text "Allow" 0 10000 || true
            sleep 0.4
        else
            return 0
        fi
    done
}

ensure_app_foreground() {
    local focus
    focus=$(adbx shell dumpsys window 2>/dev/null | sed -n 's/.*mCurrentFocus=//p' | head -1)
    case "$focus" in
        *"$PACKAGE"*) return 0 ;;
    esac
    wake_device
    adbx shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null 2>&1
    sleep 1.5
}

# ---------------------------------------------------------------- 截图
shot() { # NAME
    local name="$1" size
    ensure_app_foreground
    wake_device
    sleep "$SHOT_SETTLE"
    adbx shell screencap -p /sdcard/device_qa_shot.png >/dev/null 2>&1
    if ! adbx pull /sdcard/device_qa_shot.png "$OUTDIR/$name.png" >/dev/null 2>&1; then
        err "截图失败: $name"
        return 1
    fi
    size=$(stat -c '%s' "$OUTDIR/$name.png" 2>/dev/null || echo 0)
    if [ "$size" -lt 20000 ]; then
        err "截图异常（文件过小 ${size}B）: $name"
        return 1
    fi
    log "截图 $c_dim$OUTDIR/$name.png${c_off} (${size}B)"
}

# ---------------------------------------------------------------- mock gateway
gateway_ready() { curl -fsS --max-time 1 "$BASE_URL/anima/v1/health" >/dev/null 2>&1; }

ensure_gateway() {
    if gateway_ready; then
        log "mock gateway 已在运行，复用（$BASE_URL）"
        return 0
    fi
    : > "$WORK/mock_gateway.log"
    python3 scripts/mock_gateway.py --port "$PORT" --sim-seconds "${MOCK_SIM_SECONDS:-10}" \
        >> "$WORK/mock_gateway.log" 2>&1 &
    GATEWAY_PID=$!
    local i
    for i in $(seq 1 60); do
        gateway_ready && { log "mock gateway 启动 (pid $GATEWAY_PID, $BASE_URL)"; return 0; }
        kill -0 "$GATEWAY_PID" 2>/dev/null || break
        sleep 0.1
    done
    die "mock gateway 启动失败，见 $WORK/mock_gateway.log"
}

# 恢复 mock 预置图库（6 条），保证截图内容确定、不受上次模拟生成影响
reset_gateway() {
    if curl -fsS --max-time 2 -X POST "$BASE_URL/__mock__/reset" >/dev/null 2>&1; then
        log "mock gateway 已重置为 6 条预置图库"
    else
        warn "mock gateway 不支持 /__mock__/reset（旧版本？），图库可能包含历史记录"
    fi
}

# 当前 mock 历史条数（截图脚本用它在「生成」后判断 mock 是否出图）
history_count() {
    curl -fsS --max-time 2 -H "Authorization: Bearer $TOKEN" "$BASE_URL/anima/v1/history?limit=200" 2>/dev/null \
        | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("items", [])))' 2>/dev/null \
        || echo 0
}

cleanup() {
    restore_brightness
    adbx reverse --remove "tcp:$PORT" >/dev/null 2>&1 || true
    if [ -n "$GATEWAY_PID" ]; then
        kill "$GATEWAY_PID" >/dev/null 2>&1 || true
        GATEWAY_PID=""
    fi
}
trap cleanup EXIT

setup_reverse() {
    adbx reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1 \
        || die "adb reverse tcp:$PORT 失败（设备断开？）"
    log "adb reverse tcp:$PORT tcp:$PORT → $BASE_URL"
}

# ---------------------------------------------------------------- 构建/安装
build_and_install() {
    step "构建 debug APK（scripts/team-build.sh，内部 flock 与其他构建串行）"
    if ! scripts/team-build.sh; then
        die "构建失败（看上面 Gradle 输出；可能是别人的文件还没编译通过）"
    fi
    step "安装到设备"
    adbx install -r android/app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1 \
        || die "adb install -r 失败"
    # 避免首次启动弹通知权限对话框挡住截图
    adbx shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    log "安装完成 + 通知权限已授予"
}

# ---------------------------------------------------------------- SharedPreferences
# 注入 anima_config：配对 / 主题 / 动态取色 + 一份预填表单
# （预填正向提示词让「生成」按钮可用、也能顺带检查长文本排版）
# 用法: inject_prefs <theme_mode> <dynamic_color:true|false> [token] [base_url]
inject_prefs() {
    local theme="$1" dynamic="$2" token="${3:-$TOKEN}" url="${4:-$BASE_URL}"
    cat > "$PREFS_FILE" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="base_url">$url</string>
    <string name="token">$token</string>
    <boolean name="session_managed" value="false" />
    <string name="theme_mode">$theme</string>
    <boolean name="dynamic_color" value="$dynamic" />
    <boolean name="auto_download" value="true" />
    <boolean name="background_keepalive" value="false" />
    <string name="f_model">base</string>
    <string name="f_positive">safe, 1girl, solo, long silver hair, blue eyes, looking at viewer, upper body, school uniform, classroom, soft lighting, depth of field, detailed eyes</string>
    <string name="f_negative"></string>
    <int name="f_steps" value="30" />
    <float name="f_cfg" value="4.5" />
    <string name="f_sampler">er_sde</string>
    <string name="f_scheduler">simple</string>
    <int name="f_width" value="1024" />
    <int name="f_height" value="1024" />
    <int name="f_batch" value="1" />
    <string name="f_seed"></string>
    <boolean name="f_seed_locked" value="false" />
    <boolean name="f_auto_quality" value="true" />
    <boolean name="f_auto_negative" value="true" />
</map>
EOF
    adbx push "$PREFS_FILE" /data/local/tmp/anima_config.xml >/dev/null 2>&1 \
        || die "adb push prefs 失败"
    adbx shell "run-as $PACKAGE sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/anima_config.xml shared_prefs/anima_config.xml && chmod 660 shared_prefs/anima_config.xml'" >/dev/null 2>&1 \
        || die "prefs 注入失败（run-as cp）"
    log "已注入 prefs: theme=$theme dynamic=$dynamic token=$token url=$url"
}

restart_app() {
    adbx shell am force-stop "$PACKAGE" >/dev/null 2>&1
    sleep 0.5
    adbx shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null 2>&1 || die "am start 失败"
    sleep 1.2
}

wait_connected() { # [timeout_seconds]
    local timeout="${1:-45}" deadline=$((SECONDS + ${1:-45}))
    while [ "$SECONDS" -lt "$deadline" ]; do
        dismiss_system_prompts
        ui_dump || { sleep 0.5; continue; }
        if grep -qF '已连接' "$UI_XML" 2>/dev/null && grep -qF 'Tesla T4' "$UI_XML" 2>/dev/null; then
            log "App 已连接 mock gateway（已连接 · Tesla T4）"
            return 0
        fi
        sleep 0.7
    done
    warn "等待 App 连接超时（${timeout}s）——继续截图，但状态可能不对"
    return 1
}

wait_text_any() { # timeout text...
    local timeout="$1"; shift
    local deadline=$((SECONDS + timeout)) p
    while [ "$SECONDS" -lt "$deadline" ]; do
        ui_dump || { sleep 0.5; continue; }
        for p in "$@"; do
            if grep -qF -- "$p" "$UI_XML" 2>/dev/null; then return 0; fi
        done
        sleep 0.6
    done
    return 1
}

# ---------------------------------------------------------------- 截图流程
main_dark_shots() {
    step "冷启动 App（深色 + 动态取色，mock 已连接）"
    restart_app
    inject_prefs dark false "$TOKEN" "$BASE_URL"
    restart_app
    dismiss_system_prompts
    wait_connected 45 || true

    step "01 生成页（已连接）"
    tap_tab 生成 || true
    sleep 1.0
    shot 01-generate || true

    step "02 生成页-参数卡展开"
    tap_tab 生成 || true
    sleep 0.6
    # 先滚动让「参数」卡头进入视野（不要先点：新设计参数卡可能默认就是展开的，点了会收起）
    local i
    for i in $(seq 1 6); do
        ui_dump || { swipe_up_content; continue; }
        [ -n "$(ui_find_xy "参数" 0 2180)" ] && break
        swipe_up_content
    done
    sleep 0.5
    ui_dump || true
    if [ -z "$(ui_find_xy "步数" 0 2180)" ] && [ -z "$(ui_find_xy "CFG" 0 2180)" ] \
        && [ -z "$(ui_find_xy "种子" 0 2180)" ]; then
        # 参数卡收起状态 → 点卡头展开
        if ! tap_text "参数" 0 2180; then
            warn "02：未找到「参数」卡片头，无法展开"
        fi
        sleep 0.9
    fi
    # 把参数卡头部滚到屏幕上部，让整张参数卡入镜
    scroll_to_y "参数" 380 || true
    sleep 0.6
    shot 02-params-expanded || true
    # 兜底：如果和 01 一样（没滚动成功），补一屏重拍
    if cmp -s "$OUTDIR/01-generate.png" "$OUTDIR/02-params-expanded.png"; then
        warn "02：截图与 01 相同，补一屏重拍"
        swipe_up_content
        sleep 0.8
        shot 02-params-expanded || true
    fi

    step "03 图库（6 张 mock 历史）"
    tap_tab 图库 || true
    wait_text_any 12 "6 张" "张" >/dev/null 2>&1 || true
    sleep 3.0   # 等 NetworkImage 下载完 3 张不同的图
    shot 03-gallery || true

    step "04 大图查看器（点开第一张）"
    tap_xy "$GALLERY_CARD_X" "$GALLERY_CARD_Y"
    sleep 2.2
    shot 04-viewer || true
    adbx shell input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 0.8

    step "05 速查-Tag 模式"
    tap_tab 速查 || true
    sleep 1.2
    shot 05-explore-tags || true

    step "06 速查-模板模式"
    if ! tap_text "模板" 0 2100; then warn "06：未找到「模板」切换项"; fi
    sleep 1.2
    shot 06-explore-templates || true

    step "07 设置页"
    tap_tab 设置 || true
    sleep 1.4
    shot 07-settings || true

    step "08 设置-手动配对展开"
    scroll_until_text "手动配对" 6 || warn "08：滚动了 6 屏仍未看到「手动配对」"
    sleep 0.4
    ui_dump || true
    # 用「服务地址/保存并连接」判断是否已展开（卡片标题本身就含“地址与 Token”，不能当展开标志）
    if [ -z "$(ui_find_xy "服务地址" 0 2180)" ] && [ -z "$(ui_find_xy "保存并连接" 0 2180)" ]; then
        # 打开折叠：新设计点「地址与 Token」行；旧设计点「展开」按钮
        if ! tap_text "地址与 Token" 0 2180; then
            if ! tap_text "展开" 0 2180; then
                tap_text_any "已保存配对信息" "手动配对（备用）" "手动配对" \
                    || warn "08：没找到手动配对展开入口"
            fi
        fi
        sleep 0.9
    fi
    # 让卡片头 + 地址/Token 输入框同时入镜
    scroll_to_y "地址与 Token" 380 2>/dev/null || scroll_to_y "手动配对" 320 || true
    sleep 0.5
    if ! wait_text_any 3 "服务地址" "保存并连接"; then
        swipe_up_content
        sleep 0.5
    fi
    shot 08-settings-manual-pairing || true
}

light_shots() {
    if [ "$SKIP_LIGHT" -eq 1 ]; then
        log "跳过浅色主题截图（--no-light）"
        return 0
    fi
    step "09/10 浅色主题（动态取色关，品牌色板）"
    restart_app
    inject_prefs light false "$TOKEN" "$BASE_URL"
    restart_app
    dismiss_system_prompts
    wait_connected 45 || true
    shot 09-light-generate || true

    tap_tab 设置 || true
    sleep 1.6
    shot 10-light-settings || true
}

full_extra_shots() {
    # A. 空图库空态（mock 对 token=empty 返回空 history）
    step "11 空图库空态（token=empty）"
    restart_app
    inject_prefs dark false "empty" "$BASE_URL"
    restart_app
    dismiss_system_prompts
    wait_connected 45 || true
    tap_tab 图库 || true
    wait_text_any 10 "还没有生成记录" "空" "去生成" "0 张" >/dev/null 2>&1 || true
    sleep 1.5
    shot 11-empty-gallery || true

    # B. 连接失败错误态（base_url 指向没在监听的端口）
    step "12 连接失败错误态（base_url:8099 无服务）"
    restart_app
    inject_prefs dark false "$TOKEN" "http://127.0.0.1:8099"
    restart_app
    dismiss_system_prompts
    wait_text_any 25 "连接失败" "连接异常" "连不上" >/dev/null 2>&1 || true
    sleep 1.2
    shot 12-error-generate || true

    # C. 生成中 / 生成成功（mock 模拟 WS progress，默认 6 秒后出图）
    step "13/14 生成中 + 生成成功（mock 模拟）"
    restart_app
    inject_prefs dark false "$TOKEN" "$BASE_URL"
    restart_app
    dismiss_system_prompts
    wait_connected 45 || true
    tap_tab 生成 || true

    local baseline started=0 xy="" xy2="" i try
    baseline=$(history_count)

    # 找到主按钮：先滚到可见，等惯性滚动停稳后重新取坐标再点（避免点到滚动中的旧坐标）
    for try in 1 2 3; do
        xy=""
        for i in 1 2 3 4 5 6; do
            ui_dump || { sleep 0.4; continue; }
            xy=$(ui_find_xy "开始生成" 0 2180 exact)
            [ -z "$xy" ] && xy=$(ui_find_xy "生成" 0 2180 exact)
            [ -n "$xy" ] && break
            swipe_up_content
        done
        [ -n "$xy" ] || continue
        sleep 0.8
        ui_dump || true
        xy2=$(ui_find_xy "开始生成" 0 2180 exact)
        [ -z "$xy2" ] && xy2=$(ui_find_xy "生成" 0 2180 exact)
        [ -n "$xy2" ] && xy=$xy2
        # shellcheck disable=SC2086
        tap_xy $xy
        log "点「生成」→ $xy（第 ${try} 次）"
        # 判定「真的提交了」：随机种子被写入 UI / 出现生成中·生成完成 / mock 历史 +1
        for i in $(seq 1 16); do
            sleep 0.5
            ui_dump || continue
            if grep -qE 'text="[0-9]{9,10}"' "$UI_XML" 2>/dev/null \
                || grep -qF "生成中" "$UI_XML" 2>/dev/null \
                || grep -qF "生成完成" "$UI_XML" 2>/dev/null; then
                started=1
                break
            fi
            if [ "$(history_count)" -gt "$baseline" ]; then started=1; break; fi
        done
        [ "$started" -eq 1 ] && break
        warn "13/14：第 ${try} 次点击后没有生成迹象，重试"
    done
    if [ "$started" -eq 0 ]; then
        warn "13/14：生成任务没有提交成功，跳过这两张"
        return 0
    fi

    # 13：滚到底让进度卡完整入镜——此时「常驻进度条」按进度卡可见性判定自动隐藏，避免双进度。
    #    两个全屏上滑保证到底，随后立刻截图（mock 模拟默认 10s，窗口充足）。
    swipe_up_content
    swipe_up_content
    sleep 0.4
    if [ "$(history_count)" -gt "$baseline" ]; then
        warn "13：生成在截「运行中」前就完成了（模拟过快），13 可能显示完成态"
    fi
    shot 13-generate-running || true

    # 14：等 mock 出图（history 条数 +1）→ 滚到成功卡 → 截图
    for i in $(seq 1 30); do
        [ "$(history_count)" -gt "$baseline" ] && break
        sleep 1
    done
    if [ "$(history_count)" -le "$baseline" ]; then
        warn "14：等待生成结果超时（mock/WS 异常？）"
        return 0
    fi
    sleep 2.5   # 等成功卡 NetworkImage 下载完
    for i in 1 2 3 4; do
        ui_dump || true
        if [ -n "$(ui_find_xy "保存" 0 2180)" ] || [ -n "$(ui_find_xy "填回参数" 0 2180)" ]; then
            break
        fi
        swipe_up_content
    done
    sleep 0.8
    shot 14-generate-result || true

    # D. Material You 动态取色对照图（默认已改为品牌色板，这里证明开启动态取色可用）
    step "15 动态取色（dark + dynamic_color=true）"
    restart_app
    inject_prefs dark true "$TOKEN" "$BASE_URL"
    restart_app
    dismiss_system_prompts
    wait_connected 45 || true
    tap_tab 生成 || true
    sleep 1.2
    shot 15-dynamic-color || true
}

restore_state() {
    step "恢复常规状态（深色 + 动态取色 + 正常 mock 配置）"
    restart_app
    inject_prefs dark false "$TOKEN" "$BASE_URL"
    restart_app
    dismiss_system_prompts
    wait_connected 30 || true
}

# ---------------------------------------------------------------- main
printf 'Anima Colab device screenshots\n'
printf '  phase=%s install=%s outdir=%s serial=%s\n' "$PHASE" "$INSTALL" "$OUTDIR" "$SERIAL"

preflight
ensure_gateway
reset_gateway
setup_reverse

if [ "$INSTALL" -eq 1 ]; then
    build_and_install
fi
# 每次跑之前清掉旧 tab 状态：直接 force-stop（restart 在截图流程里自己做）
adbx shell am force-stop "$PACKAGE" >/dev/null 2>&1

main_dark_shots
light_shots
if [ "$PHASE" = "full" ]; then
    full_extra_shots
fi
restore_state

# ---------------------------------------------------------------- 汇总
step "截图汇总"
printf '  %-32s %10s\n' "文件" "大小"
for f in "$OUTDIR"/*.png; do
    [ -e "$f" ] || continue
    printf '  %-32s %10s\n' "$(basename "$f")" "$(stat -c '%s' "$f" 2>/dev/null)"
done

if [ "$PHASE" = "a" ]; then
    REQUIRED=(01-generate 02-params-expanded 03-gallery 04-viewer 05-explore-tags \
              06-explore-templates 07-settings 08-settings-manual-pairing)
    [ "$SKIP_LIGHT" -eq 0 ] && REQUIRED+=(09-light-generate 10-light-settings)
else
    REQUIRED=(01-generate 02-params-expanded 03-gallery 04-viewer 05-explore-tags \
              06-explore-templates 07-settings 08-settings-manual-pairing \
              09-light-generate 10-light-settings)
fi
missing_count=0
for name in "${REQUIRED[@]}"; do
    [ -s "$OUTDIR/$name.png" ] || { err "缺少必需截图: $name"; missing_count=$((missing_count + 1)); }
done

printf '\n'
if [ ${#WARNINGS[@]} -gt 0 ]; then
    printf '%b\n' "${c_yel}⚠ ${#WARNINGS[@]} 个警告：${c_off}"
    printf '  - %s\n' "${WARNINGS[@]}"
fi
if [ "$missing_count" -gt 0 ]; then
    printf '%b\n' "${c_red}✗ 有 $missing_count 张必需截图缺失${c_off}"
    exit 1
fi
printf '%b\n' "${c_grn}✓ 截图完成: $OUTDIR（phase=$PHASE）${c_off}"
exit 0
