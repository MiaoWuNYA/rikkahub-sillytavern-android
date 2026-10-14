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
  state.libs = {};
  // 已渲染消息的内容签名：流式刷新时只重挂真正变化的那一条
  var rendered = {};
  var variables = loadVariables();
  var globalVariables = {};
  var messageVariables = {};

  // 变量域变化广播给卡片（卡片各自留一份副本，读操作保持同步）
  function pushVarsToCards() {
    var frames = document.querySelectorAll('iframe');
    var scopes = { local: variables, global: globalVariables, message: messageVariables };
    Object.keys(scopes).forEach(function (scope) {
      var json = JSON.stringify(scopes[scope] || {});
      for (var i = 0; i < frames.length; i++) {
        try {
          var fn = frames[i].contentWindow && frames[i].contentWindow.__rikkaSetVars;
          if (fn) fn(scope, json);
        } catch (e) { /* 跨域忽略 */ }
      }
    });
  }

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

  // 酒馆内置宏：静态值与变量、掷骰、随机、注释
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
    out = out.replace(/\{\{\/\/[^\n]*?\}\}/g, '');
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

  // setvar 需要在渲染前生效，所以单独先跑一遍写操作
  function applySetVars(text) {
    if (!text || text.indexOf('{{') < 0) return text;
    return String(text).replace(/\{\{\s*(?:setvar|setglobalvar)\s*::\s*([^:\n}]+?)\s*::([^\n}]*?)\}\}/gi,
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

  // 卡片页内桥：酒馆前端卡常直接调这些接口
  // 卡片常依赖的 lodash 子集：只实现被高频使用的部分，未覆盖的返回安全的空值
  var LODASH_LITE = [
    '(function(){',
    '  var _ = {};',
    '  _.isArray = Array.isArray;',
    '  _.isObject = function(v){ return v !== null && typeof v === "object"; };',
    '  _.isString = function(v){ return typeof v === "string"; };',
    '  _.isNumber = function(v){ return typeof v === "number"; };',
    '  _.isFunction = function(v){ return typeof v === "function"; };',
    '  _.isNil = function(v){ return v === null || v === undefined; };',
    '  _.isEmpty = function(v){ if (v == null) return true; if (_.isArray(v) || _.isString(v)) return v.length === 0; return Object.keys(v).length === 0; };',
    '  _.get = function(o, path, def){',
    '    var parts = String(path == null ? "" : path).replace(/\\[(\\d+)\\]/g, ".$1").split(".").filter(Boolean);',
    '    var cur = o;',
    '    for (var i = 0; i < parts.length; i++) { if (cur == null) return def; cur = cur[parts[i]]; }',
    '    return cur === undefined ? def : cur;',
    '  };',
    '  _.set = function(o, path, value){',
    '    var parts = String(path).split("."); var cur = o;',
    '    for (var i = 0; i < parts.length - 1; i++) { if (cur[parts[i]] == null) cur[parts[i]] = {}; cur = cur[parts[i]]; }',
    '    cur[parts[parts.length - 1]] = value; return o;',
    '  };',
    '  _.has = function(o, path){ return _.get(o, path, undefined) !== undefined; };',
    '  _.cloneDeep = function(v){ try { return JSON.parse(JSON.stringify(v)); } catch(e){ return v; } };',
    '  _.clone = _.cloneDeep;',
    '  _.merge = function(target){',
    '    for (var i = 1; i < arguments.length; i++) {',
    '      var src = arguments[i] || {};',
    '      Object.keys(src).forEach(function(k){',
    '        if (_.isObject(target[k]) && _.isObject(src[k])) _.merge(target[k], src[k]); else target[k] = src[k];',
    '      });',
    '    }',
    '    return target;',
    '  };',
    '  _.assign = function(t){ for (var i = 1; i < arguments.length; i++) Object.assign(t, arguments[i] || {}); return t; };',
    '  _.forEach = function(c, fn){ if (!c) return c; if (_.isArray(c)) c.forEach(fn); else Object.keys(c).forEach(function(k){ fn(c[k], k); }); return c; };',
    '  _.map = function(c, fn){ var out = []; _.forEach(c, function(v, k){ out.push(fn(v, k)); }); return out; };',
    '  _.filter = function(c, fn){ var out = []; _.forEach(c, function(v, k){ if (fn(v, k)) out.push(v); }); return out; };',
    '  _.find = function(c, fn){ var hit; _.forEach(c, function(v, k){ if (hit === undefined && fn(v, k)) hit = v; }); return hit; };',
    '  _.keys = function(o){ return o ? Object.keys(o) : []; };',
    '  _.values = function(o){ return o ? Object.keys(o).map(function(k){ return o[k]; }) : []; };',
    '  _.random = function(a, b){ if (b === undefined) { b = a; a = 0; } return a + Math.floor(Math.random() * (b - a + 1)); };',
    '  _.sample = function(arr){ return arr && arr.length ? arr[Math.floor(Math.random() * arr.length)] : undefined; };',
    '  _.shuffle = function(arr){ var a = (arr || []).slice(); for (var i = a.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)); var t = a[i]; a[i] = a[j]; a[j] = t; } return a; };',
    '  _.debounce = function(fn, wait){ var t; return function(){ var a = arguments, self = this; clearTimeout(t); t = setTimeout(function(){ fn.apply(self, a); }, wait || 0); }; };',
    '  _.throttle = function(fn, wait){ var last = 0; return function(){ var now = Date.now(); if (now - last >= (wait || 0)) { last = now; fn.apply(this, arguments); } }; };',
    '  _.uniqueId = (function(){ var n = 0; return function(p){ n++; return String(p || "") + n; }; })();',
    '  _.escape = function(s){ return String(s == null ? "" : s).replace(/[&<>"\x27]/g, function(c){ return {"&":"&amp;","<":"&lt;",">":"&gt;","\\"":"&quot;","\x27":"&#39;"}[c]; }); };',
    '  _.template = function(str){ return function(data){ return String(str).replace(/<%=\s*([\w.]+)\s*%>/g, function(m, k){ return _.get(data, k, ""); }); }; };',
    '  _.clamp = function(v, lo, hi){ return Math.min(Math.max(v, lo), hi); };',
    '  _.range = function(n){ var out = []; for (var i = 0; i < n; i++) out.push(i); return out; };',
    '  window._ = window._ || _;',
    '  window.lodash = window.lodash || _;',
    '})();'
  ].join('\n');

  // 卡片内桥：接口名与酒馆助手对齐，能力全部回请原生
  var CARD_BRIDGE_TEMPLATE = [
    '<script>',
    '(function(){',
    '  var MID = __MID__, IDX = __IDX__;',
    '  var post = function(name, payload){ try { parent.postMessage({__rikka:name, payload:payload}, "*"); } catch(e){} };',
    '  var pending = {}; var seq = 0;',
    '  var call = function(method, payload){',
    '    return new Promise(function(resolve){',
    '      var id = "c" + (++seq);',
    '      pending[id] = resolve;',
    '      try {',
    '        if (window.parent && window.parent.rikkaNative && window.parent.rikkaNative.request) {',
    '          window.parent.rikkaNative.request(method, id, String(payload == null ? "" : payload));',
    '        } else { post("__call", JSON.stringify({method:method, id:id, payload:payload})); }',
    '      } catch(e) { resolve(null); return; }',
    '      setTimeout(function(){ if (pending[id]) { pending[id] = null; resolve(null); } }, 5000);',
    '    });',
    '  };',
    '  window.__rikkaResolve = function(id, value){ var f = pending[id]; if (f) { pending[id] = null; f(value); } };',
    '  var vars = function(type){',
    '    type = type || "local";',
    '    try { return JSON.parse(localStorage.getItem("rikka.card.vars." + type) || "{}"); } catch(e) { return {}; }',
    '  };',
    '  var saveVars = function(v, type){',
    '    type = type || "local";',
    '    try { localStorage.setItem("rikka.card.vars." + type, JSON.stringify(v || {})); } catch(e){}',
    '    post("vars", JSON.stringify({ type: type, vars: v || {} }));',
    '  };',
    '  var cache = {};',
    '  var unsupported = function(name){ return function(){ try { console.warn("[rikka] 尚未支持的接口: " + name); } catch(e){} return null; }; };',
    '  var api = {',
    '    getCurrentMessageId: function(){ return MID; },',
    '    getVariables: function(options){ return vars((options && options.type) || "local"); },',
    '    replaceVariables: function(v, options){ var t = (options && options.type) || "local"; saveVars(v, t); return v; },',
    '    setVariables: function(v, options){ var t = (options && options.type) || "local"; saveVars(v, t); return v; },',
    '    insertOrAssignVariables: function(v, options){ var t = (options && options.type) || "local"; var cur = vars(t); Object.keys(v || {}).forEach(function(k){ cur[k] = v[k]; }); saveVars(cur, t); return cur; },',
    '    insertVariables: function(v, options){ var t = (options && options.type) || "local"; var cur = vars(t); Object.keys(v || {}).forEach(function(k){ if (!(k in cur)) cur[k] = v[k]; }); saveVars(cur, t); return cur; },',
    '    deleteVariable: function(key, options){ var t = (options && options.type) || "local"; var cur = vars(t); delete cur[key]; saveVars(cur, t); return cur; },',
    '    updateVariablesWith: function(fn, options){ var t = (options && options.type) || "local"; var next = fn(vars(t)) || {}; saveVars(next, t); return next; },',
    '    triggerSlash: function(cmd){ var c = String(cmd == null ? "" : cmd); post("slash", c); return call("triggerSlash", c); },',
    '    sendMessage: function(text){ post("send", String(text == null ? "" : text)); return Promise.resolve(); },',
    '    getChatMessages: function(range){ return call("getChatMessages", String(range == null ? "" : range)); },',
    '    setChatMessages: function(messages){ return call("setChatMessages", JSON.stringify(messages || [])); },',
    '    createChatMessages: function(messages){ return call("createChatMessages", JSON.stringify(messages || [])); },',
    '    deleteChatMessages: function(ids){ return call("deleteChatMessages", JSON.stringify(ids || [])); },',
    '    // 宏走后端引擎：命中缓存直接同步返回，否则先给原文并异步取回结果',
    '    substitudeMacros: function(text){',
    '      var key = String(text == null ? "" : text);',
    '      if (key.indexOf("{{") < 0) return key;',
    '      if (cache[key] != null) return cache[key];',
    '      call("substituteMacros", key).then(function(r){',
    '        if (r == null) return;',
    '        cache[key] = r;',
    '        try { if (typeof window.onRikkaMacros === "function") window.onRikkaMacros(key, r); } catch(e){}',
    '        post("macros", JSON.stringify({ from: key, to: r }));',
    '      });',
    '      return key;',
    '    },',
    '    getCharName: function(){ return __CHAR__; },',
    '    getUserName: function(){ return __USER__; },',
    '    getCharAvatarPath: function(){ return __AVATAR__; },',
    '    stopGeneration: function(){ post("stop", ""); return Promise.resolve(); },',
    '    regenerate: function(){ post("regenerate", ""); return Promise.resolve(); },',
    '    eventOn: function(name, handler){ return events.on(name, handler, false); },',
    '    eventOnce: function(name, handler){ return events.on(name, handler, true); },',
    '    eventOff: function(name, handler){ return events.off(name, handler); },',
    '    eventRemoveListener: function(name, handler){ return events.off(name, handler); },',
    '    eventEmit: function(name){ var args = Array.prototype.slice.call(arguments, 1); events.emit(name, args); }',
    '  };',
    '  api.substituteMacros = api.substitudeMacros;',
    '  ["getLorebookEntries","setLorebookEntries","injectPrompts","getPromptsInjected","audio","playAudio","generate","generateRaw","getChatHistoryBrief","getLastMessageId","formatAsTavernRegexedString","getWorldbookNames"].forEach(function(n){ if (!api[n]) api[n] = unsupported(n); });',
    '  var events = (function(){',
    '    var table = {};',
    '    return {',
    '      on: function(name, handler, once){ (table[name] = table[name] || []).push({ fn: handler, once: !!once }); return handler; },',
    '      off: function(name, handler){ if (!table[name]) return; table[name] = table[name].filter(function(e){ return e.fn !== handler; }); },',
    '      emit: function(name, args){',
    '        (table[name] || []).slice().forEach(function(e){',
    '          try { e.fn.apply(null, args || []); } catch(err){}',
    '          if (e.once) { var i = table[name].indexOf(e); if (i >= 0) table[name].splice(i, 1); }',
    '        });',
    '      }',
    '    };',
    '  })();',
    '  window.TavernHelper = window.TavernHelper || api;',
    '  window.SillyTavern = window.SillyTavern || api;',
    '  window.triggerSlash = window.triggerSlash || api.triggerSlash;',
    '  window.sendMessage = window.sendMessage || api.sendMessage;',
    '  window.eventOn = window.eventOn || api.eventOn;',
    '  window.getVariables = window.getVariables || api.getVariables;',
    '  window.messageFormat = window.messageFormat || { getMessageId: function(){ return MID; } };',
    '  window.__rikkaEmit = function(name, argsJson){',
    '    var args = [];',
    '    try { args = JSON.parse(argsJson || "[]"); } catch(e){}',
    '    events.emit(name, args);',
    '  };',
    '  window.__rikkaSetVars = function(scope, json){ try { localStorage.setItem("rikka.card.vars." + scope, json); } catch(e){} };',
    '})();',
    '<\/script>'
  ].join('\n');

  // 组装某张卡片的桥：把消息 id、卡序号与角色信息一并注入
  function buildCardBridge(messageId, cardIndex, charName, userName, avatar) {
    return CARD_BRIDGE_TEMPLATE
      .replace('__MID__', JSON.stringify(String(messageId == null ? '' : messageId)))
      .replace('__IDX__', JSON.stringify(Number(cardIndex) || 0))
      .replace('__CHAR__', JSON.stringify(String(charName == null ? '' : charName)))
      .replace('__USER__', JSON.stringify(String(userName == null ? '' : userName)))
      .replace('__AVATAR__', JSON.stringify(String(avatar == null ? '' : avatar)));
  }

  // 前端卡：剥掉围栏标记，做成内联 iframe，宏与桥在其中一并注入
  function cardFrame(key, html, ctx) {
    ctx = ctx || {};
    var body = expandMacros(applySetVars(html));
    var libs = state.libs || {};
    var shellHead = '<!DOCTYPE html><html><head><meta charset="utf-8">' +
      '<meta name="viewport" content="width=device-width, initial-scale=1.0">' +
      '<style>' +
      'html,body{margin:0;padding:0;background:transparent;color:inherit;' +
      'font-family:-apple-system,"Noto Sans CJK SC",Roboto,sans-serif;word-break:break-word;}' +
      'img,video,canvas{max-width:100%;height:auto;}' +
      'body{overflow:hidden;}' +
      // 卡片常把内容包在自定义标签里（如 <mainbody>），未知标签默认是行内元素，
      'mainbody,body>div,body>section,body>article,body>main{display:block;}' +
      // 卡片把内容包在自定义标签里（<phone>/<wechat>/<choice>），顶层容器要按块级排；
      'body>*{display:block;}' +
      'body>*>*{display:revert;}' +
      'body>mainbody>*{margin:0 0 .5em;}' +
      (state.cardStyle ? '<style>' + state.cardStyle + '</style>' : '') +
      ':root{--TH-viewport-height:' + Math.max(320, window.innerHeight || 640) + 'px;}' +
      '</style>' +
      (libs.jquery ? '<script>' + libs.jquery + '<\/script>' : '') +
      (libs.toastr ? '<script>' + libs.toastr + '<\/script>' : '') +
      '<script>' + LODASH_LITE + '<\/script>' +
      buildCardBridge(ctx.messageId, ctx.cardIndex, ctx.charName, ctx.userName, ctx.avatar) +
      '<script>' +
      '(function(){' +
      '  function report(){' +
      '    try {' +
      '      var h = Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.scrollHeight : 0);' +
      '      if (h > 0 && window.frameElement) window.frameElement.style.height = h + "px";' +
      '    } catch (e) {}' +
      '  }' +
      '  window.addEventListener("load", report);' +
      '  document.addEventListener("DOMContentLoaded", report);' +
      '  try { if (window.ResizeObserver && document.body) new ResizeObserver(report).observe(document.body); } catch (e) {}' +
      '  setInterval(report, 800);' +
      '  setTimeout(report, 60); setTimeout(report, 400);' +
      '})();' +
      '<\/script>' +
      '</head><body>';
    var frame = document.createElement('iframe');
    frame.dataset.cardKey = key;
    frame.setAttribute('frameborder', '0');
    frame.setAttribute('loading', 'lazy');
    frame.className = 'card-frame';
    frame.srcdoc = shellHead + body + '</body></html>';
    frame.style.height = '360px';
    frame.addEventListener('load', function () { watchCardHeight(frame); });
    return frame;
  }

  // 卡片高度自适应：轮询 + ResizeObserver 双保险（卡片常异步刷新内容）
  function watchCardHeight(frame) {
    var measure = function () {
      try {
        var doc = frame.contentDocument;
        if (!doc || !doc.body) return;
        var h = Math.max(doc.body.scrollHeight, doc.documentElement ? doc.documentElement.scrollHeight : 0);
        if (h > 20 && Math.abs(h - (frame._lastHeight || 0)) > 2) {
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

  // 填充一条消息的正文区（气泡、推理、工具、卡片）
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
        wrap.appendChild(cardFrame(msg.id + ':' + (block.index || 0), block.html, { messageId: msg.id, cardIndex: block.index || 0, charName: (state.macros || {}).char, userName: (state.macros || {}).user }));
        body.appendChild(wrap);
      } else if (block.type === 'image' || block.type === 'video' || block.type === 'audio' || block.type === 'document') {
        body.appendChild(attachmentNode(block));
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

    // 点消息回请原生菜单：复制、编辑、删除等仍走原生那套，WebView 只做入口
    row.addEventListener('click', function (event) {
      if (event.target.closest && event.target.closest('a, .attach-doc, iframe, audio, video, .bubble a')) return;
      native('messageAction', JSON.stringify({ id: String(msg.id), role: msg.role }));
    });

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

    // 备选回复切换：多分支时显示切换条，点箭头回请原生切换
    if (msg.swipeCount && msg.swipeCount > 1) {
      var nav = document.createElement('div');
      nav.className = 'swipe-nav';
      var prev = document.createElement('button');
      prev.className = 'swipe-btn';
      prev.textContent = '\u2039';
      prev.addEventListener('click', function (event) {
        event.stopPropagation();
        native('selectSwipe', JSON.stringify({ nodeId: msg.nodeId, index: Math.max(0, (msg.swipeIndex || 0) - 1) }));
      });
      var label = document.createElement('span');
      label.className = 'swipe-label';
      label.textContent = ((msg.swipeIndex || 0) + 1) + '/' + msg.swipeCount;
      var next = document.createElement('button');
      next.className = 'swipe-btn';
      next.textContent = '\u203a';
      next.addEventListener('click', function (event) {
        event.stopPropagation();
        native('selectSwipe', JSON.stringify({ nodeId: msg.nodeId, index: Math.min(msg.swipeCount - 1, (msg.swipeIndex || 0) + 1) }));
      });
      nav.appendChild(prev);
      nav.appendChild(label);
      nav.appendChild(next);
      col.appendChild(nav);
    }

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

  // 流式增量：只更新正文。
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
        wrap.appendChild(cardFrame(streamMsg.id + ':' + (block.index || 0), block.html, { messageId: streamMsg.id, cardIndex: block.index || 0, charName: (state.macros || {}).char, userName: (state.macros || {}).user }));
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
    // 原生下发的卡片运行库（jQuery / toastr，base64 传输避免转义问题）
    setLibs: function (json) {
      var payload = parse(json) || {};
      Object.keys(payload).forEach(function (name) {
        try { state.libs[name] = atob(payload[name]); } catch (e) { /* 解码失败就当没有 */ }
      });
    },

    // 原生下发的遮挡区域（标题栏 / 输入栏高度）
    setSafeArea: function (json) { applySafeArea(parse(json)); },

    // 原生下发的聊天外观自定义
    setAppearance: function (json) { applyAppearance(parse(json)); },

    // 原生下发的变量域快照（local / global / message）
    setVariables: function (json) {
      var payload = parse(json) || {};
      Object.keys(payload).forEach(function (scope) {
        var vars = payload[scope] || {};
        if (scope === 'local') variables = vars;
        else if (scope === 'global') globalVariables = vars;
        else if (scope === 'message') messageVariables = vars;
      });
      pushVarsToCards();
    },

    // 原生回填：把结果送进对应卡片的 Promise（卡片的同步 API 靠这条保持可用）
    resolve: function (requestId, value) {
      var frames = document.querySelectorAll('iframe');
      for (var i = 0; i < frames.length; i++) {
        try {
          var fn = frames[i].contentWindow && frames[i].contentWindow.__rikkaResolve;
          if (fn) fn(requestId, value);
        } catch (e) { /* 忽略 */ }
      }
    },

    // 原生事件 → 页面 → 所有卡片
    emitEvent: function (name, payloadJson) {
      var frames = document.querySelectorAll('iframe');
      for (var i = 0; i < frames.length; i++) {
        try {
          var fn = frames[i].contentWindow && frames[i].contentWindow.__rikkaEmit;
          if (fn) fn(name, payloadJson);
        } catch (e) { /* 忽略 */ }
      }
    },
    setTheme: function (json) { applyTheme(parse(json)); },
    setMacros: function (json) { state.macros = parse(json) || {}; },
    setPlaceholder: function (text) { state.placeholder = text; },
    setState: function (json) {
      var payload = parse(json) || {};
      state.messages = payload.messages || [];
      if (payload.theme) applyTheme(payload.theme);
      if (payload.macros) state.macros = payload.macros;
      if (typeof payload.cardStyle === 'string') state.cardStyle = payload.cardStyle;
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
    // 追加或更新单条消息。
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


  // 聊天外观自定义：把设置里的气泡、背景、字体、引用配色落到 CSS 变量
  function withAlpha(hex, alpha) {
    var m = /^#?([0-9a-f]{6})$/i.exec(String(hex || ''));
    if (!m) return hex;
    var n = parseInt(m[1], 16);
    var a = (alpha == null ? 1 : Math.max(0, Math.min(1, alpha)));
    return 'rgba(' + ((n >> 16) & 255) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')';
  }


  // 附件块：图片 / 视频 / 音频 / 文档
  function attachmentNode(block) {
    var wrap = document.createElement('div');
    wrap.className = 'blk attach';
    if (block.type === 'image') {
      var img = document.createElement('img');
      img.className = 'attach-img';
      img.src = block.url || '';
      img.loading = 'lazy';
      img.alt = '';
      wrap.appendChild(img);
    } else if (block.type === 'video') {
      var v = document.createElement('video');
      v.className = 'attach-media';
      v.src = block.url || '';
      v.controls = true;
      v.playsInline = true;
      wrap.appendChild(v);
    } else if (block.type === 'audio') {
      var a = document.createElement('audio');
      a.className = 'attach-media';
      a.src = block.url || '';
      a.controls = true;
      wrap.appendChild(a);
    } else if (block.type === 'document') {
      var doc = document.createElement('div');
      doc.className = 'attach-doc';
      doc.textContent = block.name || '附件';
      doc.addEventListener('click', function () {
        if (block.url) native('openLink', block.url);
      });
      wrap.appendChild(doc);
    }
    return wrap;
  }

  // 原生标题栏 / 输入栏遮住的区域，页面据此留白
  function measureToolbar() {
    try {
      var bar = document.getElementById('toolbar');
      if (!bar) return;
      var h = bar.getBoundingClientRect().height || 0;
      if (h > 0) document.documentElement.style.setProperty('--toolbar-h', h + 'px');
    } catch (e) { /* 忽略 */ }
  }

  function applySafeArea(area) {
    if (!area) return;
    var root = document.documentElement;
    if (area.top != null) root.style.setProperty('--safe-top', area.top + 'px');
    if (area.bottom != null) root.style.setProperty('--safe-bottom', area.bottom + 'px');
    measureToolbar();
  }

  function applyAppearance(a) {
    if (!a) return;
    var root = document.documentElement;
    var set = function (key, value) {
      if (value !== undefined && value !== null && value !== '') root.style.setProperty(key, value);
    };
    var alpha = a.bubbleOpacity == null ? 0.9 : a.bubbleOpacity;
    set('--bubble-radius', (a.bubbleRadius == null ? 16 : a.bubbleRadius) + 'px');
    set('--font-scale', a.fontSizeRatio == null ? 1 : a.fontSizeRatio);
    if (a.userBubbleColor) set('--bubble-user', withAlpha(a.userBubbleColor, alpha));
    if (a.assistantBubbleColor) set('--bubble-ai', withAlpha(a.assistantBubbleColor, alpha));
    if (a.thinkingBubbleColor) set('--bubble-think', a.thinkingBubbleColor);
    if (a.chatBackgroundColor) set('--chat-bg', a.chatBackgroundColor);
    if (a.textColor) set('--text-color', a.textColor);
    if (a.borderColor) set('--bubble-border-color', a.borderColor);
    if (a.borderWidth) set('--bubble-border-width', a.borderWidth + 'px');
    if (a.shadowColor) set('--bubble-shadow', '0 1px 4px ' + a.shadowColor);
    if (a.quoteColor) set('--quote-color', a.quoteColor);
    if (a.italicsColor) set('--italics-color', a.italicsColor);
    if (a.bracketColor) set('--bracket-color', a.bracketColor);
    if (a.userBubbleImage) set('--bubble-user-image', 'url("' + a.userBubbleImage + '")');
    if (a.assistantBubbleImage) set('--bubble-ai-image', 'url("' + a.assistantBubbleImage + '")');
    if (a.chatBackgroundImage) set('--chat-bg-image', 'url("' + a.chatBackgroundImage + '")');
    set('--bubble-image-size', a.imageSize === 'cover' ? 'cover' : 'contain');
    document.body.classList.toggle('hide-ai-bubble', a.showAssistantBubble === false);
    document.body.classList.toggle('bubble-image-overlay', a.imageOverlay === true);
    document.body.classList.toggle('solid-text-color', a.textColorEnabled === true && !!a.textColor);
    if (a.fontFamily) document.body.dataset.font = String(a.fontFamily).toLowerCase();
  }

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
      // 卡片写变量：落到对应作用域，并请原生持久化
      try {
        var info = JSON.parse(payload) || {};
        var scope = info.type || 'local';
        var vars = info.vars || {};
        if (scope === 'local') variables = vars;
        else if (scope === 'global') globalVariables = vars;
        else if (scope === 'message') messageVariables = vars;
        saveVariables();
        if (window.rikkaNative && window.rikkaNative.request) {
          window.rikkaNative.request('saveVariables', '', JSON.stringify({
            local: variables, global: globalVariables, message: messageVariables
          }));
        }
      } catch (e) { /* 忽略坏数据 */ }
    }
    else if (data.__rikka === 'macros') {
      // 卡片请求宏展开的回执：交给页面转成一次原生展开请求
      try {
        var m = JSON.parse(payload) || {};
        if (window.rikkaNative && window.rikkaNative.request && m.from) {
          window.rikkaNative.request('substituteMacros', '', String(m.from));
        }
      } catch (e) { /* 忽略 */ }
    }
    else if (data.__rikka === '__call') {
      try {
        var c = JSON.parse(payload) || {};
        if (window.rikkaNative && window.rikkaNative.request) {
          window.rikkaNative.request(String(c.method || ''), String(c.id || ''), String(c.payload == null ? '' : c.payload));
        }
      } catch (e) { /* 忽略 */ }
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
