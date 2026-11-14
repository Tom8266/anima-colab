#!/usr/bin/env python3
"""Build colab/anima_colab.ipynb from the gateway source + cell templates.

The notebook embeds colab/gateway.py verbatim (it must be self-contained on
Colab), so always rebuild after editing the gateway source.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # repo root
OUT = ROOT / "colab" / "anima_colab.ipynb"

gateway_source = (ROOT / "colab" / "gateway.py").read_text(encoding="utf-8")
assert "'''" not in gateway_source, "gateway.py must not contain triple single quotes"

MD_INTRO = r"""# Anima on Colab T4（手机独立方案服务端）

一键在 **免费 Colab T4** 上部署：ComfyUI（只装 Anima base）+ token 鉴权网关 + Cloudflare 快速隧道。
部署完成后会打印一行给你手机 App 用的配对信息：

```
PAIR: https://xxxx-xxxx.trycloudflare.com|<随机 token>
```

## 使用方法

1. 确认「运行时 → 更改运行时类型」是 **T4 GPU**（免费版即可）。
2. 点菜单 **运行时 → 全部运行**（或 `Ctrl+F9`），等待 8-15 分钟（首次下载模型约 5.3GB）。
3. 到最后会打印醒目输出块：长按复制 `PAIR:` 那一整行。
4. 打开 Anima App → 设置 → 粘贴配对信息 → 保存 → 测试连接 → 生成图片。

## 说明

- 只下载 `anima-base-v1.0`（3.9G）+ Qwen3 文本编码器（1.2G）+ Qwen VAE（243M），不装其它模型。
- 笔记本可重复运行（幂等）：已存在的 ComfyUI / 模型 / 隧道 / token 都会复用，不重复下载。
- 下载中途断掉也安全：`.part` 文件会续传，重跑对应 cell 即可。
- **建议**：`文件 → 保存副本` 保存到 Google Drive，下次直接打开 Run all，不用重新找链接。
- 免费 Colab 会话有效期 1-3 小时（受额度限制），断线后重跑会生成新的 PAIR，App 需重新粘贴。
- 服务端仅在 VM 内运行；token 每次新会话随机生成。请只生成合规内容（遵守 Colab 内容政策）。
"""

MD_STEPS = r"""## 部署步骤（全部运行即可，无需改代码）

| 步骤 | 内容 | 首次耗时 |
|---|---|---|
| 1 | 环境检查（T4 / 磁盘） | 秒级 |
| 2 | 克隆 ComfyUI + 安装依赖 + 下载 3 个模型文件（5.3GB） | 5-10 分钟 |
| 3 | 启动 ComfyUI（127.0.0.1:8188，`--force-fp16`） | 20-40 秒 |
| 4 | 启动 token 网关（127.0.0.1:8081） | 2 秒 |
| 5 | 启动 cloudflared 快速隧道并打印 `PAIR` | 10-40 秒 |

