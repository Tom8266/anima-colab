#!/usr/bin/env python3
"""Reference implementation of the Anima App "one-key" Colab flow (PC, Python).

This mirrors what the Android App will do, so the Kotlin implementation can be
written against a working, documented chain:

    1. refresh a Google OAuth access token      (colab-cli refresh_token)
    2. GET  /tun/m/assignments                  -> reuse an existing T4 session
    3. GET/POST /tun/m/assign                   -> assign a T4 when none exists
    4. POST <runtimeProxyInfo.url>/api/kernels  -> create a python3 kernel
    5. WS   /api/kernels/<id>/channels          -> Jupyter default protocol
    6. execute_request with colab/deploy_script.py source
    7. stream iopub stdout, regex the "PAIR:" line, print it

Nothing from google-colab-cli is imported: only its OAuth token file
(~/.config/colab-cli/token.json) and the public installed-app OAuth client are
read, exactly like the App will ship them.

Usage:
    # local validation box: Google domains need the local proxy
    python3 colab/phone_bootstrap.py --proxy http://127.0.0.1:7890

    # App-like environment (direct network, no proxy):
    python3 colab/phone_bootstrap.py

    # debug: run arbitrary code through the same kernel channel
    python3 colab/phone_bootstrap.py --exec-code 'print("hello from kernel")'

Requires: requests, websockets (pip install requests websockets).
Exit code 0 only when the deployment finished and a PAIR line was found.
"""
import argparse
import asyncio
import json
import os
import re
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests

try:
    import websockets
except ImportError:  # pragma: no cover
    websockets = None

# ---------------------------------------------------------------------------
# Constants (same values google-colab-cli 0.7.5 uses)
# ---------------------------------------------------------------------------
COLAB_DOMAIN = "https://colab.research.google.com"
OAUTH_TOKEN_URI = "https://oauth2.googleapis.com/token"
XSSI_PREFIX = ")]}'\n"
TOKEN_PATH = Path(os.path.expanduser("~/.config/colab-cli/token.json"))
DEFAULT_CLIENT = {
    # installed-app OAuth client shipped by google-colab-cli (public, not a secret)
    "client_id": "764086051850-6qr4p6gpi6hn506pt8ejuq83di341hur.apps.googleusercontent.com",
    "client_secret": "d-FL95Q19q7MQmFpd7hHD0Ty",
}
PUBLIC_SCOPES = [
    "openid",
    "https://www.googleapis.com/auth/userinfo.profile",
    "https://www.googleapis.com/auth/userinfo.email",
    "https://www.googleapis.com/auth/cloud-platform",
    "https://www.googleapis.com/auth/colaboratory",
    "https://www.googleapis.com/auth/drive.file",
]
PAIR_RE = re.compile(r"PAIR:\s*(https://[A-Za-z0-9.\-]+)\|([A-Za-z0-9_\-]+)")
HERE = Path(__file__).resolve().parent
DEFAULT_DEPLOY_SCRIPT = HERE / "deploy_script.py"


# ---------------------------------------------------------------------------
# small helpers
# ---------------------------------------------------------------------------
def log(message):
    print("[bootstrap] %s %s" % (time.strftime("%H:%M:%S"), message), flush=True)


def now_utc():
    return datetime.now(timezone.utc)


