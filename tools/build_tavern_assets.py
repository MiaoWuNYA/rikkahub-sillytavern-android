#!/usr/bin/env python3
"""Reproducibly build app/src/main/assets/tavern/ from the TauriTavern reference.

The reference front-end is SillyTavern with a Tauri layer. The Android host has no
Tauri runtime, so the Tauri-only shims/entrypoints are dropped and index.html gets a
tiny inline window.__TAURI__ shim so the stock front-end does not explode on load.

Idempotent: the destination directory is purged before every run.
Usage: python3 tools/build_tavern_assets.py [--src DIR] [--dest DIR] [--quiet]
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SRC = Path("/tmp/ref/TauriTavern/src")
DEFAULT_DEST = REPO_ROOT / "app/src/main/assets/tavern"

# Top level entries copied verbatim.
KEEP_FILES = ["index.html", "init.js", "script.js", "lib.js", "style.css"]
KEEP_GLOBS = ["lib-bundle-*.js"]
KEEP_DIRS = ["css", "scripts", "lib", "webfonts", "img", "sounds", "locales"]

# Dropped by exact name / by directory segment.
EXCLUDE_NAMES = {
    "tauri-bridge.js",
    "tauri-main.js",
    "dev-sw-bootstrap.js",
    "tt-ext-sw.js",
    "manifest.json",
    "robots.txt",
    "global.d.ts",
    "types.d.ts",
    "jsconfig.json",
    ".DS_Store",
}
EXCLUDE_DIRS = {"tauri", "tauri-image-provider", "node_modules", "docs"}
EXCLUDE_SUFFIXES = {".map", ".md"}

# locales/ is flat json here: keep only these languages.
KEEP_LOCALES = {"en.json", "zh-cn.json", "zh-tw.json"}

# Scripts that must never be referenced by the shipped index.html.
EXCLUDED_SCRIPT_REFS = ("tauri-bridge.js", "tauri-main.js", "tt-ext-sw.js", "dev-sw-bootstrap.js")

SHIM = """    <!-- Android host shim: no Tauri runtime is present. Keep this ahead of every module. -->
    <script>
        (function () {
            if (window.__TAURI__) return;
            var stub = function () { return Promise.resolve(null); };
            var handler = {
                get: function (target, prop) {
                    if (prop === 'then' || prop === 'call' || prop === 'apply') return undefined;
                    if (!(prop in target)) target[prop] = makeNode();
                    return target[prop];
                }
            };
            function makeNode() {
                return new Proxy(stub, handler);
            }
            window.__TAURI__ = makeNode();
            window.__TAURI_INTERNALS__ = window.__TAURI_INTERNALS__ || { invoke: stub, metadata: {} };
        })();
    </script>
"""

SCRIPT_TAG_RE = re.compile(r"^[ \t]*<script\b[^>]*\bsrc=[\"']([^\"']+)[\"'][^>]*>[ \t]*</script>[ \t]*\r?\n?", re.IGNORECASE)


def is_excluded(rel: Path) -> bool:
    for part in rel.parts[:-1]:
        if part in EXCLUDE_DIRS:
            return True
    name = rel.parts[-1]
    if name in EXCLUDE_NAMES:
        return True
    if rel.suffix in EXCLUDE_SUFFIXES:
        return True
    return False


def copy_tree(src_dir: Path, dest_dir: Path) -> None:
    for item in sorted(src_dir.rglob("*")):
        if not item.is_file():
            continue
        rel = item.relative_to(src_dir)
        if is_excluded(rel):
            continue
        target = dest_dir / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(item, target)


def copy_locales(src: Path, dest: Path) -> None:
    if not src.is_dir():
        return
    dest.mkdir(parents=True, exist_ok=True)
    for item in sorted(src.iterdir()):
        if item.is_file() and item.name in KEEP_LOCALES:
            shutil.copy2(item, dest / item.name)


def rewrite_index(html_path: Path) -> int:
    text = html_path.read_text(encoding="utf-8")
    removed = 0

    def replace(match: re.Match[str]) -> str:
        nonlocal removed
        src = match.group(1)
        if any(ref in src for ref in EXCLUDED_SCRIPT_REFS):
            removed += 1
            return ""
        return match.group(0)

    text = SCRIPT_TAG_RE.sub(replace, text)

    # Drop any remaining single-line <script src> for excluded files (defensive).
    lines = []
    for line in text.splitlines(keepends=True):
        lowered = line.lower()
        if "<script" in lowered and any(ref in lowered for ref in EXCLUDED_SCRIPT_REFS):
            removed += 1
            continue
        lines.append(line)
    text = "".join(lines)

    if "window.__TAURI__" not in text:
        head_end = re.search(r"</head>", text, re.IGNORECASE)
        if head_end is None:
            raise SystemExit("index.html has no </head>; cannot inject the shim")
        idx = head_end.start()
        text = text[:idx] + SHIM + text[idx:]

    html_path.write_text(text, encoding="utf-8")
    return removed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--src", default=str(DEFAULT_SRC))
    parser.add_argument("--dest", default=str(DEFAULT_DEST))
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    src = Path(args.src).resolve()
    dest = Path(args.dest).resolve()
    if not src.is_dir():
        print(f"error: source directory not found: {src}", file=sys.stderr)
        return 1
    if dest == Path("/") or len(dest.parts) < 3:
        print(f"error: refusing to purge suspicious destination: {dest}", file=sys.stderr)
        return 1

    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True, exist_ok=True)

    for name in KEEP_FILES:
        candidate = src / name
        if candidate.is_file():
            shutil.copy2(candidate, dest / name)
    for pattern in KEEP_GLOBS:
        for candidate in sorted(src.glob(pattern)):
            if candidate.is_file() and not is_excluded(Path(candidate.name)):
                shutil.copy2(candidate, dest / candidate.name)
    for name in KEEP_DIRS:
        candidate = src / name
        if not candidate.is_dir():
            continue
        if name == "locales":
            copy_locales(candidate, dest / name)
        else:
            copy_tree(candidate, dest / name)

    # Defensive sweep: the exclusion rules also apply to anything copied above.
    for item in sorted(dest.rglob("*"), reverse=True):
        if item.is_dir():
            continue
        if is_excluded(item.relative_to(dest)):
            item.unlink()

    index = dest / "index.html"
    if not index.is_file():
        print(f"error: index.html was not copied into {dest}", file=sys.stderr)
        return 1
    removed = rewrite_index(index)
    if not args.quiet:
        print(f"rewrote index.html: dropped {removed} excluded <script> reference(s), injected __TAURI__ shim")

    files = [p for p in dest.rglob("*") if p.is_file()]
    total = sum(p.stat().st_size for p in files)
    print(f"dest: {dest}")
    print(f"files: {len(files)}")
    print(f"size: {total} bytes ({total / 1024 / 1024:.2f} MiB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
