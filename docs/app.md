# App 端实现说明

## 技术栈

- Kotlin + Jetpack Compose（Material3，暗色主题），AGP 9.5.1 / Kotlin 2.4.21 / Compose BOM 2026.09.00
- OkHttp 4.12（HTTP + WebSocket）、kotlinx-coroutines、org.json（Android 自带）
- 单 Activity + 底部 4 Tab（生成 / 图库 / 速查 / 设置），无导航库
- 所有配置与用户模板存 SharedPreferences；图片经 MediaStore 存 `Pictures/AnimaColab`

## 模块

```
data/
  Models.kt         数据模型（Config/ModelPreset/ServerInfo/GenParams/JobState/FormState…）
  ComfyClient.kt    网关客户端：health/info/prompt/queue/history/view + WebSocket
  PromptBuilder.kt  ComfyUI API 节点图构造（契约 §5）、质量前缀拼装、种子处理
  ConfigStore.kt    SharedPreferences：配对信息、表单、用户模板
  Assets.kt         assets/models.json、tags.json、templates.json 读取（带兜底）
  Pairing.kt        PAIR 行解析（url|token / 双行 / JSON）
  ImageLoader.kt    OkHttp 带 token 图片下载 + LruCache + 缩采样
  AppLinks.kt       Colab 笔记本链接
AppViewModel.kt     单一 StateFlow<UiState>，连接/生成/进度/历史/图片保存
ui/
  GenerateScreen.kt 提示词/模型/参数/生成/进度/结果卡
  GalleryScreen.kt  服务器历史网格 + 大图查看
  ExploreScreen.kt  Tag 速查（295 个）+ 模板
  SettingsScreen.kt 配对、服务器信息、使用说明、关于
  ImageViewer.kt    全屏 Pager 看图（保存/分享/填回参数）
  Components.kt     通用组件 + NetworkImage
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

## 生成流程

1. `GenerateScreen` 表单 → `AppViewModel.generate()`
2. 选 unet（模型注册表匹配 + `/anima/v1/info`）、VAE、TE
3. `PromptBuilder.composePositive()` 按档位自动补质量前缀；负向按预设
4. `PromptBuilder.buildGraph()` → `POST /prompt`（带 `client_id`）
5. 同时开两路：
   - `WS /ws?clientId=` 解析 ComfyUI 事件（`progress`/`executing`/`execution_success`…）驱动进度条
   - 每 2s 轮询 `/anima/v1/history/{prompt_id}` 兜底（WS 断了也能完成）
6. 拿到图片后拉 `/animai/v1/history` 刷新图库；`/view` 下载图片显示/保存/分享

## 调试

```bash
adb logcat -s AndroidRuntime:* animacolab:*     # 崩溃
adb shell run-as com.tom.animacolab ls files     # 沙箱内数据
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
