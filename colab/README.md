# colab/ — Anima Colab T4 服务端

手机 App 通过 Cloudflare 快速隧道连接 Colab T4 上的 ComfyUI 出 Anima 图。
本目录是**服务端**：一键 notebook + token 鉴权网关 + 联调脚本。接口契约见
[`../docs/contract.md`](../docs/contract.md)，使用者文档见
[`../docs/colab-setup.md`](../docs/colab-setup.md)。

## 文件

| 文件 | 作用 |
|---|---|
| `anima_colab.ipynb` | **手机端一键部署**：Run all → 打印 `PAIR: <url>|<token>`。自包含（网关源码内嵌），纯 Python、无 magic、幂等、断点续传。 |
| `gateway.py` | 网关源码（唯一实现）：`127.0.0.1:8081`，Bearer token 鉴权，反向代理 ComfyUI，WS 原始帧透传，`/history` 参数汇总。 |
| `build_notebook.py` | 由 `gateway.py` + cell 模板重新生成 `anima_colab.ipynb`（改网关后必须重跑）。 |
| `e2e_check.py` | 从任意联网机器走公网 tunnel 跑契约 §8 第 1 段验收（health/info/prompt/WS/history/view/错误码）。 |
| `TESTING.md` | 真实 T4 联调证据（prompt_id、下载图、耗时、PAIR 状态）。 |

## 快速开始（使用者）

1. 把 `anima_colab.ipynb` 存到 Google Drive（或上传到 Colab）。
2. 运行时类型选 **T4 GPU** → `运行时 → 全部运行`。
3. 等最后打印 `PAIR: https://xxxx.trycloudflare.com|<token>`。
4. 手机 App → 设置 → 粘贴整行 → 测试连接 → 生成。

细节与排错见 [`../docs/colab-setup.md`](../docs/colab-setup.md)。

## 网关 API（App 只用这些，详见契约 §4）

| 端点 | 鉴权 | 说明 |
|---|---|---|
| `GET /anima/v1/health` | 免 token | 返回 `ok/comfy/queue/device/vram_total_gb`；comfy 状态免 token 也真实。 |
| `GET /anima/v1/info` | Bearer | unets/loras/vaes/text_encoders/samplers/schedulers/defaults + device/vram。 |
| `POST /prompt` | Bearer | 原样转发 ComfyUI `/prompt`（App 节点图见契约 §5）。 |
| `GET /anima/v1/queue` | Bearer | `{ok,running[],pending[]}`（prompt_id 列表）。 |
| `POST /anima/v1/queue/clear` | Bearer | 清空排队。 |
| `POST /anima/v1/interrupt` | Bearer | 中断当前任务。 |
| `GET /anima/v1/history?limit=50` | Bearer | 汇总 ComfyUI `/history`，按完成时间倒序，含解析后的生成参数。 |
| `GET /anima/v1/history/{prompt_id}` | Bearer | 单项；不存在 404 `not_found`。 |
| `GET /view?filename=&subfolder=&type=output` | Bearer | 图片字节原样转发。 |
| `WS /ws?clientId=` | Bearer | ComfyUI WS 原始帧双向透传（不包装）。 |

错误统一 `{"ok":false,"error":"..."}`；ComfyUI 不可达 → 502 `comfy_unreachable`。
网关不把 token 写入日志（`/content/gateway.log` 只有访问日志和启动信息）。

## 开发者：改代码 → 重新部署

```bash
# 1) 改网关后重新生成 notebook（notebook 内嵌 gateway.py 全文，必须重新生成）
python3 colab/build_notebook.py

# 2) 抽出线性脚本（跳过保活 cell；出现 IPython magic 会明确报错）
python3 scripts/extract_notebook.py colab/anima_colab.ipynb /tmp/anima_deploy.py

# 3) 部署/重部署到会话（幂等：已存在 ComfyUI/模型/进程会跳过）
colab new --gpu T4 -s anima          # 没有会话时才需要
colab exec -s anima --timeout 1800 -f /tmp/anima_deploy.py

# 4) 验收（公网 PAIR 拿到后，本机任意目录）
PAIR=$(python3 -c "print(open('/tmp/pair.txt').read().strip())")  # 或从 notebook 输出复制
python3 colab/e2e_check.py --pair "$PAIR" --proxy http://127.0.0.1:7890
```

`colab exec` 在大模型下载时会报 `TimeoutError: Timeout waiting for reply`，属正常：
VM 上的下载继续，重跑同一脚本会 `curl -C -` 续传。

### 本机网络注意

- 本开发机访问 `huggingface.co`、`oauth2.googleapis.com` 会超时：模型只在 VM 内下载；
  colab CLI 需 `HTTPS_PROXY=http://127.0.0.1:7890` 才能刷新 token。
- 访问 `*.trycloudflare.com` 若直连不通，`e2e_check.py --proxy http://127.0.0.1:7890`。

## 服务端各组件日志（VM 内）

| 路径 | 内容 |
|---|---|
| `/content/comfy.log` | ComfyUI 启动与推理日志 |
| `/content/gateway.log` | 网关访问日志（无 token） |
| `/content/cloudflared.log` | 快速隧道日志（含 trycloudflare URL） |
| `/content/anima_token.txt` | 本会话 token（600 权限，PAIR 行同款） |
| `/content/anima_pair.txt` | 本会话 `PAIR:` 行（600 权限） |

## 已知限制

- 只部署 `anima-base-v1.0`（v1 契约）；换 turbo/aesthetic 需扩展 notebook 的 `MODELS` 并改
  App 侧参数，不在本版范围。
- 免费 Colab 单会话 1-3 小时、`/content` 随会话清空；断线重跑会重新下载模型（约 5-10 分钟）。
- 仅验证 T4（`--force-fp16`）；_TPU 留到 v2_（契约 §1）。
- Cloudflare 快速隧道 URL 每次随机、可能被滥用扫描；token 是唯一鉴权凭据，切勿外泄。
