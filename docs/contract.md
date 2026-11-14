# Anima Colab 契约 v1（App ⇄ 网关 ⇄ ComfyUI）

本文件是 App 端与 Colab 服务端并行开发的**唯一对齐依据**。改动接口必须先改本文件并通知对方。

## 1. 拓扑

```
Android App ──HTTPS/WSS──> Cloudflare Quick Tunnel ──> Colab VM
                                              ├─ gateway.py  (127.0.0.1:8081, token 校验)
                                              └─ ComfyUI     (127.0.0.1:8188, 仅本机)
```

- App **不加载** ComfyUI 前端，只调网关 API。
- Colab 里 ComfyUI 只监听 127.0.0.1，公网只暴露网关；网关加 token 鉴权。
- 只支持 T4 GPU（`--force-fp16`），TPU 留到 v2。
- v1 只部署 `anima-base-v1.0`（+ Qwen3 TE + Qwen VAE），App 通过 `/anima/v1/info` 自动识别实际已装模型。

## 2. 配对格式

Notebook 最后输出块打印一行（手机可长按复制）：

```
PAIR: https://<random>.trycloudflare.com|<token>
```

也可以只打印 `https://...` 和 `<token>` 两行。App「设置 → 粘贴配对信息」解析整行。
token 每次会话随机生成（`secrets.token_urlsafe(24)`），不写死、不进日志文件。

## 3. 鉴权

- 除 `GET /anima/v1/health` 外，所有请求必须带 `Authorization: Bearer <token>`。
- `POST /prompt`、`GET /view`、`WS /ws` 同样要带（OkHttp 支持 WS header）。
- 未带或错误 → `401 {"ok":false,"error":"unauthorized"}`。

## 4. 网关 HTTP API（App 只用这些）

### GET /anima/v1/health
```json
{"ok":true,"comfy":true,"gateway":"0.1.0","queue":{"running":0,"pending":0},
 "device":"Tesla T4","vram_total_gb":14.6}
```
- 不带 token 也返回 200（健康检查），但**不泄露模型/参数信息**；带上 token 时 `comfy` 才反映真实就绪状态。App 每次打开/测试连接先调它。
- ComfyUI 未就绪时 `{"ok":true,"comfy":false,...}`。

### GET /anima/v1/info
```json
{"ok":true,
 "unets":[{"name":"anima-base-v1.0.safetensors","size_gb":3.9}],
 "loras":[],
 "vaes":[{"name":"qwen_image_vae.safetensors"}],
 "text_encoders":[{"name":"qwen_3_06b_base.safetensors"}],
 "samplers":[{"name":"er_sde"},{"name":"euler"}],
 "schedulers":[{"name":"simple"}],
 "defaults":{"unet":"anima-base-v1.0.safetensors","vae":"qwen_image_vae.safetensors",
             "text_encoder":"qwen_3_06b_base.safetensors"}}
```
App 用 unets 匹配模型注册表；用 defaults 填默认值。

### POST /prompt
原样转发 ComfyUI。请求：
```json
{"prompt": { ...节点图... }, "client_id": "<uuid>"}
```
响应原样转发（`{"prompt_id":"...","number":..,"node_errors":{}}`），校验失败时 ComfyUI 的 400 也原样转发。

### GET /anima/v1/queue
```json
{"ok":true,"running":["<prompt_id>"],"pending":["<prompt_id>"]}
```

### POST /anima/v1/queue/clear
清空排队 → `{"ok":true}`

### POST /anima/v1/interrupt
中断当前执行 → `{"ok":true}`

### GET /anima/v1/history?limit=50
网关从 ComfyUI `/history` 整理，按完成时间倒序，只保留有图片输出的：
```json
{"ok":true,"items":[
 {"prompt_id":"...","status":"success","images":[{"filename":"anima_00001_.png","subfolder":"","type":"output"}],
  "created":1730000000,
  "params":{"model":"anima-base-v1.0.safetensors","positive":"...","negative":"...",
            "seed":123,"steps":30,"cfg":4.5,"sampler":"er_sde","scheduler":"simple",
            "width":1024,"height":1024}}
]}
```
字段取不到的给空值/缺省，App 做容错。

