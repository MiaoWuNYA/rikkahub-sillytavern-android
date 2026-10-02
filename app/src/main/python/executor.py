"""
Python executor for Rikkahub.
Executes Python code with stdout capture, matplotlib auto-save,
and result file detection.

Available built-in functions (call these from your code):
  query_knowledge_base(query, limit=10)         - Search knowledge base
  add_knowledge_entry(title, content)           - Add entry to knowledge base
  update_knowledge_entry(id, title, content)    - Update knowledge entry
  delete_knowledge_entry(id)                    - Delete knowledge entry
  list_knowledge_entries(limit=20)               - List knowledge base entries
  list_conversations(limit=10)                   - List recent conversations
  get_conversation_messages(conv_id)             - Read conversation messages
  list_assistants()                              - List all assistants & their key settings
  get_assistant_settings(assistant_id)           - Read full assistant settings
  update_assistant_setting(id, key, value)       - Change any assistant setting
  get_setting(key)                               - Read global app setting
  update_setting(key, value)                     - Change global app setting
  get_app_info()                                 - App version & paths

逆向引擎（Rizin + Capstone + Keystone + Unicorn + Ghidra，需 arm64 设备）：
  rev_status()                                   - 引擎是否可用
  rev_disasm(target, arch="arm64", addr=0, thumb=False, limit=200)
                                                 - 反汇编，返回 "0xADDR: BB BB  mnemonic op"
  rev_asm(asm, arch="arm64", addr=0, thumb=False)  - 汇编，返回十六进制机器码
  rev_analyze(target, arch="arm64")                - 自动分析（函数识别/符号/字符串）
  rev_functions(target, arch="arm64")              - 列出识别出的函数
  rev_xrefs(target, va, arch="arm64", direction="to")  - 交叉引用
  rev_cfg(target, func_va, arch="arm64")           - 控制流图
  rev_search(target, pattern, arch="arm64")        - 字节模式搜索（支持 ?? 通配）
  rev_crypto(target, arch="arm64")                 - 扫描加密常量（AES S-box/CRC/魔数）
  rev_esil(target, start_va, steps, arch="arm64")  - ESIL 指令级模拟
  rev_diff(file_a, file_b)                       - 二进制差异
  rev_prepare()                                    - 预热 Ghidra（反编译前先调，避免超时）
  rev_decompile(target, func_va, arch="arm64")     - 反编译为伪 C（首次较慢，会释放 sleigh）
  rev_cmd(target, command, arch="arm64")           - 执行 rizin 原生命令（如 "aaa; afl"）

  另外提供 bindisasm 模块（无需安装，直接 import）：不依赖 Rizin，
  单独走 libcapstone，适合快速反汇编一小段字节。
    import bindisasm
    bindisasm.status()                      - 引擎状态与版本
    bindisasm.disasm(data, arch, address)   - 反汇编，data 为 bytes 或 hex 串
    bindisasm.info(path)                    - 读 ELF 头，判断架构
    bindisasm.bytes_at(path, offset, length)- 按偏移读文件片段

  target 可以是绝对路径，也可以是十六进制字符串（如 "1f2003d5"）。
  arch 只接受：arm64 / arm32 / x86_64 / x86 / mips，默认 arm64。
       其它值会被拒绝——native 层遇到未知值会静默按 x86/32 处理，产生错误结果。
  建议先用 rev_analyze 列出函数，再对目标函数地址调 rev_decompile。

"""

# ── Chaquopy fix: executor replaces random.Random.__init__ with restored_init
# but doesn't inject random._traced_calls. secrets.SystemRandom() (used by
# arcanite, jingjue, taixuanshifa, ichingshifa, meihua_yi) hits:
#   AttributeError: module 'random' has no attribute '_traced_calls'
# This runs before any imports that touch random/secrets.
import random as _random
if not hasattr(_random, '_traced_calls'):
    _random._traced_calls = []

