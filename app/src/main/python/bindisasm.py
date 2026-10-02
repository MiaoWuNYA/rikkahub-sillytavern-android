"""
轻量反汇编绑定：直接通过 ctypes 调用随 APK 分发的 libcapstone.so。

为什么不装 pip 的 capstone 包：
  PyPI 只提供 manylinux / musllinux / macOS / Windows wheel，
  没有 Android ABI 的产物，Chaquopy 装不上。
  而 APK 里本来就带着 libcapstone.so（Rizin 引擎的依赖），
  直接 ctypes 调用即可，不增加任何体积。

用法：
    import bindisasm
    print(bindisasm.disasm(bytes.fromhex("1f2003d5"), arch="arm64"))
"""

import ctypes
import ctypes.util
import os

# ── capstone 常量 ────────────────────────────────────────────────
CS_ARCH_ARM = 0
CS_ARCH_ARM64 = 1
CS_ARCH_MIPS = 2
CS_ARCH_X86 = 3
CS_ARCH_PPC = 4
CS_ARCH_SPARC = 5
CS_ARCH_SYSZ = 6
CS_ARCH_XCORE = 7
CS_ARCH_M68K = 8
CS_ARCH_TMS320C64X = 9
CS_ARCH_M680X = 10
CS_ARCH_EVM = 11
CS_ARCH_RISCV = 12

CS_MODE_LITTLE_ENDIAN = 0
CS_MODE_ARM = 0
CS_MODE_16 = 1 << 1
CS_MODE_32 = 1 << 2
CS_MODE_64 = 1 << 3
CS_MODE_THUMB = 1 << 4
CS_MODE_MCLASS = 1 << 5
CS_MODE_V8 = 1 << 6
CS_MODE_MICRO = 1 << 4
CS_MODE_MIPS3 = 1 << 5
CS_MODE_MIPS32R6 = 1 << 6
CS_MODE_MIPS32 = CS_MODE_32
CS_MODE_MIPS64 = CS_MODE_64
CS_MODE_BIG_ENDIAN = 1 << 31

# 架构别名 → (arch_id, 默认 mode)
_ARCH_TABLE = {
    "arm":     (CS_ARCH_ARM,   CS_MODE_ARM),
    "arm32":   (CS_ARCH_ARM,   CS_MODE_ARM),
    "arm64":   (CS_ARCH_ARM64, CS_MODE_ARM),
    "aarch64": (CS_ARCH_ARM64, CS_MODE_ARM),
    "thumb":   (CS_ARCH_ARM,   CS_MODE_THUMB),
    "x86":     (CS_ARCH_X86,   CS_MODE_32),
    "x86_32":  (CS_ARCH_X86,   CS_MODE_32),
    "x86_64":  (CS_ARCH_X86,   CS_MODE_64),
    "x64":     (CS_ARCH_X86,   CS_MODE_64),
    "mips":    (CS_ARCH_MIPS,  CS_MODE_MIPS32),
    "mips64":  (CS_ARCH_MIPS,  CS_MODE_MIPS64),
    "riscv":   (CS_ARCH_RISCV, CS_MODE_32),
    "riscv64": (CS_ARCH_RISCV, CS_MODE_64),
    "ppc":     (CS_ARCH_PPC,   CS_MODE_32),
    "sparc":   (CS_ARCH_SPARC, CS_MODE_32),
}

# capstone 的 cs_insn 结构体。
#
# 必须完整声明到末尾的 detail 指针：cs_disasm 返回的是**连续数组**，
# 少一个字段会让 ctypes 算错 sizeof，访问 insn_ptr[i] 时按错误的步长跳转，
# 表现为第 1 条正确、后续全乱。
class _CsInsn(ctypes.Structure):
    _fields_ = [
        ("id", ctypes.c_uint),
        ("address", ctypes.c_uint64),
        ("size", ctypes.c_uint16),
        ("bytes", ctypes.c_ubyte * 24),
        ("mnemonic", ctypes.c_char * 32),   # CS_MNEMONIC_SIZE
        ("op_str", ctypes.c_char * 160),
        ("detail", ctypes.c_void_p),        # cs_detail*
    ]


_lib = None
_load_error = None


def _find_lib():
    """按优先级定位 libcapstone.so：应用私有目录 → 系统路径。"""
    candidates = []
    # Android：native 库由 PackageManager 解压到应用私有目录
    for base in (
        os.environ.get("ANDROID_NATIVE_LIB_DIR", ""),
        "/data/data/me.rerere.rikkahub.huadeng/lib",
        "/data/app",
    ):
        if base and os.path.isdir(base):
            candidates.append(os.path.join(base, "libcapstone.so"))
    found = ctypes.util.find_library("capstone")
    if found:
        candidates.append(found)
    candidates.append("libcapstone.so")
    for path in candidates:
        try:
            if path and (os.path.exists(path) or not path.startswith("/")):
                return ctypes.CDLL(path)
        except OSError:
            continue
    raise OSError("找不到 libcapstone.so")