### GET /anima/v1/history/{prompt_id}
单项，结构同上（一个 item）；不存在 → `404 {"ok":false,"error":"not_found"}`。

### GET /view?filename=&subfolder=&type=output
原样转发 ComfyUI `/view` 的图片字节（`Content-Type: image/png`）。

### WS /ws?clientId=<uuid>
原样转发 ComfyUI WebSocket 的文本帧（二进制帧也透传即可）。App 处理的事件：
- `status` → `data.status.exec_info.queue_remaining`
- `execution_start` / `execution_cached` / `executing`（`data.node`，`node==null` 表示本轮结束）
- `progress` → `data.value` / `data.max`
- `executed` → `data.output.images[]`（filename/subfolder/type）
- `execution_success` / `execution_error`
- 兼容未来新增 type：未知类型忽略。

### 错误格式
`{"ok":false,"error":"..."}`，HTTP 401/404/502。网关对 ComfyUI 不可达返回 502 `{"ok":false,"error":"comfy_unreachable"}`。

## 5. App 提交的 ComfyUI 节点图（固定模板）

节点 id 固定，参数由 App 表单填充：

```json
{
 "1": {"class_type":"CLIPLoader","inputs":{"clip_name":"qwen_3_06b_base.safetensors","type":"stable_diffusion","device":"default"}},
 "2": {"class_type":"VAELoader","inputs":{"vae_name":"qwen_image_vae.safetensors"}},
 "3": {"class_type":"CLIPTextEncode","inputs":{"text":"<positive>","clip":["1",0]}},
 "4": {"class_type":"CLIPTextEncode","inputs":{"text":"<negative>","clip":["1",0]}},
 "5": {"class_type":"UNETLoader","inputs":{"unet_name":"anima-base-v1.0.safetensors","weight_dtype":"default"}},
 "6": {"class_type":"EmptyLatentImage","inputs":{"width":1024,"height":1024,"batch_size":1}},
 "7": {"class_type":"KSampler","inputs":{"seed":123456,"steps":30,"cfg":4.5,
        "sampler_name":"er_sde","scheduler":"simple","denoise":1.0,
        "model":["5",0],"positive":["3",0],"negative":["4",0],"latent_image":["6",0]}},
 "8": {"class_type":"VAEDecode","inputs":{"samples":["7",0],"vae":["2",0]}},
 "9": {"class_type":"SaveImage","inputs":{"filename_prefix":"animacolab","images":["8",0]}}
}
```
挂 LoRA 时追加：
```json
"10": {"class_type":"LoraLoaderModelOnly","inputs":{"lora_name":"<lora>","strength_model":1.0,"model":["5",0]}}
```
并把节点 7 的 `model` 改成 `["10",0]`。batch_size>1 时其余节点不变（ComfyUI 一次出多张）。

校验过：ComfyUI 0.43.0 + Anima base 用这个图能出图（与 comfy-colab skill 的 run_anima.py 一致）。

## 6. 数据资产 schema（App assets/）

### models.json（模型注册表，App 匹配 unet 文件名）
```json
{"version":1,"models":[
 {"id":"base","match":["anima-base-v1.0"],"display":"Anima Base v1.0",
  "desc":"未精修底座，30-50 步 / CFG 4-5，必须加质量标签",
  "steps":30,"cfg":4.5,"sampler":"er_sde","scheduler":"simple",
  "qualityPositive":"masterpiece, best quality","negativePreset":"base",
  "sizes":[[1024,1024],[832,1216],[1216,832]],"recommended":true},
 {"id":"generic","match":[],"display":"通用 / 未知模型","desc":"未识别模型，用保守参数",
  "steps":30,"cfg":4.5,"sampler":"er_sde","scheduler":"simple",
  "qualityPositive":"","negativePreset":"generic","sizes":[[1024,1024]]}
]}
```
`match` 是 unet 文件名子串，按顺序取第一个命中；都没命中用 `generic`。

