# colab/ — Anima Colab T4 / TPU 服务端

手机 App 通过 Cloudflare 快速隧道连接 Colab 上的 ComfyUI 出 Anima 图；支持 T4 GPU 与 TPU v5e1。
本目录是**服务端**：一键部署脚本 + token 鉴权网关 + App 一键协议参考实现 + 联调脚本。
接口契约见 [`../docs/contract.md`](../docs/contract.md)，使用者文档见
[`../docs/colab-setup.md`](../docs/colab-setup.md)。

两种使用方式：

- **App 一键模式（推荐，v2）**：App 内登录 Google → 选 T4 / TPU → 自动开会话 → 在 kernel 里执行
  `deploy_script.py` → 自动拿 PAIR → 直接出图。用户在 App 里点一下，不需要打开 Colab 网页。
- **notebook 手动模式（备用）**：手机浏览器打开 `anima_colab.ipynb` → 全部运行 → 复制 PAIR。
  适合调试、没有 App 一键功能的场合。

## 文件

| 文件 | 作用 |
|---|---|
| `deploy_script.py` | **App 一键部署载荷**（单文件自包含，约 54KB）：内嵌网关源码与 TPU 设备补丁，kernel 里 `execute_request` 直接执行；纯 Python、无 magic、幂等、断点续传；自动识别 T4/TPU（App 会注入 `ANIMA_ACCELERATOR` 做校验），stdout 每行 `[deploy]...`，最后 `PAIR: <url>\|<token>`。App 的 `android/app/src/main/assets/colab_deploy.py` 由 `build_notebook.py` 自动同步。 |
| `phone_bootstrap.py` | **App 一键协议参考实现**（PC 可跑）：只用 `~/.config/colab-cli/token.json` 复刻完整链路（OAuth refresh → assignments → kernel REST → Jupyter WS execute → 解析 PAIR）。供 Kotlin 移植对照。 |
| `PHONE_BOOTSTRAP.md` | 一键协议文档：每步真实请求/响应样例、实测耗时、8 条坑、Kotlin 移植清单。 |
| `anima_colab.ipynb` | 备用方案：自包含 notebook（纯 Python、无 magic、幂等、保活 cell）。Run all 打印 PAIR。 |
| `gateway.py` | 网关源码（唯一实现）：`127.0.0.1:8081`，Bearer token 鉴权，反向代理 ComfyUI，WS 原始帧透传，`/history` 参数汇总。 |
| `build_notebook.py` | 由 `gateway.py` + cell 模板重新生成 `anima_colab.ipynb` **和** `deploy_script.py`，并同步到 Android assets（改网关/部署逻辑后必须重跑）。 |
| `e2e_check.py` | 从任意联网机器走公网 tunnel 跑契约 §8 第 1 段验收（19 项：health/info/prompt/WS/history/view/错误码）。 |
| `TESTING.md` | 真实 T4 联调证据（冷启动耗时、bug 复盘、19/19 验收、一键模式实测）。 |

## 快速开始（使用者）

- **App 一键**：装好 App → 设置 → 选择 T4 / TPU v5e1 →「一键启动 Colab」→ 等自动部署完（首次约 3-5 分钟）→ 生成图片。
- **备用 notebook**：把 `anima_colab.ipynb` 存到 Google Drive → 选 T4 GPU 或 TPU v5e1 运行时 → 全部运行 → 复制 `PAIR:` 行。

细节与排错见 [`../docs/colab-setup.md`](../docs/colab-setup.md)。

## 网关 API（App 只用这些，详见契约 §4）

| 端点 | 鉴权 | 说明 |
|---|---|---|
| `GET /anima/v1/health` | 免 token | `ok/comfy/queue/device/vram_total_gb`；comfy 状态免 token 也真实。 |
| `GET /anima/v1/info` | Bearer | unets/loras/vaes/text_encoders/samplers/schedulers/defaults + device/vram。 |
| `POST /prompt` | Bearer | 原样转发 ComfyUI `/prompt`。 |
| `GET /anima/v1/queue` | Bearer | `{ok,running[],pending[]}`。 |
| `POST /anima/v1/queue/clear` | Bearer | 清空排队。 |
| `POST /anima/v1/interrupt` | Bearer | 中断当前任务。 |
| `GET /anima/v1/history?limit=50` | Bearer | 汇总 ComfyUI `/history`，按完成时间倒序，含解析后的生成参数（含 batch）。 |
| `GET /anima/v1/history/{prompt_id}` | Bearer | 单项；不存在 404 `not_found`。 |
| `GET /view?filename=&subfolder=&type=output` | Bearer | 图片字节原样转发。 |
| `WS /ws?clientId=` | Bearer | ComfyUI WS 原始帧双向透传（不包装）。 |

