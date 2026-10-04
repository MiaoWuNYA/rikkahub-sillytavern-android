"""
代码检查与语言识别。

设计原则：**能力边界必须诚实**。
设备上没有 gcc/clang/javac，也没有任何第三方 linter，所以除了 Python
之外的语言都只能做浅层结构检查。这里会明确标注哪些结论是可靠的、
哪些只是启发式提示，而不是假装能编译。

语言识别同样不靠内容猜测：pygments 的 guess_lexer 实测极不可靠
（Java 判成 Text only、Go 判成 GDScript、Python 判成 Tera Term macro）。
改为「文件名优先 + 特征打分兜底」，文件名没给时才退化到特征判断。
"""

import json
import os
import re

# ============================================================
# 语言识别
# ============================================================

# 扩展名 → 语言。这是最可靠的判据，优先使用。
EXT_MAP = {
    '.py': 'python', '.pyw': 'python', '.pyi': 'python',
    '.js': 'javascript', '.mjs': 'javascript', '.cjs': 'javascript', '.jsx': 'javascript',
    '.ts': 'typescript', '.tsx': 'typescript',
    '.java': 'java', '.kt': 'kotlin', '.kts': 'kotlin', '.scala': 'scala',
    '.c': 'c', '.h': 'c', '.cc': 'cpp', '.cpp': 'cpp', '.cxx': 'cpp',
    '.hpp': 'cpp', '.hh': 'cpp', '.hxx': 'cpp',
    '.cs': 'csharp', '.go': 'go', '.rs': 'rust', '.rb': 'ruby', '.php': 'php',
    '.swift': 'swift', '.m': 'objectivec', '.mm': 'objectivec',
    '.sh': 'shell', '.bash': 'shell', '.zsh': 'shell', '.fish': 'shell',
    '.ps1': 'powershell', '.bat': 'batch', '.cmd': 'batch',
    '.sql': 'sql', '.lua': 'lua', '.pl': 'perl', '.pm': 'perl',
    '.r': 'r', '.jl': 'julia', '.dart': 'dart', '.hs': 'haskell',
    '.json': 'json', '.yaml': 'yaml', '.yml': 'yaml', '.toml': 'toml',
    '.xml': 'xml', '.html': 'html', '.htm': 'html', '.css': 'css',
    '.scss': 'scss', '.less': 'less', '.md': 'markdown', '.rst': 'rst',
    '.ini': 'ini', '.cfg': 'ini', '.conf': 'ini', '.env': 'ini',
    '.dockerfile': 'dockerfile', '.makefile': 'makefile', '.cmake': 'cmake',
    '.gradle': 'groovy', '.groovy': 'groovy', '.vue': 'vue', '.svelte': 'svelte',
    '.tex': 'latex', '.proto': 'protobuf', '.graphql': 'graphql',
    '.diff': 'diff', '.patch': 'diff',
}

# 无扩展名文件的专属名字
NAME_MAP = {
    'dockerfile': 'dockerfile', 'makefile': 'makefile', 'gnumakefile': 'makefile',
    'cmakelists.txt': 'cmake', 'rakefile': 'ruby', 'gemfile': 'ruby',
    'cargo.toml': 'toml', 'go.mod': 'gomod', 'requirements.txt': 'ini',
    '.gitignore': 'ini', '.gitattributes': 'ini', '.editorconfig': 'ini',
}

