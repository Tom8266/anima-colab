# App 一键模式协议参考实现（phone_bootstrap）

本文档记录**不用 Colab 网页、不用 colab CLI 的 exec、不用 notebook**，纯 Python 复刻 App
「登录 Google → 自动开 T4 → 自动部署 → 拿 PAIR」全链路的真实请求/响应/耗时/坑，
供 Android Kotlin 端 1:1 移植。

- 参考实现：`colab/phone_bootstrap.py`（PC 可运行）
- 部署载荷：`colab/deploy_script.py`（单文件自包含，作为 kernel `execute_request` 的 code 发送）
- 实测会话：`[anima] gpu-t4-s-usw1b2-ycpdkwttgegi | T4 | Standard`
- 实测 PAIR：`https://privileges-package-wilson-west.trycloudflare.com|<token>`
  （token 不落文档；本机测试凭据见 `/content/anima_token.txt`，只存在于 VM 内）
- 2026-11-21 扩展：`--accelerator tpu` 可复刻 App 的 TPU v5e1 一键模式
  （`variant=TPU&accelerator=V5E1`；脚本注入 `ANIMA_ACCELERATOR=tpu`，TPU 部署细节见
  [`TESTING.md` §9](TESTING.md)）。以下以 T4 为例。

## 0. 命令

```bash
# 本机（Google 域需要代理；App 在手机直连网络下不需要 --proxy）
python3 colab/phone_bootstrap.py --proxy http://127.0.0.1:7890 \
        --pair-out /tmp/pair.txt

# TPU v5e1（复用/申请 TPU 会话）
python3 colab/phone_bootstrap.py --accelerator tpu --proxy http://127.0.0.1:7890 \
        --pair-out /tmp/pair.txt

# 调试：通过同一条 kernel 通道执行任意代码（等价 App 的“远程执行”能力）
python3 colab/phone_bootstrap.py --proxy http://127.0.0.1:7890 \
        --exec-code 'print("hello from kernel")'

# 只看流程、不真正部署（可选扩展点，不是必需）
# 复用已知 kernel：--reuse-kernel <kernel-id>
```

退出码 0 = 部署成功且解析到 PAIR。

## 1. 全链路（6 步，实测）

```
OAuth refresh ──> GET /tun/m/assignments ──> (没有 T4 则 GET+POST /tun/m/assign)
        ──> POST runtimeProxyInfo.url/api/kernels ──> WS /api/kernels/<id>/channels
        ──> execute_request(deploy_script.py) ──> 流式 stdout ──> 正则 PAIR
```

### 1.1 OAuth：refresh_token 换 access_token

复用 google-colab-cli 的 token 文件与 installed-app OAuth client：

- token 文件：`~/.config/colab-cli/token.json`（字段 `refresh_token` / `token` / `expiry`）
- OAuth client：`colab_cli/oauth_config.json` 里的
  `client_id=764086051850-6qr4p6gpi6hn506pt8ejuq83di341hur.apps.googleusercontent.com`
  （public installed-app client，非机密；脚本内置 fallback）

```
POST https://oauth2.googleapis.com/token
Content-Type: application/x-www-form-urlencoded

client_id=<client_id>&client_secret=<client_secret>
&grant_type=refresh_token&refresh_token=<refresh_token>
```

响应（实测，已脱敏）：

```json
{"access_token":"ya29.<redacted>","expires_in":3599,
 "scope":"openid https://www.googleapis.com/auth/userinfo.profile ...",
 "token_type":"Bearer"}
```

要点：

- access token 有效期 ~1h；脚本会把新 access token/expiry **写回 token.json**
  （App 用自己的安全存储即可，不要与 CLI 抢文件）。
- 本机直连 `oauth2.googleapis.com` 会超时，必须 `--proxy 127.0.0.1:7890`；
  手机 App 在移动网络下应能直连，但要做「失败→提示网络」处理。

### 1.2 列 assignments（复用已有 T4 会话）

