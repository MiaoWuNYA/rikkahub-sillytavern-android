#!/usr/bin/env python3
"""Patch Caelus dist/src/electional.js before esbuild for planetary-hour weekday.

Caelus 的行星时 weekday 原用 UTC 天文日（正午边界）计算；对 UTC 日期与
出生地本地日期不同的时刻（如上海 00:00-05:10 出生，UTC 仍是前一天）会
错一天，导致 dayRuler 与整个 Chaldean 序列错位。修正为按本地日历日：
本地日号 = floor(dayStart + 0.5 + lonEast/360)，基准 1970-01-01(Thursday)
标定后 weekday = (本地日号 + 1) % 7。

Run AFTER `npm pack caelus` + 解包，BEFORE esbuild。
"""

import os
import sys


def patch_file(path, old, new):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    if old not in content:
        print(f"  WARN: pattern not found in {path}")
        return False
    content = content.replace(old, new, 1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"  patched: {path}")
    return True


def main():
    package_dir = sys.argv[1] if len(sys.argv) > 1 else "package"
    path = os.path.join(package_dir, "dist", "src", "electional.js")
    ok = patch_file(
        path,
        "const weekday = Math.floor(dayStart + 1.5) % 7; // 0 = Sunday",
        "const weekday = (Math.floor(dayStart + 0.5 + lonEast / 360) + 1) % 7; // 0 = Sunday",
    )
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()
