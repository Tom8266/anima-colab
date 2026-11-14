# Colab 服务端真实联调记录（TESTING）

> 2026-11-14 17:22-17:57（Asia/Shanghai）实测。环境：本机 Fedora + `colab` CLI 0.7.5，
> 免费 Google 账号，T4 免费会话。本文所有 prompt_id / 文件 / 输出均为真实运行结果。

## 1. 服务端环境

| 项目 | 值 |
|---|---|
| Colab 会话 | `[anima] gpu-t4-s-usw1b2-ycpdkwttgegi` / Hardware **T4** / Shape Standard |
| GPU | Tesla T4，显存 14.6 GB（nvidia-smi 15.0 GB 口径） |
| ComfyUI | **0.45.0**（`git clone --depth 1` master，不是 notes 里的 0.43.0） |
| Python / torch | 3.13.16 / 2.14.1+cu130 |
| 磁盘 | /content 共 112.6 GB |
| 模型 | anima-base-v1.0（3.89 GB）+ qwen_3_06b_base（1.11 GB）+ qwen_image_vae（0.24 GB） |
| 网关 | `colab/gateway.py` v0.1.0，127.0.0.1:8081，token 32 字符随机 |
| 隧道 | cloudflared quick tunnel（QUIC） |
| 公网 PAIR | `https://privileges-package-wilson-west.trycloudflare.com|<token>`（token 见 VM 内 `/content/anima_token.txt`） |

## 2. 首次全量部署（冷启动，CLI exec）

命令：

```bash
python3 scripts/extract_notebook.py colab/anima_colab.ipynb /tmp/anima_deploy.py
colab exec -s anima --timeout 1800 -f /tmp/anima_deploy.py
```

真实耗时（17:29:41 开始，17:33:17 完成，**共 3 分 36 秒**）：

| 阶段 | 耗时 |
|---|---|
| git clone ComfyUI（depth 1） | 2s |
| pip install -r requirements.txt | 44s |
| 下载 anima-base-v1.0（3.89GB） | ~80s（约 50MB/s，curl -C -） |
| 下载 qwen_3_06b_base（1.11GB） | ~15s |
| 下载 qwen_image_vae（0.24GB） | ~5s |
| ComfyUI 启动到 /object_info 就绪 | 26s |
| 网关启动到本地 health comfy=true | 2s |
| cloudflared 下载 + 隧道 URL + 公网 health | ~36s |
| **合计** | **3m36s** |

冷启动输出（节选，真实）：

```
[deploy] 09:29:45 GPU: Tesla T4 / 显存 14.6 GB
[deploy] 09:29:47 ComfyUI 克隆完成
[deploy] 09:30:31 依赖安装完成
[deploy] 09:31:51 下载完成: anima-base-v1.0.safetensors (3.89 GB)
[deploy] 09:32:11 三个模型文件全部就绪（base 3.9G + TE 1.2G + VAE 243M）
[deploy] 09:32:38 ComfyUI 已就绪: http://127.0.0.1:8188
[deploy] 09:32:40 网关已就绪: http://127.0.0.1:8081
[deploy] 09:32:46 隧道 URL: https://privileges-package-wilson-west.trycloudflare.com
[deploy] 09:33:16 就绪: 公网 /anima/v1/health 可达（经 Cloudflare 边缘）

PAIR: https://privileges-package-wilson-west.trycloudflare.com|wJpI...mpoP
```

首次 e2e 时 **WS 3 项失败**，其余通过；同时发现 `POST /prompt` 返回 500。

## 3. 联调发现并修复的 bug

### 3.1 `POST /prompt` 500（网关 bug，已修复）

- 现象：`POST /prompt` 经网关返回 `HTTP 500 Internal Server Error`（ComfyUI 侧日志
  显示 `got prompt` 并正常出图，说明请求其实被接受了）。
- 根因（`/content/gateway.log` traceback）：
  ```
  File "anima_gateway.py", line 351, in _forward_json
      return web.Response(status=resp.status, body=body, content_type=content_type)
  ValueError: charset must not be in content_type argument
  ```
  ComfyUI 返回 `Content-Type: application/json; charset=utf-8`，aiohttp 的
  `web.Response(content_type=...)` 不允许 charset。
- 修复：改为把上游 `Content-Type` 作为原始 header 透传（`headers={"Content-Type": ...}`）。
  热更新网关后 500 消失，E2E 全过。