# ── 执行串行化锁 ───────────────────────────────────────────────
# execute() 会临时替换进程级的 sys.stdout / sys.stderr，并调用 os.chdir()。
# 这两者都是全局状态：并发调用时会互相踩踏，典型症状是
#   AttributeError: 'TextLogStream' object has no attribute 'getvalue'
# 因为 A 还没取回输出，B 已经把 sys.stdout 还原成系统流了。
# 用一个可重入锁把「替换流 → 执行 → 取回输出 → 还原」整段串起来。
import threading as _threading
_exec_lock = _threading.RLock()

import sys
import json
import os
from io import StringIO
import traceback

# Bridge to Android services - set from Kotlin via execute() parameter
_bridge = None


# ============================================================
# Bridge wrapper functions
# ============================================================

def query_knowledge_base(query, limit=10):
    if _bridge:
        try:
            return _bridge.queryKnowledgeBase(query, limit)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def add_knowledge_entry(title, content, assistant_id=None):
    if _bridge:
        try:
            return _bridge.addKnowledgeEntry(title, content, assistant_id)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def list_knowledge_entries(limit=20):
    if _bridge:
        try:
            return _bridge.listKnowledgeEntries(limit)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def list_conversations(limit=10):
    if _bridge:
        try:
            return _bridge.listConversations(limit)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def get_conversation_messages(conversation_id, limit=50):
    if _bridge:
        try:
            return _bridge.getConversationMessages(conversation_id, limit)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def get_app_info():
    if _bridge:
        try:
            return _bridge.getAppInfo()
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def list_assistants():
    if _bridge:
        try:
            return _bridge.listAssistants()
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def get_assistant_settings(assistant_id):
    if _bridge:
        try:
            return _bridge.getAssistantSettings(assistant_id)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def update_assistant_setting(assistant_id, key, value):
    if _bridge:
        try:
            return _bridge.updateAssistantSetting(assistant_id, key, value)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def update_knowledge_entry(entry_id, title=None, content=None):
    if _bridge:
        try:
            return _bridge.updateKnowledgeEntry(entry_id, title, content)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def delete_knowledge_entry(entry_id):
    if _bridge:
        try:
            return _bridge.deleteKnowledgeEntry(entry_id)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def get_setting(key):
    if _bridge:
        try:
            return _bridge.getSetting(key)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def update_setting(key, value):
    if _bridge:
        try:
            return _bridge.updateSetting(key, value)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"


# ============================================================
# Main executor
# ============================================================

def execute(code: str, workdir: str, bridge=None) -> str:
    """Execute Python code, return JSON with results.

    整个函数体在 _exec_lock 内运行：输出捕获与工作目录切换都是进程级全局状态，
    必须串行，否则并发调用会互相破坏（详见 _exec_lock 注释）。
    """
    global _bridge
    with _exec_lock:
        return _execute_locked(code, workdir, bridge)


