# Anima × Colab T4 服务端使用说明（手机端独立方案）

本文面向**使用者**（手机党）：把 Colab T4 变成自己的 Anima 出图服务器，App 通过 Cloudflare
快速隧道连接，不依赖电脑、不需要搬 ComfyUI 前端。

- 服务端实现：`colab/anima_colab.ipynb`（一键 notebook）、`colab/gateway.py`（token 鉴权网关）
- 契约：`docs/contract.md`（App ⇄ 网关 ⇄ ComfyUI，接口以它为准）
- 联调证据：`colab/TESTING.md`

## 1. 原理（30 秒版）

```
手机 Anima App ──HTTPS/WSS──> xxx.trycloudflare.com（Cloudflare 快速隧道）──> Colab VM
                                                    ├─ gateway.py  127.0.0.1:8081（Bearer token 鉴权）
                                                    └─ ComfyUI     127.0.0.1:8188（只装 anima-base-v1.0）
```

- ComfyUI 只监听 VM 本机，公网只暴露带 token 的网关；网关只放行 App 需要的接口。
- 每次会话 token 随机生成（`secrets.token_urlsafe(24)`），不写死、不进日志。
- 只下载 3 个文件：`anima-base-v1.0.safetensors`（3.9G）+ `qwen_3_06b_base.safetensors`（1.2G）
  + `qwen_image_vae.safetensors`（243M），共约 5.3GB。

## 2. 第一次使用（电脑或手机浏览器都行）

1. **准备 notebook**
   - 方式 A：把 `colab/anima_colab.ipynb` 上传到自己的 Google Drive；
   - 方式 B：在 Colab 里 `文件 → 上传笔记本` 选择该文件。
   - 建议方式 A：以后每次直接打开，无需再次上传。
2. **确认 GPU**：`运行时 → 更改运行时类型 → T4 GPU`（免费版即可；只支持 T4）。
3. **全部运行**：菜单 `运行时 → 全部运行`（或 `Ctrl+F9`）。
   - 首次约 8-15 分钟：克隆 ComfyUI、装依赖、下载 5.3GB 模型、启动服务、建隧道。
   - 每个 cell 都有中文进度输出；下载中断没关系，重跑对应 cell 会续传。
4. **复制 PAIR**：全部运行到最后，会打印一个醒目输出块，其中一行：

   ```
   PAIR: https://xxxx-xxxx.trycloudflare.com|<token>
   ```

   长按/选中复制**整行**（包含 `PAIR:` 前缀也可以，App 会解析）。

> 手机浏览器操作 Colab 时，建议「请求桌面版网站」，菜单更完整；Colab App 也可以。

## 3. 手机 App 配对

1. 打开 Anima App → 右上角设置 → **「粘贴配对信息」**。
2. 粘贴整行 `PAIR: ...` → 保存。
3. 点 **「测试连接」**：成功即显示在线（会带出设备 Tesla T4 / 已装模型）。
4. 回主页输入提示词生成。base 1024×1024 / 30 步 / CFG 4.5 约 **65-90 秒**。

## 4. 会话与保活

- notebook 最后一个 cell 是**保活循环**（每 60 秒心跳），防止 Colab 判定闲置回收。
  **保持该页面打开**（切后台也行，但别关标签页）。
- 免费版限制：
  - 不执行代码约 90 分钟会被回收；保活 cell 就是为此存在。
  - 单次会话总时长通常 1-3 小时（受免费额度/资源池影响），到点会断开。
  - `colab usage` 显示的 compute units 为 0 属于免费账号正常现象，不影响开 T4。
- 想再次查看 PAIR：单独运行 notebook 里的「查看 PAIR / 状态」cell。

## 5. 断线后怎么办（最常见）

1. 重新打开同一个 notebook（Drive 里的副本）。
2. 确认运行时类型还是 T4，`运行时 → 全部运行`。
3. 等它重新部署（**模型会重新下载**，因为 `/content` 是临时的；约 5-10 分钟）。
4. 会打印**新的 PAIR**（新 URL + 新 token）→ App 里重新粘贴即可。

