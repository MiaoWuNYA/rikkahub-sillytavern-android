/**
 * 前端卡专属模式的宿主页面。
 *
 * 一个 WebView 承载整个消息区：普通消息渲染成 MD3 气泡，前端卡（HTML 文档）
 * 交给同页 iframe，从而避免「一条消息一个 WebView」带来的闪退与兼容问题。
 *
 * 与原生侧的契约：
 *   原生 -> 页面： window.rikka.setState(json) / appendStream(json) / setTheme(json) / setMacros(json)
 *   页面 -> 原生： window.rikkaNative.send(text) / stop() / regenerate() / openLink(url) / slash(cmd, args)
 *
 * 卡片按酒馆的习惯注入一层薄桥（TavernHelper / SillyTavern.triggerSlash），
 * 让现成的酒馆前端卡能直接跑，不必为本地再改一遍。
 */
(function () {
  'use strict';

  var list = document.getElementById('list');
  var state = { messages: [], theme: {}, streaming: null, placeholder: '', macros: {} };
  // 已渲染消息的内容签名：流式刷新时只重挂真正变化的那一条
  var rendered = {};
  var variables = loadVariables();

  function esc(s) { return window.RikkaMarkdown.escapeHtml(s == null ? '' : s); }

  function initials(name) {
    var n = String(name || '?').trim();
    return n ? n.slice(0, 2) : '?';
  }

  // —— 变量表：酒馆的 getvar/setvar 语义，落在 localStorage 上跨消息保持 ——
  function loadVariables() {
    try {
      var raw = localStorage.getItem('rikka.frontend.vars');
      var parsed = raw ? JSON.parse(raw) : {};
      return parsed && typeof parsed === 'object' ? parsed : {};
    } catch (e) {
      return {};
    }
  }

  function saveVariables() {
    try { localStorage.setItem('rikka.frontend.vars', JSON.stringify(variables)); } catch (e) { /* 隐私模式等忽略 */ }
  }

  /** 酒馆内置宏：静态值与变量、掷骰、随机、注释 */
  function expandMacros(text) {
    if (!text || text.indexOf('{{') < 0) return text;
    var m = state.macros || {};
    var now = new Date();
    var statics = {
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
      'model': m.model || '',
      'time': now.toLocaleTimeString(),
      'date': now.toLocaleDateString(),
      'weekday': ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][now.getDay()],
      'isotime': now.toTimeString().slice(0, 8),
      'isodate': now.toISOString().slice(0, 10),
      'newline': '\n'
    };
    var out = String(text);
    // 注释宏整段丢弃（酒馆用它写不会发给模型的说明）
    out = out.replace(/\{\{\/\/[\s\S]*?\}\}/g, '');
    // 变量：{{getvar::name}} / {{getglobalvar::name}}
    out = out.replace(/\{\{\s*(?:getvar|getglobalvar)\s*::\s*([^}]+?)\s*\}\}/gi, function (full, name) {
      var key = name.trim();
      return Object.prototype.hasOwnProperty.call(variables, key) ? String(variables[key]) : '';
    });
    // 掷骰：{{roll:2d6+1}} / {{roll:1d20}}
    out = out.replace(/\{\{\s*roll\s*::\s*([^}]+?)\s*\}\}/gi, function (full, expr) {
      return String(rollDice(expr));
    });
    // 随机列表：{{random:a,b}} / {{pick:a,b}}
    out = out.replace(/\{\{\s*(?:random|pick)\s*::\s*([^}]+?)\s*\}\}/gi, function (full, items) {
      var parts = String(items).split(',').map(function (s) { return s.trim(); }).filter(Boolean);
      return parts.length ? parts[Math.floor(Math.random() * parts.length)] : '';
    });
    out = out.replace(/\{\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\}\}/g, function (full, key) {
      return Object.prototype.hasOwnProperty.call(statics, key) ? statics[key] : full;
    });
    return out;
  }

  /** setvar 需要在渲染前生效，所以单独先跑一遍写操作 */
  function applySetVars(text) {
    if (!text || text.indexOf('{{') < 0) return text;
    return String(text).replace(/\{\{\s*(?:setvar|setglobalvar)\s*::\s*([^:}]+?)\s*::([\s\S]*?)\}\}/gi,
      function (full, name, value) {
        variables[name.trim()] = value.trim();
        saveVariables();
        return '';
      });
  }

  function rollDice(expr) {
    var m = /^\s*(\d*)\s*d\s*(\d+)\s*(?:([+-])\s*(\d+))?\s*$/i.exec(String(expr));
    if (!m) return expr;
    var count = m[1] ? parseInt(m[1], 10) : 1;
    var faces = parseInt(m[2], 10) || 6;
    var total = 0;
    for (var i = 0; i < Math.max(1, Math.min(count, 100)); i++) {
      total += 1 + Math.floor(Math.random() * faces);
    }
    if (m[3] === '-') total -= parseInt(m[4], 10) || 0;
    if (m[3] === '+') total += parseInt(m[4], 10) || 0;
    return total;
  }

  /** 卡片页内桥：酒馆前端卡常直接调这些接口 */
  var CARD_BRIDGE = [
    '<script>',
    '(function(){',
    '  var post = function(name, payload){ try { parent.postMessage({__rikka:name, payload:payload}, "*"); } catch(e){} };',
    '  var vars = function(){ try { return JSON.parse(localStorage.getItem("rikka.card.vars") || "{}"); } catch(e){ return {}; } };',
    '  var saveVars = function(v){ try { localStorage.setItem("rikka.card.vars", JSON.stringify(v)); } catch(e){} };',
    '  window.TavernHelper = window.TavernHelper || {',
    '    triggerSlash: function(cmd){ post("slash", String(cmd == null ? "" : cmd)); },',
    '    sendMessage: function(text){ post("send", String(text == null ? "" : text)); },',
    '    getVariables: function(){ return vars(); },',
    '    replaceVariables: function(v){ saveVars(v || {}); post("vars", JSON.stringify(v || {})); },',
    '    setVariables: function(v){ saveVars(v || {}); post("vars", JSON.stringify(v || {})); },',
    '    getCurrentMessageId: function(){ return post("messageId", ""), 0; }',
    '  };',
    '  window.SillyTavern = window.SillyTavern || window.TavernHelper;',
    '  window.triggerSlash = window.triggerSlash || function(cmd){ post("slash", String(cmd == null ? "" : cmd)); };',
    '  window.sendMessage = window.sendMessage || function(text){ post("send", String(text == null ? "" : text)); };',
    '})();',
    '<\/script>'
  ].join('\n');

  /** 前端卡：剥掉围栏标记，做成内联 iframe，宏与桥在其中一并注入 */
  function cardFrame(key, html) {
    var body = expandMacros(applySetVars(html));
    var frame = document.createElement('iframe');
    frame.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-forms allow-popups');
    frame.dataset.cardKey = key;
    frame.srcdoc = '<!DOCTYPE html><html><head><meta charset="utf-8">' +
      '<meta name="viewport" content="width=device-width, initial-scale=1.0">' +
      '<style>html,body{margin:0;padding:0;background:transparent;color:inherit;' +
      'font-family:-apple-system,"Noto Sans CJK SC",Roboto,sans-serif;word-break:break-word;}' +
      'img,video,canvas{max-width:100%;height:auto;}</style></head><body>' +
      body + CARD_BRIDGE +
      '<\/body></html>';
    // 卡片普遍靠脚本改高度：跟随内容自动长高，避免被裁成固定高度
    frame.addEventListener('load', function () { watchCardHeight(frame); });
    return frame;
  }

  /** 卡片高度自适应：轮询 + ResizeObserver 双保险（卡片常异步刷新内容） */
  function watchCardHeight(frame) {
    var measure = function () {
      try {
        var doc = frame.contentDocument;
        if (!doc || !doc.body) return;
        var h = Math.max(doc.body.scrollHeight, doc.documentElement ? doc.documentElement.scrollHeight : 0);
        if (h > 0 && Math.abs(h - (frame._lastHeight || 0)) > 2) {
          frame._lastHeight = h;
          frame.style.height = h + 'px';
        }
      } catch (e) { /* 跨域或未就绪，忽略 */ }
    };
    measure();
    [60, 200, 600, 1500].forEach(function (delay) { setTimeout(measure, delay); });
    try {
      if (frame.contentWindow && frame.contentWindow.ResizeObserver) {
        var ro = new frame.contentWindow.ResizeObserver(measure);
        ro.observe(frame.contentDocument.body);
      }
    } catch (e) { /* 老 WebView 无 ResizeObserver，靠上面的延时兜底 */ }
    if (!frame._poll) {
      frame._poll = setInterval(measure, 1000);
      setTimeout(function () { clearInterval(frame._poll); frame._poll = null; }, 30000);
    }
  }

  function textBubble(body, text, streaming) {
    var div = document.createElement('div');
    div.className = 'bubble' + (streaming ? ' typing' : '');
    div.innerHTML = window.RikkaMarkdown.render(expandMacros(text));
    body.appendChild(div);
  }

  /** 填充一条消息的正文区（气泡、推理、工具、卡片） */
  function fillBody(body, msg, streaming) {
    body.innerHTML = '';
    if (msg.reasoning) {
      var r = document.createElement('div');
      r.className = 'reasoning';
      r.textContent = msg.reasoning;
      body.appendChild(r);
    }
    var blocks = msg.blocks || [];
    if (!blocks.length && (msg.text || '').trim()) {
      blocks = [{ type: 'text', text: msg.text }];
    }
    blocks.forEach(function (block) {
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
        textBubble(body, block.text, streaming);
      }
    });
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
    body.className = 'body';
    body.style.maxWidth = '100%';
    fillBody(body, msg, !!msg.streaming);
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

  /**
   * 流式增量：只更新正文。
   * 卡片所在消息在增量里保持原 iframe 不动（重建会让卡片的脚本状态全部丢失，
   * 视觉上也会闪），只有文本部分跟着 token 走。
   */
  function syncStreaming(streamMsg) {
    var last = list.lastElementChild;
    if (!last || last.dataset.id !== streamMsg.id) {
      var node = renderMessage(streamMsg);
      list.appendChild(node);
      rendered[streamMsg.id] = streamMsg.signature;
      return;
    }
    var body = last.querySelector('.body');
    if (!body) return;
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
    setTheme: function (json) { applyTheme(parse(json)); },
    setMacros: function (json) { state.macros = parse(json) || {}; },
    setPlaceholder: function (text) { state.placeholder = text; },
    setState: function (json) {
      var payload = parse(json) || {};
      state.messages = payload.messages || [];
      if (payload.theme) applyTheme(payload.theme);
      if (payload.macros) state.macros = payload.macros;
      if (payload.placeholder) state.placeholder = payload.placeholder;
      renderAll();
    },
    appendStream: function (json) {
      var msg = parse(json);
      if (!msg) return;
      syncStreaming(msg);
      scrollToBottom(true);
    },
    finishStream: function (json) { window.rikka.setState(json); },
    /**
     * 追加或更新单条消息。
     * 生成结束时用它落定最后一条：整页 setState 会把所有卡片 iframe 重建，
     * 卡里的脚本状态和滚动位置都会丢，看起来像闪一下。
     */
    appendMessage: function (json) {
      var msg = parse(json);
      if (!msg) return;
      var existing = list.querySelector('[data-id="' + msg.id + '"]');
      if (existing) {
        if (rendered[msg.id] === msg.signature) return;
        var body = existing.querySelector('.body');
        if (body) fillBody(body, msg, false);
        var timeNode = existing.querySelector('.time');
        if (timeNode && msg.time) timeNode.textContent = msg.time;
        rendered[msg.id] = msg.signature;
        scrollToBottom(true);
        return;
      }
      state.messages.push(msg);
      var empty = list.querySelector('.empty');
      if (empty) list.removeChild(empty);
      list.appendChild(renderMessage(msg));
      rendered[msg.id] = msg.signature;
      scrollToBottom(true);
    },
    scrollToBottom: function () { scrollToBottom(true); },
    getVariables: function () { return JSON.stringify(variables); }
  };

  function parse(json) {
    if (json == null) return null;
    if (typeof json === 'object') return json;
    try { return JSON.parse(json); } catch (e) { return null; }
  }

  // —— 页面 -> 原生 ——
  function native(name, payload) {
    try {
      if (window.rikkaNative && typeof window.rikkaNative[name] === 'function') {
        window.rikkaNative[name](payload == null ? '' : String(payload));
      }
    } catch (e) { /* 原生侧未就绪时静默 */ }
  }

  // 卡片 iframe 与页面内链接的外发通道
  window.addEventListener('message', function (event) {
    var data = event.data;
    if (!data || !data.__rikka) return;
    var payload = typeof data.payload === 'string' ? data.payload : JSON.stringify(data.payload || '');
    if (data.__rikka === 'send') native('send', payload);
    else if (data.__rikka === 'slash') native('slash', payload);
    else if (data.__rikka === 'vars') {
      try { variables = JSON.parse(payload) || variables; saveVariables(); } catch (e) { /* 忽略坏数据 */ }
    }
  }, false);

  document.addEventListener('click', function (event) {
    var link = event.target.closest && event.target.closest('a');
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
