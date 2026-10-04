"""
File conversion engine for Rikkahub.
No C extension dependencies — pure Python only.

Supported conversions:
  txt <-> md <-> html (native)
  txt/md/html -> docx
  pdf -> txt, pdf -> md, pdf -> docx (via pypdf)
  docx -> txt, docx -> md
  xlsx <-> csv, xlsx/json <-> csv/json/xlsx
  pptx -> txt, pptx -> md
  zip -> extract
  txt/md -> pdf (via fpdf2)
  epub -> txt, epub -> md
  html -> markdown (via markdownify)
  pdf -> merge (multiple PDFs into one)
  pdf -> split (one file per page)
  pdf -> images (extract embedded images, raw bytes)
  docx -> images (extract embedded images, raw bytes)
  url -> md (fetch webpage, convert to markdown)
  csv -> table (pretty ASCII table via tabulate), csv -> json, csv -> xlsx
  json -> csv, json -> xlsx, json -> yaml, json -> table (object array -> markdown table)
  yaml -> json, yaml -> yaml (normalize / reformat)
  toml -> json

输入可以给文件路径，也可以直接把文本塞进 input_text —— 模型手上常常只有
一小段文本，强制落盘再读是多余的往返。csv/json/yaml/toml 的读取都兼容两种方式。

Image conversions (bmp/gif <-> png/jpg/webp, image->pdf, gif->frames)
are handled in Kotlin via Android native APIs.

Returns: {'stdout': str, 'files': [str], 'error': str?}
"""

import io
import json
import sys
import os
import csv
import zipfile
import traceback
from io import BytesIO


