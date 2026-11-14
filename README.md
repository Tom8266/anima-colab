# Anima Colab → Android

手机 App（Kotlin + Jetpack Compose）通过 **Cloudflare 快速隧道 + token 鉴权** 连接 Google Colab T4 上运行的 ComfyUI，
用 **Anima（2B DiT 动漫模型）** 文生图。不搬 ComfyUI 前端，只保留手机真正需要的功能。

```
┌──────────────┐   HTTPS / WSS   ┌──────────────────┐   HTTP/WS   ┌───────────────┐
│ Android App  │ ──────────────> │ Cloudflare 快速隧道 │ ──────────> │ Colab VM       │
│ Kotlin+Compose│   Bearer token  │ (trycloudflare)   │             │ gateway:8081   │
└──────────────┘                 └──────────────────┘             │   ↓ token 校验  │
                                                                  │ ComfyUI:8188   │
                                                                  │ (仅 127.0.0.1) │
                                                                  └───────────────┘
```

## 目录

| 路径 | 内容 |
|---|---|
| `android/` | App 源码（Gradle 9.9 / AGP 9.5.1 / Kotlin 2.4.21 / Compose BOM 2026.09） |
| `colab/anima_colab.ipynb` | 手机「全部运行」即可的服务端笔记本（装 ComfyUI + base 模型 + 网关 + 隧道） |
| `colab/gateway.py` | token 鉴权反向代理（App 只需要它暴露的接口） |
| `colab/build_notebook.py` | 由模板生成 ipynb（改服务端代码后重新生成） |
| `docs/contract.md` | App ⇄ 网关 ⇄ ComfyUI 接口契约（开发对齐唯一依据） |
| `docs/colab-setup.md` | Colab 端使用与排错 |
| `scripts/build.sh` / `install.sh` | 构建 debug APK / adb 安装启动 |
| `scripts/extract_notebook.py` | 把 ipynb 抽成 .py 供 `colab exec` 联调 |
| `scripts/e2e_check.py` | PC 侧端到端验收（health→info→提交→出图→下载→WS） |

## 手机端使用流程（一键模式，推荐）

1. **登录一次**：设置页「Colab 一键启动」→ 登录 Google（浏览器授权，把授权码粘贴回来）。
2. **一键启动**：点「一键启动 Colab T4」。App 会自动：复用/申请 T4 会话 → 在 Colab kernel 里执行部署脚本
   （`assets/colab_deploy.py`，装 ComfyUI + 下载 base 模型 + 起网关 + Cloudflare 隧道）→ 解析 PAIR → 自动完成配对。
   首次 3-5 分钟，已部署过的会话几秒完成；进度和部署日志在设置页实时显示。
3. **生成**：回生成页写提示词 → 生成。App 每 4 分钟自动给 kernel 发心跳保活；不用时点「停止会话」释放。

> 一键模式协议移植自 Google 开源的 [google-colab-cli](https://github.com/googlecolab/google-colab-cli)：
> OAuth（installed-app client + PKCE）→ `colab.research.google.com/tun/m/assign` 申请 T4 →
> `runtimeProxyInfo` → Jupyter kernel WebSocket 执行代码。手机需要能访问 Google（代理/VPN）。

## 备用：手动配对（笔记本模式）

1. **起服务端**：手机浏览器 / Colab App 打开 `anima_colab.ipynb` →「全部运行」。
   首次会装 ComfyUI + 下载 base 模型（约 5.3GB，3-5 分钟），跑完最后打印：
   ```
   PAIR: https://xxxx.trycloudflare.com|<token>
   ```
2. **配对**：App「设置 → 手动配对（备用）→ 粘贴配对信息」，长按粘贴整行 →「保存并连接」。
3. **生成**：写提示词 → 选参数 → 生成。base 1024² / 30 步 / CFG 4.5 在 T4 上约 70-90 秒。
4. **看图**：生成完成直接显示；「图库」里能看到服务器历史，支持保存到相册 / 分享 / 参数填回。
5. **写提示词**：「速查」页有 295 个中文分类 Danbooru tag、10 套模板、常用片段与负向预设，点一下就插入。

## 开发

```bash
scripts/build.sh            # 构建 debug APK
scripts/install.sh          # 手机连 adb 后安装并启动
scripts/build.sh release    # release（未签名）
```

单元测试：

```bash
source scripts/env.sh && cd android && ./gradlew :app:testDebugUnitTest
```

## 服务端联调

本机没有 GPU/网络限制，服务端通过 Colab CLI 联调：

```bash
# 本机 oauth 需要代理（可选）
export HTTPS_PROXY=http://127.0.0.1:7890
colab new --gpu T4 -s anima
python3 scripts/extract_notebook.py colab/anima_colab.ipynb /tmp/anima_deploy.py
colab exec -s anima --timeout 1800 -f /tmp/anima_deploy.py
# 从笔记本输出拿 PAIR 后：
python3 scripts/e2e_check.py 'PAIR: https://...|token'
colab stop -s anima        # 用完释放
```

## 已知限制

- 免费版 Colab：约 90 分钟无操作会断（笔记本末尾 keepalive cell 维持活跃），单次会话有时长上限；
  会话结束后 `/content` 清空，模型要重新下载。
- 快速隧道 URL 每次会话都会变，需要重新粘贴 PAIR。
- T4 用 `--force-fp16`；TPU v5e1 支持留到 v2。
- Colab 平台禁止生成色情内容，只在云端跑正常内容。
