# Anima Colab — 项目约定

Android App（Kotlin + Compose）通过 Cloudflare 隧道连接 Colab 上的 ComfyUI 出图。
结构：`android/` App、`colab/` 服务端、`docs/`（contract / DESIGN / QA / device-debug）、`scripts/` 工具。
真机：Xiaomi M2012K11AC（adb `ef9ca6f9`），1080×2400 @440dpi（392×873dp）。

## 真机调试：默认熄屏

**不要为了看画面把手机屏幕长时间点亮（AMOLED）。** 默认流程：

```bash
scripts/dev.sh start                # 熄灭面板（设备保持唤醒），侧边栏 phone-mirror 继续出帧
scripts/dev.sh status               # 设备 / 面板 / 唤醒 / 镜像状态
scripts/dev.sh shot .tmp/shot.jpg   # 取当前画面（面板熄灭也能取）
scripts/dev.sh stop                 # 点亮面板，结束调试
```

- 看画面用侧边栏「手机屏幕」；agent 用 `scripts/dev.sh shot` 或
  `curl http://127.0.0.1:3080/phone-mirror/snapshot.jpg`（用 `read_image` 打开）。
- `start` 优先调 phone-mirror 的 `/phone-mirror/screen?state=off`；插件不可用时才自己起
  `scrcpy --no-window --turn-screen-off --stay-awake` holder。
- **不要**用 `adb shell cmd display power-off`：它会让 screenrecord 冻帧、screencap 全黑。
- 面板 off 时 **不要**直接用 `adb shell screencap` 取图（黑）；用镜像快照或先 `stop`。
- 调试结束记得 `scripts/dev.sh stop`，不要把手机长期留在「亮屏 + 插电」。
- 原理与排错：`docs/device-debug.md`；独立 scrcpy 窗口：`scripts/scrcpy-off.sh`。

## 构建 / 测试

```bash
scripts/build.sh                 # debug APK
scripts/install.sh               # adb 安装并启动
scripts/team-build.sh            # 带 flock 的团队构建（device_shots --install 用它）
source scripts/env.sh && cd android && ./gradlew :app:testDebugUnitTest
```

Gradle 缓存：脚本自动优先项目内 `.gradle-user-home`（含代理配置与 rc 依赖），否则 `~/.gradle`。

## 视觉验收

```bash
scripts/device_shots.sh --install --phase full --dim   # 15 张真机截图，亮度=1（截图不受影响）
```

输出 `docs/screenshots/redesign/`；对照 `docs/design/visual-qa.md`。

## 产物与工作区

- APK 交付物：`outputs/AnimaColab-0.1-debug.apk`（`outputs/` 已 gitignore）。
- 不要提交 `outputs/`、`.tmp/`、`.gradle-user-home/`、`android/app/build/`。
- 资源里的计数以 assets 为准：288 tags / 10 templates / 12 snippets。

## 代码约定

- 共享组件 / 令牌优先（`docs/DESIGN.md`）；颜色走 `colorScheme`，不硬编码（装饰透明色除外）。
- 中文文案面向普通用户；不减少现有功能。
- 提交前至少过编译 + 单测；UI 改动尽量用熄屏镜像 / 截图确认。
