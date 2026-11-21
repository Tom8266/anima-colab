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
