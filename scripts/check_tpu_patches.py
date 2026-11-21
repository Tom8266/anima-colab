#!/usr/bin/env python3
"""Verify the TPU device patches embedded in colab/deploy_script.py.

ComfyUI upstream has no TPU backend, so the deploy script patches
`comfy/model_management.py` at 11 exact anchors. Upstream refactors can move
those anchors; this checker fails early instead of letting a deployment patch
the wrong code (or crash halfway).

Usage:
    python3 scripts/check_tpu_patches.py                  # fetch pinned tag
    python3 scripts/check_tpu_patches.py --file ~/ComfyUI/comfy/model_management.py
    HTTPS_PROXY=http://127.0.0.1:7890 python3 scripts/check_tpu_patches.py
    python3 scripts/check_tpu_patches.py --write /tmp/model_management.tpu.py

Exit code 0 only when every anchor appears exactly once and the patched file
compiles. No ComfyUI/torch import is performed (the file is only compiled).
"""
import argparse
import ast
import os
import py_compile
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEPLOY = ROOT / "colab" / "deploy_script.py"
RAW_URL = "https://raw.githubusercontent.com/comfyanonymous/ComfyUI/{tag}/comfy/model_management.py"


def load_patches():
    source = DEPLOY.read_text(encoding="utf-8")
    tree = ast.parse(source)
    wanted = {"TPU_HELPER", "TPU_PATCHES"}
    nodes = [
        node for node in tree.body
        if isinstance(node, ast.Assign)
        and isinstance(node.targets[0], ast.Name)
        and node.targets[0].id in wanted
    ]
    namespace = {}
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(DEPLOY), "exec"), namespace)
    patches = namespace.get("TPU_PATCHES")
    if not isinstance(patches, list) or not patches:
        raise SystemExit("could not find TPU_PATCHES in %s" % DEPLOY)
    return patches


def pinned_tag():
    source = DEPLOY.read_text(encoding="utf-8")
    match = None
    for line in source.splitlines():
        if line.startswith("COMFY_TPU_TAG = "):
            match = line.split("=", 1)[1].strip().strip("\"'")
            break
    if not match:
        raise SystemExit("could not find COMFY_TPU_TAG in %s" % DEPLOY)
    return match


def fetch(tag):
    url = RAW_URL.format(tag=tag)
    print("fetching %s" % url)
    request = urllib.request.Request(url, headers={"User-Agent": "anima-tpu-check"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--file", help="local comfy/model_management.py (default: fetch pinned tag)")
    parser.add_argument("--write", help="write the patched file here for inspection")
    args = parser.parse_args(argv)

    tag = pinned_tag()
    source = Path(args.file).read_text(encoding="utf-8") if args.file else fetch(tag)
    patches = load_patches()
    print("deploy script pins ComfyUI %s; %d patches" % (tag, len(patches)))

    patched = source
    failures = []
    for index, (old, new) in enumerate(patches, 1):
        count = patched.count(old)
        first_line = old.strip().splitlines()[0] if old.strip() else "<empty>"
        print("  %2d/%d count=%d  %s" % (index, len(patches), count, first_line[:70]))
        if count != 1:
            failures.append((index, count, first_line))
            continue
        patched = patched.replace(old, new, 1)

    if failures:
        print("\nFAIL: %d anchor(s) not unique. Update TPU_PATCHES and COMFY_TPU_TAG." % len(failures))
        for index, count, first_line in failures:
            print("  patch %d: count=%d  %s" % (index, count, first_line))
        return 1

    temporary = args.write
    if temporary is None:
        handle = tempfile.NamedTemporaryFile("w", suffix=".py", delete=False)
        handle.write(patched)
        handle.close()
        temporary = handle.name
    else:
        Path(temporary).write_text(patched, encoding="utf-8")

    try:
        py_compile.compile(temporary, doraise=True)
    finally:
        if args.write is None:
            try:
                os.unlink(temporary)
            except OSError:
                pass

    print("\nOK: all %d anchors applied and the patched file compiles." % len(patches))
    if args.write:
        print("patched file: %s" % args.write)
    return 0


if __name__ == "__main__":
    sys.exit(main())
