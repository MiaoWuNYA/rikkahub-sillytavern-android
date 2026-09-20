// 云南财经强智教务业务页面解析层。
//
// 从原 Java 实现（YnufeParsers.kt，基于 Jsoup）搬来，但沙箱里没有 DOM，
// 所以这里用一层极小的 HTML 扫描器顶上：找表格 / 找行 / 找单元格 / 取纯文本。
//
// 敢用扫描而不是真解析，是因为这些教务页面的表格从不嵌套（已用真实抓取页面确认），
// 所以「按 <tr>/<td> 顺序切」和 Jsoup 的结果一致。

// ---------------------------------------------------------------------------
// 迷你 HTML 工具
// ---------------------------------------------------------------------------

// 把 &nbsp; &#39; 之类的实体还原成字符
function decodeEntities(s) {
  return String(s)
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&apos;/gi, "'")
    .replace(/&amp;/gi, '&')
    .replace(/&#(\d+);/g, function (_, d) { return String.fromCharCode(parseInt(d, 10)); })
    .replace(/&#x([0-9a-f]+);/gi, function (_, h) { return String.fromCharCode(parseInt(h, 16)); });
}

/**
 * 取纯文本：丢掉 script/style/注释/标签，还原实体，折叠空白。
 *
 * 标签替换成空格而不是空串，否则 `<td>3</td><td>51</td>` 会粘成一个 "351"。
 */
function textOf(html) {
  var s = String(html)
    .replace(/<script\b[\s\S]*?<\/script\s*>/gi, ' ')
    .replace(/<style\b[\s\S]*?<\/style\s*>/gi, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<[^>]*>/g, ' ');
  return decodeEntities(s).replace(/\s+/g, ' ').replace(/^\s+|\s+$/g, '');
}

/** 读属性值，单双引号都认。 */
function attrValue(attrs, name) {
  var re = new RegExp('(?:^|\\s)' + name + '\\s*=\\s*(["\'])([\\s\\S]*?)\\1', 'i');
  var m = re.exec(attrs);
  if (m) return m[2];
  re = new RegExp('(?:^|\\s)' + name + '\\s*=\\s*([^\\s>]+)', 'i');
  m = re.exec(attrs);
  return m ? m[1] : '';
}

function hasAttr(attrs, name) {
  return new RegExp('(?:^|\\s)' + name + '(?:\\s*=|\\s|$)', 'i').test(attrs);
}

function hasClass(attrs, cls) {
  var c = attrValue(attrs, 'class');
  if (!c) return false;
  return (' ' + c.split(/\s+/).join(' ') + ' ').indexOf(' ' + cls + ' ') >= 0;
}

/** 找到与 fromIndex 处开标签配对的结束位置（用于 div/select/table 这类可嵌套元素）。 */
function endOfElement(html, tag, fromIndex) {
  var depth = 1;
  var re = new RegExp('<\\/?' + tag + '\\b[^>]*>', 'gi');
  re.lastIndex = fromIndex;
  var m;
  while ((m = re.exec(html))) {
    if (m[0].charAt(1) === '/') {
      depth--;
      if (depth === 0) return { contentEnd: m.index, after: re.lastIndex };
    } else {
      depth++;
    }
  }
  return { contentEnd: html.length, after: html.length };
}

/**
 * 取出所有 <table> 的 {attrs, html}（html 是表内内容，不含 table 标签本身）。
 */
function findTables(html) {
  var out = [];
  var re = /<table\b([^>]*)>/gi;
  var m;
  while ((m = re.exec(html))) {
    var end = endOfElement(html, 'table', re.lastIndex);
    out.push({ attrs: m[1], html: html.substring(re.lastIndex, end.contentEnd) });
    re.lastIndex = end.after;
  }
  return out;
}

/** 取出表格/片段里所有 <tr> 的内容。 */
function findRows(html) {
  var out = [];
  var re = /<tr\b[^>]*>([\s\S]*?)<\/tr\s*>/gi;
  var m;
  while ((m = re.exec(html))) out.push(m[1]);
  return out;
}

/**
 * 取出一行里所有指定标签的单元格。
 *
 * 注意成绩页的畸形标记：分数格里有 `<td><a>84</a></td></td>` 这种多余的收尾标签，
 * 非贪婪匹配正好停在第一个 </td>，多余的那个没人认领、直接跳过，结果是对的。
 * 也正因如此这里不做 colspan 展开——和 Jsoup 一致，跨列格仍算作一格。
 */
function findCells(rowHtml, tag) {
  var out = [];
  var re = new RegExp('<' + tag + '\\b([^>]*)>([\\s\\S]*?)<\\/' + tag + '\\s*>', 'gi');
  var m;
  while ((m = re.exec(rowHtml))) out.push({ attrs: m[1], html: m[2] });
  return out;
}

/** 取片段里所有指定标签的元素。 */
function findTags(html, tag) {
  var out = [];
  var re = new RegExp('<' + tag + '\\b([^>]*)>([\\s\\S]*?)<\\/' + tag + '\\s*>', 'gi');
  var m;
  while ((m = re.exec(html))) out.push({ attrs: m[1], html: m[2] });
  return out;
}

/** 取片段里第一个 <a>。 */
function firstAnchor(html) {
  var m = /<a\b([^>]*)>([\s\S]*?)<\/a\s*>/i.exec(html);
  return m ? { attrs: m[1], html: m[2] } : null;
}

/** 按 id 找某个标签的内容（如 select#xnxq01id）。 */
function findById(html, tag, id) {
  var re = new RegExp('<' + tag + '\\b([^>]*)>', 'gi');
  var m;
  while ((m = re.exec(html))) {
    if (attrValue(m[1], 'id') === id) {
      var end = endOfElement(html, tag, re.lastIndex);
      return { attrs: m[1], html: html.substring(re.lastIndex, end.contentEnd) };
    }
  }
  return null;
}

/**
 * 找所有 class 含 cls 的 div。用配对的 </div> 收尾，嵌套 div 也会被扫到，
 * 和 Jsoup 的 `div.kbcontent` 后代选择一致。
 */
function findDivsByClass(html, cls) {
  var out = [];
  var re = /<div\b([^>]*)>/gi;
  var m;
  while ((m = re.exec(html))) {
    if (!hasClass(m[1], cls)) continue;
    var end = endOfElement(html, 'div', re.lastIndex);
    out.push({
      attrs: m[1],
      id: attrValue(m[1], 'id'),
      html: html.substring(re.lastIndex, end.contentEnd),
    });
  }
  return out;
}

// ---------------------------------------------------------------------------
// 表头定位
// ---------------------------------------------------------------------------

function headerCells(table) {
  var rows = findRows(table.html);
  if (rows.length === 0) return [];
  var cells = findCells(rows[0], 'th');
  if (cells.length === 0) cells = findCells(rows[0], 'td');
  var out = [];
  for (var i = 0; i < cells.length; i++) out.push(textOf(cells[i].html));
  return out;
}

/**
 * 表头中第一个包含任一 key 的列下标，找不到用 fallback。
 *
 * excludes 是排除词：考试页里既有一列「校区」又有一列「考场校区」，
 * 光按「考场」找会命中「考场校区」，取到的就是校区而不是考场。
 */
function findCol(headers, keys, fallback, excludes) {
  for (var i = 0; i < headers.length; i++) {
    var hit = false;
    for (var k = 0; k < keys.length; k++) {
      if (headers[i].indexOf(keys[k]) >= 0) { hit = true; break; }
    }
    if (!hit) continue;
    var blocked = false;
    if (excludes) {
      for (var e = 0; e < excludes.length; e++) {
        if (headers[i].indexOf(excludes[e]) >= 0) { blocked = true; break; }
      }
    }
    if (!blocked) return i;
  }
  return fallback;
}

function cellText(tds, idx) {
  if (idx < 0 || idx >= tds.length) return '';
  return textOf(tds[idx].html);
}

/** 优先取 id="dataList" 的第一张表，退化到 class 含 Nsb_r_list 的表。 */
function firstDataTable(tables) {
  var i;
  for (i = 0; i < tables.length; i++) {
    if (attrValue(tables[i].attrs, 'id') === 'dataList') return tables[i];
  }
  for (i = 0; i < tables.length; i++) {
    if (hasClass(tables[i].attrs, 'Nsb_r_list')) return tables[i];
  }
  return null;
}

/**
 * 挑出行数最多的那张表。
 *
 * 空教室页里有多个同 id="dataList" 的表，靠前的是没数据的空壳，必须挑数据行最多的。
 */
function richestTable(tables) {
  var selectors = [['id', 'dataList'], ['id', 'Table1'], ['class', 'Nsb_r_list']];
  var best = null;
  var maxRows = 0;
  for (var s = 0; s < selectors.length; s++) {
    for (var i = 0; i < tables.length; i++) {
      var t = tables[i];
      var ok = selectors[s][0] === 'id'
        ? attrValue(t.attrs, 'id') === selectors[s][1]
        : hasClass(t.attrs, selectors[s][1]);
      if (!ok) continue;
      var n = findRows(t.html).length;
      if (n > maxRows) {
        maxRows = n;
        best = t;
      }
    }
  }
  return best;
}

function matchOr(text, re, fallback) {
  var m = re.exec(text);
  return m ? m[1] : fallback;
}

var EMPTY_MARKERS = ['未查询到数据', '暂无数据', '没有找到', '无查询结果'];

function isEmptyMarker(s) {
  for (var i = 0; i < EMPTY_MARKERS.length; i++) {
    if (s.indexOf(EMPTY_MARKERS[i]) >= 0) return true;
  }
  return false;
}

// ---------------------------------------------------------------------------
// 课表
// ---------------------------------------------------------------------------

/**
 * 课程名提取：顺着块的顶层子节点走，遇到第一个带 title 属性的元素
 * （<font title='老师'>、<font title='教室'>…）就停。
 *
 * 强智把课名当裸文本放在最前面，后面才跟一串带 title 的 <font>，
 * 所以「前缀文本」就是课名。中途碰上 jc_/kc_ 这类隐藏输入框也停。
 * 相邻两段都是拉丁字母数字时补个空格，避免 "Data 2024" 粘成 "Data2024"。
 */
function extractCourseName(blockHtml) {
  var parts = [];
  var re = /<([a-zA-Z][\w-]*)\b([^>]*?)\/?>|<\/([a-zA-Z][\w-]*)\s*>|([^<]+)/g;
  var m;
  while ((m = re.exec(blockHtml))) {
    if (m[4] !== undefined) {
      var t = textOf(m[4]);
      if (t && t !== '&nbsp;') parts.push(t);
      continue;
    }
    var isClose = m[3] !== undefined;
    if (isClose) continue;
    var tag = m[1].toLowerCase();
    var attrs = m[2] || '';
    if (tag === 'br') continue; // <br> 不阻断，也不产出文本

    var id = attrValue(attrs, 'id');
    if (hasAttr(attrs, 'title') || id.indexOf('jc_') === 0 || id.indexOf('kc_') === 0 ||
      tag === 'input' || tag === 'select') {
      break;
    }

    // 普通元素：取它的完整文本，然后跳过它整棵子树
    if (/\/$/.test(m[2] || '')) continue; // 自闭合标签
    var end = endOfElement(blockHtml, tag, re.lastIndex);
    var txt = textOf(blockHtml.substring(re.lastIndex, end.contentEnd));
    if (txt && txt !== '&nbsp;') parts.push(txt);
    re.lastIndex = end.after;
  }

  if (parts.length === 0) return '';
  var sb = '';
  for (var i = 0; i < parts.length; i++) {
    if (sb === '') {
      sb = parts[i];
    } else {
      var lastCh = sb.charAt(sb.length - 1);
      var firstCh = parts[i].charAt(0);
      var join = /[0-9a-zA-Z]/.test(lastCh) && /[0-9a-zA-Z]/.test(firstCh);
      sb += join ? ' ' + parts[i] : parts[i];
    }
  }
  return sb.replace(/^\s+|\s+$/g, '');
}

function parseTimetable(html) {
  var semesters = [];
  var semSelect = findById(html, 'select', 'xnxq01id');
  if (semSelect) {
    var opts = findTags(semSelect.html, 'option');
    for (var i = 0; i < opts.length; i++) {
      var txt = textOf(opts[i].html);
      var v = attrValue(opts[i].attrs, 'value');
      if (!v) v = txt;
      if (!v) continue;
      semesters.push({ value: v, text: txt, selected: hasAttr(opts[i].attrs, 'selected') });
    }
  }

  var currentWeek = 0;
  var zcSelect = findById(html, 'select', 'zc');
  if (zcSelect) {
    var zcOpts = findTags(zcSelect.html, 'option');
    for (var z = 0; z < zcOpts.length; z++) {
      if (!hasAttr(zcOpts[z].attrs, 'selected')) continue;
      var n = parseInt(attrValue(zcOpts[z].attrs, 'value'), 10);
      if (!isNaN(n) && n > 0) currentWeek = n;
    }
  }

  var courses = [];
  var tables = findTables(html);
  var kbTable = null;
  for (var t = 0; t < tables.length; t++) {
    if (attrValue(tables[t].attrs, 'id') === 'kbtable') { kbTable = tables[t]; break; }
  }
  if (!kbTable) {
    for (var t2 = 0; t2 < tables.length; t2++) {
      if (hasClass(tables[t2].attrs, 'kbtable')) { kbTable = tables[t2]; break; }
    }
  }

  if (kbTable) {
    var divs = findDivsByClass(kbTable.html, 'kbcontent');
    for (var d = 0; d < divs.length; d++) {
      var parts = String(divs[d].id).split('_');
      if (parts.length < 2) continue;
      var slot = parseInt(parts[0], 10);
      var day = parseInt(parts[1], 10);
      if (isNaN(slot) || isNaN(day)) continue;

      var blocks = divs[d].html.split(/<br\s*\/?>\s*-{10,}\s*<br\s*\/?>|<hr\s*\/?>/i);
      for (var b = 0; b < blocks.length; b++) {
        var block = blocks[b];
        if (!block || textOf(block) === '') continue;
        var name = extractCourseName(block);
        if (!name || name.length < 2) continue;

        var teacherM = /老师['"]>([^<]*)/.exec(block);
        var roomM = /教室['"]>([^<]*)/.exec(block);
        var weeksM = /周次\(节次\)['"]>([^<]*)/.exec(block);

        courses.push({
          name: name,
          teacher: teacherM ? teacherM[1].replace(/^\s+|\s+$/g, '') : '未知教师',
          room: roomM ? roomM[1].replace(/^\s+|\s+$/g, '') : '未定教室',
          weeks: weeksM ? weeksM[1].replace(/^\s+|\s+$/g, '') : '全周',
          day: day,
          slot: slot,
          session: Math.floor((slot + 1) / 2),
          isAdjusted: /color\s*=\s*["']red["']/i.test(block),
        });
      }
    }
  }

  // 当前学期：优先取 selected 的那项，没有就取第一个
  var currentSemesterId = '';
  var firstValue = '';
  for (var s = 0; s < semesters.length; s++) {
    if (firstValue === '') firstValue = semesters[s].value;
    if (semesters[s].selected && semesters[s].value) { currentSemesterId = semesters[s].value; break; }
  }
  if (!currentSemesterId) currentSemesterId = firstValue;

  return {
    semesters: semesters,
    currentSemesterId: currentSemesterId,
    currentWeek: currentWeek,
    courses: courses,
  };
}

// ---------------------------------------------------------------------------
// 成绩
// ---------------------------------------------------------------------------

function parseGrades(html) {
  var bodyText = textOf(html);
  var gpa = matchOr(bodyText, /平均学分绩点[:：]\s*([\d.]+)/, '0.00');
  var totalCredits = matchOr(bodyText, /所修总学分[:：]\s*([\d.]+)/, '0.0');

  var gradesList = [];
  var table = firstDataTable(findTables(html));
  if (table) {
    var headers = headerCells(table);
    var semCol = findCol(headers, ['开课学期'], 1);
    var nameCol = findCol(headers, ['课程名称'], 3);
    var scoreCol = findCol(headers, ['成绩'], 5);
    var creditCol = findCol(headers, ['学分'], 7);
    var pointCol = findCol(headers, ['绩点'], 9);

    var rows = findRows(table.html);
    for (var i = 1; i < rows.length; i++) {
      var tds = findCells(rows[i], 'td');
      if (tds.length < 10) continue;
      var name = cellText(tds, nameCol);
      if (!name || isEmptyMarker(name)) continue;
      gradesList.push({
        semester: cellText(tds, semCol),
        courseName: name,
        score: cellText(tds, scoreCol),
        credit: cellText(tds, creditCol),
        point: cellText(tds, pointCol),
      });
    }
  }

  // 用过的学期去重后倒序（新的在前）
  var semSet = {};
  var semList = [];
  for (var g = 0; g < gradesList.length; g++) {
    var sem = gradesList[g].semester;
    if (sem && !semSet[sem]) { semSet[sem] = true; semList.push(sem); }
  }
  semList.sort();
  semList.reverse();

  return {
    gpa: gpa,
    totalCredits: totalCredits,
    coursesCount: gradesList.length,
    semesters: semList,
    gradesList: gradesList,
  };
}

// ---------------------------------------------------------------------------
// 排考
// ---------------------------------------------------------------------------

function parseExams(html, term, typeLabel) {
  var out = [];
  var table = firstDataTable(findTables(html));
  if (!table) return out;

  var headers = headerCells(table);
  var nameCol = findCol(headers, ['课程名称'], 5);
  var dateCol = findCol(headers, ['考试时间'], 7);
  var roomCol = findCol(headers, ['考场'], 8, ['校区']);
  var seatCol = findCol(headers, ['座位号'], 9);
  var teacherCol = findCol(headers, ['授课教师'], 6);
  var campusCol = findCol(headers, ['校区'], 1);
  var sessionCol = findCol(headers, ['考试场次'], 3);

  var rows = findRows(table.html);
  for (var i = 1; i < rows.length; i++) {
    var tds = findCells(rows[i], 'td');
    if (tds.length < 10) continue;
    var name = cellText(tds, nameCol);
    if (!name || isEmptyMarker(name)) continue;
    out.push({
      term: term || '',
      courseName: name,
      date: cellText(tds, dateCol),
      room: cellText(tds, roomCol),
      seat: cellText(tds, seatCol),
      teacher: cellText(tds, teacherCol),
      campus: cellText(tds, campusCol),
      examSession: cellText(tds, sessionCol),
      examType: typeLabel || '考试',
    });
  }
  return out;
}

// ---------------------------------------------------------------------------
// 公告
// ---------------------------------------------------------------------------

function parseAnnouncements(html) {
  var rows = [];
  var table = firstDataTable(findTables(html));

  if (table) {
    var headers = headerCells(table);
    var titleCol = findCol(headers, ['标题'], 1);
    var dateCol = findCol(headers, ['发送时间', '发布时间'], 4);
    var actionCol = findCol(headers, ['操作'], headers.length - 1);

    var trs = findRows(table.html);
    for (var i = 1; i < trs.length; i++) {
      var tds = findCells(trs[i], 'td');
      if (tds.length < 5) continue;
      if (titleCol < 0 || titleCol >= tds.length) continue;

      var titleCell = tds[titleCol];
      var titleAnchor = firstAnchor(titleCell.html);
      var title = titleAnchor ? textOf(titleAnchor.html) : textOf(titleCell.html);
      if (!title || isEmptyMarker(title)) continue;

      var actionCell = (actionCol >= 0 && actionCol < tds.length) ? tds[actionCol] : tds[tds.length - 1];
      var actionAnchor = actionCell ? firstAnchor(actionCell.html) : null;
      var link = actionAnchor || titleAnchor;

      var url = '';
      if (link) {
        var href = attrValue(link.attrs, 'href');
        var m = /openWindow\s*\(\s*['"]([^'"]+)['"]/.exec(href);
        if (m) {
          url = m[1];
        } else if (href && !/^javascript:/i.test(href) && !/^data:/i.test(href)) {
          url = href;
        }
      }

      rows.push({ title: title, date: cellText(tds, dateCol), url: url });
    }
  }

  // 没有 dataList 表时退化成「扫所有行和列表项里的链接」
  if (rows.length === 0) {
    var candidates = [];
    var re = /<tr\b[^>]*>([\s\S]*?)<\/tr\s*>/gi;
    var m1;
    while ((m1 = re.exec(html))) candidates.push(m1[1]);
    re = /<li\b[^>]*>([\s\S]*?)<\/li\s*>/gi;
    while ((m1 = re.exec(html))) candidates.push(m1[1]);

    for (var c = 0; c < candidates.length; c++) {
      var a = firstAnchor(candidates[c]);
      if (!a) continue;
      var t = textOf(a.html);
      if (t.length <= 3) continue;
      var dm = /\d{4}-\d{2}-\d{2}/.exec(textOf(candidates[c]));
      rows.push({ title: t, date: dm ? dm[0] : '近期', url: attrValue(a.attrs, 'href') });
    }
  }

  return rows;
}

// ---------------------------------------------------------------------------
// 空闲教室
// ---------------------------------------------------------------------------

/**
 * 解析空教室查询响应。
 *
 * 返回的是「教室 × 节次」占用矩阵：首行是节次分组表头（"0102"、"030405"...），
 * 数据行首格是教室名（如 "汇新101(50/30)"），其余格只要有内容（◆）就说明该节次被占。
 * 所有节次格都空的教室才算空闲。
 */
function parseClassrooms(html) {
  var table = richestTable(findTables(html));
  var freeRooms = [];
  if (table) {
    var rows = findRows(table.html);
    for (var i = 1; i < rows.length; i++) {
      var tds = findCells(rows[i], 'td');
      if (tds.length < 2) continue;
      var name = cellText(tds, 0);
      if (!name || isEmptyMarker(name) || name === '教室名称') continue;

      var occupied = false;
      for (var c = 1; c < tds.length; c++) {
        if (textOf(tds[c].html).replace(/\s/g, '') !== '') { occupied = true; break; }
      }
      if (!occupied) freeRooms.push(name);
    }
  }
  return { freeRooms: freeRooms, count: freeRooms.length };
}
