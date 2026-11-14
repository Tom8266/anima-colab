#!/usr/bin/env python3
"""对 Anima Colab 网关做端到端验收（PC 侧，接口契约 §8 第 1 段）。

用法：
  python3 scripts/e2e_check.py 'PAIR: https://xxxx.trycloudflare.com|<token>'
  python3 scripts/e2e_check.py 'https://xxxx.trycloudflare.com|token' --steps 12 --out outputs

检查项：health → info → WS 连接 → POST /prompt（base 默认 30 步 CFG4.5 1024²）
→ 轮询 history → /view 下载 PNG 校验 magic → history 参数解析 → 打印耗时。
"""
import argparse
import json
import os
import re
import sys
import threading
import time

import requests

try:
    from websockets.sync.client import connect as ws_connect
    HAS_WS = True
except Exception:  # noqa: BLE001
    HAS_WS = False


def parse_pair(raw: str):
    raw = raw.strip()
    if raw.startswith("PAIR:"):
        raw = raw[5:].strip()
    m = re.search(r"https?://[^\s|]+", raw)
    if not m:
        raise SystemExit("找不到 URL")
    url = m.group(0).rstrip("/")
    rest = raw.replace(m.group(0), "")
    token = rest.split("|")[-1].strip() if "|" in rest else ""
    if not token:
        raise SystemExit("找不到 token（应为 url|token）")
    return url, token


def build_graph(prompt, negative, model, te, vae, steps, cfg, width, height, seed, prefix):
    return {
        "1": {"class_type": "CLIPLoader", "inputs": {"clip_name": te, "type": "stable_diffusion", "device": "default"}},
        "2": {"class_type": "VAELoader", "inputs": {"vae_name": vae}},
        "3": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["1", 0]}},
        "4": {"class_type": "CLIPTextEncode", "inputs": {"text": negative, "clip": ["1", 0]}},
        "5": {"class_type": "UNETLoader", "inputs": {"unet_name": model, "weight_dtype": "default"}},
        "6": {"class_type": "EmptyLatentImage", "inputs": {"width": width, "height": height, "batch_size": 1}},
        "7": {"class_type": "KSampler", "inputs": {
            "seed": seed, "steps": steps, "cfg": cfg, "sampler_name": "er_sde", "scheduler": "simple",
            "denoise": 1.0, "model": ["5", 0], "positive": ["3", 0], "negative": ["4", 0], "latent_image": ["6", 0]}},
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["7", 0], "vae": ["2", 0]}},
        "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": prefix, "images": ["8", 0]}},
    }