```
GET https://colab.research.google.com/tun/m/assignments?authuser=0
Authorization: Bearer <access_token>
X-Colab-Client-Agent: colab-cli
Accept: application/json
```

响应有 XSSI 前缀 `)]}'\n`，要先剥掉再 JSON parse。实测（脱敏）：

```json
{"assignments":[{"accelerator":"T4","endpoint":"gpu-t4-s-usw1b2-ycpdkwttgegi",
  "variant":1,"machineShape":0,
  "runtimeProxyInfo":{"token":"<222 chars>","tokenExpiresInSeconds":3600,
    "url":"https://8080-gpu-t4-s-usw1b2-ycpdkwttgegi-b.us-west1.prod.r.colab.dev"}}]}
```

- `accelerator` 字符串匹配 `T4` 即复用；**不要 unassign**（App 退出也不该释放，用户可能还要用）。
- `runtimeProxyInfo.token` 就是后续 kernel REST/WS 的旁路 token，**1 小时过期**（见坑 1）。

### 1.3 没有 T4 时分配（首次一键）

```
GET  /tun/m/assign?nbh=<uuid4.web-safe-base64>&variant=GPU&accelerator=T4&authuser=0
```

- 未分配时返回 `{"acc","nbh","token","variant"}`；随后带 XSRF 头再 POST 同一 URL：
  `X-Goog-Colab-Token: <token>`，返回 1.2 的 assignment 结构。
- 已分配时 GET 直接返回 assignment 结构（脚本两种都兼容，并对 502/504/ReadTimeout
  做指数退避重试；412 表示已有太多 assignment）。
- `nbh` 生成：`str(uuid4()).replace("-","_") + "." * 8`（44 字符）。
- 冷分配可能要几十秒到几分钟（免费池排队），App 要显示「正在分配 T4…」并允许取消。

### 1.4 创建 kernel

```
POST <runtimeProxyInfo.url>/api/kernels
Authorization: Bearer <proxyToken>
X-Colab-Runtime-Proxy-Token: <proxyToken>
X-Colab-Client-Agent: colab-cli
Content-Type: application/json
Accept: application/json

{"name":"python3","path":null}
```

实测响应（耗时 5.3-5.9s）：

```json
{"id":"8ccd22cc-5749-445e-a54a-7da37585e257","name":"python3",
 "last_activity":"2026-11-14T09:53:04.219149Z",
 "execution_state":"starting","connections":0}
```

- 返回即用，**不要**等单 kernel 查询接口（`GET /api/kernels/<id>` 在 runtime proxy 上
  可能 >30s 不返回，见坑 2）；channels WS 会把 execute_request 排队到 kernel ready。
- `GET /api/kernels`（列表）正常可用（约 5s），可做状态展示。

### 1.5 连接 kernel channels（Jupyter 默认协议）

```
wss://<runtime host>/api/kernels/<kernel_id>/channels
      ?session_id=<uuid4().hex32>&token=<proxyToken>
X-Colab-Client-Agent: colab-cli
X-Colab-Runtime-Proxy-Token: <proxyToken>
```

消息是**纯 JSON 文本**（不带 subprotocol），字段：

```json
{"channel":"shell","header":{...},"parent_header":{},"metadata":{},"content":{...},"buffers":[]}
```

发送 execute_request（实测，字段顺序无关）：

```json
{"header":{"msg_id":"<uuid4 32hex>","username":"","session":"<session_id>",
           "date":"2026-11-14T09:53:10.123456Z","msg_type":"execute_request","version":"5.3"},
 "parent_header":{},"metadata":{},"buffers":[],
 "channel":"shell",
 "content":{"code":"<deploy_script.py 全文>","silent":false,"store_history":true,
            "user_expressions":{},"allow_stdin":false,"stop_on_error":true}}
```

接收侧（实测）：