# 内容特征：文件名缺失或不可靠时才会用到。
# 每条是 (语言, 正则, 权重)。权重高的是该语言特有的强特征。
CONTENT_SIGNS = [
    ('python', r'^\s*(def |class |import |from \S+ import |if __name__\s*==)', re.M, 3),
    ('python', r':\s*$', re.M, 1),
    ('java', r'\b(public|private|protected)\s+(static\s+)?(final\s+)?\w+\s+\w+\s*\([^)]*\)\s*\{', 0, 4),
    ('java', r'^\s*package\s+[\w.]+;', re.M, 4),
    ('java', r'\bimport\s+java\.', 0, 4),
    ('kotlin', r'\bfun\s+\w+\s*\(', 0, 3),
    ('kotlin', r'\bval\s+\w+\s*[:=]', 0, 2),
    ('javascript', r'\b(function|const|let|var)\s+\w+\s*[=(]', 0, 2),
    ('javascript', r'=>\s*\{', 0, 2),
    ('javascript', r'\bconsole\.(log|error)\s*\(', 0, 3),
    ('typescript', r':\s*(string|number|boolean|any)\b', 0, 3),
    ('typescript', r'\binterface\s+\w+\s*\{', 0, 3),
    ('c', r'#include\s*<[\w./]+\.h>', 0, 4),
    ('c', r'\bint\s+main\s*\(', 0, 4),
    ('cpp', r'#include\s*<(iostream|vector|string|map)>', 0, 5),
    ('cpp', r'\bstd::', 0, 4),
    ('cpp', r'\btemplate\s*<', 0, 3),
    ('csharp', r'\busing\s+System\b', 0, 5),
    ('csharp', r'\bnamespace\s+[\w.]+', 0, 2),
    ('go', r'^\s*package\s+\w+\s*$', re.M, 4),
    ('go', r'\bfunc\s+\w*\s*\([^)]*\)\s*[\w\[\]\*]*\s*\{', 0, 3),
    ('go', r':=', 0, 3),
    ('rust', r'\bfn\s+\w+\s*\(', 0, 4),
    ('rust', r'\blet\s+mut\s+', 0, 4),
    ('rust', r'\buse\s+std::', 0, 3),
    ('ruby', r'^\s*(def |class |module |require )', re.M, 3),
    ('ruby', r'\bend\s*$', re.M, 2),
    ('php', r'<\?php', 0, 6),
    ('swift', r'\bimport\s+(Foundation|UIKit|SwiftUI)\b', 0, 5),
    ('swift', r'\bfunc\s+\w+\s*\([^)]*\)\s*->', 0, 3),
    ('shell', r'^#!\s*/(usr/)?bin/(env\s+)?(ba|z|da)?sh', re.M, 6),
    ('shell', r'\b(echo|export|fi|then|esac)\b', 0, 1),
    ('sql', r'\b(SELECT|INSERT|UPDATE|DELETE|CREATE\s+TABLE)\b', re.I, 3),
    ('json', r'^\s*[\{\[]', 0, 2),
    ('yaml', r'^[\w\-]+\s*:\s*\S', re.M, 2),
    ('html', r'<!DOCTYPE\s+html|<html|</div>', re.I, 5),
    ('css', r'[\w\-.]+\s*\{[^}]*:\s*[^}]+;', 0, 2),
    ('xml', r'^\s*<\?xml', 0, 5),
    ('markdown', r'^#{1,6}\s+\S', re.M, 3),
    ('markdown', r'```', 0, 2),
    ('dockerfile', r'^\s*(FROM|RUN|COPY|CMD|ENTRYPOINT)\s', re.M, 6),
]


def detect_language(code, filename=None):
    """
    识别代码语言。

    返回 (language, confidence, reason)。
    优先按文件名——这是唯一可靠的判据；没给文件名才退到特征打分。
    """
    if filename:
        base = os.path.basename(filename).lower()
        if base in NAME_MAP:
            return NAME_MAP[base], 'high', f'文件名 {base}'
        ext = os.path.splitext(base)[1]
        if ext in EXT_MAP:
            return EXT_MAP[ext], 'high', f'扩展名 {ext}'

    if not code or not code.strip():
        return 'text', 'low', '内容为空'

    scores = {}
    for lang, pattern, flags, weight in CONTENT_SIGNS:
        try:
            if re.search(pattern, code, flags if isinstance(flags, int) else 0):
                scores[lang] = scores.get(lang, 0) + weight
        except re.error:
            continue

    if not scores:
        return 'text', 'low', '没有匹配到任何语言特征'

    best = max(scores.items(), key=lambda kv: kv[1])
    total = sum(scores.values())
    # 「唯一且明显领先」才算有把握，否则只能说猜的
    if best[1] >= 4 and best[1] >= total * 0.6:
        return best[0], 'medium', f'特征得分 {best[1]}/{total}'
    return best[0], 'low', f'特征模糊（得分 {best[1]}/{total}，仅供参考）'