def _execute_locked(code: str, workdir: str, bridge=None) -> str:
    global _bridge
    _bridge = bridge
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = StringIO()
    sys.stderr = StringIO()

    # List files before execution
    before = set()
    try:
        before = set(os.listdir(workdir))
    except Exception:
        pass

    result = None
    error = None
    output_files = []

    try:
        os.chdir(workdir)
    except Exception:
        pass

    # Pre-configure matplotlib
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        plt.rcParams['figure.facecolor'] = 'white'
        plt.rcParams['axes.facecolor'] = 'white'
        plt.rcParams['savefig.facecolor'] = 'white'
    except ImportError:
        pass

    try:
        # 用户代码的命名空间。
        #
        # 两个约束必须同时满足：
        # 1) 必须是独立字典，不能直接用 globals()——否则用户定义的变量会污染
        #    executor 模块本身，多次调用互相串值。
        # 2) 必须能看见本模块的所有公开函数（query_knowledge_base、
        #    capstone_disasm、rev_* 等），因为工具描述就是让模型直接调用它们的。
        #    裸 exec(code) 天然满足第 2 点，所以这里手动把模块全局拷进来再覆盖
        #    __name__，等价于「一个新的模块级命名空间」。
        #
        # globals 与 locals 传同一份 _scope，这样模块级的 def / lambda 才能
        # 看到顶层变量（若传两份，def 的 __globals__ 指向另一份字典会 NameError）。
        _scope = dict(globals())
        _scope["__name__"] = "__main__"
        result = _run_user_code(code, _scope)

        # Auto-save matplotlib figures
        try:
            import matplotlib.pyplot as plt
            for i, fig_num in enumerate(plt.get_fignums()):
                fig = plt.figure(fig_num)
                fname = "figure_{}.png".format(i+1) if plt.get_fignums() else "figure.png"
                fig.savefig(os.path.join(workdir, fname), dpi=150,
                           bbox_inches='tight', facecolor='white', edgecolor='none')
                output_files.append(fname)
                plt.close(fig)
        except ImportError:
            pass

    except Exception as e:
        error = "{}\n{}".format(e, traceback.format_exc())

    finally:
        # 防御性取回：即使有外部代码在用户代码里改掉了 sys.stdout，
        # 也不能让清理路径本身抛异常（否则真的会把异常盖住）。
        stdout = _safe_getvalue(sys.stdout)
        stderr = _safe_getvalue(sys.stderr)
        sys.stdout = old_stdout
        sys.stderr = old_stderr

        # Find new files
        try:
            after = set(os.listdir(workdir))
            for f in after - before:
                if not f.startswith('.'):
                    fpath = os.path.join(workdir, f)
                    if os.path.isfile(fpath) and os.path.getsize(fpath) > 0:
                        output_files.append(f)
        except Exception:
            pass

    resp = {}
    if error:
        resp["error"] = error
    if stdout:
        resp["stdout"] = stdout
    if stderr:
        resp["stderr"] = stderr
    if result is not None and not error:
        resp["result"] = str(result)
    if output_files:
        resp["files"] = list(set(output_files))
    if not resp:
        resp["result"] = "ok"
    return json.dumps(resp)


# ============================================================
# 逆向引擎桥接（Rizin / Ghidra）
# 所有函数在引擎不可用时返回以 "Error:" 开头的可读字符串，不抛异常。
# ============================================================

def _run_user_code(code, scope):
    """执行用户代码，并返回最后一个表达式的值。

    直接 "eval 失败就 exec" 的写法有个真实缺陷：多语句代码若以表达式结尾，
    表达式的结果会被丢掉。例如
        a = 5
        a + 1
    原实现走 exec 分支，result 恒为 None，用户拿不到 6。

    这里按 AST 拆分：前面部分 exec，最后一条若是表达式则单独 eval 取回其值。
    这样单表达式、纯语句、语句+表达式三种形态都符合直觉。
    """
    import ast as _ast
    tree = _ast.parse(code, "<user_code>", "exec")
    if not tree.body:
        return None

    if isinstance(tree.body[-1], _ast.Expr):
        head = _ast.Module(body=tree.body[:-1], type_ignores=[])
        tail = _ast.Expression(body=tree.body[-1].value)
        if head.body:
            exec(compile(head, "<user_code>", "exec"), scope, scope)
        return eval(compile(tail, "<user_code>", "eval"), scope, scope)

    exec(compile(tree, "<user_code>", "exec"), scope, scope)
    return None


def _safe_getvalue(stream):
    """安全读取捕获流；流被替换或没有 getvalue 时回退为空串。"""
    try:
        if hasattr(stream, 'getvalue'):
            return stream.getvalue()
        return ''
    except Exception:
        return ''


def _rev(fn, *args, **kwargs):
    if not _bridge:
        return "Bridge not available"
    try:
        return getattr(_bridge, fn)(*args, **kwargs)
    except Exception as e:
        return f"Error: {e}"

