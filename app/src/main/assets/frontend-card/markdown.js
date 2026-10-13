/**
 * 轻量 Markdown 渲染：前端卡模式下的消息正文。
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
    if (/^(https?:|mailto:|tel:|data:image\/|file:)/i.test(u)) return u;
    if (/^[./#]/.test(u)) return u;
    return '#';
  }

  function inline(text) {
    var out = escapeHtml(text);
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

  function render(md) {
    var src = String(md == null ? '' : md);
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
        html.push('<pre><code' + (lang ? ' class="lang-' + escapeHtml(lang) + '"' : '') + '>' +
          escapeHtml(buf.join('\n')) + '</code></pre>');
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
        html.push('<blockquote>' + render(quote.join('\n')) + '</blockquote>');
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
    return html.join('\n');
  }

  function splitRow(line) {
    return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(function (c) { return c.trim(); });
  }

  global.RikkaMarkdown = { render: render, escapeHtml: escapeHtml, inline: inline };
})(window);
