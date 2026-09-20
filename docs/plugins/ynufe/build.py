#!/usr/bin/env python3
"""把 ynufe 插件的源码拼成沙箱要的那一个 main.js，并打包成可导入的 zip。

沙箱只对 manifest.entry 指的单个文件求值，没有 require()，
所以多文件的插件必须在打包前拼成一个文件。源码拆开是为了可读。

用法：
    python3 docs/plugins/ynufe/build.py            # 只生成 main.js
    python3 docs/plugins/ynufe/build.py --zip      # 再打一个 zip 到 docs/plugins/
"""

import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PLUGINS_DIR = os.path.dirname(HERE)

# 顺序即依赖顺序：解析层用到 OCR 的常量，业务层用到两者
SOURCES = ["captcha_templates.js", "captcha.js", "parser.js", "yunfe.js"]
ENTRY = "main.js"

BANNER = """// 云南财经大学教务系统插件 —— 由 build.py 从以下文件拼接生成，请勿直接改本文件：
//   captcha_templates.js  验证码字模（从 CaptchaTemplates.kt 生成）
//   captcha.js            验证码本地 OCR（从 YnufeCaptcha.kt 移植）
//   parser.js             教务页面 HTML 解析（从 YnufeParsers.kt 移植）
//   yunfe.js              插件业务逻辑与工具入口
//
// 改完源码跑一次 `python3 docs/plugins/ynufe/build.py --zip`。
"""


def build_main_js() -> str:
    chunks = [BANNER]
    for name in SOURCES:
        with open(os.path.join(HERE, name), encoding="utf-8") as f:
            body = f.read()
        bar = "=" * max(4, 62 - len(name))
        chunks.append("\n// %s %s %s\n" % (bar, name, bar))
        chunks.append(body.rstrip("\n") + "\n")
    return "".join(chunks)


def write_entry() -> str:
    content = build_main_js()
    out = os.path.join(HERE, ENTRY)
    with open(out, "w", encoding="utf-8") as f:
        f.write(content)
    print("wrote %s (%d bytes)" % (out, len(content.encode("utf-8"))))
    return content


def write_zip() -> None:
    with open(os.path.join(HERE, "manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
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
    write_entry()
    if "--zip" in sys.argv:
        write_zip()
