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
  rev_disasm(target, arch="arm", addr=0, thumb=False, limit=200)
                                                 - 反汇编，返回 "0xADDR: BB BB  mnemonic op"
  rev_asm(asm, arch="arm", addr=0, thumb=False)  - 汇编，返回十六进制机器码
  rev_analyze(target, arch="arm")                - 自动分析（函数识别/符号/字符串）
  rev_functions(target, arch="arm")              - 列出识别出的函数
  rev_xrefs(target, va, arch="arm", direction="to")  - 交叉引用
  rev_cfg(target, func_va, arch="arm")           - 控制流图
  rev_search(target, pattern, arch="arm")        - 字节模式搜索（支持 ?? 通配）
  rev_crypto(target, arch="arm")                 - 扫描加密常量（AES S-box/CRC/魔数）
  rev_esil(target, start_va, steps, arch="arm")  - ESIL 指令级模拟
  rev_diff(file_a, file_b)                       - 二进制差异
  rev_decompile(target, func_va, arch="arm")     - 反编译为伪 C（首次较慢，会释放 sleigh）
  rev_cmd(target, command, arch="arm")           - 执行 rizin 原生命令（如 "aaa; afl"）

  target 可以是文件路径，也可以是十六进制字符串（如 "1f2003d5"）。
  arch 取值：arm / arm64 / x86 / x86_64 / mips / riscv 等。

"""

# ── Chaquopy fix: executor replaces random.Random.__init__ with restored_init
# but doesn't inject random._traced_calls. secrets.SystemRandom() (used by
# arcanite, jingjue, taixuanshifa, ichingshifa, meihua_yi) hits:
#   AttributeError: module 'random' has no attribute '_traced_calls'
# This runs before any imports that touch random/secrets.
import random as _random
if not hasattr(_random, '_traced_calls'):
    _random._traced_calls = []

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
    """Execute Python code, return JSON with results."""
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
        try:
            result = eval(code)
        except SyntaxError:
            exec(code)
            result = None

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
        stdout = sys.stdout.getvalue()
        stderr = sys.stderr.getvalue()
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

def rev_disasm(target, arch="arm", addr=0, thumb=False, limit=200):
    """反汇编。target 为文件路径或十六进制串。"""
    return _rev("rizinDisasm", target, arch, int(addr), bool(thumb), int(limit))

def rev_asm(asm, arch="arm", addr=0, thumb=False):
    """把汇编指令转成十六进制机器码。"""
    return _rev("rizinAsm", asm, arch, int(addr), bool(thumb))

def rev_analyze(target, arch="arm"):
    """自动分析：函数边界、符号、字符串识别。"""
    return _rev("rizinAnalyze", target, arch)

def rev_functions(target, arch="arm"):
    """列出识别出的函数及其地址。"""
    return _rev("rizinFunctions", target, arch)

def rev_xrefs(target, va, arch="arm", direction="to"):
    """交叉引用。direction 取 'to' 或 'from'。"""
    return _rev("rizinXrefs", target, int(va), arch, direction)

def rev_cfg(target, func_va, arch="arm"):
    """某个函数的控制流图。"""
    return _rev("rizinCfg", target, int(func_va), arch)

def rev_search(target, pattern, arch="arm", from_va=0, to_va=0):
    """字节模式搜索，pattern 支持空格与 ?? 通配，如 '1f 20 ?? d5'。"""
    return _rev("rizinSearchBytes", target, pattern, arch, int(from_va), int(to_va))

def rev_crypto(target, arch="arm"):
    """扫描常见加密常量（AES S-box、CRC 表、哈希魔数）。"""
    return _rev("rizinScanCrypto", target, arch)

def rev_esil(target, start_va, steps, arch="arm"):
    """ESIL 指令级模拟执行。"""
    return _rev("rizinEsil", target, int(start_va), int(steps), arch)

def rev_diff(file_a, file_b):
    """比较两个二进制（文件路径）。"""
    return _rev("rizinDiff", file_a, file_b)

def rev_decompile(target, func_va, arch="arm"):
    """反编译为伪 C 代码。首次调用会释放 sleigh 数据，可能较慢。"""
    return _rev("rizinDecompile", target, int(func_va), arch)

def rev_cmd(target, command, arch="arm"):
    """执行 rizin 原生命令，多条用 ; 分隔，如 'aaa; afl'、'iS'、'iz'。"""
    return _rev("rizinCmd", target, command, arch)
