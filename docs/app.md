# App 端实现说明

## 技术栈

- Kotlin + Jetpack Compose，**Material 3 Expressive**（`androidx.compose.material3:1.5.0-rc01`，显式覆盖 BOM 的 1.4.0），
  AGP 9.5.1 / Kotlin 2.4.21 / Compose BOM 2026.09.00
- OkHttp 4.12（HTTP + WebSocket）、kotlinx-coroutines、org.json（Android 自带）
- 单 Activity + 底部 4 Tab（生成 / 图库 / 速查 / 设置），无导航库；M3 Expressive `ShortNavigationBar` + `AnimatedContent` 切页
- 深/浅色 + Android 12+ Material You 动态取色（可在设置页切换跟随系统/浅色/深色、动态取色开关）
- 所有配置与用户模板存 SharedPreferences；图片经 MediaStore 存 `Pictures/AnimaColab`

## 设计系统与外观

设计规范见 `docs/DESIGN.md`，Google 最新标准研究见 `docs/design/m3-expressive-reference.md`。

- **主题**（`ui/theme/Theme.kt`）：`MaterialExpressiveTheme` + `MotionScheme.expressive()`；
  动态取色优先，关闭时用品牌色板（电光紫 primary / 蜜桃粉 secondary / 青柠蓝 tertiary，深浅两套均校准对比度）；
  语义扩展色（success/warning）通过 `LocalAnimaExtendedColors` 提供，`MaterialTheme.extended` 读取。
- **令牌**（`ui/theme/Tokens.kt`）：8dp 间距刻度 `Spacing`、形状 `AnimaShape`（Hero 32dp / 卡片 26dp / 输入 18dp / 胶囊按钮，
  另含 `MaterialShapes` 形状库装饰）、动效 `AnimaMotion`（对齐 M3 Expressive spring 参数；尺寸类用 `spatialSize`）。
- **组件**（`ui/Components.kt`）：`ExpressiveCard`、`ScreenHeader`、`SectionLabel`、`StatusPill`、`ChoiceChips`、
  `SegmentedOptions`、`PrimaryActionButton`、`SecondaryActionButton`、`SettingRow/SettingSwitchRow`、
  `AnimaSliderRow`、`AnimaTextField`、`DropdownField`、`EmptyState`、`ShapeDecoration`、`NetworkImage`、
  `MetaText`、`InfoRow`、`Modifier.pressScale`、`AnimatedChevron` 等，所有屏幕复用。
- **外观设置**（`data/Appearance.kt` + `ConfigStore` + `UiState`）：`ThemeMode{System,Light,Dark}` 与
  `dynamicColor` 持久化，`MainActivity` 实时换主题并同步系统栏图标明暗。
- **Shell**：`enableEdgeToEdge()`，Scaffold 统一处理状态栏/底部导航 insets；底部短导航栏 4 项；
  返回键先回「生成」；启动页/自适应图标/单色图标（`res/mipmap-anydpi-v26` + `values-v31/themes.xml`）。

## 模块

```
data/
  Models.kt         数据模型（Config/ModelPreset/ServerInfo/GenParams/JobState/FormState…）
  ComfyClient.kt    网关客户端：health/info/prompt/queue/history/view + WebSocket
  PromptBuilder.kt  ComfyUI API 节点图构造（契约 §5）、质量前缀拼装、种子处理
  ConfigStore.kt    SharedPreferences：配对信息、表单、用户模板
  Assets.kt         assets/models.json、tags.json、templates.json 读取（带兜底）
  Pairing.kt        PAIR 行解析（url|token / 双行 / JSON）
  ImageLoader.kt    OkHttp 带 token 图片下载 + 字节缓存 + 缩采样
  LocalImageStore.kt 出图自动下载到 App 私有存储（不写相册）
  NotebookExporter.kt 已移除（备用笔记本改为 SAF 导出：见 SettingsScreen）
AppViewModel.kt     单一 StateFlow<UiState>，连接/生成/进度/历史/图片保存
ui/
  GenerateScreen.kt 提示词/模型/参数/SplitButton 生成/波浪进度/结果卡（M3 Expressive Hero 卡）
  GalleryScreen.kt  服务器历史双列错落网格 + 空/加载/错误态（historyError）
  ExploreScreen.kt  Tag 速查（288 个）+ 模板，分类 Tab、正/负向插入
  SettingsScreen.kt 连接状态机 Hero、外观设置、通知/配对/服务器/说明/关于
  ImageViewer.kt    全屏 Pager 看图（浮动工具栏保存/分享/填回参数）
  Components.kt     M3 Expressive 共享组件库 + NetworkImage
  theme/Theme.kt    主题（动态取色/品牌色板/扩展语义色）
  theme/Tokens.kt   间距/形状/动效令牌
```

