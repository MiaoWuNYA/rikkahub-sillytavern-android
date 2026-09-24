#!/usr/bin/env python3
"""把 aimodels 插件打包成可导入的 zip（并校验 manifest 与导出函数对得上）。

单文件插件，entry 就是 main.js；build.py 主要负责打 zip 和打包前自检。

用法：
    python3 docs/plugins/aimodels/build.py            # 只自检
    python3 docs/plugins/aimodels/build.py --zip      # 再打一个 zip 到 docs/plugins/
"""

import json
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PLUGINS_DIR = os.path.dirname(HERE)
ENTRY = "main.js"


def check_exports():
    """manifest 声明的 tools / detailCard 必须都在 main.js 里 exports 出来。"""
    with open(os.path.join(HERE, "manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    with open(os.path.join(HERE, ENTRY), encoding="utf-8") as f:
        source = f.read()

    declared = [t["name"] for t in manifest.get("tools", [])]
    if manifest.get("detailCard"):
        declared.append(manifest["detailCard"])

    exported = set(re.findall(r"exports\.(\w+)\s*=", source))
    missing = [name for name in declared if name not in exported]
    if missing:
        raise SystemExit("manifest 声明了但 main.js 未导出: %s" % missing)
    print("exports ok: %s" % ", ".join(sorted(exported)))
    return manifest


def write_zip(manifest):
    # 插件包里不能有顶层文件夹：宿主会把 zip 整个解到插件目录下
    zip_path = os.path.join(PLUGINS_DIR, "%s.zip" % manifest["id"].rsplit(".", 1)[-1])
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(HERE, "manifest.json"), "manifest.json")
        z.write(os.path.join(HERE, ENTRY), ENTRY)
    print("wrote %s" % zip_path)
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            print("   %-16s %d bytes" % (info.filename, info.file_size))


if __name__ == "__main__":
    manifest = check_exports()
    if "--zip" in sys.argv:
        write_zip(manifest)
