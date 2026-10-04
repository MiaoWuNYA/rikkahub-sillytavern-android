"""
Word / PowerPoint / Excel 的解析与修改引擎。

和 convert.py 的分工：
  convert.py 做「格式转换」——把一种格式整体读成另一种；
  这里做「结构化读写」——按段落/表格/单元格/幻灯片定位，读出结构，
  再原地改回去。前者解决「我要这份 Word 的内容」，后者解决
  「这份 Excel 第三张表 B2 是多少、帮我改成 500」。

设计原则：
  1. 读要读全。convert.py 的 docx 读取只取 paragraph.text，表格、
     表格里的单元格、页眉页脚、批注全部丢失。这里按结构返回。
  2. 改要可预期。所有修改都基于「定位表达式」而不是行号——
     行号在文档里不稳定，段落索引会随插入而变化。
  3. 写要保留原样。用 openpyxl 打开时保留公式与格式，用 python-docx
     在原文档上改而不是重建，避免丢掉用户自己的样式。

统一返回 JSON 字符串（与 convert.py 一致，便于 Kotlin 侧统一处理）。
"""

import json
import os
import re
import sys
import zipfile

MAX_CELLS = 200_000        # 单次读取的单元格上限，防超大表把内存吃光
MAX_PARAGRAPHS = 20_000    # 单次读取的段落上限
MAX_TEXT_CHARS = 200_000   # 返回文本总量上限


def _ensure_package_dirs():
    """
    兜底：确保 docx / pptx 的代码目录在磁盘上真实存在。

    正解是 build.gradle.kts 里的 `extractPackages("docx", "pptx")`——
    Chaquopy 会在首次 import 时把整包解压成真实文件，__file__ 于是指向
    磁盘，模板路径（os.path.join(dirname(__file__), "..", "templates", ...)）
    自然能解析。

    这里保留一层兜底，理由是这个失效模式很难察觉：一旦 extractPackages
    因为 Chaquopy 版本变化或配置被误删而不再生效，表现是「加页眉报
    FileNotFoundError，但文件明明在」——排查成本很高。补几个空目录几乎
    不花钱，却能在那种情况下自愈。

    判据刻意不依赖 import 成功：真需要补目录时，往往正是导入链路还没
    走通的时候。改为按「根目录下有没有 templates/ 数据」来认包。
    """
    created = []
    for pkg in ('docx', 'pptx'):
        for root in _candidate_package_roots(pkg):
            if not os.path.isdir(os.path.join(root, 'templates')):
                continue
            for sub in ('parts', 'oxml', 'oxml/text', 'oxml/shapes', 'shapes',
                        'text', 'image', 'dml', 'enum', 'opc', 'section',
                        'table', 'styles', 'chart', 'util', 'drawing'):
                target = os.path.join(root, sub)
                if os.path.exists(target):
                    continue
                if not os.path.isdir(os.path.dirname(target)):
                    continue
                try:
                    os.makedirs(target, exist_ok=True)
                    created.append(target)
                except OSError:
                    pass
    return created


def _candidate_package_roots(pkg):
    """列出包可能的安装根目录：优先已导入模块的 __file__，退回 sys.path 扫描。"""
    roots = []
    try:
        mod = __import__(pkg)
        f = getattr(mod, '__file__', None)
        if f:
            roots.append(os.path.dirname(os.path.abspath(f)))
    except Exception:
        pass

    for entry in sys.path:
        if not entry or not os.path.isdir(entry):
            continue
        candidate = os.path.join(entry, pkg)
        if os.path.isdir(candidate):
            roots.append(candidate)

    seen = set()
    out = []
    for r in roots:
        r = os.path.abspath(r)
        if r not in seen:
            seen.add(r)
            out.append(r)
    return out


# 模块导入即修一次，后续 docx/pptx 的模板访问就不会踩到
_ensure_package_dirs()


def _result(stdout='', files=None, error=None, data=None):
    return json.dumps({
        'stdout': stdout,
        'files': files or [],
        'error': error,
        'data': data,
    }, ensure_ascii=False)