最后的「保活」cell 会一直循环打印心跳，这是正常的——它让免费会话不被判定闲置。
"""

CODE_HELPERS = r'''# =============================================================================
# 步骤 0：公共配置与工具函数（Run all 时最先执行，不要单独跳过）
# =============================================================================
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

CONTENT = Path("/content")
COMFY = CONTENT / "ComfyUI"
COMFY_URL = "http://127.0.0.1:8188"
GATEWAY_URL = "http://127.0.0.1:8081"
TOKEN_FILE = CONTENT / "anima_token.txt"
PAIR_FILE = CONTENT / "anima_pair.txt"
COMFY_LOG = CONTENT / "comfy.log"
GATEWAY_LOG = CONTENT / "gateway.log"
TUNNEL_LOG = CONTENT / "cloudflared.log"
CLOUDFLARED = CONTENT / "cloudflared"
GATEWAY_PY = CONTENT / "anima_gateway.py"

HF_FILES = "https://huggingface.co/circlestone-labs/Anima/resolve/main/split_files"
MODELS = [
    ("diffusion_models", "anima-base-v1.0.safetensors",
     HF_FILES + "/diffusion_models/anima-base-v1.0.safetensors", 3_500_000_000),
    ("text_encoders", "qwen_3_06b_base.safetensors",
     HF_FILES + "/text_encoders/qwen_3_06b_base.safetensors", 1_000_000_000),
    ("vae", "qwen_image_vae.safetensors",
     HF_FILES + "/vae/qwen_image_vae.safetensors", 200_000_000),
]
CLOUDFLARED_URL = (
    "https://github.com/cloudflare/cloudflared/releases/latest/download/"
    "cloudflared-linux-amd64"
)


def log(message):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), message), flush=True)


def tail(path, lines=30):
    try:
        data = Path(path).read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return "(no log: %s)" % path
    return "\n".join(data[-lines:])


def run(command, timeout=None, check=True):
    if isinstance(command, str):
        printable = command
    else:
        printable = " ".join(str(part) for part in command)
    log("+ " + printable)
    proc = subprocess.run(
        command,
        shell=isinstance(command, str),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=timeout,
    )
    output = proc.stdout or ""
    if check and proc.returncode != 0:
        print(output[-4000:])
        raise RuntimeError(
            "命令失败（exit %d）：%s\n已下载/已安装的部分会保留，重跑本 cell 即可（幂等续传）。"
            % (proc.returncode, printable)
        )
    return proc


def http_json(url, token=None, data=None, timeout=10.0):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    body = None if data is None else json.dumps(data).encode("utf-8")
    request = urllib.request.Request(
        url, data=body, headers=headers, method="POST" if body is not None else "GET"
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def http_status(url, token=None, timeout=5.0):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        request = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response.read()
            return response.status
    except urllib.error.HTTPError as exc:
        return exc.code
    except Exception:
        return None


def wait_until(description, probe, timeout, interval=2.0):
    log("等待 " + description + " ...")
    deadline = time.time() + timeout
    while time.time() < deadline:
        if probe():
            log("就绪: " + description)
            return True
        time.sleep(interval)
    return False


def pid_of(pattern):
    proc = subprocess.run(
        ["pgrep", "-f", pattern], stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, text=True,
    )
    return [line.strip() for line in (proc.stdout or "").splitlines() if line.strip()]


def kill_matching(pattern):
    pids = pid_of(pattern)
    if not pids:
        return
    log("结束旧进程: %s (%s)" % (pattern, " ".join(pids)))
    subprocess.run(["kill", "-9"] + pids, check=False)
    time.sleep(1)
'''

CODE_ENV = r'''# =============================================================================
# 步骤 1/4：环境检查（必须是 GPU 运行时；免费版选 T4）
# =============================================================================
print("=" * 70)
print("步骤 1/4：检查 Colab 运行环境（需要 GPU / T4）")
print("=" * 70)
try:
    import torch
except Exception as exc:
    raise RuntimeError(
        "torch 不可用（%r）。请确认「运行时 → 更改运行时类型」选择 GPU（T4），"
        "然后重新「全部运行」。" % (exc,)
    )

log("torch %s / CUDA %s" % (torch.__version__, getattr(torch.version, "cuda", None)))
if not torch.cuda.is_available():
    raise RuntimeError(
        "没有检测到 GPU。当前运行时不是 GPU 运行时，请在「运行时 → 更改运行时类型 → "
        "T4 GPU」后重新「全部运行」。"
    )
gpu_name = torch.cuda.get_device_name(0)
vram_gb = round(torch.cuda.get_device_properties(0).total_memory / 1024 ** 3, 1)
log("GPU: %s / 显存 %.1f GB" % (gpu_name, vram_gb))
if "T4" not in gpu_name:
    log("提示: 本方案只针对 T4 验证过（--force-fp16），其它 GPU 可能也能跑。")

usage = shutil.disk_usage(CONTENT)
log("磁盘 /content: 共 %.1f GB，可用 %.1f GB" % (usage.total / 1024 ** 3, usage.free / 1024 ** 3))
if usage.free < 12 * 1024 ** 3:
    raise RuntimeError(
        "/content 可用空间不足 12GB（当前 %.1f GB）。请「运行时 → 重新启动运行时」后重试。"
        % (usage.free / 1024 ** 3)
    )
log("环境检查通过")
'''

CODE_CLONE = r'''# =============================================================================
# 步骤 2/4-A：ComfyUI 本体 + Python 依赖（幂等：已存在则跳过）
# =============================================================================
print("=" * 70)
print("步骤 2/4-A：安装 ComfyUI（git clone + pip 依赖）")
print("=" * 70)

if (COMFY / "main.py").exists():
    log("ComfyUI 已存在，跳过 git clone: %s" % COMFY)
else:
    if COMFY.exists():
        shutil.rmtree(COMFY, ignore_errors=True)
    last_error = None
    for attempt in range(1, 4):
        try:
            run(
                ["git", "clone", "--depth", "1",
                 "https://github.com/comfyanonymous/ComfyUI.git", str(COMFY)],
                timeout=600,
            )
            last_error = None
            break
        except Exception as exc:
            last_error = exc
            log("git clone 第 %d/3 次失败: %r" % (attempt, exc))
            shutil.rmtree(COMFY, ignore_errors=True)
            time.sleep(5 * attempt)
    if last_error is not None:
        raise RuntimeError(
            "git clone 连续 3 次失败（Colab 访问 GitHub 异常）。稍后重跑本 cell 即可：%r"
            % (last_error,)
        )
    log("ComfyUI 克隆完成")

deps_marker = COMFY / ".anima_deps_ok"
if deps_marker.exists():
    log("Python 依赖已装过，跳过 pip install")
else:
    log("安装 ComfyUI 依赖（1-3 分钟，镜像自带 torch 不会重装）...")
    run(
        [sys.executable, "-m", "pip", "install", "-q", "--no-input",
         "-r", str(COMFY / "requirements.txt")],
        timeout=1200,
    )
    deps_marker.write_text("ok", encoding="utf-8")
    log("依赖安装完成")

try:
    import aiohttp
    log("aiohttp 可用: %s" % aiohttp.__version__)
except Exception:
    log("aiohttp 缺失，安装中 ...")
    run([sys.executable, "-m", "pip", "install", "-q", "--no-input", "aiohttp"], timeout=300)
    import aiohttp
    log("aiohttp 已安装: %s" % aiohttp.__version__)
'''

CODE_MODELS = r'''# =============================================================================
# 步骤 2/4-B：下载 Anima base 模型 + 文本编码器 + VAE（幂等 + 断点续传）
# =============================================================================
print("=" * 70)
print("步骤 2/4-B：下载模型（约 5.3GB，首次约 2-6 分钟；已存在则跳过）")
print("=" * 70)


def download(url, dest, min_bytes, retries=3):
    dest = Path(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size >= min_bytes:
        log("已存在，跳过: %s (%.2f GB)" % (dest.name, dest.stat().st_size / 1024 ** 3))
        return dest
    if dest.exists():
        log("已有文件不完整，删除后重下: %s" % dest.name)
        dest.unlink()
    part = dest.with_name(dest.name + ".part")
    error_log = CONTENT / (dest.name + ".curl.log")
    for attempt in range(1, retries + 1):
        start = part.stat().st_size if part.exists() else 0
        log("下载 %s（第 %d/%d 次尝试，从 %.2f GB 续传）"
            % (dest.name, attempt, retries, start / 1024 ** 3))
        with open(error_log, "wb") as err_handle:
            proc = subprocess.Popen(
                ["curl", "-L", "--fail", "--retry", "2", "--retry-delay", "3",
                 "-C", "-", "-o", str(part), url],
                stdout=subprocess.DEVNULL, stderr=err_handle,
            )
            last_report = 0.0
            while proc.poll() is None:
                time.sleep(5)
                now = time.time()
                size = part.stat().st_size if part.exists() else 0
                if now - last_report >= 15:
                    last_report = now
                    log("  ... %s: %.2f GB / 约 %.2f GB"
                        % (dest.name, size / 1024 ** 3, min_bytes / 1024 ** 3))
        if part.exists() and part.stat().st_size >= min_bytes:
            os.replace(part, dest)
            log("下载完成: %s (%.2f GB)" % (dest.name, dest.stat().st_size / 1024 ** 3))
            return dest
        log("本次下载未完成（第 %d 次），保留 .part 稍后续传重试" % attempt)
        if error_log.exists():
            print(tail(error_log, 8))
        time.sleep(5 * attempt)
    raise RuntimeError(
        "模型下载失败: %s。已下载部分保留在 %s，重跑本 cell 会继续续传。"
        % (dest.name, part)
    )


for sub_dir, filename, url, min_bytes in MODELS:
    download(url, COMFY / "models" / sub_dir / filename, min_bytes)
log("三个模型文件全部就绪（base 3.9G + TE 1.2G + VAE 243M）")
'''

CODE_COMFY = r'''# =============================================================================
# 步骤 2/4-C：启动 ComfyUI（只监听 127.0.0.1:8188，--force-fp16）
# =============================================================================
print("=" * 70)
print("步骤 2/4-C：启动 ComfyUI")
print("=" * 70)


def comfy_ready():
    return http_status(COMFY_URL + "/object_info", timeout=3.0) == 200


if comfy_ready():
    log("ComfyUI 已在运行，跳过启动")
else:
    kill_matching("main.py --listen")
    log("后台启动 ComfyUI（日志 %s）..." % COMFY_LOG)
    log_handle = open(COMFY_LOG, "ab")
    subprocess.Popen(
        [sys.executable, "main.py", "--listen", "127.0.0.1", "--port", "8188",
         "--force-fp16"],
        cwd=str(COMFY),
        stdout=log_handle,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    if not wait_until("ComfyUI 就绪（/object_info 返回 200）", comfy_ready, timeout=240):
        print(tail(COMFY_LOG, 40))
        raise RuntimeError(
            "ComfyUI 启动超时（见上方日志）。常见原因：模型缺失 / 显存不足 / 端口占用。"
            "修复后重跑本 cell。"
        )
    log("ComfyUI 已就绪: " + COMFY_URL)

try:
    stats = http_json(COMFY_URL + "/system_stats", timeout=15)
    system = stats.get("system", {})
    log("ComfyUI %s / python %s / torch %s"
        % (system.get("comfyui_version", "?"),
           system.get("python_version", "?"),
           system.get("pytorch_version", "?")))
except Exception as exc:
    log("读取 /system_stats 失败（不影响出图）: %r" % (exc,))
'''

CODE_GATEWAY_HEAD = r'''# =============================================================================
# 步骤 3/4：写入网关并启动（随机 token 鉴权，只暴露 App 需要的接口）
# =============================================================================
print("=" * 70)
print("步骤 3/4：启动 token 鉴权网关（127.0.0.1:8081）")
print("=" * 70)

'''
CODE_GATEWAY_TAIL = r'''
GATEWAY_PY.write_text(GATEWAY_SRC, encoding="utf-8")
log("网关源码已写入: %s (%d bytes)" % (GATEWAY_PY, len(GATEWAY_SRC)))

# token：同一会话内重复运行会复用，避免已配对的 App 失效；新会话重新生成。
token = ""
if TOKEN_FILE.exists():
    token = TOKEN_FILE.read_text(encoding="utf-8").strip()
if not token:
    token = secrets.token_urlsafe(24)
    TOKEN_FILE.write_text(token, encoding="utf-8")
    os.chmod(TOKEN_FILE, 0o600)
log("token 已就绪（%d 字符，保存在 %s，不打印）" % (len(token), TOKEN_FILE))


def gateway_ready():
    try:
        payload = http_json(GATEWAY_URL + "/anima/v1/health", token=token, timeout=4.0)
        return payload.get("comfy") is True
    except Exception:
        return False


if gateway_ready():
    log("网关已在运行（token 一致），跳过启动")
else:
    kill_matching("anima_gateway.py")
    log_handle = open(GATEWAY_LOG, "ab")
    subprocess.Popen(
        [sys.executable, str(GATEWAY_PY), "--token", token, "--port", "8081",
         "--comfy", COMFY_URL, "--comfy-root", str(COMFY)],
        stdout=log_handle,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    if not wait_until("本地网关 /anima/v1/health comfy=true", gateway_ready, timeout=60):
        print(tail(GATEWAY_LOG, 30))
        raise RuntimeError("网关启动失败（见上方 gateway.log）。修复后重跑本 cell。")
    log("网关已就绪: " + GATEWAY_URL)
'''
CODE_GATEWAY = (
    CODE_GATEWAY_HEAD
    + "GATEWAY_SRC = " + "r" + chr(39) * 3 + "\n"
    + gateway_source
    + chr(39) * 3 + "\n"
    + CODE_GATEWAY_TAIL
)

CODE_TUNNEL = r'''# =============================================================================
# 步骤 4/4：安装 cloudflared + 启动快速隧道 + 打印 PAIR 配对信息
# =============================================================================
print("=" * 70)
print("步骤 4/4：启动 Cloudflare 快速隧道（公网访问入口）")
print("=" * 70)


def install_cloudflared():
    if CLOUDFLARED.exists() and CLOUDFLARED.stat().st_size > 10 * 1024 * 1024:
        log("cloudflared 已存在，跳过下载")
        return
    part = CLOUDFLARED.with_name("cloudflared.part")
    for attempt in range(1, 4):
        log("下载 cloudflared（第 %d/3 次）..." % attempt)
        proc = run(
            ["curl", "-L", "--fail", "--retry", "2", "-o", str(part), CLOUDFLARED_URL],
            check=False, timeout=300,
        )
        if proc.returncode == 0 and part.exists() and part.stat().st_size > 10 * 1024 * 1024:
            os.replace(part, CLOUDFLARED)
            os.chmod(CLOUDFLARED, 0o755)
            log("cloudflared 安装完成")
            return
        time.sleep(3 * attempt)
    raise RuntimeError(
        "cloudflared 下载失败（GitHub 访问异常）。重跑本 cell 重试；"
        "多次失败请检查 Colab 网络。"
    )


install_cloudflared()
run([str(CLOUDFLARED), "--version"], check=False)


def tunnel_url():
    try:
        text = TUNNEL_LOG.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    matches = re.findall(r"https://[a-z0-9-]+\.trycloudflare\.com", text)
    return matches[-1] if matches else None


def public_ready(url):
    try:
        payload = http_json(url + "/anima/v1/health", token=token, timeout=8.0)
        return payload.get("ok") is True
    except Exception:
        return False


def stop_tunnel():
    kill_matching("cloudflared tunnel")
    time.sleep(1)


def start_tunnel(protocol=None):
    stop_tunnel()
    if TUNNEL_LOG.exists():
        TUNNEL_LOG.unlink()
    command = [str(CLOUDFLARED), "tunnel", "--url", GATEWAY_URL, "--no-autoupdate"]
    if protocol:
        command += ["--protocol", protocol]
    log("启动 cloudflared 快速隧道（protocol=%s）..." % (protocol or "quic"))
    log_handle = open(TUNNEL_LOG, "ab")
    subprocess.Popen(
        command, stdout=log_handle, stderr=subprocess.STDOUT, start_new_session=True
    )
    deadline = time.time() + 120
    while time.time() < deadline:
        url = tunnel_url()
        if url:
            return url
        time.sleep(2)
    return None


url = None
existing = tunnel_url()
if existing and pid_of("cloudflared tunnel"):
    if public_ready(existing):
        url = existing
        log("复用已在运行的隧道: " + url)
    else:
        log("旧隧道已不可用，重新创建")
if url is None:
    url = start_tunnel()
if url is None:
    print(tail(TUNNEL_LOG, 30))
    log("QUIC 未拿到 URL，改用 HTTP/2 重试 ...")
    url = start_tunnel("http2")
if url is None:
    print(tail(TUNNEL_LOG, 30))
    raise RuntimeError(
        "cloudflared 没有返回 trycloudflare.com URL（见上方日志）。"
        "重跑本 cell 会重新创建隧道；多次失败请把日志发给 Lead。"
    )
log("隧道 URL: " + url)

if not wait_until(
    "公网 /anima/v1/health 可达（经 Cloudflare 边缘）",
    lambda: public_ready(url),
    timeout=180,
):
    print(tail(TUNNEL_LOG, 30))
    raise RuntimeError(
        "隧道已建立但公网健康检查不通（Cloudflare 边缘未就绪或被网络限制）。"
        "稍等 1 分钟后重跑本 cell；仍失败请把日志发给 Lead。"
    )

pair = url + "|" + token
PAIR_FILE.write_text(pair + "\n", encoding="utf-8")
os.chmod(PAIR_FILE, 0o600)

print("")
print("=" * 72)
print("  ✅ Anima Colab 服务端已就绪（ComfyUI + 网关 + Cloudflare 隧道）")
print("=" * 72)
print("")
print("PAIR: " + pair)
print("")
print("手机端操作：")
print("  1. 打开 Anima App → 右上角设置 → 「粘贴配对信息」")
print("  2. 粘贴上面整行（从 PAIR: 到 token 结尾）→ 保存")
print("  3. 点「测试连接」，显示在线后回主页输入提示词生成")
print("  4. base 1024x1024 / 30 步约需 65-90 秒")
print("")
print("注意：")
print("  - 保持本 Colab 页面/App 打开，免费会话约 1-3 小时（受额度限制）。")
print("  - 断开后重新打开本 notebook →「全部运行」，会生成新的 PAIR（新 token + 新 URL），")
print("    App 需要重新粘贴。")
print("  - 建议把本 notebook 保存到 Google Drive（文件 → 保存副本），下次打开直接 Run all。")
print("  - 只想再抄一次 PAIR：单独运行下面的「查看 PAIR / 状态」cell。")
print("=" * 72)
'''

CODE_STATUS = r'''# =============================================================================
# 查看 PAIR / 状态（可单独运行，不影响服务）
# =============================================================================
print("=" * 72)
if PAIR_FILE.exists():
    print("PAIR: " + PAIR_FILE.read_text(encoding="utf-8").strip())
else:
    print("还没有 PAIR：请先按顺序运行前面的部署 cell。")
print("=" * 72)

token_now = TOKEN_FILE.read_text(encoding="utf-8").strip() if TOKEN_FILE.exists() else ""
try:
    health = http_json(GATEWAY_URL + "/anima/v1/health", token=token_now, timeout=8)
    print("本地网关: " + json.dumps(health, ensure_ascii=False))
except Exception as exc:
    print("本地网关不可用: %r（重跑前面的部署 cell）" % (exc,))
try:
    info = http_json(GATEWAY_URL + "/anima/v1/info", token=token_now, timeout=30)
    print("已装 unet: " + ", ".join(item["name"] for item in info.get("unets", [])))
except Exception as exc:
    print("/info 读取失败: %r" % (exc,))
if PAIR_FILE.exists():
    public_url = PAIR_FILE.read_text(encoding="utf-8").strip().split("|")[0]
    print("公网健康: HTTP %s" % http_status(public_url + "/anima/v1/health",
                                          token=token_now, timeout=10))
'''

CODE_KEEPALIVE = r'''# =============================================================================
# 保活 cell：每 60 秒打印心跳，防止 Colab 把会话判定为闲置而回收
# 这个 cell 会一直运行，属正常现象；停止/关闭页面不影响已在后台运行的 ComfyUI。
# =============================================================================
from datetime import datetime

print("保活已启动（每 60 秒一次心跳）。只要本页保持打开，服务就可用。")
while True:
    time.sleep(60)
    print("[keepalive] %s 服务仍在运行，PAIR 见「查看 PAIR / 状态」。" 
          % datetime.now().strftime("%H:%M:%S"), flush=True)
'''

cells = [
    {"type": "markdown", "source": MD_INTRO},
    {"type": "markdown", "source": MD_STEPS},
    {"type": "code", "source": CODE_HELPERS},
    {"type": "code", "source": CODE_ENV},
    {"type": "code", "source": CODE_CLONE},
    {"type": "code", "source": CODE_MODELS},
    {"type": "code", "source": CODE_COMFY},
    {"type": "code", "source": CODE_GATEWAY},
    {"type": "code", "source": CODE_TUNNEL},
    {"type": "code", "source": CODE_STATUS},
    {"type": "code", "source": CODE_KEEPALIVE, "tags": ["keepalive"]},
]

notebook = {
    "nbformat": 4,
    "nbformat_minor": 5,
    "metadata": {
        "colab": {"provenance": [], "gpuType": "T4"},
        "kernelspec": {"name": "python3", "display_name": "Python 3"},
        "language_info": {"name": "python"},
    },
    "cells": [],
}
for index, cell in enumerate(cells):
    entry = {
        "id": "anima-%02d" % index,
        "cell_type": cell["type"],
        "metadata": {},
        "source": cell["source"],
    }
    if cell["type"] == "code":
        entry.update({"execution_count": None, "outputs": []})
        if cell.get("tags"):
            entry["metadata"]["tags"] = cell["tags"]
    notebook["cells"].append(entry)

OUT.write_text(json.dumps(notebook, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print("wrote %s (%d cells, %d bytes)" % (OUT, len(cells), OUT.stat().st_size))