```json
{"channel":"iopub","header":{"msg_type":"stream",...},
 "content":{"name":"stdout","text":"[deploy] 09:53:10 GPU: Tesla T4 / 显存 15.0 GB\n"}}

{"channel":"iopub","header":{"msg_type":"status",...},
 "content":{"execution_state":"busy"}}          // 之后会有 idle

{"channel":"shell","header":{"msg_type":"execute_reply",...},
 "content":{"status":"ok","execution_count":1}} // 失败时 status=error + ename/evalue/traceback
```

- 只处理 `iopub/stream`（拼 stdout、解析 PAIR）、`iopub/error`、`shell/execute_reply` 即可；
  `status`/`execute_input` 忽略但可作心跳。
- 先发 `import os; os.makedirs('/content',exist_ok=True); os.chdir('/content')`
  或与部署代码拼在同一个 execute_request 里（推荐后者，少一次往返）。
- 实测 kernel 就绪后 `hello` 往返 4.7s；部署脚本（复用已部署 VM）整段 13s。

## 2. 实测耗时（现有 anima 会话、/content 已部署、走本机代理）

| 步骤 | 耗时 |
|---|---|
| OAuth 刷新（缓存有效时跳过） | ~0.2s（真刷新 ~0.6s + 代理） |
| 列 assignments | 0.2-0.6s |
| 创建 kernel | 5.3-5.9s |
| 等待 kernel 就绪（不阻塞，WS 连接即排队） | 0s |
| WS channels 连接（经代理） | 6.0s |
| **execute deploy_script（幂等复用）** | **~1s 代码执行 + 输出流** |
| 端到端（复用已部署 VM，PAIR 不变） | **~13s** |
| 冷启动部署（clone + 5.3GB 下载 + 启动隧道） | 3.5 分钟（见 `TESTING.md`） |

## 3. 坑（全部实际踩过）

1. **runtimeProxy token 1 小时过期**（`tokenExpiresInSeconds=3600`）。
   一键流程每次启动都重新 list assignments 拿新 token；如果部署中途 token 过期
   （正常不会，冷部署 ~4 分钟），WS 会断 → 重新 list assignments 拿新 token 重连，
   deploy_script 幂等可续。App 里不要长期缓存这个 token。
2. **单 kernel 查询会卡死**：`GET /api/kernels/<id>` 在 runtime proxy 上实测 30s ReadTimeout
   （列表接口正常）。不要用它判断就绪；直接用 channels WS（Jupyter 会排队）。
3. **kernel 残留**：每跑一次一键就多一个 kernel（每次占内存）。App 要持久化本机
   `kernel_id`，下次先用 `GET /api/kernels` 校验是否还在，在就复用；
   不在就新建并 `DELETE` 旧 id。本参考实现用
   `~/.cache/anima_bootstrap_kernels.json` 记录并清理上一轮 kernel（实测清理成功）。
   另外「starting」状态卡住的 kernel 可以直接 DELETE（实测两个卡住的清理成功）。
4. **kernel 里是 ipykernel 的 asyncio 事件循环**：调试代码里 `asyncio.run()` 会报
   `RuntimeError: asyncio.run() cannot be called from a running event loop`，
   用 IPython 顶层 `await` 即可（只影响 Python 调试，App 内是 Jupyter 消息协议，无此问题）。
5. **Google 域直连可能极慢/被墙**：本机 `oauth2.googleapis.com` 直连超时；
   `colab.research.google.com` 直连实测 105.9s 才返回 401（curl 却很快）。
   参考实现给 `--proxy` 时优先走代理。手机 App 应：
   直连 5-10s 超时 → 提示网络/重试，不要静默等 100s。
6. **trycloudflare 的 WebSocket：App 实机正常，本机 PC 直连会被掐**。
   App（手机 OkHttp WS 经 Cloudflare）实测能正常收 progress/executed/execution_success；
   本开发机 PC 直连 `wss://*.trycloudflare.com` 25s 握手中断、经代理 6.2s 成功，
   属本机网络限制（VM 内直连该隧道 WS 也正常）。PC 调试/验收脚本加 `--proxy` 即可，
   App 保留 history 轮询兜底但不依赖它。
