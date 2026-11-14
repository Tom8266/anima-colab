# 不亮屏调试 Android（护 AMOLED）

调试这个 App 时手机经常要常亮几十分钟，AMOLED 长期高亮既费电又有烧屏风险。
下面按「完全不亮 → 短时亮」排序，最后一种是本项目截图流水线的做法。

> 设备基线：Xiaomi M2012K11AC / Android 17（API 37）/ adb `ef9ca6f9`。
> 大多数命令对 Android 12+ 通用；标了版本的是新 API。

## 0. 一句话选型

| 场景 | 做法 | 屏幕 |
|---|---|---|
| 要看界面、点按、看动画 | `scrcpy --turn-screen-off` | **物理熄灭**，镜像到电脑 |
| 只看日志 / 等长任务 | 电源键熄屏（或 `cmd display power-off 0`，后者保持唤醒） | 熄灭，设备可休眠 |
| 跑 `device_shots.sh` 自动截图 | `--dim`（亮度压到 1） | 亮，但几乎不发光 |
| 只验证布局/文案，不碰交互 | JVM 截图测试（后续，见 §6） | 完全不亮 |

## 1. 推荐：scrcpy 熄屏镜像

scrcpy 的 `--turn-screen-off` 关的是**面板电源**，不是让设备休眠：画面继续合成并镜像到电脑，
触摸/键盘注入照常工作；设备保持唤醒，App 不会被系统按「休眠」处理（不会因熄屏而 `onPause`），
进度、动画、WebSocket 都和亮屏时一样。

安装（任选）：

```bash
sudo dnf install scrcpy        # Fedora / RHEL
sudo apt install scrcpy        # Ubuntu / Debian
brew install scrcpy            # macOS
winget install Genymobile.scrcpy   # Windows
```

本机（Fedora 45）已经装好官方 **v5.0.1**（解压在 `outputs/tools/`，SHA-256 已对过官方 `SHA256SUMS`，
不需要 sudo），并封装了一条命令：

```bash
scripts/scrcpy-off.sh                  # = --turn-screen-off --stay-awake --no-audio --max-fps 30
scripts/scrcpy-off.sh --max-fps 15     # 额外参数原样传给 scrcpy
```

启动（等价的手动命令）：

```bash
scrcpy --turn-screen-off --stay-awake --no-audio --max-fps 30 --video-bit-rate 4M
# 简写：scrcpy -S -w --no-audio
```

- `--turn-screen-off` / `-S`：启动即熄屏，保持镜像。
- `--stay-awake` / `-w`：保持设备唤醒（仅充电时生效；USB 调试通常正好在充电）。
- `--no-audio`：调试用不到声音，少一条链路。
- 运行时快捷键：`MOD+o` 熄屏 / `MOD+Shift+o` 亮屏 / `MOD+p` 发送电源键（`MOD` 默认是左 `Alt` 或 `Super`）。
- 关闭 scrcpy 会自动恢复正常屏幕状态；`--stay-awake` 也会还原系统设置。

熄屏状态下用电脑窗口操作即可；此时手机面板不发光，也不会有常亮烧屏问题。

## 2. 只跑任务/看日志：直接熄屏

```bash
adb shell input keyevent KEYCODE_SLEEP     # 223 熄屏并进入休眠
adb logcat -b crash                        # 崩溃日志照常
adb logcat -s AndroidRuntime:E ActivityManager:E   # 崩溃 / ANR
adb shell input keyevent KEYCODE_WAKEUP    # 224 需要时唤醒
adb shell wm dismiss-keyguard              # 无密码时解锁
```

注意：

- 熄屏后 Activity 会 `onPause/onStop`，**UI 不再渲染是正常的**，网络/前台服务仍在；
  但系统进入 Doze 后网络会被限制，长时间保活测试建议加白名单：
  ```bash
  adb shell dumpsys deviceidle whitelist +com.tom.animacolab
  adb shell dumpsys deviceidle whitelist -com.tom.animacolab   # 测完移除
  ```