def convert(input_path, input_text, from_format, to_format, output_dir):
    result = {'stdout': '', 'files': [], 'error': None}

    try:
        _text = lambda: _read_file(input_path, input_text)
        _out = lambda ext, fallback='output': _outpath(input_path, ext, output_dir, fallback)

        # ── DOCX conversions ──
        if from_format in ('txt', 'md', 'html') and to_format == 'docx':
            from docx import Document
            from bs4 import BeautifulSoup
            text = _text()
            doc = Document()
            if from_format == 'html':
                soup = BeautifulSoup(text, 'html.parser')
                for el in soup.find_all(['h1','h2','h3','h4','h5','h6','p','li']):
                    txt = el.get_text(strip=True)
                    if not txt: continue
                    tag = el.name
                    if tag == 'h1': doc.add_heading(txt, level=1)
                    elif tag == 'h2': doc.add_heading(txt, level=2)
                    elif tag in ('h3','h4','h5','h6'): doc.add_heading(txt, level=int(tag[1]))
                    elif tag == 'li': doc.add_paragraph(txt, style='List Bullet')
                    else: doc.add_paragraph(txt)
            else:
                for para in text.split('\n\n'):
                    p = para.strip()
                    if not p: continue
                    if p.startswith('# '): doc.add_heading(p[2:], level=1)
                    elif p.startswith('## '): doc.add_heading(p[3:], level=2)
                    elif p.startswith('### '): doc.add_heading(p[4:], level=3)
                    else: doc.add_paragraph(p)
            out = _out('docx')
            doc.save(out)
            result['files'].append(out)
            result['stdout'] = f'Saved: {out}'

        # ── PDF extraction (pypdf) ──
        elif from_format == 'pdf' and to_format in ('txt', 'md'):
            from pypdf import PdfReader
            reader = PdfReader(input_path)
            pages = [page.extract_text() or '' for page in reader.pages]
            output = '\n\n'.join(pages)
            if to_format == 'md':
                output = '# Extracted from PDF\n\n' + output
            result['stdout'] = output

        elif from_format == 'pdf' and to_format == 'docx':
            from pypdf import PdfReader
            from docx import Document
            doc = Document()
            reader = PdfReader(input_path)
            for page in reader.pages:
                    text = page.extract_text() or ''
                    for line in text.split('\n'):
                        if line.strip():
                            doc.add_paragraph(line.strip())
            out = _out('docx')
            doc.save(out)
            result['files'].append(out)
            result['stdout'] = f'Saved: {out}'

        # ── DOCX extraction ──
        elif from_format == 'docx' and to_format in ('txt', 'md'):
            from docx import Document
            doc = Document(input_path)
            lines = []
            for p in doc.paragraphs:
                t = p.text.strip()
                if not t: continue
                if to_format == 'md':
                    style = p.style.name.lower() if p.style else ''
                    if 'heading 1' in style: lines.append(f'# {t}')
                    elif 'heading 2' in style: lines.append(f'## {t}')
                    elif 'heading 3' in style: lines.append(f'### {t}')
                    elif 'list' in style: lines.append(f'- {t}')
                    else: lines.append(t)
                else: lines.append(t)
            result['stdout'] = '\n'.join(lines)

        # ── Spreadsheet ──
        elif from_format == 'xlsx':
            import openpyxl
            wb = openpyxl.load_workbook(input_path)
            ws = wb.active
            if to_format == 'csv':
                out = _out('csv')
                with open(out, 'w', newline='', encoding='utf-8') as f:
                    csv.writer(f).writerows(ws.iter_rows(values_only=True))
                result['files'].append(out)
                result['stdout'] = f'Saved: {out}'
            elif to_format == 'json':
                headers = [c.value for c in ws[1]]
                data = [{headers[i]: row[i] for i in range(len(headers)) if i < len(headers)}
                        for row in ws.iter_rows(min_row=2, values_only=True)]
                result['stdout'] = json.dumps(data, ensure_ascii=False, indent=2)

        elif from_format == 'csv':
            # 统一走 _text()：既能读文件，也能直接接收 CSV 文本。
            # 模型手上常常只有一小段文本，强制落盘再读是多余的往返。
            _csv_text = _text() if to_format in ('json', 'xlsx', 'table') else None
            if to_format == 'json':
                rows = list(csv.DictReader(io.StringIO(_csv_text)))
                result['stdout'] = json.dumps(rows, ensure_ascii=False, indent=2)
            elif to_format == 'xlsx':
                import openpyxl
                wb = openpyxl.Workbook()
                ws = wb.active
                for row in csv.reader(io.StringIO(_csv_text)): ws.append(row)
                out = _out('xlsx')
                wb.save(out)
                result['files'].append(out)
                result['stdout'] = f'Saved: {out}'
            elif to_format == 'table':
                # CSV → Markdown 表格。比原始 CSV 更适合直接贴进对话。
                from tabulate import tabulate
                rows = list(csv.reader(io.StringIO(_csv_text)))
                result['stdout'] = (
                    tabulate(rows[1:], headers=rows[0], tablefmt='pipe', numalign='left')
                    if rows else '(empty)'
                )
            else:
                raise ValueError(f'csv → {to_format} 不支持')


        elif from_format == 'json' and to_format in ('yaml', 'yml'):
            result['stdout'] = _dump_yaml(json.loads(_text()))


        elif from_format == 'json' and to_format == 'table':
            # 对象数组 → Markdown 表格。比原始 JSON 更适合直接贴进对话。
            data = json.loads(_text())
            from tabulate import tabulate
            if isinstance(data, list) and data and isinstance(data[0], dict):
                cols = list(data[0].keys())
                rows = [[_cell(row.get(c)) for c in cols] for row in data]
                result['stdout'] = tabulate(rows, headers=cols, tablefmt='pipe', numalign='left')
            elif isinstance(data, dict):
                result['stdout'] = tabulate(
                    [[_cell(k), _cell(v)] for k, v in data.items()],
                    headers=['key', 'value'], tablefmt='pipe', numalign='left')
            else:
                raise ValueError('json → table 需要对象数组或对象')


        elif from_format == 'json':
            # 用 _text() 而不是 open(input_path)：允许直接把 JSON 文本传进来
            data = json.loads(_text())
            if isinstance(data, dict): data = [data]
            if not data: raise ValueError('Empty JSON')
            if to_format == 'csv':
                headers = list(data[0].keys())
                out = _out('csv')
                with open(out, 'w', newline='', encoding='utf-8') as f:
                    w = csv.writer(f)
                    w.writerow(headers)
                    for row in data: w.writerow([row.get(h, '') for h in headers])
                result['files'].append(out)
                result['stdout'] = f'Saved: {out}'
            elif to_format == 'xlsx':
                import openpyxl
                wb = openpyxl.Workbook()
                ws = wb.active
                headers = list(data[0].keys())
                ws.append(headers)
                for row in data: ws.append([row.get(h, '') for h in headers])
                out = _out('xlsx')
                wb.save(out)
                result['files'].append(out)
                result['stdout'] = f'Saved: {out}'

        # ── PowerPoint ──
        elif from_format == 'pptx' and to_format in ('txt', 'md'):
            from pptx import Presentation
            prs = Presentation(input_path)
            lines = []
            for i, slide in enumerate(prs.slides, 1):
                lines.append(f'## Slide {i}' if to_format == 'md' else f'--- Slide {i} ---')
                for shape in slide.shapes:
                    if hasattr(shape, 'text') and shape.text.strip():
                        lines.append(shape.text.strip())
            result['stdout'] = '\n\n'.join(lines)

        # ── ZIP extraction ──
        elif from_format == 'zip':
            extract_dir = os.path.join(output_dir, os.path.basename(input_path).rsplit('.',1)[0] + '_extracted')
            with zipfile.ZipFile(input_path, 'r') as z:
                z.extractall(extract_dir)
            files = [os.path.join(root, f) for root, _, fnames in os.walk(extract_dir) for f in fnames]
            result['stdout'] = f'Extracted to {extract_dir} ({len(files)} files)'
            result['files'] = files

        # ── Text → PDF (via fpdf2, lightweight) ──
        elif from_format in ('txt', 'md') and to_format == 'pdf':
            from fpdf import FPDF
            text = _text()
            pdf = FPDF()
            pdf.set_auto_page_break(auto=True, margin=15)
            pdf.add_page()
            pdf.set_font('Helvetica', size=11)
            for line in text.split('\n'):
                s = line.strip()
                if not s:
                    pdf.ln(5)
                    continue
                if from_format == 'md' and s.startswith('#'):
                    level = min(len(s.split(' ')[0]), 4)
                    pdf.set_font('Helvetica', size=[24, 18, 14, 12][level-1])
                    pdf.multi_cell(0, 10, s.lstrip('#').strip())
                    pdf.set_font('Helvetica', size=11)
                else:
                    pdf.multi_cell(0, 7, s)
            out = _out('pdf')
            pdf.output(out)
            result['files'].append(out)
            result['stdout'] = f'Saved: {out}'

        # ── HTML → Markdown (via markdownify) ──
        elif from_format in ('html',) and to_format == 'md':
            from markdownify import markdownify as md
            html = _text()
            md_text = md(html, heading_style='ATX')
            out = _out('md')
            with open(out, 'w', encoding='utf-8') as f:
                f.write(md_text)
            result['files'].append(out)
            result['stdout'] = f'Saved: {out}'

        # ── EPUB extraction ──
        elif from_format == 'epub' and to_format in ('txt', 'md'):
            import ebooklib
            from ebooklib import epub
            from bs4 import BeautifulSoup
            book = epub.read_epub(input_path)
            lines = []
            title = book.get_metadata('DC', 'title')
            if title:
                lines.append(f'# {title[0][0]}\n' if to_format == 'md' else f'{title[0][0]}\n{"="*len(title[0][0])}\n')
            for item in book.get_items():
                if item.get_type() == ebooklib.ITEM_DOCUMENT and item.get_body_content():
                    text = BeautifulSoup(item.get_body_content(), 'html.parser').get_text(strip=True)
                    if text: lines.append(text)
            result['stdout'] = '\n\n'.join(lines)

        # ── PDF merge ──
        elif from_format in ('pdf',) and to_format == 'merge' and input_text:
            paths = [p.strip() for p in input_text.split(',') if p.strip()]
            if not paths:
                raise ValueError('Provide comma-separated PDF paths in input_text')
            from pypdf import PdfWriter
            merger = PdfWriter()
            for p in paths:
                merger.append(p)
            out = os.path.join(output_dir, 'merged.pdf')
            merger.write(out)
            merger.close()
            result['files'].append(out)
            result['stdout'] = f'Merged {len(paths)} PDFs into: {out}'

        # ── PDF split ──
        elif from_format == 'pdf' and to_format == 'split':
            from pypdf import PdfReader, PdfWriter
            reader = PdfReader(input_path)
            base = os.path.basename(input_path).rsplit('.',1)[0]
            for i, page in enumerate(reader.pages, 1):
                w = PdfWriter()
                w.add_page(page)
                out = os.path.join(output_dir, f'{base}_p{i:03d}.pdf')
                w.write(out)
                w.close()
                result['files'].append(out)
            result['stdout'] = f'Split {len(reader.pages)} pages'

        # ── PDF extract images (no Pillow needed — save raw bytes) ──
        elif from_format == 'pdf' and to_format == 'images':
            from pypdf import PdfReader
            reader = PdfReader(input_path)
            base = os.path.basename(input_path).rsplit('.',1)[0]
            count = 0
            for page_num, page in enumerate(reader.pages, 1):
                for img_idx, img in enumerate(page.images):
                    try:
                        ext = img.name.rsplit('.',1)[-1] if '.' in img.name else 'png'
                        out = os.path.join(output_dir, f'{base}_p{page_num}_img{img_idx+1}.{ext}')
                        with open(out, 'wb') as f:
                            f.write(img.data)
                        result['files'].append(out)
                        count += 1
                    except Exception:
                        pass
            result['stdout'] = f'Extracted {count} images from {len(reader.pages)} pages'

        # ── DOCX extract images (no Pillow needed — save raw bytes from zip) ──
        elif from_format == 'docx' and to_format == 'images':
            base = os.path.basename(input_path).rsplit('.',1)[0]
            count = 0
            with zipfile.ZipFile(input_path) as z:
                for name in z.namelist():
                    if name.startswith('word/media/'):
                        try:
                            data = z.read(name)
                            ext = name.rsplit('.',1)[-1]
                            out = os.path.join(output_dir, f'{base}_{os.path.basename(name)}')
                            with open(out, 'wb') as f:
                                f.write(data)
                            result['files'].append(out)
                            count += 1
                        except Exception:
                            pass
            result['stdout'] = f'Extracted {count} images from docx'

        # ── URL → Markdown ──
        elif from_format == 'url' and to_format == 'md':
            import requests
            from markdownify import markdownify as md
            url = input_text.strip() if input_text else (input_path or '')
            if not url:
                raise ValueError('Provide URL in input_text')
            resp = requests.get(url, timeout=30)
            resp.raise_for_status()
            md_text = md(resp.text, heading_style='ATX')
            out = os.path.join(output_dir, 'webpage.md')
            with open(out, 'w', encoding='utf-8') as f:
                f.write(md_text)
            result['files'].append(out)
            result['stdout'] = f'Fetched {url} → markdown ({len(md_text)} chars)'

        # ── Data-format interchange ──
        # YAML / JSON / TOML 之间互转，以及 CSV → JSON。
        # 这几个格式在日常对话里出现频率很高（配置文件、接口返回、数据导出），
        # 而此前 convert.py 只认 xlsx/csv/json 里的 json，没有 YAML。
        elif from_format in ('yaml', 'yml') and to_format in ('json', 'yaml', 'yml'):
            data = _load_yaml(_text())
            if to_format == 'json':
                result['stdout'] = json.dumps(data, ensure_ascii=False, indent=2)
            else:
                result['stdout'] = _dump_yaml(data)

        elif from_format == 'toml' and to_format == 'json':
            data = _load_toml(_text())
            result['stdout'] = json.dumps(data, ensure_ascii=False, indent=2)

        else:
            raise ValueError(f'Conversion from {from_format} to {to_format} not supported')

    except Exception as e:
        result['error'] = f'{type(e).__name__}: {str(e)}'
        result['stdout'] = ''

    return json.dumps(result)