class WsCollector(threading.Thread):
    def __init__(self, base_url, token, client_id):
        super().__init__(daemon=True)
        self.base_url = base_url
        self.token = token
        self.client_id = client_id
        self.events = []
        self.error = None
        self.stop = threading.Event()

    def run(self):
        ws_url = self.base_url.replace("https://", "wss://").replace("http://", "ws://")
        ws_url += f"/ws?clientId={self.client_id}"
        headers = {"Authorization": f"Bearer {self.token}"}
        try:
            with ws_connect(ws_url, additional_headers=headers, open_timeout=20, close_timeout=2) as ws:
                while not self.stop.is_set():
                    try:
                        msg = ws.recv(timeout=1)
                    except Exception:  # noqa: BLE001
                        continue
                    if isinstance(msg, str):
                        try:
                            self.events.append(json.loads(msg))
                        except json.JSONDecodeError:
                            pass
        except Exception as e:  # noqa: BLE001
            self.error = str(e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pair", help="PAIR: url|token 或 url|token")
    ap.add_argument("--prompt", default="masterpiece, best quality, safe, 1girl, solo, long silver hair, "
                                        "looking at viewer, upper body, classroom, soft lighting")
    ap.add_argument("--negative", default="worst quality, low quality, blurry, text, watermark, signature, "
                                          "score_1, score_2, score_3")
    ap.add_argument("--steps", type=int, default=30)
    ap.add_argument("--cfg", type=float, default=4.5)
    ap.add_argument("--size", type=int, nargs=2, default=[1024, 1024])
    ap.add_argument("--seed", type=int, default=20261114)
    ap.add_argument("--timeout", type=int, default=600)
    ap.add_argument("--out", default="outputs")
    args = ap.parse_args()

    base, token = parse_pair(args.pair)
    session = requests.Session()
    session.headers["Authorization"] = f"Bearer {token}"
    print(f"网关: {base}")

    # 1. health / info
    t0 = time.time()
    h = session.get(f"{base}/anima/v1/health", timeout=30).json()
    print("health:", json.dumps(h, ensure_ascii=False))
    if not h.get("comfy"):
        print("!! ComfyUI 未就绪（comfy=false），先等部署脚本跑完", file=sys.stderr)
        return 2
    info = session.get(f"{base}/anima/v1/info", timeout=30).json()
    print("info:", json.dumps({k: info.get(k) for k in ("unets", "vaes", "text_encoders", "samplers", "schedulers", "device", "vram_total_gb", "defaults")}, ensure_ascii=False))
    defaults = info.get("defaults") or {}
    unets = [u.get("name") for u in info.get("unets", []) if isinstance(u, dict)]
    model = defaults.get("unet") or (unets[0] if unets else None)
    te = defaults.get("text_encoder") or (info.get("text_encoders") or [{}])[0].get("name")
    vae = defaults.get("vae") or (info.get("vaes") or [{}])[0].get("name")
    if not (model and te and vae):
        print("!! info 缺少默认模型/TE/VAE", file=sys.stderr)
        return 2
    print(f"使用模型: {model} / {te} / {vae}")

    # 2. WebSocket
    client_id = f"e2e-{int(time.time())}"
    ws_thread = None
    if HAS_WS:
        ws_thread = WsCollector(base, token, client_id)
        ws_thread.start()
        time.sleep(1.5)
        if ws_thread.error:
            print(f"!! WebSocket 连接失败（继续用轮询兜底）: {ws_thread.error}")
    else:
        print("（本地没有 websockets 库，跳过 WS 检查）")

    # 3. 提交生成
    graph = build_graph(args.prompt, args.negative, model, te, vae, args.steps, args.cfg,
                        args.size[0], args.size[1], args.seed, "e2echeck")
    ts = time.time()
    resp = session.post(f"{base}/prompt", json={"prompt": graph, "client_id": client_id}, timeout=60)
    print(f"POST /prompt -> {resp.status_code}", flush=True)
    if resp.status_code != 200:
        print(resp.text[:1000], file=sys.stderr)
        return 1
    prompt_id = resp.json().get("prompt_id")
    print(f"prompt_id = {prompt_id}，开始等待出图（预计 {args.steps * 2.8 + 10:.0f}s 内）…")

    # 4. 轮询 history
    item = None
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        time.sleep(2)
        r = session.get(f"{base}/anima/v1/history/{prompt_id}", timeout=30)
        if r.status_code == 200:
            item = r.json()
            if item.get("images"):
                break
        if ws_thread and ws_thread.is_alive() and [e for e in ws_thread.events if e.get("type") == "execution_error"]:
            print("!! 执行错误:", json.dumps([e for e in ws_thread.events if e.get("type") == "execution_error"][0], ensure_ascii=False)[:1500])
            return 1
    elapsed = time.time() - ts
    if not item or not item.get("images"):
        print("!! 等待超时", file=sys.stderr)
        return 1
    print(f"完成，耗时 {elapsed:.1f}s")
    print("history params:", json.dumps(item.get("params"), ensure_ascii=False))

    # 5. 下载图片
    img = item["images"][0]
    q = urllib_parse(img)
    r = session.get(f"{base}/view?{q}", timeout=60)
    print(f"GET /view -> {r.status_code}, {len(r.content)} bytes, {r.headers.get('content-type')}")
    if r.status_code != 200 or not r.content.startswith(b"\x89PNG"):
        print("!! 图片不是有效 PNG", file=sys.stderr)
        return 1
    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, f"e2e_{int(ts)}.png")
    with open(path, "wb") as f:
        f.write(r.content)
    print(f"图片已保存: {path}")

    # 6. WS 事件检查
    if ws_thread:
        ws_thread.stop.set()
        ws_thread.join(timeout=3)
        types = [e.get("type") for e in ws_thread.events]
        print("WS 事件类型:", sorted(set(types)))
        if "progress" not in types:
            print("!! 没有收到 progress 事件（App 进度条会退化为轮询）", file=sys.stderr)
            return 1
        if "executed" not in types and "execution_success" not in types:
            print("!! 没有收到 executed/execution_success 事件", file=sys.stderr)
            return 1
    print("\nE2E OK ✅")
    return 0


def urllib_parse(img):
    from urllib.parse import urlencode
    return urlencode({
        "filename": img.get("filename", ""),
        "subfolder": img.get("subfolder", ""),
        "type": img.get("type", "output"),
    })


if __name__ == "__main__":
    sys.exit(main())
