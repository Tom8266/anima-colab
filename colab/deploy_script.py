#!/usr/bin/env python3
"""Anima Colab T4 / TPU one-file deployment script (runs inside the Colab kernel).

Self-contained: installs ComfyUI + anima-base + qwen TE/VAE, starts ComfyUI,
starts the token-auth gateway and a Cloudflare quick tunnel, then prints

    PAIR: https://<random>.trycloudflare.com|<token>

Accelerator:
    auto-detected (T4 via nvidia-smi, TPU via torch_tpu / TPU env vars).
    The App sets ANIMA_ACCELERATOR=t4|tpu before sending this file, which also
    acts as a guard: a mismatched Colab runtime fails fast with instructions.
    TPU mode pins ComfyUI to the tag its 11 device patches are verified against,
    skips torch/torchvision/torchaudio in requirements, and starts with
    --bf16-unet instead of --force-fp16.

How the App uses it:
    POST /api/kernels -> WS /api/kernels/<id>/channels -> execute_request with
    this file's source -> stream stdout -> regex the "PAIR:" line.
    (See colab/phone_bootstrap.py for a working reference implementation.)

Idempotent: re-running reuses /content, running processes and the existing
tunnel/token, so the printed PAIR stays the same.
"""

# =============================================================================
# 步骤 0：公共配置与工具函数（Run all 时最先执行，不要单独跳过）
# =============================================================================
import importlib.util
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

# TPU 设备补丁的锚点按这个版本验证；TPU 模式固定使用它，避免上游改代码后补丁打歪。
# 升级 ComfyUI 前先重测 11 处补丁（复现命令见 colab/README.md）。
COMFY_TPU_TAG = "v0.46.2"

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
    # "[deploy]" prefix is what the App's kernel-output parser looks for.
    print("[deploy] %s %s" % (time.strftime("%H:%M:%S"), message), flush=True)


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

# =============================================================================
# 步骤 1/4：环境检查（T4 GPU 或 TPU v5e1）
# =============================================================================
print("=" * 70)
print("步骤 1/4：检查 Colab 运行环境（T4 GPU / TPU v5e1）")
print("=" * 70)


def _module_installed(name):
    try:
        return importlib.util.find_spec(name) is not None
    except (ImportError, ValueError):
        return False


