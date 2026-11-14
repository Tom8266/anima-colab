#!/usr/bin/env python3
"""Anima mock gateway —— 真机截图 / 视觉 QA 用的本地假网关（纯 Python3 标准库）。

为什么需要它：截图时手机不应该依赖真实的 Colab T4 会话。这个脚本在本机
127.0.0.1:8091 上实现 docs/contract.md 里 App 实际会用到的全部端点，
返回稳定的假数据 + outputs/ 里的真实 PNG，配合 `adb reverse tcp:8091 tcp:8091`
就能让手机 App 以「已连接 T4、图库 6 张图」的状态跑起来。

用法：
    python3 scripts/mock_gateway.py                    # 监听 127.0.0.1:8091
    python3 scripts/mock_gateway.py --port 8091 --verbose
    python3 scripts/mock_gateway.py --fixtures outputs # 指定真图目录（默认 outputs/）

手机侧（App 的 base_url 填 http://127.0.0.1:8091，token 任意非空，例如 devtoken）：
    adb reverse tcp:8091 tcp:8091

实现端点（契约 §4）：
    GET  /anima/v1/health         T4 / 14.6G / comfy=true；带 token 时队列真实统计
    GET  /anima/v1/info           unets/loras/vaes/text_encoders/samplers/schedulers/defaults
    POST /prompt                  （兼容 /anima/v1/prompt）返回 prompt_id 并异步「生成」完成
    GET  /anima/v1/queue          running/pending（跑模拟生成时 running 非空）
    POST /anima/v1/queue/clear    清空
    POST /anima/v1/interrupt      中断当前模拟生成
    GET  /anima/v1/history        6 条预置记录 + 本次提交的记录（按 created 倒序）
    GET  /anima/v1/history/<id>   单条
    GET  /view?filename=…         真实 PNG 字节（outputs/*.png；找不到时用内置渐变 PNG 兜底）
    WS   /ws?clientId=…           模拟 ComfyUI 事件流：status / execution_start / progress /
                                  executing / executed / execution_success；会回应 ping（pong）
    GET  /                        {"ok":true,"mock":true,...} 便于探活
    POST /__mock__/reset          恢复 6 条预置图库（截图脚本每次运行前调用，无需 token）

鉴权：除 /anima/v1/health 外都要求 `Authorization: Bearer <非空 token>`，
任意非空 token 均接受（未带 token → 401 {"ok":false,"error":"unauthorized"}）。
特例：token 恰好为 `empty` 时 /anima/v1/history 返回空列表（截图「空图库」空态用）。

调试：
    curl -s http://127.0.0.1:8091/anima/v1/health | python3 -m json.tool
    curl -s -H 'Authorization: Bearer devtoken' http://127.0.0.1:8091/anima/v1/history?limit=3
    curl -s -o /tmp/x.png 'http://127.0.0.1:8091/view?filename=anima_00001_.png&subfolder=&type=output'
"""

import argparse
import base64
import hashlib
import json
import os
import struct
import sys
import threading
import time
import urllib.parse
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

GATEWAY_VERSION = "0.1.0-mock"
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# 与真实网关 / ComfyUI 对齐的 GPU 信息（契约 §4 health）
DEVICE = "Tesla T4"
VRAM_TOTAL_GB = 14.6

