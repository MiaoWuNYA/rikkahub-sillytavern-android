"""
Text diff engine for Rikkahub.

用标准库 difflib，不引入任何新依赖。输出 unified diff 加一行统计摘要。

设计取舍：
  - left/right 既可能是文件路径，也可能是模型直接贴进来的文本。
    判定规则是「这个路径真的存在吗」——存在就当文件读，不存在就当文本。
    反过来（先当文本、失败再当路径）会让一段恰好长得像路径的文字被误读，
    而文件不存在是更常见的失败情形。
  - 输出体积有上限。两个大文件全文对比能产生几万行 diff，
    一次性塞回给模型既超限也没意义，所以超出时截断并明确告知。
"""

import difflib
import json
import os

# diff 输出上限：够覆盖日常「看改了哪几行」的需求，
# 再多就说明对比对象选错了（应该先定位再对比，而不是全文对全文）。
MAX_OUTPUT_CHARS = 24_000

# 单侧输入上限。整份文件读进内存，给个上限避免误传大文件导致 OOM。
MAX_INPUT_BYTES = 4 * 1024 * 1024


def _read_side(value, force_path, side_name):
    """
    把一侧输入解析成 (文本, 来源说明)。

    force_path 为真时按路径强制读取；否则先看该路径是否存在，
    存在则读文件，不存在则把 value 当文本。
    """
    if value is None:
        return '', f'{side_name}: (empty)'

    if force_path:
        if not os.path.isfile(value):
            raise ValueError(f'{side_name} 指定为文件但不存在: {value}')
        return _read_file(value), f'{side_name}: {value}'

    if os.path.isfile(value):
        return _read_file(value), f'{side_name}: {value}'

    return value, f'{side_name}: (inline text)'


def _read_file(path):
    size = os.path.getsize(path)
    if size > MAX_INPUT_BYTES:
        raise ValueError(
            f'文件过大 ({size} 字节 > {MAX_INPUT_BYTES})，请先定位到要对比的片段'
        )
    # 编码探测交给调用方（chardet 已内置），这里先试 UTF-8 再退 GBK，
    # 因为中文环境里这两种覆盖绝大多数情况。
    data = open(path, 'rb').read()
    for enc in ('utf-8', 'gbk', 'latin-1'):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    return data.decode('utf-8', errors='replace')


def diff_entries(left, right, left_is_path=False, right_is_path=False,
                 ignore_whitespace=False, context_lines=3):
    """对比两侧并返回统一格式的文本结果。"""
    try:
        left_text, left_src = _read_side(left, left_is_path, 'left')
        right_text, right_src = _read_side(right, right_is_path, 'right')
    except Exception as e:
        return f'Error: {type(e).__name__}: {e}'

    left_lines = left_text.splitlines()
    right_lines = right_text.splitlines()

    if ignore_whitespace:
        # 只在比较时归一化，输出里仍保留原始行
        def norm(s):
            return ' '.join(s.split())
        left_cmp = [norm(l) for l in left_lines]
        right_cmp = [norm(l) for l in right_lines]
    else:
        left_cmp, right_cmp = left_lines, right_lines

    if left_cmp == right_cmp:
        same = (f'两侧完全一致（{len(left_lines)} 行'
                + ('，已忽略空白差异' if ignore_whitespace else '') + '）')
        return f'{left_src}\n{right_src}\n\n{same}'

    sm = difflib.SequenceMatcher(None, left_cmp, right_cmp, autojunk=False)
    added = removed = 0
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag in ('replace', 'delete'):
            removed += i2 - i1
        if tag in ('replace', 'insert'):
            added += j2 - j1

    context = max(0, int(context_lines))
    diff_iter = difflib.unified_diff(
        left_lines, right_lines,
        fromfile=_label(left_src, 'a'),
        tofile=_label(right_src, 'b'),
        lineterm='',
        n=context,
    )
    body_lines = list(diff_iter)

    truncated = False
    body = '\n'.join(body_lines)
    if len(body) > MAX_OUTPUT_CHARS:
        body = body[:MAX_OUTPUT_CHARS]
        # 截断在行边界上，避免留下半行
        body = body.rsplit('\n', 1)[0]
        truncated = True

    header = [
        left_src,
        right_src,
        '',
        f'+{added} 行 / -{removed} 行',
    ]
    if truncated:
        header.append(
            f'（差异超过 {MAX_OUTPUT_CHARS} 字符，输出已截断——'
            f'建议缩小对比范围或分段对比）'
        )
    header.append('')

    return '\n'.join(header) + body


def _label(src, side):
    """
    给 diff 头取一个能区分左右的标签。

    两侧都是内联文本时，如果都叫 inline，头就变成 a/inline 对 b/inline，
    看不出哪边是哪边。所以内联时带上左右标识。
    """
    if src.endswith('(inline text)'):
        return f'inline-{side}'
    if ':' in src:
        return os.path.basename(src.split(':', 1)[1].strip())
    return f'inline-{side}'


def diff_stats(left, right, **kwargs):
    """只返回统计，不返回正文。供需要摘要的调用方使用。"""
    full = diff_entries(left, right, **kwargs)
    for line in full.splitlines():
        if line.startswith('+') and line.endswith('行') and '/' in line:
            return line
    return ''