# ============================================================
# 括号与结构检查（所有语言通用，浅层但可靠）
# ============================================================

PAIRS = {'(': ')', '[': ']', '{': '}'}
CLOSERS = {v: k for k, v in PAIRS.items()}

# 各语言的注释与字符串规则，用于在检查括号时跳过它们，
# 否则 "}" 出现在字符串或注释里会误判。
def _strip_comments_and_strings(code, line_comment='//', block=('/*', '*/'),
                                quotes=('"', "'"), triple=None):
    """把注释与字符串内容替换成等长空白，保留换行以维持行号。"""
    out = []
    i = 0
    n = len(code)
    while i < n:
        ch = code[i]

        # 三引号字符串（Python）
        if triple and code.startswith(triple, i):
            end = code.find(triple, i + len(triple))
            if end == -1:
                end = n - len(triple)
            seg = code[i:end + len(triple)]
            out.append(''.join('\n' if c == '\n' else ' ' for c in seg))
            i = end + len(triple)
            continue

        # 行注释
        if line_comment and code.startswith(line_comment, i):
            end = code.find('\n', i)
            if end == -1:
                end = n
            out.append(' ' * (end - i))
            i = end
            continue

        # 块注释
        if block and code.startswith(block[0], i):
            end = code.find(block[1], i + len(block[0]))
            end = n if end == -1 else end + len(block[1])
            seg = code[i:end]
            out.append(''.join('\n' if c == '\n' else ' ' for c in seg))
            i = end
            continue

        # 字符串
        if ch in quotes:
            j = i + 1
            while j < n:
                if code[j] == '\\':
                    j += 2
                    continue
                if code[j] == ch:
                    break
                if code[j] == '\n' and ch != '`':
                    # 未闭合的字符串，到此为止
                    break
                j += 1
            seg = code[i:j + 1]
            out.append(''.join('\n' if c == '\n' else ' ' for c in seg))
            i = j + 1
            continue

        out.append(ch)
        i += 1
    return ''.join(out)


def check_brackets(code, language):
    """检查括号配对。所有语言通用，结论可靠（不依赖编译器）。"""
    rules = {
        'python': dict(line_comment='#', block=None, quotes=('"', "'"), triple='"""'),
        'shell': dict(line_comment='#', block=None, quotes=('"', "'"), triple=None),
        'yaml': dict(line_comment='#', block=None, quotes=('"', "'"), triple=None),
        'ruby': dict(line_comment='#', block=None, quotes=('"', "'"), triple=None),
        'sql': dict(line_comment='--', block=('/*', '*/'), quotes=('"', "'"), triple=None),
    }
    r = rules.get(language, dict(line_comment='//', block=('/*', '*/'),
                                 quotes=('"', "'", '`'), triple=None))
    if language in ('json',):
        r = dict(line_comment=None, block=None, quotes=('"',), triple=None)

    cleaned = _strip_comments_and_strings(code, **r)

    stack = []
    problems = []
    for idx, ch in enumerate(cleaned):
        if ch in PAIRS:
            stack.append((ch, idx))
        elif ch in CLOSERS:
            if not stack:
                line = cleaned.count('\n', 0, idx) + 1
                problems.append(f'第 {line} 行有多余的 {ch}')
            else:
                op, oidx = stack.pop()
                if PAIRS[op] != ch:
                    line = cleaned.count('\n', 0, idx) + 1
                    problems.append(
                        f'第 {line} 行的 {ch} 与第 {cleaned.count(chr(10), 0, oidx) + 1} 行的 {op} 不匹配')
    for op, oidx in stack:
        line = cleaned.count('\n', 0, oidx) + 1
        problems.append(f'第 {line} 行的 {op} 没有闭合')
    return problems