> 想更快：Colab 付费版会话更久、可用 `colab drivemount` 把模型放 Drive 持久化；v1 未内置。

## 6. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 某 cell 报 `torch 不可用` / `没有检测到 GPU` | 运行时类型不是 GPU。`运行时 → 更改运行时类型 → T4 GPU`，再「全部运行」。 |
| `colab exec` 报 `TimeoutError: Timeout waiting for reply` | 大文件下载超出 CLI 等待时间，**VM 仍在继续下载**。重跑即可，`.part` 续传。 |
| 模型下载反复失败 | Colab 访问 HF 抖动。重跑对应 cell；代码内已自动重试 3 次并保留 `.part`。 |
| cloudflared 下载/启动失败 | 代码会自动重试并自动从 QUIC 切 HTTP/2；还失败就重跑第 4 步 cell。日志：`/content/cloudflared.log`。 |
| 打印了 URL 但手机连不上（1033 / 502） | 隧道边缘还没就绪，notebook 会自己轮询到通为止；若手快复制了旧 URL，等 1 分钟重新运行「步骤 4/4」cell。 |
| App 测试连接 401 | token 过期（会话重建过）。重新复制当前 PAIR 整行粘贴。 |
| ComfyUI 启动超时 | 看 cell 输出的 `/content/comfy.log` 尾部：常见是模型没下完（重跑步骤 2/4-B）或显存被别的进程占用（重启运行时）。 |
| 出图卡住 / 超过 3 分钟没结果 | 手机端取消后 `POST /anima/v1/interrupt`，或重连 WS；服务端 `GET /anima/v1/queue` 可看排队。 |
| 生成很慢 | T4 上 base 30 步 CFG 4.5 约 65-90 秒属正常；App 端别把超时设太短。 |
| 想换模型（turbo/aesthetic） | v1 只部署 base；`/anima/v1/info` 会告诉 App 实际可用模型。扩展部署需改 notebook（见 `colab/README.md` 开发者部分）。 |

## 7. 安全与合规

- 公网只暴露网关 `8081`，ComfyUI `8188` 仅监听 `127.0.0.1`；所有业务接口都必须
  `Authorization: Bearer <token>`，只有 `/anima/v1/health` 免 token（不泄露模型信息）。
- token 每次新会话随机生成，只出现在 notebook 最终输出和 `/content/anima_token.txt`（600 权限），
  不会写进网关/ComfyUI 日志。
- 隧道 URL 与 token 等同于服务器密码，**不要发到公开群/仓库**。
- Colab 禁止色情等违规内容，违规会导致封号；请只生成合规内容。

## 8. 开发者：本地联调（不需要打开浏览器）

```bash
# 1) 建会话（免费版可 T4；注意同一时间只保留一个会话）
colab new --gpu T4 -s anima

# 2) 把 notebook 抽成线性 Python 脚本（跳过保活 cell；发现 IPython magic 会报错）
python3 scripts/extract_notebook.py colab/anima_colab.ipynb /tmp/anima_deploy.py

# 3) 在 VM 上部署（幂等，可重复执行；下载超时属正常）
colab exec -s anima --timeout 1800 -f /tmp/anima_deploy.py

# 4) 取 PAIR（脚本会自动打印；也可单独 exec 查看）
```

联调验收（契约 §8 第 1 段）用 `colab/TESTING.md` 里的命令/脚本；真实 App E2E 由 App 侧完成。
用完由负责人执行 `colab stop -s anima` 释放会话。

> 注意：本开发机 `oauth2.googleapis.com` 直连可能超时导致 colab CLI token 刷新失败，
> 可给 CLI 加 `HTTPS_PROXY=http://127.0.0.1:7890`（本机代理）后执行。
> 本机访问 huggingface.co 也可能超时——模型只在 Colab VM 内下载。