- 附带修复：`/anima/v1/health`、`/info` 的 `device` 原来解析成
  `"0 Tesla T4 : cudaMallocAsync"`（ComfyUI `/system_stats` 的原始 name），
  现输出 `"Tesla T4"`；`vram_total_gb=14.6`。

### 3.2 网关 WS 是好的，客户端网络限制导致直连失败（App 需降级兜底）

- 本机（PC，与手机同 Wi-Fi）直连 `wss://<tunnel>/ws`：`TimeoutError: timed out during
  opening handshake`（25s），**经本机代理 127.0.0.1:7890 则 6.2s 握手成功**。
- 从 Colab VM 内部验证：`ws://127.0.0.1:8081/ws` 直连 **CONNECTED**，
  `wss://<tunnel>/ws` 经 Cloudflare **CONNECTED**，首帧
  `{"type":"status","data":{"status":{"exec_info":{"queue_remaining":0}},"sid":"..."}}`；
  未带 token 的 WS 正确返回 401。
- 结论：网关 WS 双向透传正常（aiohttp 实测），但本机对 `*.trycloudflare.com` 的
  WebSocket 直连被网络环境掐断。**App 端建议：WS 失败时降级轮询 `/anima/v1/queue`
  与 `/anima/v1/history`，或提示切换网络**；HTTP 全链路不受影响。

## 4. 契约 §8 第 1 段验收（PC 经公网 tunnel，19 项全过）

命令（本机需代理才能直连隧道 WS；HTTP 直连也可）：

```bash
python3 colab/e2e_check.py --pair-file /tmp/anima_pair.txt \
        --out-dir outputs --seed 777001 --proxy http://127.0.0.1:7890
```

结果：**19 checks, 0 failed -> ALL PASS**（17:54:34-17:56:02，总耗时 88.6s）。
完整证据 JSON：`outputs/e2e_20261114-175434_result.json`（outputs/ 已 gitignore）。

| # | 检查 | 结果 | 关键输出 |
|---|---|---|---|
| 1 | `GET /anima/v1/health`（免 token） | PASS | HTTP 200，`comfy=true`，`device=Tesla T4`，`vram_total_gb=14.6` |
| 2 | `GET /anima/v1/health`（Bearer） | PASS | 同上 + `queue={"running":[],"pending":[]}` |
| 3 | `GET /anima/v1/info` | PASS | `unets=["anima-base-v1.0.safetensors"]`；defaults unet/vae/text_encoder 正确 |
| 4 | info.samplers | PASS | 44 个采样器，含 `er_sde`（字符串数组） |
| 5 | `GET /anima/v1/queue` | PASS | `{"ok":true,"running":[],"pending":[]}` |
| 6 | `POST /prompt`（base 30 步 CFG4.5 1024²） | PASS | HTTP 200，`prompt_id=5959574f-0acd-488d-b7b4-dfbb269c6303` |
| 7 | `WS /ws` 收到 progress | PASS | 28 帧 `progress`（value 3-30 / max 30） |
| 8 | `WS /ws` 收到 executed | PASS | `{"filename":"anima-e2e_00002_.png","subfolder":"","type":"output"}` |
| 9 | `WS /ws` execution_success | PASS | 事件类型：status/executing/progress_state/progress/executed/execution_success |
| 10 | `GET /history` 找到本次任务 | PASS | 提交后 0.6s 即可见 |
| 11 | history.params 正确 | PASS | model/positive/negative/seed=777001/steps=30/cfg=4.5/er_sde/simple/1024×1024/batch=1 |
| 12 | `GET /history/{prompt_id}` | PASS | HTTP 200 |
| 13 | `GET /view` 下载 | PASS | HTTP 200，`image/png`，**876,374 bytes** |
| 14 | PNG magic | PASS | `89504e470d0a1a0a` |
| 15 | 图片保存 | PASS | `outputs/e2e_20261114-175434_anima-e2e_00002_.png` |
| 16 | 未鉴权访问 `/info` | PASS | `401 {"ok":false,"error":"unauthorized"}` |
| 17 | 不存在 history id | PASS | `404 {"ok":false,"error":"not_found"}` |
| 18 | 未知路由 | PASS | 404 |
| 19 | 整体 | PASS | 端到端 88.6s（含 71s 出图） |

下载图校验：

```
sha256 1a06eb90f241d42014604318acbcd8f4ebea9fba5fc420a3e1e24afe571d9c2d
file   PNG image data, 1024 x 1024, 8-bit/color RGB, non-interlaced
```

