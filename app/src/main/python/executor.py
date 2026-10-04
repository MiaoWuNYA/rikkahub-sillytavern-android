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

# 并发上限：锁保证了正确性，但一堆调用排队等待时仍会占满线程与内存。
# 这里限制同时在跑的执行数，超出的调用会阻塞等待而不是失败——
# 对模型表现为"稍慢"，而不是报错。
#
# 上限取 8：本机实测 16 线程 × 6 次共 96 次调用零错误，耗时随线程数线性增长，
# 说明排队本身是健康的。原先取 4 时，Android 侧的单工具超时（默认 120s）
# 会把「排队等待」也算进去，8 并发下排在后面的调用会在轮到之前就被
# Generation cancelled 掐断——那看起来像崩溃，其实是排队加超时的组合效应。
# 提高上限可显著降低这种误伤，同时仍保有过载保护。
#
# 注意：threading.Semaphore 没有公开的 .value 属性，内部是 ._value。
# 在任意一次调用内部读到 ._value == 7 是正常的（当前调用自己占了一个名额）。
_exec_sem = _threading.Semaphore(8)

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
    with _exec_sem:
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
        # 2) 必须能看见本模块的所有公开函数（query_knowledge_base 等），
        #    因为工具描述就是让模型直接调用它们的。
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