- 如果只是想让屏幕“黑掉但设备不休眠”（Android 15+）：
  ```bash
  adb shell cmd display power-off 0   # 0 = 主屏
  adb shell cmd display power-on 0    # 这台 ROM 没有该子命令，见下
  ```
  实测（M2012K11AC / Android 17）：`power-off` 生效（HWC `powerMode=Off`、设备仍 Awake），
  但 **`screencap` 会全黑**，而且这台 ROM 不认 `power-on`；恢复要按电源键再唤醒（`KEYCODE_POWER` → `KEYCODE_WAKEUP`）。
  所以要看画面时用 §1 的 scrcpy，要自动截图用 §4 的 `--dim`，别用这条。

## 2.1 实测：scrcpy 熄屏时截图仍可用

本机验证（v5.0.1）：`--turn-screen-off` 生效后 `dumpsys SurfaceFlinger` 显示 `powerMode=Off`、
`mWakefulness=Awake`，server 日志有 `Device display turned off`；此时 `screencap` 仍能取到**完整画面**
（1080×2400，文件 ~200KB，亮度均值与亮屏基本一致）。scrcpy 退出后显示电源会自动恢复 `powerMode=On`。

对比之下 `cmd display power-off` 会让 screencap 全黑——两者机制不同，别混用。
（理论上截图流水线也能在 scrcpy 熄屏状态下跑，但自动点按/uiautomator 尚未验证，先不接。）

## 3. 无线调试：手机可以放进抽屉

```bash
# 先 USB 连一次
adb tcpip 5555
adb connect 192.168.1.23:5555     # 手机 IP，见 设置 → WLAN → 当前网络
adb devices                        # 确认出现 192.168.1.23:5555

scrcpy --tcpip=192.168.1.23:5555 --turn-screen-off --no-audio
```

Android 11+ 也可以走「无线调试 → 使用配对码配对」（`adb pair`），不必先插线。
`--stay-awake` 在纯无线（未充电）时不生效，这是 Android 本身的行为。

## 4. 自动截图流水线：`--dim`

`scripts/device_shots.sh` 必须让屏幕亮着才能 `screencap` + 自动点按，但可以把面板亮度压到最低：

```bash
scripts/device_shots.sh --install --phase full --dim
```

- 只改 `screen_brightness=1` 并临时关掉自动亮度，**退出时（含 Ctrl-C）自动恢复原值**。
- 截图取的是 SurfaceFlinger 帧缓冲，与面板亮度无关 → 截图观感、对比度分析都不受影响。
- 整轮 15 张约 3–5 分钟，低亮度下对 OLED 的损耗可以忽略；也可以拆成几次 `--phase a` 跑。

另外两个常见“屏幕一直亮”的原因，调试时建议关掉：

- 开发者选项「不锁定屏幕 / Stay awake」——充电时永不熄屏；
- 系统「注视屏幕不熄屏」——人坐在电脑前时手机一直亮。

## 5. 本项目相关的护屏习惯

- 长任务（生成、一键部署）不需要盯着手机：熄屏后看崩溃日志（`adb logcat -b crash`）和系统通知；
  前台服务通知表示保活中，出图另有一条系统通知，收到就说明任务完成。
- 视觉验收优先用 mock 网关（不需要真实 Colab），一次跑完再熄屏，避免“亮着等出图”。
- 深色主题在 OLED 上确实比浅色省电，但**熄屏才是根本**；不要用「深色 + 常亮」安慰自己。

## 6. 后续：完全不用手机的截图测试

目前视觉 QA 依赖真机（`device_shots.sh`）。可以再补一层 JVM 渲染的截图测试
（Compose Preview Screenshot Testing / Robolectric 原生图形 / Paparazzi 任一），
把 15 个关键状态在 CI 里渲染成 PNG：

- 好处：零亮屏、秒级反馈、可 diff；真机只做最终抽检（动效、真实字体、动态取色）。
- 注意：需要支持 Compose BOM 2026.09 / AGP 9.5 的版本，接入前先做一次可行性验证。

## 7. 快速自检（熄屏调试没坏）

1. `scrcpy --turn-screen-off` 启动，手机面板应熄灭、电脑窗口有画面；
2. 在电脑窗口点「生成」，手机端应能提交并看到进度（`adb logcat` 有 WS 日志）；
3. 熄屏超过 10 分钟，前台服务通知仍在，心跳没有断（一键模式每 4 分钟一次）；
4. 关闭 scrcpy，手机屏幕自动恢复，亮度/常亮设置未被改坏。