# 预置图库记录：6 条，按 created 倒序展示。filename 会映射到 outputs/ 里的真实 PNG。
HISTORY_SPEC = [
    {
        "filename": "anima_00001_.png",
        "positive": "masterpiece, best quality, safe, 1girl, solo, long silver hair, "
                    "looking at viewer, upper body, classroom, soft lighting, "
        "depth of field, detailed eyes",
        "negative": "worst quality, low quality, blurry, jpeg artifacts, text, watermark, signature",
        "seed": 20261114, "steps": 30, "cfg": 4.5, "sampler": "er_sde", "scheduler": "simple",
        "width": 1024, "height": 1024, "batch": 1, "age_sec": 420,
    },
    {
        "filename": "anima_00002_.png",
        "positive": "masterpiece, best quality, safe, 1girl, solo, white dress, standing, "
                    "full body, white background, (@hiten \\(hitenkei\\):1.5), very delicate",
        "negative": "worst quality, low quality, blurry, text, watermark, signature, score_1, score_2",
        "seed": 777, "steps": 30, "cfg": 4.5, "sampler": "er_sde", "scheduler": "simple",
        "width": 832, "height": 1216, "batch": 1, "age_sec": 3600,
    },
    {
        "filename": "anima_00003_.png",
        "positive": "masterpiece, best quality, safe, 2girls, cool afternoon, rooftop, "
                    "cityscape, wind, sunset, cinematic lighting",
        "negative": "worst quality, low quality, blurry, text, watermark, signature",
        "seed": 123456, "steps": 32, "cfg": 5.0, "sampler": "euler", "scheduler": "simple",
        "width": 1216, "height": 832, "batch": 2, "age_sec": 3 * 3600,
    },
    {
        "filename": "anima_00004_.png",
        "positive": "masterpiece, best quality, safe, 1girl, solo, miku, blue hair, twin tails, "
                    "concert stage, glowing lights, night, crowd",
        "negative": "worst quality, low quality, blurry, text, watermark, signature, score_1, score_2, score_3",
        "seed": 999999, "steps": 28, "cfg": 4.0, "sampler": "er_sde", "scheduler": "simple",
        "width": 1024, "height": 1024, "batch": 1, "age_sec": 24 * 3600,
    },
    {
        "filename": "anima_00005_.png",
        "positive": "masterpiece, best quality, safe, no humans, landscape, mountain, lake, "
                    "autumn forest, morning mist, volumetric light",
        "negative": "worst quality, low quality, blurry, text, watermark, signature",
        "seed": 20261115, "steps": 30, "cfg": 4.5, "sampler": "dpmpp_2m", "scheduler": "karras",
        "width": 1216, "height": 832, "batch": 1, "age_sec": 2 * 24 * 3600,
    },
    {
        "filename": "anima_00006_.png",
        "positive": "masterpiece, best quality, safe, 1girl, solo, hanfu, bamboo forest, "
                    "traditional chinese garden, soft light, (@hiten \\(hitenkei\\):1.2)",
        "negative": "worst quality, low quality, blurry, text, watermark, signature, score_1, score_2",
        "seed": 1234, "steps": 30, "cfg": 4.5, "sampler": "er_sde", "scheduler": "simple",
        "width": 1024, "height": 1024, "batch": 1, "age_sec": 3 * 24 * 3600,
    },
]

INFO_PAYLOAD = {
    "ok": True,
    "unets": [
        {"name": "anima-base-v1.0.safetensors", "size_gb": 3.9},
        {"name": "anima-aesthetic-v1.1.safetensors", "size_gb": 3.9},
    ],
    "loras": [],
    "vaes": [{"name": "qwen_image_vae.safetensors"}],
    "text_encoders": [{"name": "qwen_3_06b_base.safetensors"}],
    "samplers": ["er_sde", "euler", "dpmpp_2m"],
    "schedulers": ["simple", "karras", "normal"],
    "defaults": {
        "unet": "anima-base-v1.0.safetensors",
        "vae": "qwen_image_vae.safetensors",
        "text_encoder": "qwen_3_06b_base.safetensors",
    },
    "device": DEVICE,
    "vram_total_gb": VRAM_TOTAL_GB,
}


# ---------------------------------------------------------------- PNG fixtures

def _gradient_png(w=1024, h=1024):
    """纯标准库生成一张渐变 PNG（outputs/ 里没真图时的兜底）。"""
    rows = []
    for y in range(h):
        row = bytearray([0])  # filter type 0
        gy = y / max(1, h - 1)
        for x in range(w):
            gx = x / max(1, w - 1)
            r = int(90 + 120 * gx)
            g = int(70 + 80 * gy)
            b = int(150 + 80 * (1 - gx))
            row += bytes((r, g, b))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6))
            + chunk(b"IEND", b""))