# ============================================================
# Python：真正的静态检查（pyflakes）
# ============================================================

def check_python(code, filename='<code>'):
    """
    用 pyflakes 做真正的静态分析。

    pyflakes 是纯 Python 包，在设备上能跑，能查出：
    未定义的名字、未使用的 import/变量、重复定义、
    f-string 占位符缺失、以及语法错误本身。

    这是本模块里唯一「能真正判定代码有错」的检查。
    """
    import io
    import ast

    results = []

    # 先做语法检查——语法不过就没必要继续
    try:
        ast.parse(code, filename=filename)
    except SyntaxError as e:
        return {
            'language': 'python',
            'reliable': True,
            'syntax_ok': False,
            'issues': [{
                'line': e.lineno or 0,
                'col': e.offset or 0,
                'kind': 'syntax',
                'message': e.msg or 'syntax error',
            }],
            'note': '语法错误，代码无法运行。',
        }

    try:
        import pyflakes.api
        import pyflakes.reporter

        out = io.StringIO()
        reporter = pyflakes.reporter.Reporter(out, out)
        pyflakes.api.check(code, filename, reporter)
        raw = out.getvalue()
    except ImportError:
        return {
            'language': 'python',
            'reliable': True,
            'syntax_ok': True,
            'issues': [],
            'note': '语法正确。pyflakes 未安装，跳过静态分析。',
        }

    kind_map = {
        'imported but unused': 'unused-import',
        'imported but unused in': 'unused-import',
        'assigned to but never used': 'unused-variable',
        'undefined name': 'undefined-name',
        'local variable': 'unused-variable',
        'redefinition of unused': 'redefinition',
        'f-string is missing placeholders': 'fstring',
        'unable to detect undefined names': 'star-import',
        'may be undefined': 'possibly-undefined',
        "'...' imported but unused": 'unused-import',
    }

    for line in raw.splitlines():
        m = re.match(r'^[^:]+:(\d+):(\d+)?:?\s*(.*)$', line)
        if not m:
            continue
        lineno = int(m.group(1))
        msg = (m.group(3) or '').strip()
        if not msg:
            continue
        kind = 'other'
        for key, k in kind_map.items():
            if key in msg:
                kind = k
                break
        results.append({
            'line': lineno,
            'col': int(m.group(2)) if m.group(2) else 0,
            'kind': kind,
            'message': msg,
        })

    return {
        'language': 'python',
        'reliable': True,
        'syntax_ok': True,
        'issues': results,
        'note': '语法正确。' + ('发现问题（见 issues）。' if results else '未发现问题。'),
    }


# ============================================================
# 其他语言：浅层检查（明确标注不可靠）
# ============================================================

