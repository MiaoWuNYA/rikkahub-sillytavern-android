/**
 * 前端卡专属模式的宿主页面。
 *
 * 一个 WebView 承载整个消息区：普通消息渲染成 MD3 气泡，前端卡（HTML 文档）
 * 交给同页 iframe，从而避免「一条消息一个 WebView」带来的闪退与兼容问题。
 *
 * 与原生侧的契约：
 *   原生 -> 页面： window.rikka.setState(json) / appendStream(json) / setTheme(json)
 *   页面 -> 原生： window.rikkaNative.send(text) / stop() / regenerate() / editMessage(id)
 */
(function () {
  'use strict';

  var list = document.getElementById('list');
  var state = { messages: [], theme: {}, streaming: null, placeholder: '' };
  // 卡片 iframe 的 srcdoc 缓存：流式更新时只重挂内容变化的那一条
  var rendered = {};

  function esc(s) { return window.RikkaMarkdown.escapeHtml(s == null ? '' : s); }

  function initials(name) {
    var n = String(name || '?').trim();
    return n ? n.slice(0, 2) : '?';
  }

  /** 静态宏替换：值与酒馆对齐，未支持的宏保持原样，方便一眼看出缺什么 */
  function expandMacros(text) {
    if (!text || text.indexOf('{{') < 0) return text;
    var m = state.macros || {};
    var map = {
      'char': m.char || '', 'charName': m.char || '',
      'user': m.user || '', 'userName': m.user || '',
      'persona': m.persona || '',
      'description': m.description || '',
      'personality': m.personality || '',
      'scenario': m.scenario || '',
      'mesExamples': m.mesExamples || '',
      'charPrompt': m.charPrompt || '',
      'charInstruction': m.charInstruction || '',
      'lastMessage': m.lastMessage || '',
      'time': new Date().toLocaleTimeString(),
      'date': new Date().toLocaleDateString(),
      'weekday': ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][new Date().getDay()],
      'isotime': new Date().toTimeString().slice(0, 8),
      'isodate': new Date().toISOString().slice(0, 10),
      'newline': '\n'
    };
    return String(text).replace(/\{\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\}\}/g, function (full, key) {
      return Object.prototype.hasOwnProperty.call(map, key) ? map[key] : full;
    });
  }

  /** 前端卡：剥掉围栏标记，做成内联 iframe，宏在其中照常展开 */
  function cardFrame(key, html) {
    var frame = document.createElement('iframe');
    frame.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-forms allow-popups');
    frame.dataset.cardKey = key;
    frame.srcdoc = '<!DOCTYPE html><html><head><meta charset="utf-8">' +
      '<meta name="viewport" content="width=device-width, initial-scale=1.0">' +
      '<style>html,body{margin:0;padding:0;background:transparent;color:inherit;' +
      'font-family:-apple-system,"Noto Sans CJK SC",Roboto,sans-serif;word-break:break-word;}' +
      'img,video,canvas{max-width:100%;height:auto;}</style></head><body>' +
      expandMacros(html) + '</body></html>';
    return frame;
  }

  function textBubble(body, text, streaming) {
    var div = document.createElement('div');
    div.className = 'bubble' + (streaming ? ' typing' : '');
    div.innerHTML = window.RikkaMarkdown.render(expandMacros(text));
    body.appendChild(div);
  }

  function renderMessage(msg) {
    var row = document.createElement('div');
    row.className = 'row ' + (msg.role === 'user' ? 'user' : 'ai');
    row.dataset.id = msg.id;

    var avatar = document.createElement('div');
    avatar.className = 'avatar';
    if (msg.avatar) {
      var img = document.createElement('img');
      img.src = msg.avatar;
      avatar.appendChild(img);
    } else {
      avatar.textContent = initials(msg.name);
    }
    row.appendChild(avatar);

    var col = document.createElement('div');
    col.className = 'col';
    if (msg.name) {
      var name = document.createElement('div');
      name.className = 'name';
      name.textContent = msg.name;
      col.appendChild(name);
    }

    var body = document.createElement('div');
    body.style.maxWidth = '100%';

    if (msg.reasoning) {
      var r = document.createElement('div');
      r.className = 'reasoning';
      r.textContent = msg.reasoning;
      body.appendChild(r);
    }

    (msg.blocks || []).forEach(function (block) {
      if (block.type === 'card') {
        var wrap = document.createElement('div');
        wrap.className = 'card';
        wrap.appendChild(cardFrame(msg.id + ':' + (block.index || 0), block.html));
        body.appendChild(wrap);
      } else if (block.type === 'tool') {
        var tool = document.createElement('div');
        tool.className = 'tool';
        tool.textContent = block.text || '';
        body.appendChild(tool);
      } else if (block.type === 'text' && (block.text || '').trim()) {
        textBubble(body, block.text, !!msg.streaming);
      }
    });

    if (!body.childNodes.length) textBubble(body, msg.text || '', !!msg.streaming);
    col.appendChild(body);

    if (msg.time) {
      var time = document.createElement('div');
      time.className = 'time';
      time.textContent = msg.time;
      col.appendChild(time);
    }

    row.appendChild(col);
    return row;
  }

  function renderAll() {
    list.innerHTML = '';
    rendered = {};
    if (!state.messages.length) {
      var empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = state.placeholder || '';
      list.appendChild(empty);
      return;
    }
    state.messages.forEach(function (msg) {
      rendered[msg.id] = msg.signature;
      list.appendChild(renderMessage(msg));
    });
    scrollToBottom(false);
  }

  /** 流式增量：只改最后一条，避免整页重建导致 iframe 重载闪烁 */
  function syncStreaming(streamMsg) {
    var last = list.lastElementChild;
    if (!last || last.dataset.id !== streamMsg.id) {
      var node = renderMessage(streamMsg);
      list.appendChild(node);
      rendered[streamMsg.id] = streamMsg.signature;
      return;
    }
    var col = last.querySelector('.col');
    var body = col.querySelector('div[style]') || col;
    body.innerHTML = '';
    if (streamMsg.reasoning) {
      var r = document.createElement('div');
      r.className = 'reasoning';
      r.textContent = streamMsg.reasoning;
      body.appendChild(r);
    }
    (streamMsg.blocks || []).forEach(function (block) {
      if (block.type === 'card') {
        var wrap = document.createElement('div');
        wrap.className = 'card';
        wrap.appendChild(cardFrame(streamMsg.id + ':' + (block.index || 0), block.html));
        body.appendChild(wrap);
      } else if (block.type === 'text' && (block.text || '').trim()) {
        textBubble(body, block.text, true);
      }
    });
    rendered[streamMsg.id] = streamMsg.signature;
  }

  function scrollToBottom(smooth) {
    var target = document.body.scrollHeight;
    try {
      window.scrollTo({ top: target, behavior: smooth ? 'smooth' : 'auto' });
    } catch (e) {
      window.scrollTo(0, target);
    }
  }

  function applyTheme(theme) {
    if (!theme) return;
    Object.keys(theme).forEach(function (key) {
      document.documentElement.style.setProperty('--md-' + key, theme[key]);
    });
  }

  // —— 原生侧接口 ——
  window.rikka = {
    setTheme: function (json) {
      applyTheme(typeof json === 'string' ? JSON.parse(json) : json);
    },
    setMacros: function (json) {
      state.macros = typeof json === 'string' ? JSON.parse(json) : json;
    },
    setPlaceholder: function (text) { state.placeholder = text; },
    setState: function (json) {
      var payload = typeof json === 'string' ? JSON.parse(json) : json;
      state.messages = payload.messages || [];
      if (payload.theme) applyTheme(payload.theme);
      if (payload.macros) state.macros = payload.macros;
      if (payload.placeholder) state.placeholder = payload.placeholder;
      renderAll();
    },
    appendStream: function (json) {
      var msg = typeof json === 'string' ? JSON.parse(json) : json;
      syncStreaming(msg);
      scrollToBottom(true);
    },
    /** 已完成的最后一条与流式结果对齐时，用它把「打字」气泡落定 */
    finishStream: function (json) {
      window.rikka.setState(json);
    },
    /** 追加一条新消息（发送后立刻回显） */
    appendMessage: function (json) {
      var msg = typeof json === 'string' ? JSON.parse(json) : json;
      if (rendered[msg.id] === msg.signature && list.querySelector('[data-id="' + msg.id + '"]')) return;
      state.messages.push(msg);
      var empty = list.querySelector('.empty');
      if (empty) list.removeChild(empty);
      list.appendChild(renderMessage(msg));
      rendered[msg.id] = msg.signature;
      scrollToBottom(true);
    },
    scrollToBottom: function () { scrollToBottom(true); }
  };

  // —— 页面 -> 原生 ——
  function native(name, payload) {
    try {
      if (window.rikkaNative && typeof window.rikkaNative[name] === 'function') {
        window.rikkaNative[name](payload == null ? '' : String(payload));
      }
    } catch (e) { /* 原生侧未就绪时静默 */ }
  }

  document.addEventListener('click', function (event) {
    var link = event.target.closest('a');
    if (link && link.href) {
      event.preventDefault();
      native('openLink', link.href);
    }
  }, true);

  window.rikkaSend = function (text) { native('send', text); };
  window.rikkaStop = function () { native('stop', ''); };
  window.rikkaRegenerate = function () { native('regenerate', ''); };
  window.rikkaReady = function () { native('ready', ''); };
})();