def _outpath(input_path, suffix, output_dir, fallback='output'):
    """
    计算输出路径。

    output_dir 不传时写到「输入文件所在目录」；连输入都没有（纯文本
    场景）才退回临时目录。早先版本直接把 None 交给 os.path.join，
    三个 *_edit 函数在默认参数下都会 TypeError 崩掉。

    刻意不默认写 cwd：executor 会把 workdir 当工作目录，cwd 通常是它，
    但直接调用时 cwd 可能是源码目录，一跑测试就往包里落垃圾文件。
    """
    base = os.path.basename(input_path).rsplit('.', 1)[0] if input_path else fallback
    if output_dir:
        target_dir = output_dir
    elif input_path:
        target_dir = os.path.dirname(os.path.abspath(input_path)) or os.getcwd()
    else:
        import tempfile
        target_dir = tempfile.gettempdir()
    return os.path.join(target_dir, f'{base}{suffix}')


# ══════════════════════════════════════════════════════════════
# Word (.docx)
# ══════════════════════════════════════════════════════════════

def docx_inspect(path):
    """读取 docx 的完整结构：段落（含样式）、表格、节、页眉页脚、内嵌图片清单。"""
    try:
        from docx import Document
        doc = Document(path)
    except Exception as e:
        return _result(error=f'{type(e).__name__}: {e}')

    paragraphs = []
    for i, p in enumerate(doc.paragraphs):
        if i >= MAX_PARAGRAPHS:
            break
        paragraphs.append({
            'index': i,
            'style': p.style.name if p.style else None,
            'text': p.text,
            'runs': len(p.runs),
            'bold': any(r.bold for r in p.runs) if p.runs else False,
        })

    tables = []
    for ti, t in enumerate(doc.tables):
        rows = []
        for row in t.rows:
            rows.append([c.text for c in row.cells])
        tables.append({'index': ti, 'rows': len(t.rows), 'cols': len(t.columns), 'data': rows})

    headers = []
    footers = []
    for si, section in enumerate(doc.sections):
        try:
            headers.append({
                'section': si,
                'text': '\n'.join(p.text for p in section.header.paragraphs if p.text.strip()),
            })
            footers.append({
                'section': si,
                'text': '\n'.join(p.text for p in section.footer.paragraphs if p.text.strip()),
            })
        except Exception:
            # 某些文档的页眉结构异常，不该让整个读取失败
            pass

    images = []
    try:
        with zipfile.ZipFile(path) as z:
            images = [n for n in z.namelist() if n.startswith('word/media/')]
    except Exception:
        pass

    summary = [
        f'段落 {len(paragraphs)} 个，表格 {len(tables)} 个，图片 {len(images)} 张',
    ]
    if paragraphs:
        styles = {}
        for p in paragraphs:
            styles[p['style']] = styles.get(p['style'], 0) + 1
        top = sorted(styles.items(), key=lambda kv: -kv[1])[:5]
        summary.append('样式分布: ' + ', '.join(f'{s}×{n}' for s, n in top))

    return _result(
        stdout='\n'.join(summary),
        data={
            'paragraphs': paragraphs,
            'tables': tables,
            'headers': headers,
            'footers': footers,
            'images': images,
        },
    )