# ── 数据格式辅助 ────────────────────────────────────────────────
# YAML / TOML 在 Python 3.11+ 的标准库里只有 tomllib（只读）。
# 这里对 YAML 做一层极简实现，覆盖配置文件的常见子集：
# 嵌套映射、列表、标量、引号、注释、多行块。
# 不追求完整 YAML 1.1 规范（锚点/别名/标签等），因为那些在日常对话里
# 几乎不出现，而引入 PyYAML 会多带一个包。遇到不支持的结构会明确报错，
# 而不是静默解析出错误结果。

def _load_yaml(text):
    """解析 YAML 子集。失败时抛 ValueError 并说明原因。"""
    lines = []
    for raw in text.splitlines():
        # 去掉注释（不在引号内的 #）
        s = raw.split('#')[0] if not _has_quoted_hash(raw) else raw
        if s.strip() == '' and raw.strip() != '':
            continue
        if s.strip() != '':
            lines.append((len(s) - len(s.lstrip()), s.strip(), raw))
    if not lines:
        return None
    value, idx = _parse_yaml_block(lines, 0, lines[0][0])
    if idx != len(lines):
        raise ValueError(f'YAML 解析在第 {idx + 1} 行停止，可能存在不支持的语法: {lines[idx][2]!r}')
    return value


