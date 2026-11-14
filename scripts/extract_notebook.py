#!/usr/bin/env python3
"""Extract the code cells of a Jupyter notebook into one plain Python file.

Used to run colab/anima_colab.ipynb on a Colab VM without a browser:

    python3 scripts/extract_notebook.py colab/anima_colab.ipynb /tmp/anima_deploy.py
    colab exec -s anima --timeout 1800 -f /tmp/anima_deploy.py

Rules:
* code cells are concatenated in notebook order, separated by banner comments;
* cells tagged {"tags": ["keepalive"]} are skipped (an infinite loop is useless
  for a one-shot deploy script -- the tunnel stays up without it);
* IPython magics (lines starting with ``!`` or ``%``) are rejected with a clear
  error: the notebook must stay pure Python so it can run in a plain kernel.

Exit codes: 0 success, 1 bad magic / syntax error, 2 unreadable notebook.
"""
import argparse
import json
import sys
from pathlib import Path

def is_keepalive(cell):
    tags = ((cell.get("metadata") or {}).get("tags") or [])
    return "keepalive" in tags


def looks_like_magic(stripped):
    """True for IPython magics like ``!pip install x``, ``%timeit``, ``%%writefile``.

    A line that merely starts with ``%`` as part of a Python expression
    continuation (e.g. ``% (a, b)``) is not a magic: a magic has a name right
    after the prefix.
    """
    if stripped.startswith("!"):
        return True
    if stripped.startswith("%"):
        rest = stripped.lstrip("%")
        return bool(rest) and (rest[0].isalpha() or rest[0] == "_")
    return False


def find_magic(cell_source):
    """Return the first line that looks like an IPython magic, or None."""
    for line in cell_source.splitlines():
        stripped = line.lstrip()
        if looks_like_magic(stripped):
            return line.strip()
    return None


def extract(notebook_path):
    nb = json.loads(Path(notebook_path).read_text(encoding="utf-8"))
    if not isinstance(nb.get("cells"), list):
        raise ValueError("not a notebook: no 'cells' list")
    blocks = []
    code_count = 0
    skipped = 0
    for index, cell in enumerate(nb["cells"]):
        if cell.get("cell_type") != "code":
            continue
        source = cell.get("source", "")
        if isinstance(source, list):
            source = "".join(source)
        if is_keepalive(cell):
            skipped += 1
            continue
        try:
            compile(source, "<cell %d>" % index, "exec")
        except SyntaxError as exc:
            magic = find_magic(source)
            if magic is not None:
                raise ValueError(
                    "cell #%d contains an IPython magic, the notebook must stay "
                    "pure Python (use subprocess instead): %r" % (index, magic)
                )
            raise ValueError(
                "cell #%d has a Python syntax error: %s (line %s)"
                % (index, exc.msg, exc.lineno)
            )
        code_count += 1
        blocks.append("# ---- cell %d ----\n%s\n" % (index, source.rstrip()))
    return blocks, code_count, skipped


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Extract a notebook's code cells into a runnable .py script"
    )
    parser.add_argument("notebook", help="input .ipynb")
    parser.add_argument("output", help="output .py")
    parser.add_argument(
        "--keep-headers", action="store_true", help="keep markdown headers as comments"
    )
    args = parser.parse_args(argv)

    try:
        blocks, code_count, skipped = extract(args.notebook)
    except FileNotFoundError:
        sys.stderr.write("error: notebook not found: %s\n" % args.notebook)
        return 2
    except json.JSONDecodeError as exc:
        sys.stderr.write("error: invalid notebook JSON in %s: %s\n" % (args.notebook, exc))
        return 2
    except ValueError as exc:
        sys.stderr.write("error: %s\n" % exc)
        return 2

    header = (
        "#!/usr/bin/env python3\n"
        "# Auto-generated from %s by scripts/extract_notebook.py. Do not edit.\n"
        "# %d code cells, %d keepalive cell(s) skipped.\n"
        % (Path(args.notebook).name, code_count, skipped)
    )
    text = header + "\n" + "\n\n".join(blocks)
    try:
        compile(text, args.output, "exec")
    except SyntaxError as exc:
        sys.stderr.write(
            "error: extracted script does not compile: %s (line %s)\n" % (exc.msg, exc.lineno)
        )
        return 1

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    print(
        "wrote %s (%d code cells, %d keepalive cell(s) skipped, %d bytes)"
        % (out, code_count, skipped, len(text.encode("utf-8")))
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
