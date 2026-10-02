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
# 关键：bytes 数组长度随版本变化——
#   capstone 4.x: uint8_t bytes[16]   → sizeof(cs_insn) = 240
#   capstone 5.x: uint8_t bytes[24]   → sizeof(cs_insn) = 248
# 写死任何一个长度，在另一个版本上整个结构体偏移都会错 8 字节：
# mnemonic 会读到相邻字段，地址会变成乱码，字节列会读到结构体外的内存
# （表现为每次运行输出都不一样）。
#
# 因此这里不硬编码，而是运行时用 cs_version() 探测主版本再选布局。
# cs_disasm 返回的是连续数组，步长必须与真实 sizeof 一致，别无选择。
def _make_insn_struct(bytes_len):
    class _CsInsn(ctypes.Structure):
        _fields_ = [
            ("id", ctypes.c_uint),
            ("address", ctypes.c_uint64),
            ("size", ctypes.c_uint16),
            ("bytes", ctypes.c_ubyte * bytes_len),
            ("mnemonic", ctypes.c_char * 32),   # CS_MNEMONIC_SIZE，4/5 均为 32
            ("op_str", ctypes.c_char * 160),
            ("detail", ctypes.c_void_p),        # cs_detail*
        ]
    return _CsInsn


# 4.x 用 16，其余（含 5.x）用 24
_CsInsn = _make_insn_struct(16)
_INSN_BYTES_LEN = 16
_INSN_LAYOUT_LOCK = __import__("threading").Lock()
_INSN_LAYOUT_DETECTED = False


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


def _set_disasm_sig(lib):
    """按当前 _CsInsn 设定 cs_disasm 的参数类型。"""
    lib.cs_disasm.argtypes = [
        ctypes.c_void_p, ctypes.c_char_p, ctypes.c_size_t,
        ctypes.c_uint64, ctypes.c_size_t,
        ctypes.POINTER(ctypes.POINTER(_CsInsn)),
    ]
    lib.cs_disasm.restype = ctypes.c_size_t
    lib.cs_free.argtypes = [ctypes.POINTER(_CsInsn), ctypes.c_size_t]
    lib.cs_free.restype = None


def _detect_insn_layout(lib):
    """按实际 capstone 主版本选择 cs_insn 布局。

    这是此前输出乱码的根因：capstone 4 的 bytes[16] 与 5 的 bytes[24]
    使 sizeof 相差 8 字节，按错版本解读会导致助记符错位、地址乱码、
    以及越界读取（字节列每次运行都不同）。
    """
    global _CsInsn, _INSN_BYTES_LEN, _INSN_LAYOUT_DETECTED
    with _INSN_LAYOUT_LOCK:
        if _INSN_LAYOUT_DETECTED:
            return
        try:
            lib.cs_version.argtypes = [ctypes.POINTER(ctypes.c_int),
                                       ctypes.POINTER(ctypes.c_int)]
            lib.cs_version.restype = ctypes.c_uint
            major, minor = ctypes.c_int(), ctypes.c_int()
            lib.cs_version(ctypes.byref(major), ctypes.byref(minor))
            # 先解 4 字节 ARM 指令，用两种布局各试一次，选能解出 nop 的那个。
            # 仅凭版本号判断有风险（某些发行版会回填错值），实测更可靠。
            probe = bytes.fromhex("1f2003d5")
            chosen = None
            for blen in (16, 24):
                if _try_layout(lib, blen, probe):
                    chosen = blen
                    break
            if chosen is None:
                # 都解不出时退回版本号推断
                chosen = 16 if (major.value, minor.value) < (5, 0) else 24
            _CsInsn = _make_insn_struct(chosen)
            _INSN_BYTES_LEN = chosen
            _INSN_LAYOUT_DETECTED = True
        except Exception:
            # 探测失败时保留默认布局，不阻断后续调用
            _INSN_LAYOUT_DETECTED = True


def _try_layout(lib, blen, probe):
    """用指定布局尝试解码，成功且助记符可打印才算匹配。"""
    try:
        cls = _make_insn_struct(blen)
        # 探测期间临时按本布局设签名，探测结束由调用方重设
        lib.cs_disasm.argtypes = [
            ctypes.c_void_p, ctypes.c_char_p, ctypes.c_size_t,
            ctypes.c_uint64, ctypes.c_size_t,
            ctypes.POINTER(ctypes.POINTER(cls)),
        ]
        lib.cs_disasm.restype = ctypes.c_size_t
        handle = ctypes.c_void_p()
        if lib.cs_open(ctypes.c_int(1), ctypes.c_int(0), ctypes.byref(handle)) != 0:
            return False
        try:
            ptr = ctypes.POINTER(cls)()
            buf = ctypes.create_string_buffer(probe, len(probe))
            n = lib.cs_disasm(handle, buf, len(probe), ctypes.c_uint64(0),
                              ctypes.c_size_t(1), ctypes.byref(ptr))
            if n != 1:
                return False
            b = bytes(ptr[0].bytes[:ptr[0].size])
            m = ptr[0].mnemonic
            # 校验：字节应与输入一致，助记符应全是可打印 ASCII
            if b != probe or not m:
                return False
            return all(32 <= c < 127 for c in m)
        finally:
            lib.cs_close(ctypes.byref(handle))
    except Exception:
        return False


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
        _set_disasm_sig(lib)
        lib.cs_free.argtypes = [ctypes.POINTER(_CsInsn), ctypes.c_size_t]
        lib.cs_free.restype = None
        lib.cs_close.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
        lib.cs_close.restype = ctypes.c_int
        _lib = lib
        _detect_insn_layout(lib)
        # 布局可能在探测中变化，必须按最终布局重设签名，
        # 否则 ctypes 会因 POINTER(_CsInsn) 指向旧类型而拒绝传参。
        _set_disasm_sig(lib)
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
        return "ok capstone %d.%d (cs_insn bytes[%d])" % (
            major.value, minor.value, _INSN_BYTES_LEN)
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