def _has_quoted_hash(raw):
    return raw.count('"') % 2 == 1 or raw.count("'") % 2 == 1


def _parse_yaml_block(lines, idx, indent):
    """解析同一缩进层级的一块，返回 (值, 下一个索引)。"""
    if idx >= len(lines):
        return None, idx
    # 列表：以 "- " 开头
    if lines[idx][1].startswith('- ') or lines[idx][1] == '-':
        out = []
        while idx < len(lines) and lines[idx][0] == indent and (
                lines[idx][1].startswith('- ') or lines[idx][1] == '-'):
            item = lines[idx][1][1:].strip()
            if item == '':
                # 纯嵌套块：- 后面没内容，值在下一层缩进
                nxt = lines[idx + 1][0] if idx + 1 < len(lines) else indent
                child, idx = _parse_yaml_block(lines, idx + 1, nxt)
                out.append(child)
            elif ':' in item and not _looks_like_scalar_with_colon(item):
                # 对象列表项：- key: value，后续同缩进的 key 属于同一个对象。
                # 这是配置文件里最常见的结构（比如一串用户、一串服务），
                # 必须把后续行一起并进来，否则只解析出第一个键。
                item_indent = lines[idx][0] + 2
                sub_lines = [(item_indent, item, lines[idx][2])]
                idx += 1
                while idx < len(lines) and lines[idx][0] >= item_indent:
                    sub_lines.append(lines[idx])
                    idx += 1
                # 用统一的子块解析器处理，这样嵌套映射/列表都能正确落到值上
                child, _ = _parse_yaml_block(sub_lines, 0, sub_lines[0][0])
                out.append(child)
            else:
                out.append(_yaml_scalar(item))
                idx += 1
        return out, idx
    # 映射
    out = {}
    while idx < len(lines) and lines[idx][0] == indent and ':' in lines[idx][1]:
        key, _, rest = lines[idx][1].partition(':')
        key = _yaml_scalar(key.strip())
        rest = rest.strip()
        if rest == '':
            # 值在下一层缩进
            if idx + 1 < len(lines) and lines[idx + 1][0] > indent:
                child, idx = _parse_yaml_block(lines, idx + 1, lines[idx + 1][0])
                out[key] = child
            else:
                out[key] = None
                idx += 1
        else:
            out[key] = _yaml_scalar(rest)
            idx += 1
    return out, idx