出图参数与契约 §5 节点图一致；`/user` 负向 + 质量前缀正常生效。
第一次完整出图（17:39 的 `anima-e2e_00001_.png`，588,648 bytes）在网关 bug 修复前、
WS 未通的情况下也成功出图并可从 history/view 取回。

## 5. App 一键模式参考实现（不碰 notebook / 不用 CLI exec）

协议与真实样例见 `colab/PHONE_BOOTSTRAP.md`。实测：

```
[bootstrap] 17:52:58 == step 1: load OAuth token + refresh access token ==
[bootstrap] 17:52:59 assignments: 1 ... 复用 anima T4
[bootstrap] 17:53:04 kernel created: 8ccd22cc-5749-445e-a54a-7da37585e257 (5267 ms)
[bootstrap] 17:53:10 kernel channels connected (proxy)
[... 流式 [deploy] 输出 ...]
PAIR: https://privileges-package-wilson-west.trycloudflare.com|wJpI...mpoP
[bootstrap] 17:53:11 execute_reply status=ok
```

- 端到端（复用已部署 VM）**13s**；第二次重跑 **~21s**，PAIR 完全一致，
  且自动 DELETE 上一轮 kernel（幂等 + 清理残留）。
- 验证方式：只读 `~/.config/colab-cli/token.json` + 裸 OAuth/Colab/kernel WS 协议，
  **未调用 colab CLI 的 exec，也未使用 notebook**。

## 6. 复现命令

```bash
# 1) 会话（免费版 T4；已有 anima 会话时跳过）
colab sessions || colab new --gpu T4 -s anima

# 2) 冷启动全量部署（幂等；colab exec 超时属正常，VM 继续）
python3 scripts/extract_notebook.py colab/anima_colab.ipynb /tmp/anima_deploy.py
colab exec -s anima --timeout 1800 -f /tmp/anima_deploy.py

# 3) App 一键模式（参考实现；只用 token.json）
python3 colab/phone_bootstrap.py --proxy http://127.0.0.1:7890 --pair-out /tmp/pair.txt

# 4) 契约 §8 第 1 段验收
python3 colab/e2e_check.py --pair-file /tmp/pair.txt --out-dir outputs \
        --seed 777001 --proxy http://127.0.0.1:7890

# 5) 查看 VM 内服务状态（VM 内执行）
python3 colab/phone_bootstrap.py --proxy http://127.0.0.1:7890 \
        --exec-code 'import urllib.request;print(urllib.request.urlopen("http://127.0.0.1:8081/anima/v1/health").read().decode())'
```

## 7. App 实机联调旁证（服务端视角，17:57 采样）

`GET /anima/v1/history?limit=5` 显示 App（`filename_prefix=animacolab`）已成功出图：

| prompt_id | 完成时间 | 参数 | 输出 |
|---|---|---|---|
| `f085ac82` | 17:55:52 | base 30 步，seed 3648752911 | `animacolab_00004_.png` |
| `8c7483e7` | 17:48:09 | base 30 步，seed 3648326774 | `animacolab_00003_.png` |
| `8a3506d1` | 17:41:04 | base 30 步，seed 3647901839 | `animacolab_00002_.png` |

最新一张 `animacolab_00004_.png` 经网关 `/view` 下载校验：HTTP 200，**907,303 bytes**，
PNG magic 正确，尺寸 1024×1024。说明「App → tunnel → 网关 → ComfyUI 出图 → /view 下载」
服务端链路在实机上打通。

## 8. 当前状态（交接）

- 会话**保持运行**，PAIR 有效；ComfyUI 模型已常驻 T4 显存（复用后单图约 71s）。
- 保活：本机已启动临时守护进程（每 8 分钟向 kernel 发一条 `print('[app] keepalive')`）：
  `pgrep -f anima_keepalive.py` 可查、`pkill -f anima_keepalive.py` 可停；会话被
  `colab stop` 释放后它会自动退出。日志 `/tmp/anima_keepalive.log`。
- App 一键模式自带 4 分钟 kernel 心跳（`KeepaliveService`），与上面的守护进程作用相同。
- 免费会话预计 1-3 小时上限，掉线后重跑一键流程（`/content` 会重新下载模型，约 3.5 分钟）
  会生成新 PAIR。
- 未做：`colab stop` 由 Lead 收尾释放；服务端队友保持待命，随时可重新部署/热更新网关。
