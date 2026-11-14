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

## 手机端使用流程

1. **起服务端**：手机浏览器 / Colab App 打开 `anima_colab.ipynb` →「全部运行」。
   首次会装 ComfyUI + 下载 base 模型（约 5.3GB，3-5 分钟），跑完最后打印：
   ```
   PAIR: https://xxxx.trycloudflare.com|<token>
   ```
2. **配对**：App「设置 → 粘贴配对信息」，长按粘贴整行 →「保存并连接」。
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