def iso_now():
    return now_utc().strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def is_expired(expiry, margin_seconds=120):
    if not expiry:
        return True
    try:
        parsed = datetime.strptime(expiry, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
    except ValueError:
        return True
    return parsed - now_utc() <= timedelta(seconds=margin_seconds)


class Http:
    """requests wrapper: direct first, optional proxy fallback for Google hosts."""

    def __init__(self, proxy=None):
        self.proxy = proxy
        self.proxies = {"http": proxy, "https": proxy} if proxy else None
        self.session = requests.Session()

    def request(self, method, url, *, headers=None, params=None, json_body=None,
                timeout=60):
        # With --proxy we go through the proxy first: on restricted networks a
        # direct connect can take >100s before failing. Without --proxy (App
        # environment) we always go direct.
        attempts = ([self.proxies, None] if self.proxies else [None])
        last_error = None
        for index, proxies in enumerate(attempts):
            try:
                return self.session.request(
                    method, url, headers=headers, params=params, json=json_body,
                    timeout=timeout, proxies=proxies,
                )
            except (requests.ConnectionError, requests.Timeout) as exc:
                last_error = exc
                if index + 1 < len(attempts):
                    log("request %s %s failed (%s), retrying via proxy" % (method, url, exc))
        raise last_error


def load_oauth_client():
    candidates = [
        Path(os.path.expanduser("~/.colab-cli-oauth-config.json")),
    ]
    candidates += sorted(
        Path(sys.prefix).glob("**/colab_cli/oauth_config.json"),
        key=lambda item: len(str(item)),
    )
    candidates += sorted(
        Path(os.path.expanduser("~/.local/share/uv/tools")).glob(
            "**/colab_cli/oauth_config.json"
        ),
        key=lambda item: len(str(item)),
    )
    for candidate in candidates:
        try:
            if candidate.is_file():
                data = json.loads(candidate.read_text(encoding="utf-8"))
                installed = data.get("installed") or data.get("web")
                if installed and installed.get("client_id"):
                    return installed
        except (OSError, ValueError):
            continue
    return dict(DEFAULT_CLIENT)


def load_token(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except OSError as exc:
        raise RuntimeError("OAuth token file not found at %s: %s" % (path, exc))


def save_token(path, token_data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".bootstrap.tmp")
    tmp.write_text(json.dumps(token_data, indent=2), encoding="utf-8")
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def refresh_access_token(http, client, token_data, token_path):
    """Exchange the stored refresh_token for a fresh access token."""
    started = time.time()
    payload = {
        "client_id": client["client_id"],
        "client_secret": client["client_secret"],
        "grant_type": "refresh_token",
        "refresh_token": token_data["refresh_token"],
    }
    try:
        response = http.session.post(
            OAUTH_TOKEN_URI, data=payload, timeout=60,
            proxies=http.proxies if http.proxies else None,
        )
    except requests.ConnectionError as exc:
        raise RuntimeError(
            "cannot reach %s directly (%s). If this machine needs a proxy, pass "
            "--proxy http://127.0.0.1:7890 ." % (OAUTH_TOKEN_URI, exc)
        )
    if response.status_code != 200:
        raise RuntimeError(
            "OAuth token refresh failed: HTTP %s %s"
            % (response.status_code, response.text[:500])
        )
    body = response.json()
    token_data["token"] = body["access_token"]
    expires_in = int(body.get("expires_in", 3600))
    token_data["expiry"] = (now_utc() + timedelta(seconds=expires_in)).strftime(
        "%Y-%m-%dT%H:%M:%S.%fZ"
    )
    if body.get("refresh_token"):
        token_data["refresh_token"] = body["refresh_token"]
    save_token(token_path, token_data)
    log("access token refreshed (expires in %ds, %.0f ms)"
        % (expires_in, (time.time() - started) * 1000))
    return token_data["token"]


# ---------------------------------------------------------------------------
# Colab control-plane calls
# ---------------------------------------------------------------------------
def colab_headers(access_token):
    return {
        "Authorization": "Bearer " + access_token,
        "Accept": "application/json",
        "X-Colab-Client-Agent": "colab-cli",
    }


def colab_request(http, access_token, method, path, *, params=None, extra_headers=None,
                  timeout=60, expect=200):
    url = COLAB_DOMAIN + path
    headers = colab_headers(access_token)
    if extra_headers:
        headers.update(extra_headers)
    full_params = dict(params or {})
    full_params.setdefault("authuser", "0")
    response = http.request(method, url, headers=headers, params=full_params,
                            timeout=timeout)
    if response.status_code != expect:
        raise RuntimeError(
            "Colab %s %s failed: HTTP %s %s"
            % (method, path, response.status_code, response.text[:800])
        )
    text = response.text
    if text.startswith(XSSI_PREFIX):
        text = text[len(XSSI_PREFIX):]
    return json.loads(text) if text.strip() else {}


def uuid_to_nbh():
    value = str(uuid.uuid4())
    return value.replace("-", "_") + "." * (44 - len(value))


def list_assignments(http, access_token):
    data = colab_request(http, access_token, "GET", "/tun/m/assignments")
    return data.get("assignments") or []


def pick_t4(assignments):
    for assignment in assignments:
        if str(assignment.get("accelerator", "")).upper() == "T4":
            return assignment
    return None


def assign_t4(http, access_token, retries=3, backoff=10.0):
    """GET /tun/m/assign then POST with the returned XSRF token (CLI protocol)."""
    nbh = uuid_to_nbh()
    params = {"nbh": nbh, "variant": "GPU", "accelerator": "T4", "authuser": "0"}
    for attempt in range(retries + 1):
        try:
            first = colab_request(http, access_token, "GET", "/tun/m/assign",
                                  params=params, timeout=180)
            if "acc" in first and "token" in first:
                second = colab_request(
                    http, access_token, "POST", "/tun/m/assign", params=params,
                    extra_headers={"X-Goog-Colab-Token": first["token"]},
                    timeout=300, expect=200,
                )
                return second
            # already assigned: {"accelerator","endpoint","runtimeProxyInfo",...}
            return first
        except requests.ReadTimeout as exc:
            if attempt >= retries:
                raise
            delay = backoff * (2 ** attempt)
            log("assign request timed out (%s), retrying in %.0fs" % (exc, delay))
            time.sleep(delay)
    raise RuntimeError("unreachable")


# ---------------------------------------------------------------------------
# Kernel REST + Jupyter websocket protocol
# ---------------------------------------------------------------------------
def runtime_headers(proxy_info):
    return {
        "Authorization": "Bearer " + proxy_info["token"],
        "X-Colab-Runtime-Proxy-Token": proxy_info["token"],
        "X-Colab-Client-Agent": "colab-cli",
        "Accept": "application/json",
        "Content-Type": "application/json",
    }


def runtime_base(proxy_info):
    return proxy_info["url"].rstrip("/")


def create_kernel(http, proxy_info, timeout=180, retries=3, backoff=10.0):
    body = {"name": "python3", "path": None}
    url = runtime_base(proxy_info) + "/api/kernels"
    last_error = None
    for attempt in range(retries + 1):
        started = time.time()
        try:
            response = http.request("POST", url, headers=runtime_headers(proxy_info),
                                    json_body=body, timeout=timeout)
            if response.status_code in (200, 201):
                return response.json()
            last_error = RuntimeError("create kernel failed: HTTP %s %s"
                                      % (response.status_code, response.text[:800]))
            if response.status_code not in (502, 503, 504):
                raise last_error
        except (requests.ConnectionError, requests.Timeout) as exc:
            last_error = exc
        if attempt < retries:
            delay = backoff * (2 ** attempt)
            log("kernel create retry %d/%d in %.0fs (last: %.1fs, %r)"
                % (attempt + 1, retries, delay, time.time() - started, last_error))
            time.sleep(delay)
    raise last_error


def check_kernel_listed(http, proxy_info, kernel_id, timeout=15):
    """Best-effort status lookup via /api/kernels (the single-kernel GET can hang).

    A fresh VM reports the kernel as "starting"; the channels websocket queues
    execute_request until it is ready, so we never block on this check.
    """
    try:
        response = http.request("GET", runtime_base(proxy_info) + "/api/kernels",
                                headers=runtime_headers(proxy_info), timeout=timeout)
        if response.status_code == 200:
            for kernel in response.json() or []:
                if kernel.get("id") == kernel_id:
                    return kernel.get("execution_state")
    except (requests.ConnectionError, requests.Timeout) as exc:
        log("kernel list check skipped: %r" % (exc,))
    return None


def delete_kernel(http, proxy_info, kernel_id, timeout=20):
    try:
        response = http.request(
            "DELETE", runtime_base(proxy_info) + "/api/kernels/" + kernel_id,
            headers=runtime_headers(proxy_info), timeout=timeout)
        return response.status_code in (200, 204)
    except (requests.ConnectionError, requests.Timeout):
        return False


def cleanup_stale_kernels(http, proxy_info, state_file, keep_kernel_id):
    """Delete kernels left over from previous bootstrap runs (kernel residue)."""
    state_file = Path(state_file)
    try:
        stale = json.loads(state_file.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        stale = []
    for kernel_id in stale:
        if kernel_id == keep_kernel_id:
            continue
        if delete_kernel(http, proxy_info, kernel_id):
            log("deleted stale kernel %s" % kernel_id)
    state_file.parent.mkdir(parents=True, exist_ok=True)
    state_file.write_text(json.dumps([keep_kernel_id]), encoding="utf-8")


def shutdown_kernel(http, proxy_info, kernel_id):
    try:
        http.request("DELETE", runtime_base(proxy_info) + "/api/kernels/" + kernel_id,
                     headers=runtime_headers(proxy_info), timeout=30)
        return True
    except Exception:
        return False


def _jupyter_message(session_id, code):
    return {
        "header": {
            "msg_id": uuid.uuid4().hex,
            "username": "",
            "session": session_id,
            "date": iso_now(),
            "msg_type": "execute_request",
            "version": "5.3",
        },
        "parent_header": {},
        "metadata": {},
        "buffers": [],
        "channel": "shell",
        "content": {
            "code": code,
            "silent": False,
            "store_history": True,
            "user_expressions": {},
            "allow_stdin": False,
            "stop_on_error": True,
        },
    }


def _ws_url(proxy_info, kernel_id, session_id):
    base = proxy_info["url"].replace("https://", "wss://").replace("http://", "ws://")
    base = base.rstrip("/")
    return ("%s/api/kernels/%s/channels?session_id=%s&token=%s"
            % (base, kernel_id, session_id, proxy_info["token"]))


async def _connect(proxy_info, kernel_id, session_id, proxy, timeout=30):
    headers = {
        "X-Colab-Client-Agent": "colab-cli",
        "X-Colab-Runtime-Proxy-Token": proxy_info["token"],
    }
    url = _ws_url(proxy_info, kernel_id, session_id)
    candidates = ([(proxy, "proxy %s" % proxy), (None, "direct")] if proxy
                  else [(None, "direct")])
    last_error = None
    for proxy_arg, label in candidates:
        try:
            connection = await websockets.connect(
                url, additional_headers=headers, proxy=proxy_arg,
                open_timeout=timeout, ping_interval=20, max_size=None,
            )
            log("kernel channels connected (%s)" % label)
            return connection
        except Exception as exc:  # noqa: BLE001 - report which route failed
            last_error = exc
            log("WS connect %s failed: %r" % (label, exc))
    raise RuntimeError("cannot connect to kernel channels: %r" % (last_error,))


async def execute_remote(proxy_info, kernel_id, code, *, proxy=None,
                         timeout=1800, pair_out=None, print_stream=True):
    """Execute code on the kernel over the Jupyter websocket, stream stdout.

    Returns (status, output_text, pair).
    """
    session_id = uuid.uuid4().hex
    ws = await _connect(proxy_info, kernel_id, session_id, proxy)
    message = _jupyter_message(session_id, code)
    sent_at = time.time()
    pair = None
    output_parts = []
    seen_types = set()
    reply_status = None
    error_text = ""

    async def pump():
        nonlocal pair, reply_status, error_text
        while True:
            remaining = timeout - (time.time() - sent_at)
            if remaining <= 0:
                raise TimeoutError("kernel execution exceeded %ss" % timeout)
            raw = await asyncio.wait_for(ws.recv(), timeout=remaining)
            try:
                payload = json.loads(raw)
            except (TypeError, ValueError):
                continue
            channel = payload.get("channel")
            header = payload.get("header") or {}
            msg_type = header.get("msg_type")
            content = payload.get("content") or {}
            if msg_type in ("status", "execute_input", "kernel_info_reply"):
                if msg_type == "status":
                    if "status:%s" % content.get("execution_state") not in seen_types:
                        seen_types.add("status:%s" % content.get("execution_state"))
                        log("ws <= status %s" % content.get("execution_state"))
                continue
            if channel == "iopub" and msg_type == "stream":
                text = content.get("text", "")
                output_parts.append(text)
                if print_stream:
                    sys.stdout.write(text)
                    sys.stdout.flush()
                found = PAIR_RE.search("".join(output_parts))
                if found:
                    pair = found
            elif channel == "iopub" and msg_type == "error":
                error_text = "\n".join(content.get("traceback") or []) or \
                    "%s: %s" % (content.get("ename"), content.get("evalue"))
                if print_stream:
                    sys.stdout.write(error_text + "\n")
                    sys.stdout.flush()
            elif channel == "shell" and msg_type == "execute_reply":
                reply_status = content.get("status")
                if reply_status == "error" and not error_text:
                    error_text = "%s: %s" % (content.get("ename"), content.get("evalue"))
                return

    try:
        await ws.send(json.dumps(message))
        log("execute_request sent (%d bytes of code, kernel %s)" % (len(code), kernel_id))
        await pump()
    finally:
        await ws.close()
    output = "".join(output_parts)
    if pair_out and pair:
        Path(pair_out).write_text("PAIR: %s|%s\n" % pair.groups(), encoding="utf-8")
        os.chmod(pair_out, 0o600)
        log("PAIR written to %s" % pair_out)
    return reply_status, output, pair, error_text


# ---------------------------------------------------------------------------
# main flow
# ---------------------------------------------------------------------------
def main(argv=None):
    parser = argparse.ArgumentParser(description="Anima App one-key Colab bootstrap (reference)")
    parser.add_argument("--token-file", default=str(TOKEN_PATH))
    parser.add_argument("--proxy", default=None,
                        help="proxy URL for Google domains needing it (e.g. http://127.0.0.1:7890)")
    parser.add_argument("--proxy-runtime", default=None,
                        help="proxy URL for Colab runtime HTTP/WS (default: direct, then --proxy)")
    parser.add_argument("--max-assign-wait", type=float, default=600.0)
    parser.add_argument("--exec-file", default=str(DEFAULT_DEPLOY_SCRIPT),
                        help="file whose source is sent to the kernel (default: deploy_script.py)")
    parser.add_argument("--exec-code", default=None, help="debug: run this code instead")
    parser.add_argument("--exec-timeout", type=float, default=1800.0)
    parser.add_argument("--pair-out", default=None, help="write the PAIR line to this file")
    parser.add_argument("--delete-kernel-after", action="store_true",
                        help="DELETE the kernel after execution (deploy keeps running)")
    parser.add_argument("--reuse-kernel", default=None,
                        help="reuse this kernel id instead of creating a new one")
    parser.add_argument("--kernel-state", default=os.path.expanduser(
                            "~/.cache/anima_bootstrap_kernels.json"),
                        help="records bootstrap kernel ids to clean up on the next run")
    args = parser.parse_args(argv)

    if websockets is None:
        sys.stderr.write("error: websockets is required (pip install websockets)\n")
        return 2

    http = Http(args.proxy)

    # -- 1. OAuth ----------------------------------------------------------
    log("== step 1: load OAuth token + refresh access token ==")
    client = load_oauth_client()
    token_data = load_token(args.token_file)
    if not token_data.get("refresh_token"):
        raise RuntimeError(
            "%s has no refresh_token; authorize the CLI once (colab sessions) first"
            % args.token_file
        )
    if not is_expired(token_data.get("expiry")):
        access_token = token_data["token"]
        log("cached access token still valid, skipping refresh")
    else:
        access_token = refresh_access_token(http, client, token_data, args.token_file)

    # -- 2. assignments ----------------------------------------------------
    log("== step 2: list assignments ==")
    assignments = list_assignments(http, access_token)
    log("assignments: %d" % len(assignments))
    for assignment in assignments:
        info = assignment.get("runtimeProxyInfo") or {}
        log("  endpoint=%s accelerator=%s variant=%s url=%s"
            % (assignment.get("endpoint"), assignment.get("accelerator"),
               assignment.get("variant"), info.get("url")))
    assignment = pick_t4(assignments)
    if assignment is None:
        log("no T4 assignment found, requesting a new one (may take a few minutes)")
        started = time.time()
        assignment = assign_t4(http, access_token)
        log("assigned T4 in %.1fs" % (time.time() - started))
    else:
        log("reusing existing T4 assignment: %s" % assignment.get("endpoint"))
    proxy_info = assignment.get("runtimeProxyInfo") or {}
    if not proxy_info.get("url") or not proxy_info.get("token"):
        raise RuntimeError("assignment has no runtimeProxyInfo: %r" % (assignment,))
    expires_in = int(proxy_info.get("tokenExpiresInSeconds") or 0)
    log("runtime proxy: %s (token valid %ss)"
        % (proxy_info["url"], expires_in))

    # -- 3. kernel ---------------------------------------------------------
    log("== step 3: create kernel ==")
    started = time.time()
    if args.reuse_kernel:
        kernel = {"id": args.reuse_kernel}
        log("reusing kernel %s" % args.reuse_kernel)
    else:
        kernel = create_kernel(http, proxy_info, timeout=args.max_assign_wait)
        log("kernel created: %s (%.0f ms)" % (kernel.get("id"), (time.time() - started) * 1000))
    kernel_id = kernel.get("id")
    if not kernel_id:
        raise RuntimeError("create kernel returned no id: %r" % (kernel,))
    cleanup_stale_kernels(http, proxy_info, args.kernel_state, kernel_id)
    state = check_kernel_listed(http, proxy_info, kernel_id)
    log("kernel listed, execution_state=%r (channels buffered until ready)" % (state,))
    # NOTE: do not block on the single-kernel GET endpoint: on Colab's runtime
    # proxy it can take >30s; the channels websocket queues execute_request
    # until the kernel is ready.

    # -- 4. code to execute -------------------------------------------------
    if args.exec_code is not None:
        code = args.exec_code
        log("== step 4: execute --exec-code (%d chars) ==" % len(code))
    else:
        deploy_path = Path(args.exec_file)
        if not deploy_path.is_file():
            raise RuntimeError("deploy script not found: %s" % deploy_path)
        code = deploy_path.read_text(encoding="utf-8")
        log("== step 4: execute %s (%d bytes) ==" % (deploy_path, len(code)))
    # make sure the kernel cwd is /content (the Colab working directory)
    prelude = ("import os\n"
               "os.makedirs('/content', exist_ok=True)\n"
               "os.chdir('/content')\n")
    code = prelude + code + "\n"

    # -- 5. execute + stream ------------------------------------------------
    log("== step 5: kernel execute + stream stdout ==")
    status, output, pair, error_text = asyncio.run(
        execute_remote(
            proxy_info, kernel_id, code,
            proxy=args.proxy_runtime or args.proxy,
            timeout=args.exec_timeout, pair_out=args.pair_out,
        )
    )
    log("execute_reply status=%s" % status)

    if args.delete_kernel_after:
        log("deleting kernel %s: %s" % (kernel_id, shutdown_kernel(http, proxy_info, kernel_id)))

    elapsed = "ok"
    if status != "ok":
        sys.stderr.write("\n[!] deployment failed (execute_reply status=%s)\n" % status)
        if error_text:
            sys.stderr.write(error_text[-4000:] + "\n")
        else:
            sys.stderr.write(output[-4000:] + "\n")
        return 1
    if pair is None:
        sys.stderr.write("\n[!] deployment finished but no 'PAIR:' line was found\n")
        return 1

    print("")
    print("=" * 72)
    print("  Anima App one-key bootstrap finished")
    print("=" * 72)
    print("PAIR: %s|%s" % pair.groups())
    print("")
    print("kernel id: %s (kept alive for keepalive; delete via "
          "DELETE %s/api/kernels/%s)" % (kernel_id, runtime_base(proxy_info), kernel_id))
    print("=" * 72)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
