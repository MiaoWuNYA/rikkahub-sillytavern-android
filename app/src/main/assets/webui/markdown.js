/**
 * 轻量 Markdown 渲染：聊天 WebView（webui 与前端卡模式共用）的消息正文。
 *
 * 不引第三方库（离线可用、体积可控），只覆盖聊天里真正出现的语法：
 * 标题、加粗/斜体/删除线、行内代码、围栏代码、引用、列表、表格、链接、图片、换行。
 * 文本先做 HTML 转义，再按标记替换，避免模型输出的标签直接注入 DOM。
 */
(function (global) {
  'use strict';

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  // 只允许安全协议，挡掉 javascript: 之类的注入
  function safeUrl(url) {
    var u = String(url || '').trim();
    if (/^(https?:|mailto:|tel:|data:image\/|file:|content:)/i.test(u)) return u;
    if (/^[./#]/.test(u)) return u;
    return '#';
  }

  /**
   * 解一层 HTML 实体。
   *
   * 有些来源（客户端复制、正则脚本、模型自我保护）会把 HTML 先转义一次再落进消息，
   * 文本里于是是 `&lt;span style=&quot;color:#x&quot;&gt;`。渲染器再转义一次就成了双重转义，
   * 白名单还原匹配不上，页面上就原样显示 `<span style="color:#x">` 给用户看。
   * 先用单次扫描解开一层（不做递归），再走正常的转义 + 白名单还原。
   */
  var ENTITY_RE = /&(?:lt|gt|quot|#39|amp);/g;
  var ENTITY_MAP = { '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#39;': "'", '&amp;': '&' };

  function decodeEntities(text) {
    var s = String(text == null ? '' : text);
    if (s.indexOf('&') < 0) return s;
    // 最多解两层：客户端复制与正则脚本常各转义一次
    for (var i = 0; i < 2; i++) {
      var next = s.replace(ENTITY_RE, function (m) { return ENTITY_MAP[m] || m; });
      if (next === s) break;
      s = next;
    }
    return s;
  }

  function inline(text) {
    var out = escapeHtml(decodeEntities(text));
    // 行内代码先占位，避免其中内容再被其它规则改写
    var codes = [];
    out = out.replace(/`([^`\n]+)`/g, function (_, code) {
      codes.push(code);
      return '\u0000' + (codes.length - 1) + '\u0000';
    });
    out = out.replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+&quot;([^&]*)&quot;)?\)/g, function (_, alt, src) {
      return '<img alt="' + alt + '" src="' + safeUrl(src) + '">';
    });
    out = out.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, function (_, label, href) {
      return '<a href="' + safeUrl(href) + '" target="_blank" rel="noreferrer">' + label + '</a>';
    });
    out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    out = out.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
    out = out.replace(/~~([^~]+)~~/g, '<del>$1</del>');
    out = out.replace(/__([^_]+)__/g, '<u>$1</u>');
    out = out.replace(/\u0000(\d+)\u0000/g, function (_, i) {
      return '<code>' + codes[Number(i)] + '</code>';
    });
    return out;
  }

  function isTableRow(line) {
    return /^\s*\|.*\|\s*$/.test(line);
  }

  function isTableSep(line) {
    return /^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(line) && line.indexOf('-') >= 0;
  }

  /**
   * 还原被转义的内联标签并按白名单过滤属性。
   *
   * 模型与角色卡常用 <span style="color:#xxx"> 上色，渲染器为安全先把 HTML 转义了，
   * 这里在输出阶段把白名单标签放回去——与原生渲染路径同一套白名单与 CSS 属性集。
   */
  var INLINE_TAG_RE = /&lt;(\/?)(span|font|b|i|u|em|strong|br|sub|sup|small)\b([^&]*(?:&(?!gt;)[^&]*)*)&gt;/gi;
  var SAFE_CSS_RE = /(color|background-color|font-size|font-weight|font-style|text-decoration|opacity)\s*:\s*([^;'"<>()]{1,64})/gi;

  function sanitizeStyle(raw) {
    var out = [];
    var m;
    SAFE_CSS_RE.lastIndex = 0;
    while ((m = SAFE_CSS_RE.exec(String(raw || ''))) !== null) {
      out.push(m[1].toLowerCase() + ':' + m[2].trim());
    }
    return out.join(';');
  }

  function restoreInlineTags(html) {
    if (!html || html.indexOf('&lt;') < 0) return html;
    return html.replace(INLINE_TAG_RE, function (full, slash, tag, attrs) {
      var name = tag.toLowerCase();
      if (slash === '/') return '</' + name + '>';
      if (name === 'br') return '<br>';
      var attrText = String(attrs || '')
        .replace(/&quot;/g, '"')
        .replace(/&#39;/g, "'")
        .replace(/&amp;/g, '&');
      var styleMatch = /style\s*=\s*"([^"]*)"/i.exec(attrText);
      if (name === 'font') {
        var colorMatch = /color\s*=\s*"([^"]*)"/i.exec(attrText);
        var color = colorMatch ? colorMatch[1].trim() : '';
        if (!/^#[0-9a-fA-F]{3,8}$|^rgb/i.test(color)) color = '';
        return color ? '<font color="' + color + '">' : '<font>';
      }
      if (name === 'span') {
        var style = styleMatch ? sanitizeStyle(styleMatch[1]) : '';
        return style ? '<span style="' + style + '">' : '<span>';
      }
      return '<' + name + '>';
    });
  }

  /**
   * 非 ASCII 标签还原：卡作者把说话人写成 <清韵>，浏览器不认这种标签名
   * （`<` 退化成文本、`</清韵>` 退化成注释），页面上就出现「<清韵>」字面量。
   * 转成 ASCII 类名 + data 属性的行内元素，文字正常显示且能被 CSS 选中。
   */
  var CHARA_TAG_RE = /&lt;(\/?)([A-Za-z0-9_:\-]*[\u0080-\uFFFF][A-Za-z0-9_:\-\u0080-\uFFFF]*)((?:(?!&gt;)[\s\S])*?)(\/?)&gt;/g;

  function restoreCharaTags(html) {
    if (!html || html.indexOf('&lt;') < 0) return html;
    return html.replace(CHARA_TAG_RE, function (full, slash, name, attrs, selfClose) {
      if (slash === '/') return '</span>';
      var open = '<span class="chara-tag" data-chara="' + name + '">' + name + '</span>';
      // 自闭合的写法只出现一次标签，补一个空配对避免后续 </span> 错位
      return selfClose ? open : '<span class="chara-tag" data-chara="' + name + '">';
    });
  }

  var COPY_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<rect x="9" y="9" width="11" height="11" rx="2"></rect><path d="M5 15V5a2 2 0 0 1 2-2h8"></path></svg>';

  var COLLAPSE_LINES = 10;
  // 仅折叠「前端」代码块时认这些语言标签
  var FRONTEND_LANG_RE = /^(html?|xml|svg|vue|svelte)$/i;

  /** 围栏代码块：外框 + 语言标签 + 复制按钮（对齐 HighlightCodeBlock） */
  function codeBlock(code, lang, opts) {
    var lines = code.split('\n');
    var body;
    if (opts && opts.lineNumbers) {
      body = lines.map(function (line) {
        return '<span class="cl">' + escapeHtml(line) + '</span>';
      }).join('\n');
    } else {
      body = escapeHtml(code);
    }
    var mode = opts && opts.collapse;
    var wantCollapse = mode === true || (mode === 'frontend' && FRONTEND_LANG_RE.test(lang || ''));
    var collapsed = wantCollapse && lines.length > COLLAPSE_LINES;
    return '<pre' + (collapsed ? ' class="collapsed"' : '') + '>' +
      '<div class="code-head">' +
      '<span class="lang">' + escapeHtml(lang || '') + '</span>' +
      '<span class="spacer"></span>' +
      '<button type="button" class="copy" data-code aria-label="复制代码" title="复制代码">' + COPY_ICON + '</button>' +
      '</div>' +
      '<code' + (lang ? ' class="lang-' + escapeHtml(lang) + '"' : '') + '>' + body + '</code>' +
      (collapsed ? '<button type="button" class="expand" data-expand>展开全部（共 ' + lines.length + ' 行）</button>' : '') +
      '</pre>';
  }

  function render(md, opts) {
    var src = String(md == null ? '' : md);
    var options = opts || {};
    var lines = src.split('\n');
    var html = [];
    var i = 0;

    while (i < lines.length) {
      var line = lines[i];

      if (/^\s*```/.test(line)) {
        var lang = line.replace(/^\s*```/, '').trim();
        var buf = [];
        i++;
        while (i < lines.length && !/^\s*```/.test(lines[i])) { buf.push(lines[i]); i++; }
        i++;
        html.push(codeBlock(buf.join('\n'), lang, options));
        continue;
      }

      var heading = /^(#{1,6})\s+(.*)$/.exec(line);
      if (heading) {
        var level = Math.min(heading[1].length, 6);
        html.push('<h' + level + '>' + inline(heading[2]) + '</h' + level + '>');
        i++;
        continue;
      }

      if (/^\s*(---|\*\*\*|___)\s*$/.test(line)) { html.push('<hr>'); i++; continue; }

      if (isTableRow(line) && i + 1 < lines.length && isTableSep(lines[i + 1])) {
        var head = splitRow(line);
        var rows = [];
        i += 2;
        while (i < lines.length && isTableRow(lines[i])) { rows.push(splitRow(lines[i])); i++; }
        var t = '<table><thead><tr>' + head.map(function (c) { return '<th>' + inline(c) + '</th>'; }).join('') + '</tr></thead><tbody>';
        rows.forEach(function (r) {
          t += '<tr>' + r.map(function (c) { return '<td>' + inline(c) + '</td>'; }).join('') + '</tr>';
        });
        html.push(t + '</tbody></table>');
        continue;
      }

      if (/^\s*>\s?/.test(line)) {
        var quote = [];
        while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
          quote.push(lines[i].replace(/^\s*>\s?/, ''));
          i++;
        }
        html.push('<blockquote>' + render(quote.join('\n'), options) + '</blockquote>');
        continue;
      }

      if (/^\s*[-*+]\s+/.test(line)) {
        var items = [];
        while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) {
          items.push(lines[i].replace(/^\s*[-*+]\s+/, ''));
          i++;
        }
        html.push('<ul>' + items.map(function (t2) { return '<li>' + inline(t2) + '</li>'; }).join('') + '</ul>');
        continue;
      }

      if (/^\s*\d+[.)]\s+/.test(line)) {
        var oitems = [];
        while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) {
          oitems.push(lines[i].replace(/^\s*\d+[.)]\s+/, ''));
          i++;
        }
        html.push('<ol>' + oitems.map(function (t3) { return '<li>' + inline(t3) + '</li>'; }).join('') + '</ol>');
        continue;
      }

      var para = [];
      while (i < lines.length && lines[i].trim() !== '' &&
             !/^\s*```/.test(lines[i]) && !/^#{1,6}\s/.test(lines[i]) &&
             !/^\s*>\s?/.test(lines[i]) && !/^\s*[-*+]\s+/.test(lines[i]) &&
             !/^\s*\d+[.)]\s+/.test(lines[i])) {
        para.push(lines[i]);
        i++;
      }
      if (para.length) {
        html.push('<p>' + para.map(inline).join('<br>') + '</p>');
      } else {
        i++;
      }
    }
    return colorizeHtml(restoreCharaTags(restoreInlineTags(html.join('\n'))), options.colors);
  }

  function splitRow(line) {
    return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(function (c) { return c.trim(); });
  }

  /**
   * 引号/括号染色（对原生 Markdown.kt 的 TEXT_COLOR_REGEX 同一套配对）。
   *
   * 只对标签之外的文本做替换，且输出 class 而不是内联 span：内联 span 的 style 属性里
   * 带引号，注入后再被引号规则扫到就会整段错位——原生那边正是这么坏掉的。
   * 匹配半角引号用 `&quot;` 实体形态，天然不会碰到刚注入标签自己的属性引号。
   */
  var COLOR_SKIP_RE = /<(pre|code|script|style|textarea)\b[^>]*>[\s\S]*?<\/\1\s*>/gi;
  var COLOR_TAG_RE = /<[^>]*>/g;
  var COLOR_BRACKET_RE = /（[^（）]*）|【[^【】]*】|「[^「」]*」|『[^『』]*』|《[^《》]*》|‹[^‹›]*›|〔[^〔〕]*〕|｛[^｛｝]*｝|\{[^{}]*\}/g;
  var COLOR_QUOTE_RE = /&quot;[^&]*?&quot;|“[^“”]*”|«[^«»]*»|＂[^＂]*＂/g;

  function colorizeChunk(chunk, colors) {
    var out = chunk;
    if (colors.bracket) out = out.replace(COLOR_BRACKET_RE, '<span class="b">$&</span>');
    if (colors.quote) out = out.replace(COLOR_QUOTE_RE, '<span class="q">$&</span>');
    return out;
  }

  function colorizeSegment(html, colors) {
    var out = '';
    var last = 0;
    var m;
    COLOR_TAG_RE.lastIndex = 0;
    while ((m = COLOR_TAG_RE.exec(html)) !== null) {
      out += colorizeChunk(html.slice(last, m.index), colors) + m[0];
      last = m.index + m[0].length;
    }
    return out + colorizeChunk(html.slice(last), colors);
  }

  function colorizeHtml(html, colors) {
    if (!colors || (!colors.quote && !colors.bracket)) return html;
    var out = '';
    var last = 0;
    var m;
    COLOR_SKIP_RE.lastIndex = 0;
    while ((m = COLOR_SKIP_RE.exec(html)) !== null) {
      out += colorizeSegment(html.slice(last, m.index), colors) + m[0];
      last = m.index + m[0].length;
    }
    return out + colorizeSegment(html.slice(last), colors);
  }

  global.RikkaMarkdown = {
    render: render,
    escapeHtml: escapeHtml,
    inline: inline,
    restoreInlineTags: restoreInlineTags,
    restoreCharaTags: restoreCharaTags,
    colorizeHtml: colorizeHtml
  };
})(window);
