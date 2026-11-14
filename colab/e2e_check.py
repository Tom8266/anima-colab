#!/usr/bin/env python3
"""Contract §8 acceptance check for the Anima Colab server (PC side).

Runs the whole first acceptance stage through the public Cloudflare tunnel:

    health -> info -> POST /prompt -> WS events -> /history -> /view (PNG) -> errors

Usage:
    python3 colab/e2e_check.py --pair-file /tmp/anima_pair.txt
    python3 colab/e2e_check.py --pair 'https://xxx.trycloudflare.com|token'
    python3 colab/e2e_check.py --url https://xxx.trycloudflare.com --token xxx
    python3 colab/e2e_check.py --pair-file /tmp/anima_pair.txt --proxy http://127.0.0.1:7890

Environment: needs `requests` and `websockets` (pip install requests websockets).
Downloaded PNG lands in outputs/e2e_<timestamp>.png (gitignored) and a JSON summary
is printed at the end (also written next to the PNG).
"""
import argparse
import asyncio
import json
import os
import sys
import time
import uuid
from pathlib import Path

import requests

try:
    import websockets
except ImportError:  # pragma: no cover
    websockets = None

GRAPH = {
    "1": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen_3_06b_base.safetensors",
                                                 "type": "stable_diffusion", "device": "default"}},
    "2": {"class_type": "VAELoader", "inputs": {"vae_name": "qwen_image_vae.safetensors"}},
    "3": {"class_type": "CLIPTextEncode",
          "inputs": {"text": "masterpiece, best quality, safe, 1girl, solo, long silver hair, "
                             "looking at viewer, simple background", "clip": ["1", 0]}},
    "4": {"class_type": "CLIPTextEncode",
          "inputs": {"text": "worst quality, low quality, blurry, jpeg artifacts, text, "
                             "watermark, signature, score_1, score_2, score_3", "clip": ["1", 0]}},
    "5": {"class_type": "UNETLoader", "inputs": {"unet_name": "anima-base-v1.0.safetensors",
                                                 "weight_dtype": "default"}},
    "6": {"class_type": "EmptyLatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
    "7": {"class_type": "KSampler", "inputs": {"seed": 424242, "steps": 30, "cfg": 4.5,
                                               "sampler_name": "er_sde", "scheduler": "simple",
                                               "denoise": 1.0, "model": ["5", 0],
                                               "positive": ["3", 0], "negative": ["4", 0],
                                               "latent_image": ["6", 0]}},
    "8": {"class_type": "VAEDecode", "inputs": {"samples": ["7", 0], "vae": ["2", 0]}},
    "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "anima-e2e", "images": ["8", 0]}},
}

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
results = {}