# 各语言的常见可疑写法。这些只是启发式，绝不等于「代码有问题」。
HEURISTICS = {
    'c': [
        (r'^\s*#include\s*<\w+\s*$', '可能的 #include 缺少右尖括号'),
        (r'\b(if|while|for)\s*\([^)]*\)\s*[^;{\s]', 'if/while/for 后建议加花括号'),
        (r'\bchar\s*\*\s*\w+\s*=\s*"[^"]*"\s*;', '字符串字面量赋值给非 const 指针'),
        (r'\bgets\s*\(', 'gets() 已废弃且有缓冲区溢出风险，改用 fgets()'),
        (r'\bstrcpy\s*\(|\bstrcat\s*\(|\bsprintf\s*\(', '无边界检查的字符串函数，考虑 strncpy/snprintf'),
        (r'\bmalloc\s*\([^)]*\)\s*;', 'malloc 返回值未检查'),
        (r'\bprintf\s*\(\s*\w+\s*\)', 'printf 直接把变量当格式串，应写 printf("%s", x)'),
    ],
    'cpp': [
        (r'\bgets\s*\(', 'gets() 已废弃且有缓冲区溢出风险'),
        (r'\bstrcpy\s*\(|\bstrcat\s*\(|\bsprintf\s*\(', '无边界检查的字符串函数'),
        (r'\bnew\b[^;]*;', '检查 new 的配对 delete 或改用智能指针'),
        (r'\bmalloc\s*\(', 'C++ 中优先用 new / 智能指针'),
        (r'\busing namespace std\s*;', '头文件中 using namespace 会污染调用方'),
    ],
    'java': [
        (r'==\s*"[^"]*"', '字符串比较用 == 比的是引用，应改用 .equals()'),
        (r'\.equals\s*\(\s*null\s*\)', 'equals(null) 恒为 false'),
        (r'\bSystem\.exit\s*\(', 'System.exit 会终止整个进程'),
        (r'\bcatch\s*\(\s*\w+\s+\w+\s*\)\s*\{\s*\}', '空的 catch 块会吞掉异常'),
        (r'\bThread\.sleep\s*\(', 'Thread.sleep 会阻塞线程'),
    ],
    'javascript': [
        (r'[^=!]==[^=]', '建议用 === 而非 ==（会做类型转换）'),
        (r'[^=!]!=[^=]', '建议用 !== 而非 !='),
        (r'\bvar\s+', 'var 有函数作用域问题，建议 let/const'),
        (r'\beval\s*\(', 'eval 有安全与性能风险'),
        (r'\bdocument\.write\s*\(', 'document.write 会阻塞解析'),
        (r'\bfor\s*\(\s*var\s+\w+\s+in\s+', 'for-in 会遍历原型链，建议 for-of 或 Object.keys'),
    ],
    'typescript': [
        (r':\s*any\b', 'any 会关闭类型检查'),
        (r'[^=!]==[^=]', '建议用 === 而非 =='),
        (r'@ts-ignore', '@ts-ignore 会静默压制错误，建议 @ts-expect-error'),
    ],
    'go': [
        (r'\bif\s+err\s*!=\s*nil\s*\{', '检查是否真的处理了 err（而不是只打了日志）'),
        (r':=.*\n.*:=\s*$', '检查 := 是否意外遮蔽了外层变量'),
        (r'\bpanic\s*\(', 'panic 应只用于不可恢复的错误'),
        (r'\bfmt\.Print(f|ln)?\s*\(', '生产代码建议用带级别的日志'),
    ],
    'rust': [
        (r'\.unwrap\s*\(\s*\)', 'unwrap 会在 None/Err 时 panic，考虑 ? 或 match'),
        (r'\.expect\s*\(', 'expect 同样会 panic，确认这是预期行为'),
        (r'\bpanic!\s*\(', 'panic! 应只用于不可恢复的错误'),
        (r'\bunsafe\s*\{', 'unsafe 块需要人工确认安全性'),
    ],
    'shell': [
        (r'\brm\s+-rf\s+/', '危险的 rm -rf /，务必确认路径'),
        (r'\brm\s+-rf\s+\$\w+', 'rm -rf 变量展开结果，变量为空时会误删'),
        (r'[^"]\$[\w]+[^"]', '变量未加引号，含空格时会词分裂'),
        (r'\bcd\s+\$\w+\s*&&', 'cd 失败时 && 之后的命令不会执行，注意检查'),
    ],
}

HEURISTIC_NOTE = (
    '设备上没有该语言的编译器或分析器，以下只是基于常见写法的启发式提示，'
    '**不代表代码有错**，需要人工确认。'
)