def _nvidia_gpu():
    """返回 (name, vram_gb)；没有 GPU 时返回 None。不 import torch。"""
    try:
        proc = subprocess.run(
            ["nvidia-smi", "--query-gpu=name,memory.total",
             "--format=csv,noheader,nounits"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=15,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if proc.returncode != 0 or not proc.stdout.strip():
        return None
    parts = [part.strip() for part in proc.stdout.strip().splitlines()[0].split(",")]
    try:
        return parts[0], float(parts[1]) / 1024.0
    except (IndexError, ValueError):
        return None


def _tpu_present():
    # 不 import torch：torch_tpu 只查模块元数据；Colab TPU 还会设这些环境变量。
    if _module_installed("torch_tpu"):
        return True
    return any(os.environ.get(key) for key in (
        "TPU_NAME", "TPU_WORKER_ID", "COLAB_TPU_ADDR", "LIBTPU_INIT_ARGS",
    ))


def _kernel_has_libtpu():
    """kernel 自己 import 过 torch 会映射 libtpu.so，之后 ComfyUI 抢不到 TPU。"""
    try:
        maps = Path("/proc/self/maps").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    return "libtpu" in maps


def detect_accelerator():
    override = os.environ.get("ANIMA_ACCELERATOR", "").strip().lower()
    if override not in ("", "auto", "t4", "tpu"):
        raise RuntimeError("ANIMA_ACCELERATOR 只能是 t4 / tpu / auto，实际：%r" % override)
    gpu = _nvidia_gpu()
    tpu = _tpu_present()
    if override == "t4":
        if gpu is None:
            raise RuntimeError(
                "App 请求 T4，但当前运行时没有 GPU。"
                "请「运行时 → 更改运行时类型 → T4 GPU」后重试。"
            )
        return "t4", gpu[0], gpu[1]
    if override == "tpu":
        if not tpu:
            raise RuntimeError(
                "App 请求 TPU，但当前运行时不是 TPU（找不到 torch_tpu / TPU 环境变量）。"
                "请「运行时 → 更改运行时类型 → TPU v5e1」后重试。"
            )
        return "tpu", "TPU v5e1", 16.0
    if gpu is not None:
        return "t4", gpu[0], gpu[1]
    if tpu:
        return "tpu", "TPU v5e1", 16.0
    raise RuntimeError(
        "没有检测到加速卡：既没有 nvidia-smi（GPU），也没有 torch_tpu/TPU 环境变量。"
        "请在「运行时 → 更改运行时类型」选择 T4 GPU 或 TPU v5e1。"
    )


ACCELERATOR, DEVICE_LABEL, VRAM_GB = detect_accelerator()
if ACCELERATOR == "tpu":
    log("设备: %s（bf16；TPU 只归一个进程）" % DEVICE_LABEL)
    if _kernel_has_libtpu():
        raise RuntimeError(
            "当前 kernel 已加载 libtpu（torch 被 import 过），ComfyUI 会报 "
            "「TPU is already in use」。请「运行时 → 断开并删除运行时」后重新 Run all。"
        )
else:
    log("GPU: %s / 显存 %.1f GB" % (DEVICE_LABEL, VRAM_GB))
    if "T4" not in DEVICE_LABEL:
        log("提示: T4 是验证过的档位；其它 GPU 用 fp16 通常也能跑。")

usage = shutil.disk_usage(CONTENT)
log("磁盘 /content: 共 %.1f GB，可用 %.1f GB" % (usage.total / 1024 ** 3, usage.free / 1024 ** 3))
if usage.free < 12 * 1024 ** 3:
    raise RuntimeError(
        "/content 可用空间不足 12GB（当前 %.1f GB）。请「运行时 → 重新启动运行时」后重试。"
        % (usage.free / 1024 ** 3)
    )
log("环境检查通过（%s）" % DEVICE_LABEL)

# =============================================================================
# 步骤 2/4-A：ComfyUI 本体 + Python 依赖（幂等：已存在则跳过）
# =============================================================================
print("=" * 70)
print("步骤 2/4-A：安装 ComfyUI（git clone + pip 依赖）")
print("=" * 70)


def _comfy_version():
    try:
        text = (COMFY / "comfyui_version.py").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""
    match = re.search(r'__version__\s*=\s*"([^"]+)"', text)
    return match.group(1) if match else ""


TPU_REF_MARKER = COMFY / ".anima_tpu_ref"


def _checkout_tpu_ref():
    wanted = COMFY_TPU_TAG.lstrip("v")
    current = _comfy_version()
    marker = ""
    try:
        marker = TPU_REF_MARKER.read_text(encoding="utf-8").strip()
    except OSError:
        pass
    if current == wanted and marker == COMFY_TPU_TAG:
        log("ComfyUI 已固定在 TPU 验证版本 %s" % COMFY_TPU_TAG)
        return
    log("TPU 模式固定使用 ComfyUI %s（当前 %s），切换中…"
        % (COMFY_TPU_TAG, current or "未知"))
    run(["git", "-C", str(COMFY), "fetch", "--depth", "1",
         "origin", "tag", COMFY_TPU_TAG], timeout=300)
    run(["git", "-C", str(COMFY), "checkout", "-f", COMFY_TPU_TAG], timeout=120)
    TPU_REF_MARKER.write_text(COMFY_TPU_TAG, encoding="utf-8")
    log("ComfyUI 已切换到 %s" % COMFY_TPU_TAG)


if (COMFY / "main.py").exists():
    log("ComfyUI 已存在，跳过 git clone: %s" % COMFY)
    if ACCELERATOR == "tpu":
        _checkout_tpu_ref()
else:
    if COMFY.exists():
        shutil.rmtree(COMFY, ignore_errors=True)
    last_error = None
    for attempt in range(1, 4):
        try:
            command = ["git", "clone", "--depth", "1"]
            if ACCELERATOR == "tpu":
                command += ["--branch", COMFY_TPU_TAG]
            command += ["https://github.com/comfyanonymous/ComfyUI.git", str(COMFY)]
            run(command, timeout=600)
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
    if ACCELERATOR == "tpu":
        TPU_REF_MARKER.write_text(COMFY_TPU_TAG, encoding="utf-8")
    log("ComfyUI 克隆完成")


def _write_tpu_requirements():
    """TPU 镜像自带配对的 torch/torch_tpu，装 requirements 时必须跳过 torch 三件套。"""
    kept = []
    for line in (COMFY / "requirements.txt").read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            kept.append(line)
            continue
        pkg = re.split(r"[=<>!~\[ ;]", stripped)[0].lower()
        if pkg in ("torch", "torchvision", "torchaudio"):
            continue
        kept.append(line)
    path = CONTENT / "requirements_tpu.txt"
    path.write_text("\n".join(kept) + "\n", encoding="utf-8")
    return path


deps_marker = COMFY / (".anima_deps_ok_tpu" if ACCELERATOR == "tpu" else ".anima_deps_ok")
marker_value = ""
if deps_marker.exists():
    marker_value = deps_marker.read_text(encoding="utf-8", errors="replace").strip()
deps_ready = deps_marker.exists() and (
    ACCELERATOR != "tpu" or marker_value == COMFY_TPU_TAG
)
if deps_ready:
    log("Python 依赖已装过，跳过 pip install")
else:
    if ACCELERATOR == "tpu":
        log("安装 ComfyUI 依赖（TPU：跳过 torch/torchvision/torchaudio，保留镜像配对版本）...")
        requirements = _write_tpu_requirements()
    else:
        log("安装 ComfyUI 依赖（1-3 分钟，镜像自带 torch 不会重装）...")
        requirements = COMFY / "requirements.txt"
    run(
        [sys.executable, "-m", "pip", "install", "-q", "--no-input",
         "-r", str(requirements)],
        timeout=1200,
    )
    deps_marker.write_text(COMFY_TPU_TAG if ACCELERATOR == "tpu" else "ok",
                           encoding="utf-8")
    log("依赖安装完成")

try:
    import aiohttp
    log("aiohttp 可用: %s" % aiohttp.__version__)
except Exception:
    log("aiohttp 缺失，安装中 ...")
    run([sys.executable, "-m", "pip", "install", "-q", "--no-input", "aiohttp"], timeout=300)
    import aiohttp
    log("aiohttp 已安装: %s" % aiohttp.__version__)

# =============================================================================
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

# =============================================================================
# 步骤 2/4-C：启动 ComfyUI（T4: --force-fp16 / TPU: 打设备补丁 + --bf16-unet）
# =============================================================================
print("=" * 70)
print("步骤 2/4-C：启动 ComfyUI（%s）"
      % ("TPU bf16" if ACCELERATOR == "tpu" else "T4 fp16"))
print("=" * 70)

TPU_HELPER = """def is_tpu():
    return torch.accelerator.is_available() and torch.accelerator.current_accelerator().type == "tpu"

"""

TPU_PATCHES = [
    # 设备选择
    ("""def get_torch_device():
    global cpu_state""",
     TPU_HELPER + """def get_torch_device():
    global cpu_state"""),
    ("""        if is_intel_xpu():
            return torch.device("xpu", torch.xpu.current_device())""",
     """        if is_tpu():
            return torch.device("tpu")
        elif is_intel_xpu():
            return torch.device("xpu", torch.xpu.current_device())"""),
    ("""        elif is_intel_xpu():
            for i in range(torch.xpu.device_count()):
                devices.append(torch.device("xpu", i))""",
     """        elif is_tpu():
            for i in range(torch.accelerator.device_count()):
                devices.append(torch.device("tpu", i))
        elif is_intel_xpu():
            for i in range(torch.xpu.device_count()):
                devices.append(torch.device("xpu", i))"""),
    # 显存统计
    ("""        if is_intel_xpu():
            stats = torch.xpu.memory_stats(dev)
            mem_reserved = stats['reserved_bytes.all.current']
            mem_total_xpu = torch.xpu.get_device_properties(dev).total_memory""",
     """        if is_tpu():
            mem_total_torch = torch.accelerator.memory_reserved()
            _, mem_total = torch.accelerator.get_memory_info(dev)
        elif is_intel_xpu():
            stats = torch.xpu.memory_stats(dev)
            mem_reserved = stats['reserved_bytes.all.current']
            mem_total_xpu = torch.xpu.get_device_properties(dev).total_memory"""),
    ("""        if is_intel_xpu():
            stats = torch.xpu.memory_stats(dev)
            mem_active = stats['active_bytes.all.current']""",
     """        if is_tpu():
            mem_active = torch.accelerator.memory_allocated()
            mem_reserved = torch.accelerator.memory_reserved()
            mem_free_tpu, _ = torch.accelerator.get_memory_info(dev)
            mem_free_torch = mem_reserved - mem_active
            mem_free_total = mem_free_tpu + mem_free_torch
        elif is_intel_xpu():
            stats = torch.xpu.memory_stats(dev)
            mem_active = stats['active_bytes.all.current']"""),
    # 设备名与切换（torch.tpu.* 是内部接口，用户代码直接调会抛错）
    ("""        elif device.type == "xpu":
            return "{} {}".format(device, torch.xpu.get_device_name(device))""",
     """        elif device.type == "tpu":
            return "{}".format(device.type)
        elif device.type == "xpu":
            return "{} {}".format(device, torch.xpu.get_device_name(device))"""),
    ("""    if is_device_cuda(device):
        torch.cuda.set_device(device)
    elif is_device_xpu(device):
        torch.xpu.set_device(device)""",
     """    if is_tpu():
        torch.accelerator.set_device_index(device)
    elif is_device_cuda(device):
        torch.cuda.set_device(device)
    elif is_device_xpu(device):
        torch.xpu.set_device(device)"""),
    # 精度策略：TPU 只吃 bf16
    ("""def should_use_fp16(device=None, model_params=0, prioritize_performance=True, manual_cast=False):
    if device is not None:
        if is_device_cpu(device):
            return False
""",
     """def should_use_fp16(device=None, model_params=0, prioritize_performance=True, manual_cast=False):
    if device is not None:
        if is_device_cpu(device):
            return False

    if is_tpu():
        return False
"""),
    ("""    if is_intel_xpu():
        return torch.xpu.is_bf16_supported()""",
     """    if is_tpu():
        return True

    if is_intel_xpu():
        return torch.xpu.is_bf16_supported()"""),
    # 同步与缓存
    ("""def synchronize():
    if cpu_mode():
        return
    if is_intel_xpu():""",
     """def synchronize():
    if cpu_mode():
        return
    if is_tpu():
        torch.accelerator.synchronize()
    elif is_intel_xpu():"""),
    ("""    if cpu_state == CPUState.MPS:
        torch.mps.empty_cache()
    elif is_intel_xpu():""",
     """    if cpu_state == CPUState.MPS:
        torch.mps.empty_cache()
    elif is_tpu():
        torch.accelerator.empty_cache()
    elif is_intel_xpu():"""),
]


def patch_model_management_tpu():
    path = COMFY / "comfy" / "model_management.py"
    source = path.read_text(encoding="utf-8")
    if "def is_tpu(" in source:
        log("TPU 设备补丁已存在，跳过")
        return
    patched = source
    for index, (old, new) in enumerate(TPU_PATCHES, 1):
        if old not in patched:
            raise RuntimeError(
                "TPU 设备补丁第 %d/%d 处锚点缺失：ComfyUI %s 与补丁不匹配。"
                "TPU 模式固定使用 %s；请删除 /content/ComfyUI 后重跑，"
                "或「运行时 → 断开并删除运行时」再 Run all。"
                % (index, len(TPU_PATCHES), _comfy_version() or "未知", COMFY_TPU_TAG)
            )
        patched = patched.replace(old, new, 1)
    path.write_text(patched, encoding="utf-8")
    # 只编译、不 import：kernel import torch 会占住 TPU。
    run([sys.executable, "-m", "py_compile", str(path)], timeout=60)
    shutil.rmtree(COMFY / "comfy" / "__pycache__", ignore_errors=True)
    log("已给 comfy/model_management.py 打上 %d 处 TPU 设备补丁"
        % len(TPU_PATCHES))


def comfy_ready():
    return http_status(COMFY_URL + "/object_info", timeout=3.0) == 200


def comfy_device_name():
    try:
        stats = http_json(COMFY_URL + "/system_stats", timeout=10)
        devices = stats.get("devices") or []
        if devices and isinstance(devices[0], dict):
            return str(devices[0].get("name") or "")
    except Exception:
        pass
    return ""


def comfy_mismatch():
    current = comfy_device_name().lower()
    if ACCELERATOR == "tpu":
        return "tpu" not in current
    return "tpu" in current


if comfy_ready() and not comfy_mismatch():
    log("ComfyUI 已在运行，跳过启动")
else:
    if comfy_ready():
        log("现有 ComfyUI 设备与本次 %s 不匹配，重启服务…" % DEVICE_LABEL)
    kill_matching("main.py --listen")
    if ACCELERATOR == "tpu":
        patch_model_management_tpu()
    flags = ["--bf16-unet"] if ACCELERATOR == "tpu" else ["--force-fp16"]
    log("后台启动 ComfyUI（%s，日志 %s）..."
        % ("bf16" if ACCELERATOR == "tpu" else "fp16", COMFY_LOG))
    log_handle = open(COMFY_LOG, "ab")
    subprocess.Popen(
        [sys.executable, "main.py", "--listen", "127.0.0.1", "--port", "8188"] + flags,
        cwd=str(COMFY),
        stdout=log_handle,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    timeout = 360 if ACCELERATOR == "tpu" else 240
    if not wait_until("ComfyUI 就绪（/object_info 返回 200）", comfy_ready, timeout=timeout):
        log_text = tail(COMFY_LOG, 60)
        print(log_text)
        if ACCELERATOR == "tpu" and "TPU is already in use" in log_text:
            raise RuntimeError(
                "TPU 被其它进程占用（见上方日志）。请「运行时 → 断开并删除运行时」后重新 Run all。"
            )
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
    devices = stats.get("devices") or []
    if devices and isinstance(devices[0], dict):
        log("ComfyUI 计算设备: %s" % devices[0].get("name"))
except Exception as exc:
    log("读取 /system_stats 失败（不影响出图）: %r" % (exc,))

# =============================================================================
# 步骤 3/4：写入网关并启动（随机 token 鉴权，只暴露 App 需要的接口）
# =============================================================================
print("=" * 70)
print("步骤 3/4：启动 token 鉴权网关（127.0.0.1:8081）")
print("=" * 70)

GATEWAY_SRC = r'''
#!/usr/bin/env python3
"""Anima Gateway: token-auth reverse proxy for ComfyUI (contract v1).

Exposes only the endpoints the Android App needs, on 127.0.0.1:8081.
Public traffic arrives through a Cloudflare quick tunnel:

    App --HTTPS/WSS--> cloudflared --HTTP/WS--> gateway:8081 --> ComfyUI:8188

Run:  python gateway.py --token <random> [--port 8081] [--comfy http://127.0.0.1:8188]

Dependencies: python stdlib + aiohttp (installed by the notebook if missing).
The token is never written to logs.
"""
import argparse
import asyncio
import hmac
import json
import os
import re
import subprocess
import sys
import urllib.parse

try:
    from aiohttp import ClientError, ClientSession, ClientTimeout, WSMsgType, web
except ImportError:  # pragma: no cover - the notebook installs it first
    sys.stderr.write("aiohttp is required: python -m pip install aiohttp\n")
    raise

GATEWAY_VERSION = "0.1.0"
COMFY = os.environ.get("COMFY_URL", "http://127.0.0.1:8188").rstrip("/")
COMFY_ROOT = os.environ.get("COMFY_ROOT", "/content/ComfyUI")
TOKEN = ""
HEALTH_PATH = "/anima/v1/health"

# name -> model sub directory, used to report accurate file sizes
SIZE_DIRS = {
    "diffusion_models": "diffusion_models",
    "text_encoders": "text_encoders",
    "vae": "vae",
    "loras": "loras",
}

# 部署脚本会传入实际加速卡；TPU 的 ComfyUI /system_stats 只报 "tpu"，
# 用 DEVICE_LABEL 把它变成 App 里更好读的 "TPU v5e1"。
ACCELERATOR = os.environ.get("ANIMA_ACCELERATOR", "").strip().lower()
DEVICE_LABEL = os.environ.get("ANIMA_DEVICE_LABEL", "").strip()
_device_cache = {"name": DEVICE_LABEL or "unknown", "vram_total_gb": 0.0}


def _err(status, error):
    return web.json_response({"ok": False, "error": error}, status=status)


def _authed(request):
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        return False
    return hmac.compare_digest(header[7:].strip(), TOKEN)


async def _fetch_json(session, path, timeout=10.0):
    async with session.get(COMFY + path, timeout=ClientTimeout(total=timeout)) as resp:
        body = await resp.read()
        if resp.status != 200:
            raise _UpstreamError(resp.status, body)
        return json.loads(body.decode("utf-8"))


class _UpstreamError(Exception):
    def __init__(self, status, body=b""):
        self.status = status
        self.body = body


def _probe_device():
    """Best-effort GPU name/VRAM via nvidia-smi, cached at startup."""
    if ACCELERATOR == "tpu":
        return
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=5,
        )
        first = out.stdout.decode("utf-8", "replace").strip().splitlines()[0]
        name, mem_mb = [part.strip() for part in first.split(",")[:2]]
        _device_cache["name"] = name
        _device_cache["vram_total_gb"] = round(float(mem_mb) / 1024.0, 1)
    except Exception:
        pass


def _clean_device_name(raw):
    """ComfyUI may report e.g. "cuda:0 Tesla T4 : cudaMallocAsync"."""
    name = re.sub(r"^cuda:\d+\s+", "", str(raw or "")).strip()
    name = re.sub(r"\s*:\s*cudaMallocAsync.*$", "", name, flags=re.IGNORECASE).strip()
    return name


def _size_gb(sub, name):
    path = os.path.join(COMFY_ROOT, "models", SIZE_DIRS.get(sub, sub), name)
    try:
        return round(os.path.getsize(path) / 1024 ** 3, 1)
    except OSError:
        return None


def _first_choice(info, node, field):
    """Pull the allowed-value list for one input from a /object_info payload."""
    try:
        node_info = info[node]
        spec = node_info["input"]
        val = None
        for group in ("required", "optional"):
            if field in spec.get(group, {}):
                val = spec[group][field]
                break
        if not val:
            return []
        choices = val[0]
        if isinstance(choices, list):
            return [str(c) for c in choices]
    except (KeyError, IndexError, TypeError):
        pass
    return []


def _collect(info, nodes, field):
    out = []
    for node in nodes:
        for name in _first_choice(info, node, field):
            if name not in out:
                out.append(name)
    return out


def _comfy_error(resp):
    """Translate an upstream ComfyUI error into the contract error shape."""
    try:
        return web.json_response(
            {"ok": False, "error": "comfy_error", "status": resp.status},
            status=502 if resp.status >= 500 else resp.status,
        )
    except Exception:
        return _err(502, "comfy_unreachable")


def _history_params(prompt_nodes):
    """Extract generation params from the fixed contract v1 node graph."""
    params = {
        "model": "",
        "positive": "",
        "negative": "",
        "seed": None,
        "steps": None,
        "cfg": None,
        "sampler": "",
        "scheduler": "",
        "width": None,
        "height": None,
        "batch": None,
    }
    if not isinstance(prompt_nodes, dict):
        return params
    try:
        unet = prompt_nodes.get("5", {}).get("inputs", {})
        params["model"] = unet.get("unet_name", "") or ""
        params["positive"] = prompt_nodes.get("3", {}).get("inputs", {}).get("text", "") or ""
        params["negative"] = prompt_nodes.get("4", {}).get("inputs", {}).get("text", "") or ""
        ks = prompt_nodes.get("7", {}).get("inputs", {})
        params["seed"] = ks.get("seed")
        params["steps"] = ks.get("steps")
        params["cfg"] = ks.get("cfg")
        params["sampler"] = ks.get("sampler_name", "") or ""
        params["scheduler"] = ks.get("scheduler", "") or ""
        latent = prompt_nodes.get("6", {}).get("inputs", {})
        params["width"] = latent.get("width")
        params["height"] = latent.get("height")
        params["batch"] = latent.get("batch_size")
    except (AttributeError, TypeError):
        pass
    return params


def _history_item(prompt_id, record):
    """Convert one ComfyUI /history record into a contract §4 item (or None)."""
    if not isinstance(record, dict):
        return None
    outputs = record.get("outputs") or {}
    images = []
    for node_out in outputs.values():
        if not isinstance(node_out, dict):
            continue
        for image in node_out.get("images") or []:
            if not isinstance(image, dict) or not image.get("filename"):
                continue
            if image.get("type", "output") != "output":
                continue
            images.append(
                {
                    "filename": image.get("filename", ""),
                    "subfolder": image.get("subfolder", ""),
                    "type": image.get("type", "output"),
                    "node": image.get("node"),
                }
            )
    if not images:
        return None

    prompt_nodes = {}
    prompt_meta = record.get("prompt")
    if isinstance(prompt_meta, list) and len(prompt_meta) >= 3:
        if isinstance(prompt_meta[2], dict):
            prompt_nodes = prompt_meta[2]

    created = 0
    status = record.get("status") or {}
    messages = status.get("messages") or []
    for message in messages:
        if not isinstance(message, (list, tuple)) or len(message) < 2:
            continue
        if message[0] in ("execution_start", "execution_success", "execution_error"):
            data = message[1] if isinstance(message[1], dict) else {}
            stamp = data.get("timestamp")
            if isinstance(stamp, (int, float)) and stamp > 0:
                created = max(created, int(stamp / 1000) if stamp > 1e12 else int(stamp))
                break

    status_str = status.get("status_str") or ""
    if status.get("completed") or status_str == "success":
        state = "success"
    else:
        state = status_str or "unknown"

    return {
        "prompt_id": prompt_id,
        "status": state,
        "images": images,
        "created": created,
        "params": _history_params(prompt_nodes),
    }


async def _probe_system_stats(session):
    try:
        return await _fetch_json(session, "/system_stats", timeout=3.0)
    except Exception:
        return None


# ---------------------------------------------------------------- auth


@web.middleware
async def auth_middleware(request, handler):
    if request.path == HEALTH_PATH:
        return await handler(request)
    if not _authed(request):
        return _err(401, "unauthorized")
    return await handler(request)


# ---------------------------------------------------------------- endpoints


async def handle_health(request):
    session = request.app["session"]
    authed = _authed(request)
    payload = {
        "ok": True,
        "comfy": False,
        "gateway": GATEWAY_VERSION,
        "queue": {"running": 0, "pending": 0},
        "device": _device_cache["name"],
        "vram_total_gb": _device_cache["vram_total_gb"],
    }
    # The App uses /health (also without token) to tell whether ComfyUI is
    # ready, so probe it for everyone; the queue (prompt ids) stays token-only.
    stats = await _probe_system_stats(session)
    if stats is None:
        return web.json_response(payload)
    payload["comfy"] = True
    devices = stats.get("devices") or []
    if devices and isinstance(devices[0], dict):
        cleaned = _clean_device_name(devices[0].get("name"))
        if cleaned:
            if cleaned.lower() == "tpu" and DEVICE_LABEL:
                cleaned = DEVICE_LABEL
            payload["device"] = cleaned
            # /info 只读 _device_cache；TPU 没有 nvidia-smi，必须在这里回填。
            _device_cache["name"] = cleaned
        vram = devices[0].get("vram_total")
        if isinstance(vram, (int, float)) and vram > 0:
            payload["vram_total_gb"] = round(vram / 1024 ** 3, 1)
            _device_cache["vram_total_gb"] = payload["vram_total_gb"]
    if authed:
        try:
            queue = await _fetch_json(session, "/queue", timeout=3.0)
            running = [item[1] for item in queue.get("queue_running") or [] if len(item) > 1]
            pending = [item[1] for item in queue.get("queue_pending") or [] if len(item) > 1]
            payload["queue"] = {"running": running, "pending": pending}
        except Exception:
            pass
    return web.json_response(payload)


async def handle_info(request):
    session = request.app["session"]
    try:
        info = await _fetch_json(session, "/object_info", timeout=20.0)
    except _UpstreamError:
        return _err(502, "comfy_unreachable")
    except Exception:
        return _err(502, "comfy_unreachable")

    unets = _collect(info, ["UNETLoader"], "unet_name")
    loras = _collect(info, ["LoraLoaderModelOnly", "LoraLoader"], "lora_name")
    vaes = _collect(info, ["VAELoader"], "vae_name")
    text_encoders = _collect(info, ["CLIPLoader"], "clip_name")
    samplers = _collect(info, ["KSampler", "KSamplerAdvanced"], "sampler_name")
    schedulers = _collect(info, ["KSampler", "KSamplerAdvanced"], "scheduler")

    def pick(candidates, available):
        for name in candidates:
            if name in available:
                return name
        return available[0] if available else ""

    defaults = {
        "unet": pick(
            ["anima-base-v1.0.safetensors", "anima-aesthetic-v1.1.safetensors"], unets
        ),
        "vae": pick(["qwen_image_vae.safetensors"], vaes),
        "text_encoder": pick(["qwen_3_06b_base.safetensors"], text_encoders),
    }
    return web.json_response(
        {
            "ok": True,
            "unets": [{"name": n, "size_gb": _size_gb("diffusion_models", n)} for n in unets],
            "loras": [{"name": n, "size_gb": _size_gb("loras", n)} for n in loras],
            "vaes": [{"name": n} for n in vaes],
            "text_encoders": [{"name": n} for n in text_encoders],
            "samplers": samplers,
            "schedulers": schedulers,
            "defaults": defaults,
            "device": _device_cache["name"],
            "vram_total_gb": _device_cache["vram_total_gb"],
        }
    )


async def _forward_json(request, path, method="GET"):
    session = request.app["session"]
    try:
        kwargs = {}
        if method == "POST":
            raw = await request.read()
            kwargs["data"] = raw
            kwargs["headers"] = {"Content-Type": request.content_type or "application/json"}
        async with session.request(
            method,
            COMFY + path,
            timeout=ClientTimeout(total=30.0),
            **kwargs,
        ) as resp:
            body = await resp.read()
            # ComfyUI sends "application/json; charset=utf-8" -- pass it as a
            # raw header, aiohttp rejects charset in the content_type argument.
            headers = {
                "Content-Type": resp.headers.get("Content-Type", "application/json"),
                "Cache-Control": "no-store",
            }
            return web.Response(status=resp.status, body=body, headers=headers)
    except _UpstreamError as exc:
        return _comfy_error(exc)
    except (ClientError, asyncio.TimeoutError, OSError):
        return _err(502, "comfy_unreachable")


async def handle_prompt(request):
    return await _forward_json(request, "/prompt", method="POST")


async def handle_queue(request):
    session = request.app["session"]
    try:
        queue = await _fetch_json(session, "/queue", timeout=5.0)
    except _UpstreamError:
        return _err(502, "comfy_unreachable")
    except Exception:
        return _err(502, "comfy_unreachable")
    running = [item[1] for item in queue.get("queue_running") or [] if len(item) > 1]
    pending = [item[1] for item in queue.get("queue_pending") or [] if len(item) > 1]
    return web.json_response({"ok": True, "running": running, "pending": pending})


async def handle_queue_clear(request):
    session = request.app["session"]
    try:
        async with session.post(
            COMFY + "/queue",
            json={"clear": True},
            timeout=ClientTimeout(total=10.0),
        ) as resp:
            await resp.read()
        return web.json_response({"ok": True})
    except (ClientError, asyncio.TimeoutError, OSError):
        return _err(502, "comfy_unreachable")


async def handle_interrupt(request):
    session = request.app["session"]
    try:
        async with session.post(
            COMFY + "/interrupt", timeout=ClientTimeout(total=10.0)
        ) as resp:
            await resp.read()
        return web.json_response({"ok": True})
    except (ClientError, asyncio.TimeoutError, OSError):
        return _err(502, "comfy_unreachable")


async def _all_history(session):
    try:
        history = await _fetch_json(session, "/history", timeout=30.0)
    except _UpstreamError:
        raise
    except Exception:
        raise
    return history if isinstance(history, dict) else {}


async def handle_history(request):
    session = request.app["session"]
    try:
        history = await _all_history(session)
    except _UpstreamError:
        return _err(502, "comfy_unreachable")
    except Exception:
        return _err(502, "comfy_unreachable")
    raw_limit = request.query.get("limit", "50")
    try:
        limit = max(1, min(200, int(raw_limit)))
    except ValueError:
        limit = 50
    items = []
    for prompt_id, record in history.items():
        item = _history_item(prompt_id, record)
        if item is not None:
            items.append(item)
    items.sort(key=lambda item: item.get("created") or 0, reverse=True)
    return web.json_response({"ok": True, "items": items[:limit]})


async def handle_history_one(request):
    session = request.app["session"]
    prompt_id = request.match_info.get("prompt_id", "")
    try:
        history = await _all_history(session)
    except _UpstreamError:
        return _err(502, "comfy_unreachable")
    except Exception:
        return _err(502, "comfy_unreachable")
    record = history.get(prompt_id)
    item = _history_item(prompt_id, record) if record is not None else None
    if item is None:
        return _err(404, "not_found")
    return web.json_response(item)


async def handle_view(request):
    session = request.app["session"]
    query = dict(request.query)
    query.setdefault("type", "output")
    if not query.get("filename"):
        return _err(404, "not_found")
    url = COMFY + "/view?" + urllib.parse.urlencode(query)
    try:
        upstream = await session.get(url, timeout=ClientTimeout(total=120.0))
    except (ClientError, asyncio.TimeoutError, OSError):
        return _err(502, "comfy_unreachable")
    try:
        if upstream.status != 200:
            await upstream.read()
            return _err(404, "not_found")
        response = web.StreamResponse(
            status=200,
            headers={
                "Content-Type": upstream.headers.get("Content-Type", "image/png"),
                "Cache-Control": "no-store",
            },
        )
        await response.prepare(request)
        async for chunk in upstream.content.iter_chunked(64 * 1024):
            await response.write(chunk)
        await response.write_eof()
        return response
    finally:
        upstream.release()


async def handle_ws(request):
    session = request.app["session"]
    client_id = (request.query.get("clientId") or "anima").strip()[:128]
    ws_client = web.WebSocketResponse(heartbeat=30.0, max_msg_size=0)
    await ws_client.prepare(request)
    backend_url = COMFY + "/ws?clientId=" + urllib.parse.quote(client_id, safe="")
    try:
        ws_backend = await session.ws_connect(
            backend_url, heartbeat=30.0, max_msg_size=0
        )
    except (ClientError, asyncio.TimeoutError, OSError):
        await ws_client.close()
        return ws_client

    async def client_to_backend():
        async for message in ws_client:
            if message.type == WSMsgType.TEXT:
                await ws_backend.send_str(message.data)
            elif message.type == WSMsgType.BINARY:
                await ws_backend.send_bytes(message.data)
            else:
                break

    async def backend_to_client():
        async for message in ws_backend:
            if message.type == WSMsgType.TEXT:
                await ws_client.send_str(message.data)
            elif message.type == WSMsgType.BINARY:
                await ws_client.send_bytes(message.data)
            else:
                break

    tasks = [
        asyncio.ensure_future(client_to_backend()),
        asyncio.ensure_future(backend_to_client()),
    ]
    try:
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if not ws_backend.closed:
            await ws_backend.close()
        if not ws_client.closed:
            await ws_client.close()
    return ws_client


# ---------------------------------------------------------------- app


def build_app():
    app = web.Application(middlewares=[auth_middleware], client_max_size=32 * 1024 * 1024)
    app.router.add_get(HEALTH_PATH, handle_health)
    app.router.add_get("/anima/v1/info", handle_info)
    app.router.add_post("/prompt", handle_prompt)
    app.router.add_get("/anima/v1/queue", handle_queue)
    app.router.add_post("/anima/v1/queue/clear", handle_queue_clear)
    app.router.add_post("/anima/v1/interrupt", handle_interrupt)
    app.router.add_get("/anima/v1/history", handle_history)
    app.router.add_get("/anima/v1/history/{prompt_id}", handle_history_one)
    app.router.add_get("/view", handle_view)
    app.router.add_get("/ws", handle_ws)
    return app


async def run(args):
    global TOKEN, COMFY, COMFY_ROOT
    TOKEN = args.token or os.environ.get("ANIMA_TOKEN", "")
    if not TOKEN:
        sys.stderr.write("missing token: pass --token or set ANIMA_TOKEN\n")
        return 2
    if args.comfy:
        COMFY = args.comfy.rstrip("/")
    if args.comfy_root:
        COMFY_ROOT = args.comfy_root
    _probe_device()

    app = build_app()
    app["session"] = ClientSession()
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, args.host, args.port)
    await site.start()
    print(
        "anima-gateway v%s listening on http://%s:%d (comfy=%s)"
        % (GATEWAY_VERSION, args.host, args.port, COMFY),
        flush=True,
    )
    try:
        await asyncio.Event().wait()
    finally:
        await app["session"].close()
        await runner.cleanup()
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description="Anima token-auth gateway for ComfyUI")
    parser.add_argument("--token", default="", help="bearer token (or env ANIMA_TOKEN)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8081)
    parser.add_argument("--comfy", default="", help="ComfyUI base URL")
    parser.add_argument("--comfy-root", default="", help="ComfyUI install dir")
    args = parser.parse_args(argv)
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
'''

import hashlib

GATEWAY_PY.write_text(GATEWAY_SRC, encoding="utf-8")
gateway_hash = hashlib.sha256(GATEWAY_SRC.encode("utf-8")).hexdigest()
log("网关源码已写入: %s (%d bytes, sha256 %s)"
    % (GATEWAY_PY, len(GATEWAY_SRC), gateway_hash[:12]))

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


hash_file = CONTENT / ".anima_gateway_hash"
previous_hash = hash_file.read_text(encoding="utf-8").strip() if hash_file.exists() else ""

if gateway_ready() and previous_hash == gateway_hash:
    log("网关已在运行（token 与源码一致），跳过启动")
else:
    if gateway_ready():
        log("网关源码已更新，重启网关加载新代码")
    kill_matching("anima_gateway.py")
    log_handle = open(GATEWAY_LOG, "ab")
    gateway_env = dict(os.environ)
    gateway_env["ANIMA_ACCELERATOR"] = ACCELERATOR
    gateway_env["ANIMA_DEVICE_LABEL"] = DEVICE_LABEL
    subprocess.Popen(
        [sys.executable, str(GATEWAY_PY), "--token", token, "--port", "8081",
         "--comfy", COMFY_URL, "--comfy-root", str(COMFY)],
        stdout=log_handle,
        stderr=subprocess.STDOUT,
        start_new_session=True,
        env=gateway_env,
    )
    if not wait_until("本地网关 /anima/v1/health comfy=true", gateway_ready, timeout=60):
        print(tail(GATEWAY_LOG, 30))
        raise RuntimeError("网关启动失败（见上方 gateway.log）。修复后重跑本 cell。")
    hash_file.write_text(gateway_hash, encoding="utf-8")
    log("网关已就绪: %s（%s）" % (GATEWAY_URL, DEVICE_LABEL))

# =============================================================================
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
print("  ✅ Anima Colab 服务端已就绪（%s + ComfyUI + 网关 + Cloudflare 隧道）" % DEVICE_LABEL)
print("=" * 72)
print("")
print("PAIR: " + pair)
print("")
print("手机端操作：")
print("  1. 打开 Anima App → 右上角设置 → 「粘贴配对信息」")
print("  2. 粘贴上面整行（从 PAIR: 到 token 结尾）→ 保存")
print("  3. 点「测试连接」，显示在线后回主页输入提示词生成")
if ACCELERATOR == "tpu":
    print("  4. 首张图会先编译约 1 分钟；之后 1024x1024 / 30 步约 36 秒")
else:
    print("  4. base 1024x1024 / 30 步约需 65-90 秒")
print("")
print("注意：")
print("  - 保持本 Colab 页面/App 打开，免费会话约 1-3 小时（受额度限制）。")
print("  - 断开后重新打开本 notebook →「全部运行」，会生成新的 PAIR（新 token + 新 URL），")
print("    App 需要重新粘贴。")
print("  - 建议把本 notebook 保存到 Google Drive（文件 → 保存副本），下次打开直接 Run all。")
if ACCELERATOR == "tpu":
    print("  - TPU 模式：kernel 不能 import torch；若报 'TPU is already in use'，")
    print("    请「运行时 → 断开并删除运行时」后重新 Run all。")
print("  - 只想再抄一次 PAIR：单独运行下面的「查看 PAIR / 状态」cell。")
print("=" * 72)