def load_fixtures(fixtures_dir):
    """把 outputs/ 里的真实 PNG 读进内存，返回 [bytes, ...]（至少 1 张）。"""
    pngs = []
    if fixtures_dir and os.path.isdir(fixtures_dir):
        for name in sorted(os.listdir(fixtures_dir)):
            if not name.lower().endswith(".png"):
                continue
            path = os.path.join(fixtures_dir, name)
            try:
                with open(path, "rb") as fh:
                    data = fh.read()
                if data.startswith(b"\x89PNG\r\n\x1a\n"):
                    pngs.append((name, data))
            except OSError:
                continue
    if not pngs:
        sys.stderr.write("mock-gateway: 没找到真实 PNG，使用内置渐变图兜底\n")
        pngs = [("_fallback.png", _gradient_png())]
    # 截图不需要 4K 大图，避免设备内存压力；按文件名排序保证稳定映射
    return pngs


# ---------------------------------------------------------------- WebSocket

class MockWebSocket:
    """手写最小 WebSocket 服务端帧协议（文本帧 + ping/pong/close 足够 OkHttp 用）。"""

    def __init__(self, rfile, wfile, send_lock=None):
        self.rfile = rfile
        self.wfile = wfile
        self._lock = send_lock or threading.Lock()
        self.closed = False

    @staticmethod
    def _frame(opcode, payload=b""):
        header = bytearray([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header.append(n)
        elif n < (1 << 16):
            header.append(126)
            header += struct.pack(">H", n)
        else:
            header.append(127)
            header += struct.pack(">Q", n)
        return bytes(header) + payload

    def send_text(self, obj):
        payload = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        return self.send_raw(0x1, payload)

    def send_raw(self, opcode, payload=b""):
        if self.closed:
            return False
        try:
            with self._lock:
                self.wfile.write(self._frame(opcode, payload))
                self.wfile.flush()
            return True
        except (OSError, ValueError):
            self.closed = True
            return False

    def _read_exact(self, n):
        data = b""
        while len(data) < n:
            chunk = self.rfile.read(n - len(data))
            if not chunk:
                raise EOFError
            data += chunk
        return data

    def read_loop(self):
        """阻塞读客户端帧，直到 close/EOF。返回后调用方负责注销。"""
        try:
            while not self.closed:
                head = self._read_exact(2)
                b0, b1 = head[0], head[1]
                opcode = b0 & 0x0F
                masked = bool(b1 & 0x80)
                length = b1 & 0x7F
                if length == 126:
                    length = struct.unpack(">H", self._read_exact(2))[0]
                elif length == 127:
                    length = struct.unpack(">Q", self._read_exact(8))[0]
                if length > 32 * 1024 * 1024:  # 防御：异常大帧
                    break
                mask = self._read_exact(4) if masked else b"\x00\x00\x00\x00"
                payload = self._read_exact(length) if length else b""
                if masked:
                    payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
                if opcode == 0x8:  # close
                    self.send_raw(0x8, payload[:2])
                    break
                if opcode == 0x9:  # ping → pong（OkHttp pingInterval 需要）
                    self.send_raw(0xA, payload)
                # 文本/二进制帧（App 上行的消息）忽略
        except (EOFError, OSError, ValueError):
            pass
        finally:
            self.closed = True


# ---------------------------------------------------------------- state

class MockState:
    def __init__(self, fixtures, sim_seconds=6.0):
        self.lock = threading.Lock()
        self.fixtures = fixtures  # [(name, bytes), ...]
        self.sim_seconds = float(sim_seconds)
        self.sockets = []         # [MockWebSocket]
        self.history = []         # [item dict]（新→旧）
        self.prompts = {}         # pid -> item
        self.prompt_counter = 0
        self.running = 0
        self.history = self._preset_history()

    def fixture_bytes(self, filename):
        """filename -> PNG bytes（稳定映射；未知文件名走哈希取模）。"""
        h = int(hashlib.sha1(filename.encode("utf-8")).hexdigest()[:8], 16)
        return self.fixtures[h % len(self.fixtures)][1]

    def _preset_history(self):
        now = int(time.time())
        items = []
        for spec in HISTORY_SPEC:
            batch = spec.get("batch", 1)
            images = [{"filename": spec["filename"], "subfolder": "", "type": "output"}]
            for i in range(1, batch):
                images.append({
                    "filename": spec["filename"].replace("_.png", "_%d_.png" % i),
                    "subfolder": "", "type": "output",
                })
            items.append({
                "prompt_id": "mock-%s" % spec["filename"].replace(".png", ""),
                "status": "success",
                "images": images,
                "created": now - spec.get("age_sec", 600),
                "params": {
                    "model": "anima-base-v1.0.safetensors",
                    "positive": spec["positive"],
                    "negative": spec["negative"],
                    "seed": spec["seed"],
                    "steps": spec["steps"],
                    "cfg": spec["cfg"],
                    "sampler": spec["sampler"],
                    "scheduler": spec["scheduler"],
                    "width": spec["width"],
                    "height": spec["height"],
                    "batch": batch,
                },
            })
        return items

    # ---- WebSocket 客户端管理 ----
    def add_socket(self, ws):
        with self.lock:
            self.sockets.append(ws)

    def remove_socket(self, ws):
        with self.lock:
            self.sockets = [s for s in self.sockets if s is not ws]

    def broadcast(self, event):
        with self.lock:
            sockets = list(self.sockets)
        for ws in sockets:
            if not ws.send_text(event):
                self.remove_socket(ws)

    # ---- health/info/history ----
    def health_payload(self, authed):
        with self.lock:
            running = self.running
        payload = {
            "ok": True,
            "comfy": True,
            "gateway": GATEWAY_VERSION,
            "queue": {"running": running, "pending": 0},
            "device": DEVICE,
            "vram_total_gb": VRAM_TOTAL_GB,
        }
        return payload

    def history_payload(self, limit):
        with self.lock:
            items = list(self.history[:limit])
        return {"ok": True, "items": items}

    def history_item(self, pid):
        with self.lock:
            return self.prompts.get(pid)

    def clear_queue(self):
        with self.lock:
            self.running = 0

    # ---- 提交 / 模拟生成 ----
    def reset(self):
        """清空本次运行期间提交的记录，恢复 6 条预置图库（截图前保证确定性）。"""
        with self.lock:
            self.history = self._preset_history()
            self.prompts = {}
            self.prompt_counter = 0
            self.running = 0

    def submit(self, body):
        prompt = body.get("prompt") if isinstance(body, dict) else None
        if not isinstance(prompt, dict):
            return None
        with self.lock:
            self.prompt_counter += 1
            n = self.prompt_counter
        pid = "mock-%04d" % n
        filename = "anima_%05d_.png" % (100 + n)
        params = extract_params(prompt)
        item = {
            "prompt_id": pid,
            "status": "success",
            "images": [{"filename": filename, "subfolder": "", "type": "output"}],
            "created": int(time.time()),
            "params": params,
        }
        with self.lock:
            self.running += 1
        t = threading.Thread(target=self._simulate, args=(pid, item), daemon=True)
        t.start()
        return pid

    def _simulate(self, pid, item):
        """按 --sim-seconds 模拟 ComfyUI 事件流（默认 6 秒），完成后把记录放进 history。"""
        steps = item["params"].get("steps") or 30
        steps = max(1, min(int(steps), 60))
        interval = max(0.02, self.sim_seconds / steps)
        try:
            self.broadcast({"type": "status", "data": {"status": {"exec_info": {"queue_remaining": 1}}}})
            self.broadcast({"type": "execution_start", "data": {
                "prompt_id": pid, "timestamp": int(time.time() * 1000)}})
            self.broadcast({"type": "executing", "data": {"prompt_id": pid, "node": "7"}})
            time.sleep(0.15)
            for i in range(1, steps + 1):
                self.broadcast({"type": "progress", "data": {
                    "prompt_id": pid, "node": "7", "value": i, "max": steps}})
                time.sleep(interval)
            image = item["images"][0]
            self.broadcast({"type": "executed", "data": {
                "prompt_id": pid, "node": "9",
                "output": {"images": [image]}}})
            self.broadcast({"type": "executing", "data": {"prompt_id": pid, "node": None}})
            with self.lock:
                self.running = max(0, self.running - 1)
                self.prompts[pid] = item
                self.history.insert(0, item)
            self.broadcast({"type": "execution_success", "data": {"prompt_id": pid}})
            self.broadcast({"type": "status", "data": {"status": {"exec_info": {"queue_remaining": 0}}}})
        except Exception:  # noqa: BLE001 - 模拟线程不允许拖垮网关
            with self.lock:
                self.running = max(0, self.running - 1)


def extract_params(prompt):
    """从契约 §5 固定节点图里抽参数（与 colab/gateway.py 的 _history_params 对齐）。"""
    params = {
        "model": "", "positive": "", "negative": "",
        "seed": 0, "steps": 0, "cfg": 0.0,
        "sampler": "", "scheduler": "", "width": 0, "height": 0, "batch": 1,
    }
    try:
        unet = prompt.get("5", {}).get("inputs", {})
        params["model"] = unet.get("unet_name", "") or ""
        params["positive"] = prompt.get("3", {}).get("inputs", {}).get("text", "") or ""
        params["negative"] = prompt.get("4", {}).get("inputs", {}).get("text", "") or ""
        ks = prompt.get("7", {}).get("inputs", {})
        params["seed"] = ks.get("seed") or 0
        params["steps"] = ks.get("steps") or 0
        params["cfg"] = ks.get("cfg") or 0.0
        params["sampler"] = ks.get("sampler_name", "") or ""
        params["scheduler"] = ks.get("scheduler", "") or ""
        latent = prompt.get("6", {}).get("inputs", {})
        params["width"] = latent.get("width") or 0
        params["height"] = latent.get("height") or 0
        params["batch"] = latent.get("batch_size") or 1
    except (AttributeError, TypeError):
        pass
    return params


# ---------------------------------------------------------------- HTTP handler

def _json_bytes(obj):
    return json.dumps(obj, ensure_ascii=False).encode("utf-8")


class MockHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "anima-mock/0.1"

    # ---- 基础工具 ----
    @property
    def state(self):
        return self.server.mock_state  # type: ignore[attr-defined]

    def log_message(self, fmt, *args):  # noqa: D401 - 静默/详细日志由 --verbose 控制
        if getattr(self.server, "verbose", False):  # type: ignore[attr-defined]
            sys.stderr.write("[mock-gw] %s - %s\n" % (self.address_string(), fmt % args))

    def _authed(self):
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return False
        return bool(header[7:].strip())

    def _token(self):
        header = self.headers.get("Authorization", "")
        return header[7:].strip() if header.startswith("Bearer ") else ""

    def _send_json(self, status, obj):
        body = _json_bytes(obj)
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _err(self, status, error):
        self._send_json(status, {"ok": False, "error": error})

    def _query(self):
        return dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(self.path).query, keep_blank_values=True))

    def _read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length > 0 else b""

    # ---- GET ----
    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        query = self._query()

        if path == "/ws":
            self._handle_ws()
            return
        if path in ("/", "/index.html"):
            self._send_json(200, {
                "ok": True, "mock": True, "gateway": GATEWAY_VERSION,
                "device": DEVICE, "vram_total_gb": VRAM_TOTAL_GB,
                "endpoints": ["/anima/v1/health", "/anima/v1/info", "/anima/v1/history",
                              "/view", "/prompt", "/ws"],
            })
            return
        if path == "/anima/v1/health":
            self._send_json(200, self.state.health_payload(self._authed()))
            return

        # 其余端点要求任意非空 Bearer token
        if not self._authed():
            self._err(401, "unauthorized")
            return

        if path == "/anima/v1/info":
            self._send_json(200, INFO_PAYLOAD)
        elif path == "/anima/v1/queue":
            with self.state.lock:
                running = self.state.running
            self._send_json(200, {"ok": True,
                                  "running": ["mock-running"] if running else [],
                                  "pending": []})
        elif path == "/anima/v1/history":
            if self._token() == "empty":
                self._send_json(200, {"ok": True, "items": []})
                return
            try:
                limit = max(1, min(200, int(query.get("limit", "50"))))
            except ValueError:
                limit = 50
            self._send_json(200, self.state.history_payload(limit))
        elif path.startswith("/anima/v1/history/"):
            pid = urllib.parse.unquote(path[len("/anima/v1/history/"):])
            item = self.state.history_item(pid)
            if item is None:
                self._err(404, "not_found")
            else:
                self._send_json(200, item)
        elif path == "/view":
            self._handle_view(query)
        else:
            self._err(404, "not_found")

    def _handle_view(self, query):
        filename = query.get("filename", "")
        if not filename:
            self._err(404, "not_found")
            return
        if filename.startswith("_fallback.png") or not self.state.fixtures:
            data = self.state.fixture_bytes(filename)
        else:
            data = self.state.fixture_bytes(filename)
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    # ---- POST ----
    def do_POST(self):
        path = urllib.parse.urlsplit(self.path).path
        # 截图流水线专用：恢复预置状态（无需 token，仅本机调试用）
        if path == "/__mock__/reset":
            self.state.reset()
            self._send_json(200, {"ok": True, "items": len(self.state.history)})
            return
        if not self._authed():
            self._err(401, "unauthorized")
            return
        raw = self._read_body()
        if path in ("/prompt", "/anima/v1/prompt"):
            try:
                body = json.loads(raw.decode("utf-8") or "{}")
            except (UnicodeDecodeError, json.JSONDecodeError):
                self._err(400, "invalid_json")
                return
            pid = self.state.submit(body)
            if pid is None:
                self._err(400, "invalid_prompt")
                return
            self._send_json(200, {"prompt_id": pid, "number": 1, "node_errors": {}})
        elif path == "/anima/v1/queue/clear":
            self.state.clear_queue()
            self._send_json(200, {"ok": True})
        elif path == "/anima/v1/interrupt":
            self.state.clear_queue()
            self._send_json(200, {"ok": True})
        else:
            self._err(404, "not_found")

    # ---- WebSocket upgrade ----
    def _handle_ws(self):
        if not self._authed():
            self._err(401, "unauthorized")
            return
        key = self.headers.get("Sec-WebSocket-Key", "")
        if not key:
            self._err(400, "missing_sec_websocket_key")
            return
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        # 手动写 101 响应（BaseHTTPRequestHandler 的 send_response 会带 Server 头，无妨）
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.wfile.flush()
        ws = MockWebSocket(self.rfile, self.wfile)
        self.state.add_socket(ws)
        # ComfyUI 连上后先发一条 status（App 用它更新队列）
        ws.send_text({"type": "status", "data": {"status": {"exec_info": {"queue_remaining": 0}}}})
        try:
            ws.read_loop()
        finally:
            self.state.remove_socket(ws)
            self.close_connection = True