错误统一 `{"ok":false,"error":"..."}`；ComfyUI 不可达 → 502 `comfy_unreachable`。
token 不写入任何日志（`/content/gateway.log` 只有启动信息）。

## 开发者：改代码 → 重新部署

```bash
# 1) 改 gateway.py / 部署 cell 后重新生成 notebook + deploy_script，
#    并自动同步到 android/app/src/main/assets/
python3 colab/build_notebook.py
python3 scripts/extract_notebook.py colab/anima_colab.ipynb /tmp/anima_deploy.py

# 2) 部署/重部署（幂等；colab exec 大下载超时属正常，VM 继续）
colab new --gpu T4 -s anima          # 没有会话时才需要；TPU 用 --tpu v5e1
colab exec -s anima --timeout 1800 -f /tmp/anima_deploy.py

# 3) App 一键协议自测（不经过 colab CLI exec / notebook）
python3 colab/phone_bootstrap.py --proxy http://127.0.0.1:7890 --pair-out /tmp/pair.txt
python3 colab/phone_bootstrap.py --accelerator tpu --proxy http://127.0.0.1:7890

# 5) 契约 §8 第 1 段验收（PC 侧；WS 直连被网络限制时加 --proxy）
python3 colab/e2e_check.py --pair-file /tmp/pair.txt --out-dir outputs \
        --seed 777001 --proxy http://127.0.0.1:7890
```

> 注意：大下载期间 `colab exec` 可能报 `TimeoutError: Timeout waiting for reply`，属正常；
> VM 上的下载继续，重跑同一脚本会 `curl -C -` 续传。

### 本机网络注意

- 本开发机访问 `huggingface.co`、`oauth2.googleapis.com` 直连会超时（模型只在 VM 内下载）；
  colab CLI / phone_bootstrap 需要 `HTTPS_PROXY=http://127.0.0.1:7890`。
- 本机直连 `wss://*.trycloudflare.com` 会被网络掐断（HTTP 正常），`e2e_check.py`
  用 `--proxy http://127.0.0.1:7890` 可通。**这是客户端网络问题，不是网关问题**
  （VM 内直连/经 Cloudflare 的 WS 都验证过）。

## 服务端日志（VM 内）

| 路径 | 内容 |
|---|---|
| `/content/comfy.log` | ComfyUI 启动与推理日志 |
| `/content/gateway.log` | 网关启动日志（无 token） |
| `/content/cloudflared.log` | 快速隧道日志（含 trycloudflare URL） |
| `/content/anima_token.txt` | 本会话 token（600 权限） |
| `/content/anima_pair.txt` | 本会话 `PAIR:` 行（600 权限） |

## 已知限制

- 只部署 `anima-base-v1.0`（v1 契约）；换 turbo/aesthetic 需扩展 `deploy_script.py` 的
  `MODELS` 并改 App 参数，不在本版范围。
- 免费 Colab 单会话 1-3 小时、`/content` 随会话清空；掉线后重跑一键流程会重新下载模型
  （约 3-4 分钟）并生成新 PAIR。
- T4（`--force-fp16`）与 TPU v5e1（`--bf16-unet` + 11 处设备补丁）均已接入；TPU 为实验档：
  固定 ComfyUI `v0.46.2`，首图需 JIT 编译约 1 分钟，kernel 不能 import torch。
- **TPU 补丁升级流程**：改 `COMFY_TPU_TAG` 前先跑
  `python3 scripts/check_tpu_patches.py`（默认拉取固定 tag，可用 `--file` 指定本地
  `comfy/model_management.py`）：11 处锚点必须各命中一次且 `py_compile` 通过；
  否则 App 会在部署时给出明确的锚点缺失错误。
- 网关 WS 已验证可用：App 实机（手机 OkHttp WS 经 Cloudflare）能正常收到
  `progress` / `executed` / `execution_success`（App 另有 `/history` 轮询兜底）。
  仅本开发机 PC 直连 `wss://*.trycloudflare.com` 会被网络掐断，PC 验收脚本加
  `--proxy http://127.0.0.1:7890` 即可——是本机网络限制，不是服务端/App 问题。
- Cloudflare 快速隧道 URL 每次随机，token 是唯一鉴权凭据，切勿外泄。