def docx_edit(path, operations, output_dir=None, output_path=None):
    """
    在原文档上做修改并另存。

    operations 是一组操作，按顺序执行：
      {'op':'set_text',  'paragraph':3, 'text':'新内容'}
      {'op':'replace',   'find':'旧词', 'replace':'新词', 'all':true}
      {'op':'append_paragraph', 'text':'...', 'style':'Heading 1'}
      {'op':'insert_paragraph','after':3, 'text':'...'}
      {'op':'delete_paragraph','paragraph':5}
      {'op':'set_table_cell','table':0,'row':1,'col':2,'text':'x'}
      {'op':'add_table_row','table':0,'values':['a','b']}
      {'op':'delete_table_row','table':0,'row':3}
      {'op':'add_table','data':[['h1','h2'],['a','b']]}
    """
    try:
        from docx import Document
    except Exception as e:
        return _result(error=f'python-docx 不可用: {e}')

    try:
        doc = Document(path)
    except Exception as e:
        return _result(error=f'打开失败 {path}: {e}')

    log = []
    for op in operations or []:
        kind = op.get('op')
        try:
            if kind == 'set_text':
                doc.paragraphs[int(op['paragraph'])].text = op.get('text', '')
                log.append(f"段落 {op['paragraph']} 已替换")

            elif kind == 'replace':
                find = op.get('find', '')
                repl = op.get('replace', '')
                if not find:
                    continue
                count = 0
                limit = None if op.get('all', True) else 1
                for p in doc.paragraphs:
                    if find in p.text:
                        # 逐 run 替换会丢失跨 run 的匹配，这里直接改整段文本：
                        # 段落内的样式本来也只在 run 级别，整体替换更可预期。
                        new = p.text.replace(find, repl) if limit is None \
                            else p.text.replace(find, repl, limit - count)
                        count += p.text.count(find) if limit is None else min(limit - count, p.text.count(find))
                        p.text = new
                        if limit is not None and count >= limit:
                            break
                log.append(f'替换 "{find}" → "{repl}"，{count} 处')

            elif kind == 'append_paragraph':
                doc.add_paragraph(op.get('text', ''), style=op.get('style'))
                log.append('已追加段落')

            elif kind == 'insert_paragraph':
                # python-docx 没有插入段落的公开 API，用底层 XML 操作
                after = int(op.get('after', len(doc.paragraphs) - 1))
                target = doc.paragraphs[after]
                new_p = target.insert_paragraph_before(op.get('text', ''))
                if op.get('style'):
                    new_p.style = op['style']
                log.append(f'已在段落 {after} 后插入')

            elif kind == 'delete_paragraph':
                idx = int(op['paragraph'])
                p = doc.paragraphs[idx]
                p._element.getparent().remove(p._element)
                log.append(f'已删除段落 {idx}')

            elif kind == 'set_table_cell':
                t = doc.tables[int(op['table'])]
                t.cell(int(op['row']), int(op['col'])).text = op.get('text', '')
                log.append(f"表格 {op['table']} 单元格 ({op['row']},{op['col']}) 已改")

            elif kind == 'add_table_row':
                t = doc.tables[int(op['table'])]
                row = t.add_row()
                for i, v in enumerate(op.get('values', [])):
                    if i < len(row.cells):
                        row.cells[i].text = str(v)
                log.append(f"表格 {op['table']} 已加一行")

            elif kind == 'delete_table_row':
                t = doc.tables[int(op['table'])]
                idx = int(op['row'])
                t._tbl.remove(t.rows[idx]._tr)
                log.append(f"表格 {op['table']} 已删第 {idx} 行")

            elif kind == 'add_table':
                data = op.get('data', [])
                if not data:
                    continue
                t = doc.add_table(rows=len(data), cols=len(data[0]))
                for ri, row in enumerate(data):
                    for ci, v in enumerate(row):
                        if ci < len(t.columns):
                            t.cell(ri, ci).text = str(v)
                log.append(f'已添加 {len(data)} 行表格')

            else:
                log.append(f'未知操作 {kind}，已跳过')
        except Exception as e:
            log.append(f'{kind} 失败: {type(e).__name__}: {e}')

    out = output_path or _outpath(path, '.edited.docx', output_dir)
    try:
        doc.save(out)
    except Exception as e:
        return _result(error=f'保存失败: {e}', data={'log': log})

    return _result(stdout='\n'.join(log) or '(无操作)', files=[out], data={'log': log})


# ══════════════════════════════════════════════════════════════
# PowerPoint (.pptx)
# ══════════════════════════════════════════════════════════════