7. **token.json 写入**：参考实现刷新 access token 后原子写回 `~/.config/colab-cli/token.json`
   （600 权限），保持 colab CLI 仍可用。App 不要写 CLI 文件，用自己的存储。
8. **不要 unassign 现有会话**：免费账号单会话；App「退出登录/关闭」不要释放 assignment，
   否则用户每次都要重新排队分配 T4（冷启动 1-3 分钟）。

## 4. Kotlin 端移植清单（对照实现）

| 能力 | 参考实现位置 | Kotlin 建议 |
|---|---|---|
| OAuth refresh | `refresh_access_token()` | OkHttp form POST + Google OAuth（installed app client） |
| assignments 列表/分配 | `list_assignments()` / `assign_t4()` | OkHttp GET/POST，剥 XSSI 前缀，JSON 解析 |
| 创建 kernel | `create_kernel()` | OkHttp POST，三个必要头（见 1.4） |
| WS channels | `_jupyter_message()` / `execute_remote()` | OkHttp WebSocket（无 subprotocol），文本 JSON 帧 |
| 部署载荷 | `colab/deploy_script.py`（打包进 App assets） | 原样作为 execute_request.content.code 发送（约 41KB，可 gzip 后 base64 减小，但没必要） |
| 流式解析 | `PAIR_RE` / 主循环 | 按 `channel=iopub/stream` 增量拼 stdout，正则 `PAIR:\s*(https://\S+)\|([A-Za-z0-9_\-]+)` |
| 完成判定 | `execute_reply.content.status` | `ok`+找到 PAIR=成功；`error`=带 traceback 展示 |
| 保活 | 未实现（用同一 kernel 发心跳） | 每 5-10 分钟向 kernel 发一条轻量 execute_request（如 `print("alive")`），同时 App 侧定时 ping `/anima/v1/health` |
| 失败恢复 | 幂等 deploy_script + 重新 list assignments | 401/过期 → 重新刷新；kernel 丢失 → 重建；PAIR 不变（/content 不丢） |

## 5. 验证证据（2026-11-14 17:52-17:54 本机实测）

```
[bootstrap] 17:52:58 == step 1: load OAuth token + refresh access token ==
[bootstrap] 17:52:58 cached access token still valid, skipping refresh
[bootstrap] 17:52:58 == step 2: list assignments ==
[bootstrap] 17:52:59 assignments: 1
[bootstrap] 17:52:59   endpoint=gpu-t4-s-usw1b2-ycpdkwttgegi accelerator=T4 variant=1
[bootstrap] 17:52:59 reusing existing T4 assignment: gpu-t4-s-usw1b2-ycpdkwttgegi
[bootstrap] 17:52:59 runtime proxy: https://...prod.r.colab.dev (token valid 3600s)
[bootstrap] 17:52:59 == step 3: create kernel ==
[bootstrap] 17:53:04 kernel created: 8ccd22cc-5749-445e-a54a-7da37585e257 (5267 ms)
[bootstrap] 17:53:04 kernel listed, execution_state='starting' (channels buffered until ready)
[bootstrap] 17:53:04 == step 4: execute colab/deploy_script.py (38787 bytes) ==
[bootstrap] 17:53:10 kernel channels connected (proxy http://127.0.0.1:7890)
[bootstrap] 17:53:10 execute_request sent (38858 bytes of code, kernel 8ccd22cc-...)
[... 流式 [deploy] 输出，全部幂等跳过 ...]
PAIR: https://privileges-package-wilson-west.trycloudflare.com|wJpI...mpoP
[bootstrap] 17:53:11 execute_reply status=ok
```

- 网关 WS 从 VM 内直连/经 Cloudflare 均验证连通（含 401 未鉴权拒绝）：
  `gateway-local: CONNECTED` / `gateway-via-cloudflare: CONNECTED`，首帧
  `{"type":"status","data":{"status":{"exec_info":{"queue_remaining":0}},"sid":...}}`。
- 结论：**App 一键模式协议可行**，核心风险在客户端网络（WS 直连）与 token/kernel 生命周期。