def rev_status():
    """逆向引擎是否可用。"""
    return _rev("rizinStatus")

def rev_disasm(target, arch="arm64", addr=0, thumb=False, limit=200):
    """反汇编。target 为文件路径或十六进制串。"""
    return _rev("rizinDisasm", target, arch, int(addr), bool(thumb), int(limit))

def rev_asm(asm, arch="arm64", addr=0, thumb=False):
    """把汇编指令转成十六进制机器码。"""
    return _rev("rizinAsm", asm, arch, int(addr), bool(thumb))

def rev_analyze(target, arch="arm64"):
    """自动分析：函数边界、符号、字符串识别。"""
    return _rev("rizinAnalyze", target, arch)

def rev_functions(target, arch="arm64"):
    """列出识别出的函数及其地址。"""
    return _rev("rizinFunctions", target, arch)

def rev_xrefs(target, va, arch="arm64", direction="to"):
    """交叉引用。direction 取 'to' 或 'from'。"""
    return _rev("rizinXrefs", target, int(va), arch, direction)

def rev_cfg(target, func_va, arch="arm64"):
    """某个函数的控制流图。"""
    return _rev("rizinCfg", target, int(func_va), arch)

def rev_search(target, pattern, arch="arm64", from_va=0, to_va=0):
    """字节模式搜索，pattern 支持空格与 ?? 通配，如 '1f 20 ?? d5'。"""
    return _rev("rizinSearchBytes", target, pattern, arch, int(from_va), int(to_va))

def rev_crypto(target, arch="arm64"):
    """扫描常见加密常量（AES S-box、CRC 表、哈希魔数）。"""
    return _rev("rizinScanCrypto", target, arch)

def rev_esil(target, start_va, steps, arch="arm64"):
    """ESIL 指令级模拟执行。"""
    return _rev("rizinEsil", target, int(start_va), int(steps), arch)

def rev_diff(file_a, file_b):
    """比较两个二进制（文件路径）。"""
    return _rev("rizinDiff", file_a, file_b)

def rev_prepare():
    """预热 Ghidra（首次反编译前调用，避免撞上工具超时）。可重复调用。"""
    return _rev("rizinPrepare")

def rev_decompile(target, func_va, arch="arm64"):
    """反编译为伪 C 代码。首次调用会释放 sleigh 数据，可能较慢。"""
    return _rev("rizinDecompile", target, int(func_va), arch)

def rev_cmd(target, command, arch="arm64"):
    """执行 rizin 原生命令，多条用 ; 分隔，如 'aaa; afl'、'iS'、'iz'。"""
    return _rev("rizinCmd", target, command, arch)


# ============================================================
# 轻量反汇编（libcapstone，独立于 Rizin）
# 引擎不可用或输入无效时返回 'Error: ...' 文本，不抛异常。
# ============================================================

def capstone_status():
    """反汇编引擎（libcapstone）是否可用及版本。"""
    try:
        import bindisasm
        return bindisasm.status()
    except Exception as e:
        return "Error: %s" % e

def capstone_disasm(data, arch="arm64", address=0, count=0):
    """用 libcapstone 反汇编。

    data    : bytes 或十六进制字符串，如 "1f2003d5"
    arch    : arm64/arm32/thumb/x86/x86_64/mips/mips64/riscv/riscv64/ppc/sparc
    address : 首字节虚拟地址
    count   : 最多反汇编条数，0 为不限
    返回 '0xADDR: BB BB  mnemonic op' 逐行文本。
    """
    try:
        import bindisasm
        return bindisasm.disasm(data, arch=arch, address=int(address), count=int(count))
    except Exception as e:
        return "Error: %s" % e

def elf_info(path):
    """读取 ELF 头，判断位数/端序/机器类型。"""
    try:
        import bindisasm
        return bindisasm.info(path)
    except Exception as e:
        return "Error: %s" % e
