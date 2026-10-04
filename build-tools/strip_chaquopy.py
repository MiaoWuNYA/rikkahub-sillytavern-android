#!/usr/bin/env python3
"""
剔除 Chaquopy imy 中运行时用不到的条目，减小 APK 体积。

背景：requirements-common.imy 是 stored 的 zip，占 APK 近一半体积。
它把每个包完整的 tests/ 目录也打了进去——pandas 一家的测试套件就有
十几 MB，全部包的 tests 合计约 15.7 MB。生产环境不会跑 pytest
（包里根本没装），也没有任何代码 import 它们。

剔除范围：
  - 路径段中出现 tests/ 的条目（只匹配整段，避免误伤 numpy.testing
    这类运行时真的要用的模块）
  - .pyi 类型存根、.h/.hpp/.c/.pyx 源码（编译期用，运行时无关）
  - .dist-info 里的元数据（pip 安装阶段已消费完，除了 RECORD）

用法: strip_chaquopy.py <merged-assets-dir>
"""

import os
import sys
import zipfile

# 只碰 requirements-*。
# stdlib-* 是 Python 标准库，里面没有 tests 可丢；重新压缩一遍反而会让
# 体积变大（实测 stdlib-common 从 4.2MB 涨到 9.6MB），纯属负收益。
IMY_NAMES = (
    "requirements-common.imy",
    "requirements-arm64-v8a.imy",
    "requirements-x86_64.imy",
)

DROP_SUFFIXES = (".pyi", ".h", ".hpp", ".c", ".pyx")


def should_drop(name):
    segments = name.split("/")
    # 整段匹配 "tests"，不用子串匹配——numpy/testing 是运行时需要的
    if "tests" in segments:
        return True
    if name.endswith(DROP_SUFFIXES):
        return True
    if ".dist-info/" in name and not name.endswith("RECORD"):
        return True
    return False


def rewrite(path):
    """
    重建 zip，跳过该丢的条目。

    必须沿用 deflate：imy 里的条目本来就是压缩存储的，
    用 ZIP_STORED 原样搬会把它撑大近一倍。
    """
    before = os.path.getsize(path)
    tmp = path + ".stripped"
    dropped = kept = 0

    with zipfile.ZipFile(path, "r") as zin:
        infos = zin.infolist()
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED,
                             compresslevel=9, allowZip64=True) as zout:
            for info in infos:
                if info.is_dir() or should_drop(info.filename):
                    dropped += 1
                    continue
                # 必须显式指定 compress_type：ZipInfo 会沿用传进来的
                # 压缩方式，不指定就退回 stored，整个 imy 会被撑大一倍。
                out_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                out_info.compress_type = zipfile.ZIP_DEFLATED
                zout.writestr(out_info, zin.read(info.filename))
                kept += 1

    after = os.path.getsize(tmp)
    # 负优化保护：重压本来就不保证一定变小（zlib 版本、字典窗口的差异
    # 都可能让它变大）。变大了就丢掉结果，保留原文件。
    if after >= before:
        os.remove(tmp)
        return before, before, 0, kept

    os.replace(tmp, path)
    return before, after, dropped, kept


def main():
    if len(sys.argv) < 2:
        print("用法: strip_chaquopy.py <merged-assets-dir>", file=sys.stderr)
        return 2

    chaquopy_dir = os.path.join(sys.argv[1], "chaquopy")
    if not os.path.isdir(chaquopy_dir):
        print(f"未找到 chaquopy 目录，跳过: {chaquopy_dir}", file=sys.stderr)
        return 0

    total_before = total_after = 0
    for name in IMY_NAMES:
        path = os.path.join(chaquopy_dir, name)
        if not os.path.isfile(path):
            continue
        before, after, dropped, kept = rewrite(path)
        total_before += before
        total_after += after
        print(f"   {name}: {before/1048576:.2f} MB -> {after/1048576:.2f} MB "
              f"(丢弃 {dropped}, 保留 {kept})")

    if total_before:
        print(f"✅ Chaquopy 瘦身: {total_before/1048576:.1f} MB -> "
              f"{total_after/1048576:.1f} MB (省 {(total_before-total_after)/1048576:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