def _looks_like_scalar_with_colon(s):
    """
    判断 `a: b` 到底是不是一个映射项。
    `http://example.com`、`12:30` 这类带冒号的标量不该被当成映射。
    """
    key, _, rest = s.partition(':')
    key = key.strip()
    if not key:
        return True
    if rest.startswith('//'):      # URL
        return True
    if key.isdigit():              # 时间 12:30
        return True
    if ' ' in key:                 # 键里不该有空格
        return True
    return False


def _yaml_scalar(s):
    s = s.strip()
    if s in ('null', '~', 'Null', 'NULL', ''):
        return None
    if s in ('true', 'True', 'TRUE', 'yes', 'on'):
        return True
    if s in ('false', 'False', 'FALSE', 'no', 'off'):
        return False
    if (s.startswith('"') and s.endswith('"')) or (s.startswith("'") and s.endswith("'")):
        return s[1:-1]
    try:
        return int(s)
    except ValueError:
        pass
    try:
        return float(s)
    except ValueError:
        pass
    # 行内列表 / 行内映射
    if s.startswith('[') and s.endswith(']'):
        inner = s[1:-1].strip()
        if not inner:
            return []
        return [_yaml_scalar(x) for x in _split_toplevel(inner, ',')]
    if s.startswith('{') and s.endswith('}'):
        inner = s[1:-1].strip()
        if not inner:
            return {}
        out = {}
        for pair in _split_toplevel(inner, ','):
            k, _, v = pair.partition(':')
            out[_yaml_scalar(k.strip())] = _yaml_scalar(v.strip())
        return out
    return s