def check_generic(code, language, filename='<code>'):
    """非 Python 语言的浅层检查：括号配对 + 常见可疑写法。"""
    issues = []

    for msg in check_brackets(code, language):
        issues.append({
            'line': int(re.search(r'第 (\d+) 行', msg).group(1)) if '第' in msg else 0,
            'kind': 'bracket',
            'message': msg,
        })

    for pattern, msg in HEURISTICS.get(language, []):
        try:
            for m in re.finditer(pattern, code, re.M):
                line = code.count('\n', 0, m.start()) + 1
                issues.append({'line': line, 'kind': 'heuristic', 'message': msg})
        except re.error:
            continue

    # 去重并按行号排序
    seen = set()
    uniq = []
    for it in sorted(issues, key=lambda x: x['line']):
        key = (it['line'], it['message'])
        if key not in seen:
            seen.add(key)
            uniq.append(it)

    return {
        'language': language,
        'reliable': False,
        'syntax_ok': None,
        'issues': uniq,
        'note': HEURISTIC_NOTE,
    }


# ============================================================
# 统一入口
# ============================================================

def check(code, filename=None, language=None):
    """
    检查一段代码。

    参数：
      code      代码文本
      filename  文件名（强烈建议提供，这是语言识别最可靠的依据）
      language  直接指定语言，跳过识别

    返回 JSON 字符串，字段：
      language     识别出的语言
      confidence   识别把握（high/medium/low）
      detected_by  识别依据
      reliable     检查结论是否可信。False 表示只是启发式提示
      syntax_ok    语法是否正确（只有 Python 能给出确定答案，其余为 null）
      issues       问题列表，每项含 line/kind/message
      note         给模型看的说明
    """
    if not isinstance(code, str):
        return _err('code 必须是字符串')
    if len(code) > 2_000_000:
        return _err('代码过长（超过 2 MB）')

    if language:
        lang, conf, why = language.lower(), 'high', '调用方指定'
    else:
        lang, conf, why = detect_language(code, filename)

    display_name = None
    try:
        from pygments.lexers import get_lexer_by_name
        display_name = get_lexer_by_name(lang).name
    except Exception:
        pass

    try:
        if lang == 'python':
            result = check_python(code, filename or '<code>')
        else:
            result = check_generic(code, lang, filename or '<code>')
    except Exception as e:
        return _err(f'检查过程出错: {type(e).__name__}: {e}')

    result['confidence'] = conf
    result['detected_by'] = why
    if display_name:
        result['lexer'] = display_name
    return json.dumps(result, ensure_ascii=False)


def _err(msg):
    return json.dumps({'error': msg}, ensure_ascii=False)


def lex(code, filename=None, language=None):
    """
    词法分析：把代码切成 token。

    用途和 check() 不同——这里不求证对错，只提取结构信息
    （关键字出现情况、标识符、注释、字符串），可以用来做代码摘要。
    """
    if not isinstance(code, str):
        return _err('code 必须是字符串')

    if language:
        lang = language.lower()
    else:
        lang, _, _ = detect_language(code, filename)

    try:
        from pygments import lex as pygments_lex
        from pygments.lexers import get_lexer_by_name
        from pygments.token import Token
    except ImportError:
        return _err('pygments 未安装')

    try:
        lexer = get_lexer_by_name(lang)
    except Exception:
        return _err(f'不支持的语言: {lang}')

    counts = {}
    keywords = []
    comments = []
    strings = []

    for token_type, value in pygments_lex(code, lexer):
        if token_type in Token.Text or token_type in Token.Whitespace:
            continue
        name = str(token_type).split('.')[0]
        counts[name] = counts.get(name, 0) + 1

        if token_type in Token.Keyword and value.strip():
            keywords.append(value.strip())
        elif token_type in Token.Comment and value.strip():
            comments.append(value.strip()[:200])
        elif token_type in Token.Literal.String and value.strip():
            strings.append(value.strip()[:200])

    return json.dumps({
        'language': lang,
        'token_counts': counts,
        'keywords': keywords[:200],
        'comments': comments[:50],
        'strings': strings[:50],
    }, ensure_ascii=False)