def record(name, ok, detail=None):
    results[name] = {"ok": bool(ok), "detail": detail}
    print("  [%s] %s%s" % ("PASS" if ok else "FAIL", name,
                          "" if detail is None else " -> " + str(detail)), flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Anima Colab server §8 acceptance check")
    parser.add_argument("--pair", help="'PAIR: url|token' line (prefix optional)")
    parser.add_argument("--pair-file", help="file containing the PAIR line")
    parser.add_argument("--url")
    parser.add_argument("--token")
    parser.add_argument("--proxy", default=None, help="HTTP(S) proxy URL, e.g. http://127.0.0.1:7890")
    parser.add_argument("--out-dir", default="outputs")
    parser.add_argument("--ws-timeout", type=float, default=300.0)
    parser.add_argument("--seed", type=int, default=424242)
    args = parser.parse_args(argv)

    url, token = args.url, args.token
    pair_line = args.pair or ""
    if args.pair_file:
        pair_line = Path(args.pair_file).read_text(encoding="utf-8")
    if pair_line:
        clean = pair_line.strip()
        if clean.lower().startswith("pair:"):
            clean = clean.split(":", 1)[1].strip()
        if "|" in clean:
            url, token = clean.split("|", 1)
    if not url or not token:
        parser.error("need --pair/--pair-file or --url + --token")
    url = url.rstrip("/")
    token = token.strip()

    session = requests.Session()
    proxies = {"http": args.proxy, "https": args.proxy} if args.proxy else None
    headers = {"Authorization": "Bearer " + token}
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    summary = {"url": url, "started": stamp, "proxy": args.proxy}
    started = time.time()

    def get(path, expect=200, **kwargs):
        return session.get(url + path, headers=headers, proxies=proxies,
                           timeout=kwargs.pop("timeout", 30), **kwargs)

    # ---- 1. health without token -----------------------------------------
    print("== 1. GET /anima/v1/health (no token)")
    started_health = time.time()
    resp = session.get(url + "/anima/v1/health", proxies=proxies, timeout=30)
    health_public = resp.json() if resp.status_code == 200 else {}
    record("health_no_token", resp.status_code == 200 and health_public.get("ok") is True,
           "HTTP %s comfy=%s %.2fs" % (resp.status_code, health_public.get("comfy"),
                                      time.time() - started_health))
    summary["health_public"] = {"status": resp.status_code, "body": health_public}

    # ---- 2. health with token --------------------------------------------
    print("== 2. GET /anima/v1/health (Bearer token)")
    resp = get("/anima/v1/health")
    health = resp.json() if resp.status_code == 200 else {}
    record("health_token", resp.status_code == 200 and health.get("comfy") is True,
           "device=%r vram=%s queue=%s" % (health.get("device"), health.get("vram_total_gb"),
                                          health.get("queue")))
    summary["health"] = health
    if not health.get("comfy"):
        print("!! ComfyUI not ready, aborting generation checks")
        return finish(summary, out_dir, stamp, started)

    # ---- 3. info ----------------------------------------------------------
    print("== 3. GET /anima/v1/info")
    resp = get("/anima/v1/info", timeout=60)
    info = resp.json() if resp.status_code == 200 else {}
    unet_names = [u.get("name") for u in info.get("unets", [])]
    record("info", resp.status_code == 200 and "anima-base-v1.0.safetensors" in unet_names,
           "unets=%s defaults=%s" % (unet_names, info.get("defaults")))
    sampler_ok = info.get("samplers") and "er_sde" in info.get("samplers")
    record("info_samplers", bool(sampler_ok), info.get("samplers"))
    record("info_extra", "device" in info and "vram_total_gb" in info,
           "device=%r vram=%s" % (info.get("device"), info.get("vram_total_gb")))
    summary["info"] = info

    # ---- 4. queue ---------------------------------------------------------
    print("== 4. GET /anima/v1/queue")
    resp = get("/anima/v1/queue")
    queue = resp.json() if resp.status_code == 200 else {}
    record("queue", resp.status_code == 200 and queue.get("ok") is True,
           "running=%s pending=%s" % (queue.get("running"), queue.get("pending")))
    summary["queue"] = queue

    # ---- 5. POST /prompt --------------------------------------------------
    print("== 5. POST /prompt (base, 30 steps, CFG 4.5, 1024x1024)")
    client_id = str(uuid.uuid4())
    graph = json.loads(json.dumps(GRAPH))
    graph["7"]["inputs"]["seed"] = args.seed
    payload = {"prompt": graph, "client_id": client_id}
    submit_at = time.time()
    resp = session.post(url + "/prompt", json=payload, headers=headers, proxies=proxies, timeout=60)
    prompt_resp = resp.json() if resp.headers.get("Content-Type", "").startswith("application/json") else {}
    prompt_id = prompt_resp.get("prompt_id")
    record("prompt", resp.status_code == 200 and bool(prompt_id),
           "HTTP %s prompt_id=%s node_errors=%s"
           % (resp.status_code, prompt_id, prompt_resp.get("node_errors")))
    summary["prompt_response"] = {"status": resp.status_code, "body": prompt_resp}
    if not prompt_id:
        return finish(summary, out_dir, stamp, started)

    # ---- 6. WebSocket events ---------------------------------------------
    print("== 6. WS /ws?clientId=... (expect progress/executed events)")
    ws_events = run_ws(args, url, token, client_id, prompt_id)
    summary["ws"] = ws_events
    record("ws_progress", ws_events.get("progress_count", 0) > 0, "progress frames=%s" %
           ws_events.get("progress_count"))
    record("ws_executed", bool(ws_events.get("executed_images")),
           ws_events.get("executed_images"))
    record("ws_execution_success", ws_events.get("execution_success") is True,
           "types=%s" % ws_events.get("types"))
    if not prompt_id:
        return finish(summary, out_dir, stamp, started)

    # ---- 7. history polling ----------------------------------------------
    print("== 7. poll GET /anima/v1/history?limit=10 for this prompt_id")
    item = None
    deadline = time.time() + 180
    poll_started = time.time()
    while time.time() < deadline:
        resp = get("/anima/v1/history?limit=10")
        if resp.status_code == 200:
            for candidate in resp.json().get("items", []):
                if candidate.get("prompt_id") == prompt_id:
                    item = candidate
                    break
        if item:
            break
        time.sleep(2)
    record("history_item", item is not None,
           "%.1fs since submit" % (time.time() - poll_started) if item else "timeout")
    summary["history_item"] = item
    params = (item or {}).get("params") or {}
    expected = {"model": "anima-base-v1.0.safetensors", "steps": 30, "cfg": 4.5,
                "sampler": "er_sde", "scheduler": "simple", "width": 1024, "height": 1024,
                "seed": args.seed}
    mismatched = {k: (params.get(k), v) for k, v in expected.items() if params.get(k) != v}
    record("history_params", not mismatched, mismatched or "exact match, batch=%s"
           % (params.get("batch"),))

    # ---- 8. history/{prompt_id} ------------------------------------------
    print("== 8. GET /anima/v1/history/{prompt_id}")
    resp = get("/anima/v1/history/" + prompt_id)
    single = resp.json() if resp.status_code == 200 else {}
    record("history_single", resp.status_code == 200 and single.get("prompt_id") == prompt_id,
           "HTTP %s" % resp.status_code)

    # ---- 9. /view download ------------------------------------------------
    print("== 9. GET /view (download PNG)")
    images = (item or {}).get("images") or []
    if images:
        image = images[0]
        resp = get("/view", params={"filename": image["filename"],
                                    "subfolder": image.get("subfolder", ""),
                                    "type": image.get("type", "output")}, timeout=120)
        png = resp.content
        record("view_status", resp.status_code == 200,
               "HTTP %s content-type=%s bytes=%d" %
               (resp.status_code, resp.headers.get("Content-Type"), len(png)))
        record("view_png_magic", png.startswith(PNG_MAGIC), png[:8].hex())
        target = out_dir / ("e2e_%s_%s" % (stamp, image["filename"]))
        target.write_bytes(png)
        summary["download"] = {"path": str(target.resolve()), "bytes": len(png),
                               "content_type": resp.headers.get("Content-Type")}
        record("view_saved", True, str(target))
    else:
        record("view_status", False, "no images in history item")

    # ---- 10. error contract ----------------------------------------------
    print("== 10. error contract (401 / 404 / bad payload)")
    resp = session.get(url + "/anima/v1/info", proxies=proxies, timeout=30)
    body = resp.json() if resp.status_code == 401 else {}
    record("error_401", resp.status_code == 401 and body.get("error") == "unauthorized",
           "HTTP %s %s" % (resp.status_code, body))
    resp = get("/anima/v1/history/does-not-exist")
    body = resp.json() if resp.status_code == 404 else {}
    record("error_404", resp.status_code == 404 and body.get("error") == "not_found",
           "HTTP %s %s" % (resp.status_code, body))
    resp = session.get(url + "/anima/v1/nope", headers=headers, proxies=proxies, timeout=30)
    record("unknown_route", resp.status_code == 404, "HTTP %s" % resp.status_code)

    summary["elapsed_seconds"] = round(time.time() - started, 1)
    return finish(summary, out_dir, stamp, started)


def run_ws(args, url, token, client_id, prompt_id):
    """Connect to WS /ws and collect ComfyUI events until execution ends."""
    if websockets is None:
        return {"error": "websockets not installed"}
    ws_url = url.replace("https://", "wss://").replace("http://", "ws://")
    ws_url += "/ws?clientId=" + client_id
    headers = {"Authorization": "Bearer " + token}
    if args.proxy:
        os.environ.setdefault("HTTPS_PROXY", args.proxy)
        os.environ.setdefault("HTTP_PROXY", args.proxy)
    proxy_arg = args.proxy if args.proxy else None
    events = {"types": [], "progress_count": 0, "executed_images": [],
              "execution_success": False, "error": None}

    async def collect():
        async with websockets.connect(
            ws_url, additional_headers=headers, proxy=proxy_arg,
            open_timeout=30, ping_interval=20, max_size=None,
        ) as ws:
            while True:
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=args.ws_timeout)
                except asyncio.TimeoutError:
                    events["error"] = "ws timeout"
                    return
                try:
                    message = json.loads(raw)
                except (TypeError, ValueError):
                    continue
                message_type = message.get("type")
                data = message.get("data") or {}
                if message_type not in events["types"]:
                    events["types"].append(message_type)
                print("  ws <- %-18s %s" % (message_type, _short(data)), flush=True)
                if message_type == "progress":
                    events["progress_count"] += 1
                elif message_type == "executed":
                    images = (data.get("output") or {}).get("images") or []
                    events["executed_images"].extend(images)
                elif message_type == "execution_success":
                    events["execution_success"] = True
                    return
                elif message_type == "execution_error":
                    events["error"] = data.get("exception_message", "execution_error")
                    return
                elif message_type == "executing" and data.get("node") is None:
                    # ComfyUI sends executing(node=null) when the queue item ends;
                    # execution_success normally arrives before it, but don't hang.
                    return

    try:
        asyncio.run(asyncio.wait_for(collect(), timeout=args.ws_timeout + 60))
    except Exception as exc:  # noqa: BLE001 - report any WS failure as evidence
        events["error"] = repr(exc)
    return events


def _short(data):
    text = json.dumps(data, ensure_ascii=False)
    return text if len(text) <= 110 else text[:107] + "..."


def finish(summary, out_dir, stamp, started):
    summary["elapsed_seconds"] = round(time.time() - started, 1)
    failures = [name for name, value in results.items() if not value["ok"]]
    summary["results"] = results
    summary["failures"] = failures
    target = out_dir / ("e2e_%s_result.json" % stamp)
    target.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n== summary: %d checks, %d failed -> %s"
          % (len(results), len(failures), ", ".join(failures) if failures else "ALL PASS"))
    print("evidence json: %s" % target.resolve())
    if summary.get("download"):
        print("image: %s (%d bytes)" % (summary["download"]["path"], summary["download"]["bytes"]))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