def _split_toplevel(s, sep):
    """按分隔符切分，忽略括号与引号内部的分隔符。"""
    parts, depth, buf, quote = [], 0, '', None
    for ch in s:
        if quote:
            buf += ch
            if ch == quote:
                quote = None
            continue
        if ch in ('"', "'"):
            quote = ch
            buf += ch
        elif ch in '[{':
            depth += 1
            buf += ch
        elif ch in ']}':
            depth -= 1
            buf += ch
        elif ch == sep and depth == 0:
            parts.append(buf)
            buf = ''
        else:
            buf += ch
    parts.append(buf)
    return parts


def _dump_yaml(data, indent=0):
    """把 Python 结构转成 YAML 文本。"""
    pad = ' ' * indent
    if isinstance(data, dict):
        if not data:
            return pad + '{}'
        lines = []
        for k, v in data.items():
            if isinstance(v, (dict, list)) and v:
                lines.append(f'{pad}{k}:')
                lines.append(_dump_yaml(v, indent + 2))
            else:
                lines.append(f'{pad}{k}: {_yaml_repr(v)}')
        return '\n'.join(lines)
    if isinstance(data, list):
        if not data:
            return pad + '[]'
        lines = []
        for item in data:
            if isinstance(item, (dict, list)) and item:
                body = _dump_yaml(item, indent + 2).lstrip()
                lines.append(f'{pad}- {body}')
            else:
                lines.append(f'{pad}- {_yaml_repr(item)}')
        return '\n'.join(lines)
    return pad + _yaml_repr(data)


def _yaml_repr(v):
    if v is None:
        return 'null'
    if v is True:
        return 'true'
    if v is False:
        return 'false'
    if isinstance(v, (int, float)):
        return str(v)
    s = str(v)
    # 含特殊字符或会被误读成其它类型的字符串要加引号
    if s == '' or s != s.strip() or any(c in s for c in ':#{}[],&*?|>%@`"\'') \
            or s.lower() in ('null', 'true', 'false', 'yes', 'no', 'on', 'off'):
        return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'
    return s


def _load_toml(text):
    try:
        import tomllib
        return tomllib.loads(text)
    except ImportError:
        raise ValueError('当前 Python 版本没有 tomllib（需要 3.11+）')


def _cell(v):
    """表格单元格取值：嵌套结构转紧凑 JSON，其余转字符串。"""
    if isinstance(v, (dict, list)):
        return json.dumps(v, ensure_ascii=False)
    return '' if v is None else str(v)


def _read_file(path, text):
    if path:
        with open(path, 'r', encoding='utf-8') as f:
            return f.read()
    return text or ''


def _outpath(input_path, ext, output_dir, fallback='output'):
    base = os.path.basename(input_path).rsplit('.', 1)[0] if input_path else fallback
    return os.path.join(output_dir, f'{base}.{ext}')


if __name__ == '__main__' and len(sys.argv) > 1:
    args = [sys.argv[i] if len(sys.argv) > i else '' for i in range(1, 7)]
    print(convert(*args, '/storage/emulated/0/Download' if len(sys.argv) < 7 else sys.argv[6]))