def pptx_inspect(path):
    """读取 pptx 结构：每页的版式、标题、文本框、表格、图片数量、备注。"""
    try:
        from pptx import Presentation
        prs = Presentation(path)
    except Exception as e:
        return _result(error=f'{type(e).__name__}: {e}')

    slides = []
    for i, slide in enumerate(prs.slides):
        title = ''
        texts = []
        tables = []
        images = 0
        for shape in slide.shapes:
            if shape.has_text_frame:
                t = shape.text_frame.text
                if not title and (shape == slide.shapes.title if slide.shapes.title else False):
                    title = t
                if t.strip():
                    texts.append({'shape': shape.name, 'text': t})
            if getattr(shape, 'has_table', False) and shape.has_table:
                rows = [[c.text for c in row.cells] for row in shape.table.rows]
                tables.append(rows)
            if shape.shape_type is not None and 'PICTURE' in str(shape.shape_type):
                images += 1

        notes = ''
        try:
            if slide.has_notes_slide:
                notes = slide.notes_slide.notes_text_frame.text
        except Exception:
            pass

        if not title and texts:
            title = texts[0]['text'].split('\n')[0]

        slides.append({
            'index': i,
            'layout': slide.slide_layout.name,
            'title': title,
            'texts': texts,
            'tables': tables,
            'images': images,
            'notes': notes,
        })

    return _result(
        stdout=f'{len(slides)} 页幻灯片',
        data={'slides': slides, 'slide_size': [prs.slide_width, prs.slide_height]},
    )


def pptx_edit(path, operations, output_dir=None, output_path=None):
    """
    修改 pptx。
      {'op':'set_title','slide':0,'text':'新标题'}
      {'op':'replace','find':'旧','replace':'新'}        全文替换
      {'op':'add_slide','layout':1,'title':'...','content':'...'}
      {'op':'delete_slide','slide':2}
      {'op':'set_notes','slide':0,'text':'备注'}
    """
    try:
        from pptx import Presentation
    except Exception as e:
        return _result(error=f'python-pptx 不可用: {e}')

    try:
        prs = Presentation(path)
    except Exception as e:
        return _result(error=f'打开失败 {path}: {e}')

    log = []
    for op in operations or []:
        kind = op.get('op')
        try:
            if kind == 'set_title':
                slide = prs.slides[int(op['slide'])]
                if slide.shapes.title is not None:
                    slide.shapes.title.text = op.get('text', '')
                    log.append(f"第 {op['slide']} 页标题已改")
                else:
                    log.append(f"第 {op['slide']} 页没有标题占位符")

            elif kind == 'replace':
                find, repl = op.get('find', ''), op.get('replace', '')
                if not find:
                    continue
                count = 0
                for slide in prs.slides:
                    for shape in slide.shapes:
                        if shape.has_text_frame:
                            for para in shape.text_frame.paragraphs:
                                for run in para.runs:
                                    if find in run.text:
                                        count += run.text.count(find)
                                        run.text = run.text.replace(find, repl)
                log.append(f'替换 "{find}"，{count} 处')

            elif kind == 'add_slide':
                layout_idx = int(op.get('layout', 1))
                layout = prs.slide_layouts[min(layout_idx, len(prs.slide_layouts) - 1)]
                slide = prs.slides.add_slide(layout)
                if op.get('title') and slide.shapes.title is not None:
                    slide.shapes.title.text = op['title']
                if op.get('content'):
                    # 找第一个非标题的文本占位符放正文
                    for shape in slide.placeholders:
                        if shape != slide.shapes.title and shape.has_text_frame:
                            shape.text_frame.text = op['content']
                            break
                log.append(f'已添加第 {len(prs.slides) - 1} 页')

            elif kind == 'delete_slide':
                idx = int(op['slide'])
                xml_slides = prs.slides._sldIdLst
                slides = list(xml_slides)
                if 0 <= idx < len(slides):
                    rId = slides[idx].get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id')
                    prs.part.drop_rel(rId)
                    xml_slides.remove(slides[idx])
                    log.append(f'已删除第 {idx} 页')

            elif kind == 'set_notes':
                slide = prs.slides[int(op['slide'])]
                slide.notes_slide.notes_text_frame.text = op.get('text', '')
                log.append(f"第 {op['slide']} 页备注已改")

            else:
                log.append(f'未知操作 {kind}，已跳过')
        except Exception as e:
            log.append(f'{kind} 失败: {type(e).__name__}: {e}')

    out = output_path or _outpath(path, '.edited.pptx', output_dir)
    try:
        prs.save(out)
    except Exception as e:
        return _result(error=f'保存失败: {e}', data={'log': log})

    return _result(stdout='\n'.join(log) or '(无操作)', files=[out], data={'log': log})