# ---------------------------------------------------------------- main

def main(argv=None):
    parser = argparse.ArgumentParser(description="Anima mock gateway for device screenshots (stdlib only)")
    parser.add_argument("--host", default="127.0.0.1", help="监听地址（默认 127.0.0.1，配合 adb reverse）")
    parser.add_argument("--port", type=int, default=8091, help="监听端口（默认 8091）")
    parser.add_argument("--fixtures", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                           "..", "outputs"),
                        help="真实 PNG 目录（默认仓库 outputs/）")
    parser.add_argument("--verbose", action="store_true", help="打印每个请求")
    parser.add_argument("--sim-seconds", type=float, default=6.0,
                        help="模拟生成耗时秒数（默认 6，便于截「生成中」进度卡）")
    args = parser.parse_args(argv)

    fixtures = load_fixtures(os.path.abspath(args.fixtures))
    state = MockState(fixtures, sim_seconds=args.sim_seconds)
    server = ThreadingHTTPServer((args.host, args.port), MockHandler)
    server.daemon_threads = True
    server.mock_state = state  # type: ignore[attr-defined]
    server.verbose = args.verbose  # type: ignore[attr-defined]

    print("anima-mock-gateway v%s listening on http://%s:%d (fixtures=%d, token=any non-empty)"
          % (GATEWAY_VERSION, args.host, args.port, len(fixtures)), flush=True)
    print("  adb reverse tcp:%d tcp:%d  →  App base_url=http://127.0.0.1:%d token=devtoken"
          % (args.port, args.port, args.port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