## 一键启动 Colab（colab/ 包）

```
colab/
  ColabApi.kt            assignments API：list / assign(GET xsrf→POST) / unassign / notebookHash
  JupyterKernelClient.kt runtime REST（create/delete kernel）+ Jupyter WebSocket execute（流式 stdout）
  GoogleAuth.kt          Google OAuth（PKCE + remote 授权码流程）+ token 自动刷新 + 本地存储
  ColabSessionStore.kt   一键会话持久化（endpoint/kernel/proxy token/PAIR，App 重启可复用）
  ColabSessionManager.kt 状态机：复用或申请 T4 → 建 kernel → 执行 assets/colab_deploy.py
                         → 解析 PAIR → 写入 ConfigStore → 4 分钟心跳保活 → 停止时 unassign
```

流程：设置页点「一键启动 Colab T4」→ `ColabSessionManager.start()` →
`ColabApi.listAssignments()`（优先复用持久化的 endpoint，其次任意 T4）→ 无则 `assign(new nbh, T4)` →
`JupyterKernelClient.createKernel()` → `execute(部署脚本)`（`onStream` 实时把 `[deploy]` 行推给 UI）→
正则解析 `PAIR: <url>|<token>` → 写入配对 → `connect()`。
停止会话：删 kernel + `unassign(endpoint)` + 清空本地会话；若是自动写入的配对会一并清除（手动配对不受影响）。

## 后台保活 / 通知 / 自动下载

- **前台服务保活**：会话 Ready 后启动 `KeepaliveService`（`foregroundServiceType=specialUse`），
  常驻通知「Anima Colab 会话运行中 / T4 保活中」，并带「打开 App」「停止会话」两个动作。
  心跳（每 4 分钟一次 kernel 执行）跑在 Application 级协程里，所以切后台/关屏不会断。
  设置页可关（「通知与保存 → 后台保活」）。
- **生成完自动下载**：出图后自动把 PNG 拉到 App 私有存储 `files/images/`（不写系统相册），
  通知文案明确「点保存才会进相册」。`保存` 按钮才写 MediaStore（`Pictures/AnimaColab`）；
  `分享` 也优先复用本地字节，不再重复走隧道下载。
- **系统通知**：`Notifications` 统一管理两个渠道（`colab_session` 低优先级常驻；`generation` 普通优先级），
  事件包括：Colab T4 已就绪 / 出图完成（含自动下载数量）/ 生成失败 / 会话断开。
  Android 13+ 启动时申请 `POST_NOTIFICATIONS`。

## 生成流程

1. `GenerateScreen` 表单 → `AppViewModel.generate()`
2. 选 unet（模型注册表匹配 + `/anima/v1/info`）、VAE、TE
3. `PromptBuilder.composePositive()` 按档位自动补质量前缀；负向按预设
4. `PromptBuilder.buildGraph()` → `POST /prompt`（带 `client_id`）
5. 同时开两路：
   - `WS /ws?clientId=` 解析 ComfyUI 事件（`progress`/`executing`/`execution_success`…）驱动进度条
   - 每 2s 轮询 `/anima/v1/history/{prompt_id}` 兜底（WS 断了也能完成）
6. 拿到图片后拉 `/animai/v1/history` 刷新图库；`/view` 下载图片显示/保存/分享

## 调试 / 真机视觉验收

```bash
adb logcat -s AndroidRuntime:* animacolab:*     # 崩溃
adb shell run-as com.tom.animacolab ls files     # 沙箱内数据

# 无需 Colab：本机 mock 网关 + adb reverse + 自动截图（深/浅色、动态取色、空/错误/生成中/结果）
scripts/device_shots.sh --install --phase full   # → docs/screenshots/redesign/ + 视觉 QA 报告
# 只起 mock 网关（127.0.0.1:8091）手工联调：
python3 scripts/mock_gateway.py
adb reverse tcp:8091 tcp:8091
```

配对信息可以用下面方式免手输注入（debug 包）：

```bash
cat > /tmp/anima_config.xml <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="base_url">https://xxxx.trycloudflare.com</string>
    <string name="token">token</string>
</map>
EOF
adb push /tmp/anima_config.xml /data/local/tmp/anima_config.xml
adb shell "run-as com.tom.animacolab sh -c 'cp /data/local/tmp/anima_config.xml shared_prefs/anima_config.xml'"
adb shell am force-stop com.tom.animacolab && adb shell am start -n com.tom.animacolab/.MainActivity
```