# ══════════════════════════════════════════════════════════════
# Excel (.xlsx)
# ══════════════════════════════════════════════════════════════

_CELL_REF = re.compile(r'^([A-Za-z]+)(\d+)$')


def _parse_ref(ref):
    """把 'B2' 解析成 (row, col) 的 1-based 索引。"""
    m = _CELL_REF.match((ref or '').strip())
    if not m:
        raise ValueError(f'无效单元格引用: {ref!r}（应形如 B2）')
    col_letters, row = m.group(1).upper(), int(m.group(2))
    col = 0
    for ch in col_letters:
        col = col * 26 + (ord(ch) - ord('A') + 1)
    return row, col


def _col_letter(n):
    s = ''
    while n > 0:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


def xlsx_inspect(path, sheet=None, max_rows=200):
    """
    读取 Excel 结构：所有工作表名、每张表的尺寸、公式、以及指定表的单元格内容。

    sheet 为 None 时返回全部表的概览 + 第一张表的内容预览。
    """
    try:
        import openpyxl
    except Exception as e:
        return _result(error=f'openpyxl 不可用: {e}')

    try:
        # data_only=False 保留公式原文；需要计算结果时另开一次 data_only=True
        wb = openpyxl.load_workbook(path, data_only=False)
    except Exception as e:
        return _result(error=f'打开失败 {path}: {e}')

    sheets_info = []
    for ws in wb.worksheets:
        sheets_info.append({
            'name': ws.title,
            'rows': ws.max_row,
            'cols': ws.max_column,
            'merged': len(ws.merged_cells.ranges) if ws.merged_cells else 0,
        })

    target_name = sheet or (wb.worksheets[0].title if wb.worksheets else None)
    content = None
    formulas = []

    if target_name and target_name in wb.sheetnames:
        ws = wb[target_name]
        rows = []
        for ri, row in enumerate(ws.iter_rows(max_row=min(max_rows, ws.max_row or 0)), start=1):
            vals = []
            for cell in row:
                v = cell.value
                if v is None:
                    vals.append('')
                elif isinstance(v, str) and v.startswith('='):
                    vals.append(v)
                    formulas.append(f'{cell.coordinate}: {v}')
                else:
                    vals.append(str(v))
            # 去掉整行空白，避免预览里全是空行
            if any(x != '' for x in vals):
                rows.append({'row': ri, 'cells': vals})
        content = {'sheet': target_name, 'rows': rows}
        if ws.max_row > max_rows:
            content['note'] = f'仅显示前 {max_rows} 行，共 {ws.max_row} 行'

    summary = f'{len(sheets_info)} 张工作表: ' + ', '.join(
        f"{s['name']}({s['rows']}×{s['cols']})" for s in sheets_info
    )
    if formulas:
        summary += f'\n公式 {len(formulas)} 个'

    return _result(stdout=summary, data={
        'sheets': sheets_info,
        'content': content,
        'formulas': formulas[:50],
    })