### tags.json（tag 速查）
```json
{"version":1,"categories":[
 {"id":"hair","zh":"头发","tags":[
   {"en":"long hair","zh":"长发"},
   {"en":"@hiten \\(hitenkei\\)","zh":"画师 hiten（注意转义）","artist":true}
 ]}
]}
```
- 至少 180 个 tag，覆盖：头发/眼睛表情/服装/动作姿势/构图镜头/场景/光照氛围/质量元数据/安全分级/画师/多人。
- 画师 tag 必须写成 Anima 规则：`@danbooru 名`，下划线换空格，括号转义 `\(...\)`。
- 插入提示词时 App 直接追加 `en` 字段。

### templates.json（提示词模板 + 片段 + 负向预设）
```json
{"version":1,
 "templates":[
   {"id":"tachie","name":"白底全身立绘","model":"base",
    "prompt":"safe, 1girl, solo, (@hiten \\(hitenkei\\):1.5)","negative":"",
    "steps":30,"cfg":4.5,"size":[1080,2400],"note":"base 30步 CFG4.5，长条画布触发立绘模式"}],
 "snippets":[{"id":"quality-base","name":"base 质量前缀","text":"masterpiece, best quality","kind":"positive"}],
 "negativePresets":[
   {"id":"base","name":"base 标准负向","text":"worst quality, low quality, blurry, jpeg artifacts, text, watermark, signature, score_1, score_2, score_3"},
   {"id":"aesthetic","name":"aesthetic 负向","text":"worst quality, low quality, blurry, jpeg artifacts, text, watermark, signature"},
   {"id":"generic","name":"通用负向","text":"worst quality, low quality, blurry, text, watermark, signature"}]
}
```
templates ≥ 8 套（标准单人、白底立绘、双人横构图、画师风格复刻、轻小说插画、纯风景、角色复刻、自由创作/自定义）。
App 里用户保存的自定义模板存本地（不进 assets）。

## 7. 笔记本行为契约（colab/anima_colab.ipynb）

1. 手机浏览器 / Colab App 打开 →「全部运行」即可，无需键入命令。
2. 幂等：重复运行不重下已存在模型、不重复 git clone。
3. 顺序：装 ComfyUI → 下 base 模型/TE/VAE（`curl -C -` + `.part`）→ 起 ComfyUI（`nohup ... --listen 127.0.0.1 --port 8188 --force-fp16`）→ 起网关（token）→ 起 cloudflared quick tunnel → 解析 URL → 打印 `PAIR:` 行。
4. 下载大文件/安装期间要有进度输出，`colab exec` 长任务可超时但 VM 继续；重跑可续。
5. 最后保活 cell：无限循环 `time.sleep` 打印心跳，保持 kernel 活跃（免费版 90 分钟闲置回收；免费额度仍有单次会话上限）。
6. 失败时输出明确原因（torch 不可用 / 下载失败 / 端口占用 / cloudflared 缺失）与重跑方式。
7. 只在 VM 内跑正常内容（Colab 内容政策）。

## 8. 联调约定

- 服务端测试环境：`colab new --gpu T4 -s anima` + 把 notebook 代码单元抽成脚本用 `colab exec -f` 跑（本地 `scripts/extract_notebook.py`）。
- 联调通过标准（两段都要过）：
  1. PC 上用 curl/脚本：`/anima/v1/health` → `/anima/v1/info` → `/prompt` 出 base 图 → `/view` 下载 PNG 有效 → `/anima/v1/history` 能看到参数 → WS 收到 progress 事件。
  2. App 实机：粘贴 PAIR → 测试连接 → 生成一张 1024² base 图（约 65-90 秒）→ 进度条走完 → 图库可见且能保存/分享。
- 会话使用完由 Lead 执行 `colab stop -s anima` 释放。