def _get_lib():
    global _lib, _load_error
    if _lib is not None:
        return _lib
    if _load_error is not None:
        raise OSError(_load_error)
    try:
        lib = _find_lib()
        lib.cs_open.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_void_p)]
        lib.cs_open.restype = ctypes.c_int
        lib.cs_disasm.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_size_t,
                                  ctypes.c_uint64, ctypes.c_size_t, ctypes.POINTER(ctypes.POINTER(_CsInsn))]
        lib.cs_disasm.restype = ctypes.c_size_t
        lib.cs_free.argtypes = [ctypes.POINTER(_CsInsn), ctypes.c_size_t]
        lib.cs_free.restype = None
        lib.cs_close.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
        lib.cs_close.restype = ctypes.c_int
        _lib = lib
        return lib
    except Exception as e:
        _load_error = "libcapstone 加载失败: %s" % e
        raise OSError(_load_error)


def available():
    """检查反汇编引擎是否可用。"""
    try:
        _get_lib()
        return True
    except Exception:
        return False


def status():
    """返回引擎可用状态与版本，用于自检。"""
    try:
        lib = _get_lib()
        major, minor = ctypes.c_int(), ctypes.c_int()
        lib.cs_version(ctypes.byref(major), ctypes.byref(minor))
        return "ok capstone %d.%d" % (major.value, minor.value)
    except Exception as e:
        return "unavailable: %s" % e


def arch_list():
    """返回支持的架构标识列表。"""
    return sorted(_ARCH_TABLE.keys())


def disasm(data, arch="arm64", address=0, count=0):
    """
    反汇编一段机器码。

    data      : bytes / bytearray，或十六进制字符串
    arch      : _ARCH_TABLE 中的键，如 arm64 / arm32 / x86_64 / mips / riscv
    address   : 首字节的虚拟地址，影响分支目标计算
    count     : 最多反汇编多少条；0 表示不限

    返回文本，每行一条：'0xADDR: BB BB  mnemonic op_str'
    失败时返回以 'Error:' 开头的说明，不抛异常。
    """
    try:
        if isinstance(data, str):
            data = bytes.fromhex(data.replace(" ", "").replace(":", "").replace("-", ""))
        elif isinstance(data, (bytearray, memoryview)):
            data = bytes(data)
        if not isinstance(data, bytes):
            return "Error: data 必须是 bytes 或十六进制字符串"
        if not data:
            return "Error: 输入为空"

        key = (arch or "arm64").lower()
        if key not in _ARCH_TABLE:
            return "Error: 不支持的架构 '%s'，可选: %s" % (arch, " / ".join(arch_list()))
        arch_id, mode = _ARCH_TABLE[key]

        # 十六进制串若带空格，上面已剔除；这里统一走 bytes
        lib = _get_lib()
        handle = ctypes.c_void_p()
        err = lib.cs_open(arch_id, mode, ctypes.byref(handle))
        if err != 0:
            return "Error: cs_open 失败 (code %d)，架构 %s 可能未被编译进该库" % (err, arch)

        try:
            insn_ptr = ctypes.POINTER(_CsInsn)()
            buf = ctypes.create_string_buffer(data, len(data))
            n = lib.cs_disasm(handle, buf, len(data), address, count, ctypes.byref(insn_ptr))
            if n == 0:
                return "Error: 无法反汇编（数据可能不是有效的 %s 指令流）" % arch
            lines = []
            try:
                for i in range(n):
                    insn = insn_ptr[i]
                    raw = bytes(insn.bytes[:insn.size])
                    hex_bytes = " ".join("%02X" % b for b in raw)
                    mnem = insn.mnemonic.decode("utf-8", "replace")
                    ops = insn.op_str.decode("utf-8", "replace")
                    text = (mnem + " " + ops).strip()
                    lines.append("0x%X:\t%s\t%s" % (insn.address, hex_bytes, text))
            finally:
                lib.cs_free(insn_ptr, n)
            return "\n".join(lines)
        finally:
            lib.cs_close(ctypes.byref(handle))
    except Exception as e:
        return "Error: %s" % e


def bytes_at(path, offset, length):
    """
    从文件指定偏移读取一段字节，方便直接喂给 disasm。
    适合处理大文件：不必整个读进内存。
    """
    try:
        with open(path, "rb") as f:
            f.seek(offset)
            return f.read(length)
    except Exception as e:
        return "Error: %s" % e


def info(path):
    """读取文件头部，判断是否 ELF 及其架构，返回简短描述。"""
    try:
        with open(path, "rb") as f:
            head = f.read(20)
        if len(head) < 20 or head[:4] != b"\x7fELF":
            return "不是 ELF 文件（头部: %s）" % head[:4].hex()
        is64 = head[4] == 2
        little = head[5] == 1
        e_machine = int.from_bytes(head[18:20], "little" if little else "big")
        machines = {
            0x03: "x86", 0x3E: "x86_64", 0x28: "arm32", 0xB7: "arm64",
            0x08: "mips", 0x14: "ppc", 0x2B: "sparc", 0xF3: "riscv",
        }
        return "ELF %s %s, machine=%s(%s)" % (
            "64-bit" if is64 else "32-bit",
            "LE" if little else "BE",
            e_machine,
            machines.get(e_machine, "unknown"),
        )
    except Exception as e:
        return "Error: %s" % e