def xlsx_edit(path, operations, output_dir=None, output_path=None):
    """
    修改 xlsx，保留公式与格式。

      {'op':'set_cell','sheet':'Sheet1','cell':'B2','value':123}
      {'op':'set_formula','sheet':'Sheet1','cell':'C2','formula':'=A2+B2'}
      {'op':'set_range','sheet':'Sheet1','start':'A1','values':[[1,2],[3,4]]}
      {'op':'add_sheet','name':'新表'}
      {'op':'delete_sheet','sheet':'旧表'}
      {'op':'rename_sheet','sheet':'旧名','name':'新名'}
      {'op':'append_row','sheet':'Sheet1','values':[1,2,3]}
      {'op':'delete_row','sheet':'Sheet1','row':5}
      {'op':'insert_row','sheet':'Sheet1','row':3}
      {'op':'delete_col','sheet':'Sheet1','col':'C'}
      {'op':'insert_col','sheet':'Sheet1','col':'C'}
      {'op':'find_replace','find':'旧','replace':'新','sheet':'Sheet1'}
      {'op':'set_style','sheet':'Sheet1','cell':'A1','bold':true,'fill':'FFFF00'}
    """
    try:
        import openpyxl
    except Exception as e:
        return _result(error=f'openpyxl 不可用: {e}')

    try:
        wb = openpyxl.load_workbook(path)
    except Exception as e:
        return _result(error=f'打开失败 {path}: {e}')

    log = []
    for op in operations or []:
        kind = op.get('op')
        try:
            name = op.get('sheet')
            ws = wb[name] if name and name in wb.sheetnames else wb.active

            if kind == 'set_cell':
                ws[op['cell']] = op.get('value')
                log.append(f"{ws.title}!{op['cell']} = {op.get('value')!r}")

            elif kind == 'set_formula':
                f = op.get('formula', '')
                ws[op['cell']] = f if f.startswith('=') else '=' + f
                log.append(f"{ws.title}!{op['cell']} 公式已设")

            elif kind == 'set_range':
                start_row, start_col = _parse_ref(op.get('start', 'A1'))
                values = op.get('values', [])
                for ri, row in enumerate(values):
                    for ci, v in enumerate(row):
                        ws.cell(row=start_row + ri, column=start_col + ci, value=v)
                log.append(f'{ws.title} 写入 {len(values)} 行')

            elif kind == 'add_sheet':
                wb.create_sheet(op.get('name') or 'Sheet')
                log.append(f"已添加工作表 {op.get('name')}")

            elif kind == 'delete_sheet':
                sname = op.get('sheet')
                if sname and sname in wb.sheetnames:
                    if len(wb.sheetnames) <= 1:
                        log.append('只剩一张表，拒绝删除')
                    else:
                        del wb[sname]
                        log.append(f'已删除工作表 {sname}')

            elif kind == 'rename_sheet':
                if ws.title:
                    old = ws.title
                    ws.title = op.get('name', old)
                    log.append(f'工作表 {old} → {ws.title}')

            elif kind == 'append_row':
                ws.append(op.get('values', []))
                log.append(f'{ws.title} 追加一行')

            elif kind == 'delete_row':
                ws.delete_rows(int(op['row']))
                log.append(f"{ws.title} 删除第 {op['row']} 行")

            elif kind == 'insert_row':
                ws.insert_rows(int(op['row']))
                log.append(f"{ws.title} 第 {op['row']} 行前插入")

            elif kind == 'delete_col':
                _, col = _parse_ref(op['col'] + '1')
                ws.delete_cols(col)
                log.append(f'{ws.title} 删除 {op["col"]} 列')

            elif kind == 'insert_col':
                _, col = _parse_ref(op['col'] + '1')
                ws.insert_cols(col)
                log.append(f'{ws.title} {op["col"]} 列前插入')

            elif kind == 'find_replace':
                find, repl = op.get('find', ''), op.get('replace', '')
                if not find:
                    continue
                count = 0
                for row in ws.iter_rows():
                    for cell in row:
                        if isinstance(cell.value, str) and find in cell.value:
                            cell.value = cell.value.replace(find, repl)
                            count += 1
                log.append(f'{ws.title} 替换 {count} 处')

            elif kind == 'set_style':
                from openpyxl.styles import Font, PatternFill
                cell = ws[op['cell']]
                if op.get('bold') is not None:
                    cell.font = Font(bold=bool(op['bold']))
                if op.get('fill'):
                    cell.fill = PatternFill(
                        start_color=op['fill'], end_color=op['fill'], fill_type='solid')
                log.append(f"{ws.title}!{op['cell']} 样式已设")

            else:
                log.append(f'未知操作 {kind}，已跳过')
        except Exception as e:
            log.append(f'{kind} 失败: {type(e).__name__}: {e}')

    out = output_path or _outpath(path, '.edited.xlsx', output_dir)
    try:
        wb.save(out)
    except Exception as e:
        return _result(error=f'保存失败: {e}', data={'log': log})

    return _result(stdout='\n'.join(log) or '(无操作)', files=[out], data={'log': log})
