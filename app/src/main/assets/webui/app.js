/**
 * 聊天 WebView 宿主页面（webui 模式）。
 *
 * 一个 WebView 承载整个消息区与底部工具条：普通消息渲染成 MD3 气泡，前端卡（HTML 文档）
 * 交给同页 iframe，避免「一条消息一个 WebView」。顶部标题栏、输入框仍由原生 Compose 提供。
 *
 * 原生 -> 页面（window.webui.*）：
 *   setTheme(theme) / setState({messages, theme, macros, placeholder}) /
 *   appendStream(msg) / appendMessage(msg) / setToolbar(bar) / scrollToBottom()
 * 页面 -> 原生（window.rikkaNative.*）：
 *   send / stop / regenerate / openLink / slash / requestMenu / insertSymbol
 *
 * 卡片按酒馆习惯注入一层薄桥（TavernHelper / SillyTavern），卡内 postMessage({__rikka}) 由外层转发。
 */
(function () {
  'use strict';

  // 内置兜底补丁（最小集）；正式补丁是 compat.js，仅在它没就位时用这份顶上
  var RUNTIME_COMPAT = [
    '(function(){',
    '  var W = window;',
    '  if (typeof W.requestIdleCallback !== "function") {',
    '    W.requestIdleCallback = function(cb, opts){',
    '      var start = Date.now();',
    '      var timeout = opts && opts.timeout > 0 ? opts.timeout : 0;',
    '      return setTimeout(function(){',
    '        cb({ didTimeout: !!timeout && (Date.now() - start) >= timeout,',
    '             timeRemaining: function(){ return Math.max(0, 50 - (Date.now() - start)); } });',
    '      }, 1);',
    '    };',
    '  }',
    '  if (typeof W.cancelIdleCallback !== "function") W.cancelIdleCallback = function(id){ clearTimeout(id); };',
    '  var at = function(i){ var n = Number(i) || 0; if (n < 0) n += this.length; return this[n]; };',
    '  if (typeof Array.prototype.at !== "function") Object.defineProperty(Array.prototype, "at", { value: at, configurable: true, writable: true });',
    '  if (typeof String.prototype.at !== "function") Object.defineProperty(String.prototype, "at", { value: at, configurable: true, writable: true });',
    '  if (typeof Array.prototype.findLast !== "function") Array.prototype.findLast = function(fn, t){ for (var i = this.length - 1; i >= 0; i--) { if (fn.call(t, this[i], i, this)) return this[i]; } };',
    '  if (typeof Array.prototype.findLastIndex !== "function") Array.prototype.findLastIndex = function(fn, t){ for (var i = this.length - 1; i >= 0; i--) { if (fn.call(t, this[i], i, this)) return i; } return -1; };',
    '  if (typeof Array.prototype.toSorted !== "function") Array.prototype.toSorted = function(cmp){ return Array.prototype.slice.call(this).sort(cmp); };',
    '  if (typeof Array.prototype.toReversed !== "function") Array.prototype.toReversed = function(){ return Array.prototype.slice.call(this).reverse(); };',
    '  if (typeof Array.prototype.with !== "function") Array.prototype.with = function(i, v){ var a = Array.prototype.slice.call(this); var n = Number(i) || 0; if (n < 0) n += a.length; a[n] = v; return a; };',
    '  if (typeof Object.hasOwn !== "function") Object.hasOwn = function(o, k){ return Object.prototype.hasOwnProperty.call(Object(o), k); };',
    '  if (typeof String.prototype.replaceAll !== "function") String.prototype.replaceAll = function(find, rep){',
    '    var s = String(this);',
    '    if (find instanceof RegExp) return s.replace(new RegExp(find.source, find.flags.indexOf("g") >= 0 ? find.flags : find.flags + "g"), rep);',
    '    return s.split(String(find)).join(String(rep));',
    '  };',
    '  var bridge = function(){',
    '    try { if (window.rikkaNative && window.rikkaNative.clipboard) return window.rikkaNative; } catch(e){}',
    '    try { if (window.parent && window.parent !== window && window.parent.rikkaNative && window.parent.rikkaNative.clipboard) return window.parent.rikkaNative; } catch(e){}',
    '    return null;',
    '  };',
    '  var writeText = function(text){',
    '    var b = bridge();',
    '    if (b) { try { b.clipboard(String(text == null ? "" : text)); return Promise.resolve(); } catch(e){} }',
    '    return Promise.reject(new Error("clipboard unavailable"));',
    '  };',
    '  try {',
    '    if (!navigator.clipboard) Object.defineProperty(navigator, "clipboard", { value: {}, configurable: true });',
    '    navigator.clipboard.writeText = writeText;',
    '  } catch (e) {}',
    '  if (!W.rikkaWriteClipboard) W.rikkaWriteClipboard = writeText;',
    '})();'
  ].join('\n');

  // 把脚本源码同步注入当前文档（页面与卡片是两个独立 JS 环境，各跑一次）
  function injectScript(source) {
    try {
      var el = document.createElement('script');
      el.textContent = source;
      (document.head || document.documentElement).appendChild(el);
      if (el.parentNode) el.parentNode.removeChild(el);
      return true;
    } catch (e) { return false; }
  }

  // 正式补丁走 index.html 的 compat.js；这里只兜底，setLibs 到了再补完整版
  if (!window.RikkaCompat) injectScript(RUNTIME_COMPAT);

  var list = document.getElementById('list');
  var toolbar = document.getElementById('toolbar');

  var state = { messages: [], placeholder: '', macros: {} };
  state.libs = {};
  // 外壳版本：setLibs 或 cardStyle 变化时自增，用来把已装配的卡片重装一次
  var shellVersion = 0;
  // 工具条状态：默认值保证「原生还没下发 setToolbar」时页面依然可用
  var bar = {
    modelName: '',
    searchEnabled: false, searchVisible: true,
    reasoningEnabled: false, reasoningVisible: false,
    symbolVisible: true, bracketSymbol: '',
    moreVisible: true
  };
  var ui = {
    model: document.getElementById('tb-model'),
    modelLabel: document.getElementById('tb-model-label'),
    search: document.getElementById('tb-search'),
    reasoning: document.getElementById('tb-reasoning'),
    symbol: document.getElementById('tb-symbol'),
    more: document.getElementById('tb-more')
  };
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

  function initials(name) {
    var n = String(name == null ? '' : name).trim();
    return n ? n.slice(0, 2) : '?';
  }

  // 来自 Kotlin 的地址也要过一遍协议白名单：只允许 http/https/mailto/tel/file/data:image 与相对路径
  function safeUrl(url) {
    var u = String(url == null ? '' : url).trim();
    if (/^(https?:|mailto:|tel:|file:|data:image\/)/i.test(u)) return u;
    if (/^[.#/]/.test(u)) return u;
    return '';
  }

  // —— 变量表：酒馆 getvar/setvar 语义，落在 localStorage 上跨消息保持 ——
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

  // 酒馆内置宏：静态值、变量、掷骰、随机、注释
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
    out = out.replace(/\{\{\s*(?:getvar|getglobalvar)\s*::\s*([^}]+?)\s*\}\}/gi, function (full, name) {
      var key = name.trim();
      return Object.prototype.hasOwnProperty.call(variables, key) ? String(variables[key]) : '';
    });
    out = out.replace(/\{\{\s*roll\s*::\s*([^}]+?)\s*\}\}/gi, function (full, expr) {
      return String(rollDice(expr));
    });
    out = out.replace(/\{\{\s*(?:random|pick)\s*::\s*([^}]+?)\s*\}\}/gi, function (full, items) {
      var parts = String(items).split(',').map(function (s) { return s.trim(); }).filter(Boolean);
      return parts.length ? parts[Math.floor(Math.random() * parts.length)] : '';
    });
    out = out.replace(/\{\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\}\}/g, function (full, key) {
      return Object.prototype.hasOwnProperty.call(statics, key) ? statics[key] : full;
    });
    return out;
  }

  // setvar 要在渲染前生效，所以先单独跑一遍写操作
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

  // 卡片外壳默认排版：未知容器标签默认行内（酒馆卡的 <mainbody>/<phone> 会挤成一行），这里兜块级
  var CARD_SHELL_CSS =
    'html,body{margin:0;padding:0;background:transparent;color:inherit;' +
    'font-family:-apple-system,"Noto Sans CJK SC",Roboto,sans-serif;word-break:break-word;}' +
    'img,video,canvas{max-width:100%;height:auto;}' +
    'body{overflow:hidden;}' +
    'mainbody,body>div,body>section,body>article,body>main{display:block;box-sizing:border-box;max-width:100%;}' +
    'mainbody>*{margin:0 0 .5em;}' +
    'phone,wechat,weibo,twitter,status,statusbar,topbar,screen,bilibili,douyin,adv,ad,' +
    'chat,message,dialog,dialogue,bubble,card,panel,content,container,item,row,col,column,' +
    'header,footer,title{display:block;box-sizing:border-box;max-width:100%;}';

  // CSS 选择不到「未知标签」，只能渲染后遍历：容器上下文里仍是 inline 的未知标签升为块级
  var CARD_SHELL_NORMALIZE = [
    '(function(){',
    '  var KNOWN = {};',
    '  ("a abbr address area article aside audio b base bdi bdo blockquote body br button canvas caption cite code col colgroup data datalist dd del details dfn dialog div dl dt em embed fieldset figcaption figure footer form h1 h2 h3 h4 h5 h6 head header hgroup hr html i iframe img input ins kbd label legend li link main map mark menu meta meter nav noscript object ol optgroup option output p picture pre progress q rp rt ruby s samp script search section select slot small source span strong style sub summary sup table tbody td template textarea tfoot th thead time title tr track u ul var video wbr svg path circle rect g line polygon polyline ellipse defs use text tspan mask clippath lineargradient radialgradient stop filter pattern image symbol marker foreignobject").split(" ").forEach(function(t){ KNOWN[t.toUpperCase()] = 1; });',
    '  function normalize(){',
    '    if (!document.body) return;',
    '    var els = document.body.getElementsByTagName("*");',
    '    for (var i = 0; i < els.length; i++) {',
    '      var el = els[i];',
    '      if (el.namespaceURI !== "http://www.w3.org/1999/xhtml") continue;',
    '      var tag = String(el.tagName || "").toUpperCase();',
    '      if (KNOWN[tag]) continue;',
    '      var parent = el.parentNode;',
    '      var parentTag = parent ? String(parent.tagName || "").toUpperCase() : "";',
    '      if (parent !== document.body && KNOWN[parentTag]) continue;',
    '      try { if (getComputedStyle(el).display === "inline") el.style.display = "block"; } catch (e) {}',
    '    }',
    '  }',
    '  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", normalize);',
    '  else normalize();',
    '  window.addEventListener("load", normalize);',
    '  setTimeout(normalize, 300); setTimeout(normalize, 1200);',
    '  window.__rikkaNormalize = normalize;',
    '})();'
  ].join('\n');

  // 前端卡：做成内联 iframe，宏与桥在其中一并注入
  // 前端卡：作为网页嵌在对话流里，卡片原文整段放进 body；不加 sandbox（同源才能写回高度）
  // 把脚本源码包成 srcdoc 里安全的内联 <script>：源码里的 `</script` 会提前收尾，先打断
  function inlineScript(source) {
    return '<script>' + String(source == null ? '' : source).replace(/<\/(script)/gi, '<\\/$1') + '<\/script>';
  }

  // 拆分卡片文档：head 只保留 style/link/script，title 与 meta 丢弃
  function splitCardDoc(html) {
    try {
      var doc = new DOMParser().parseFromString(String(html), 'text/html');
      var kept = [];
      Array.prototype.forEach.call(doc.head ? doc.head.querySelectorAll('style,link,script') : [], function (el) {
        kept.push(el.outerHTML);
      });
      return { head: kept.join('\n'), body: doc.body ? doc.body.innerHTML : String(html) };
    } catch (e) {
      return { head: '', body: String(html) };
    }
  }

  function cardFrame(key, html, ctx) {
    ctx = ctx || {};
    var body = expandMacros(applySetVars(html));
    var libs = state.libs || {};
    // 卡片才真正需要补丁：优先原生下发的 compat.js，未下发时退回内置最小集
    var compat = libs.compat || RUNTIME_COMPAT;
    var shellHead = '<!DOCTYPE html><html><head><meta charset="utf-8">' +
      '<meta name="viewport" content="width=device-width, initial-scale=1.0">' +
      '<style>' + CARD_SHELL_CSS +
      ':root{--TH-viewport-height:' + Math.max(320, window.innerHeight || 640) + 'px;}' +
      '</style>' +
      // 卡片自带样式必须独立成 <style>：嵌在外层 <style> 里只是纯文本，规则与 :root 变量全废
      (state.cardStyle ? '<style>' + state.cardStyle + '</style>' : '') +
      // 运行环境排在最前面：卡片的 jQuery/lodash 与卡片脚本都可能在首帧用到这些 API
      inlineScript(compat) +
      (libs.jquery ? inlineScript(libs.jquery) : '') +
      (libs.toastr ? inlineScript(libs.toastr) : '') +
      inlineScript(LODASH_LITE) +
      buildCardBridge(ctx.messageId, ctx.cardIndex, ctx.charName, ctx.userName, ctx.avatar) +
      inlineScript(CARD_SHELL_NORMALIZE) +
      inlineScript(
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
        '})();'
      ) +
      parts.head +
      '</head><body>';
    var parts = splitCardDoc(body);
    var frame = document.createElement('iframe');
    frame.dataset.cardKey = key;
    frame.setAttribute('frameborder', '0');
    frame.setAttribute('loading', 'lazy');
    frame.className = 'card-frame';
    frame.srcdoc = shellHead + parts.body + '</body></html>';
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

  // —— 消息正文：把一条消息摊平成有序的块序列，再与现有 DOM 逐位对齐 ——

  // 块序列：reasoning（若有）在前，其后按 blocks 顺序展开 text/card/tool
  function blocksOf(msg) {
    var desc = [];
    if (msg.reasoning) desc.push({ kind: 'reasoning', text: String(msg.reasoning) });
    var blocks = msg.blocks || [];
    if (!blocks.length && String(msg.text || '').trim()) blocks = [{ type: 'text', text: msg.text }];
    blocks.forEach(function (block) {
      if (!block) return;
      if (block.type === 'card') {
        desc.push({
          kind: 'card',
          key: String(msg.id) + ':' + (block.index || 0),
          html: block.html == null ? '' : String(block.html),
          ctx: {
            messageId: msg.id,
            cardIndex: block.index || 0,
            charName: (state.macros || {}).char,
            userName: (state.macros || {}).user
          }
        });
      } else if (block.type === 'image' || block.type === 'video' || block.type === 'audio' || block.type === 'document') {
        // 附件也是有序块：必须交给 patchBody 定位复用，直接塞 body 会重复叠加
        desc.push({
          kind: 'attach',
          key: [block.type, block.url || '', block.name || ''].join('|'),
          block: block
        });
      } else if (block.type === 'tool') {
        desc.push({ kind: 'tool', text: block.text == null ? '' : String(block.text) });
      } else if (block.type === 'text' && String(block.text || '').trim()) {
        desc.push({ kind: 'text', text: String(block.text) });
      }
    });
    return desc;
  }

  function makeBlockNode(desc, streaming) {
    if (desc.kind === 'attach') {
      var attach = attachmentNode(desc.block);
      attach.dataset.kind = 'attach';
      attach.dataset.attachKey = desc.key;
      return attach;
    }
    var node = document.createElement('div');
    node.dataset.kind = desc.kind;
    if (desc.kind === 'reasoning') {
      node.className = 'blk reasoning';
      node.textContent = desc.text;
    } else if (desc.kind === 'card') {
      node.className = 'blk card';
      node.dataset.cardKey = desc.key;
      // 流式帧只铺文本与光标，不装配卡片（对齐 TauriTavern 的 transient 渲染），落定才建 iframe
      if (!streaming) {
        node.appendChild(cardFrame(desc.key, desc.html, desc.ctx));
        node._html = desc.html;
        node._desc = desc;
        node._shellVersion = shellVersion;
      }
    } else if (desc.kind === 'tool') {
      node.className = 'blk tool';
      node.textContent = desc.text;
    } else {
      node.className = 'blk bubble';
      node.innerHTML = window.RikkaMarkdown.render(expandMacros(desc.text));
      node._text = desc.text;
      if (streaming) node.classList.add('typing');
    }
    return node;
  }

  // 就地更新块；返回真正留在 DOM 里的节点（卡片换了内容时会换成新节点）
  function updateBlockNode(node, desc, streaming) {
    if (desc.kind === 'card') {
      // 流式期间绝不重建 iframe（卡内脚本状态会全丢）；落定且内容/外壳版本变了才换新
      if (!streaming && (node._html !== desc.html || node._shellVersion !== shellVersion)) {
        var fresh = makeBlockNode(desc, false);
        node.parentNode.replaceChild(fresh, node);
        return fresh;
      }
      return node;
    }
    // 附件内容由 block 决定，key 变了才会在 patchBody 里换新节点
    if (desc.kind === 'attach') return node;
    if (desc.kind === 'text') {
      // 文本变了才重设 innerHTML，避免每帧重排导致滚动跳动
      if (node._text !== desc.text) {
        node._text = desc.text;
        node.innerHTML = window.RikkaMarkdown.render(expandMacros(desc.text));
      }
      node.classList.toggle('typing', !!streaming);
      return node;
    }
    if (node.textContent !== desc.text) node.textContent = desc.text;
    return node;
  }

  // 把块序列铺进 body：同 kind（卡片还要求 key 相同）的节点原地复用
  function patchBody(body, msg, streaming) {
    var want = blocksOf(msg);
    var children = [];
    for (var c = 0; c < body.children.length; c++) {
      if (body.children[c].classList.contains('blk')) children.push(body.children[c]);
    }
    for (var i = 0; i < want.length; i++) {
      var d = want[i];
      var cur = children[i];
      var reusable = !!cur && cur.dataset.kind === d.kind &&
        (d.kind !== 'card' || cur.dataset.cardKey === d.key) &&
        (d.kind !== 'attach' || cur.dataset.attachKey === d.key);
      if (reusable) {
        children[i] = updateBlockNode(cur, d, streaming);
      } else {
        var node = makeBlockNode(d, streaming);
        if (cur) body.replaceChild(node, cur); else body.appendChild(node);
        children[i] = node;
      }
    }
    for (var j = children.length - 1; j >= want.length; j--) {
      if (children[j].parentNode === body) body.removeChild(children[j]);
    }
  }

  // 运行库或 cardStyle 变化后重装已装配的卡片：首帧 setState 早于 setLibs，不重装会一直缺 jQuery/compat
  function rebuildCards() {
    var nodes = list.querySelectorAll('.blk.card');
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (!node._desc || node._html == null || !node.parentNode) continue;
      node.parentNode.replaceChild(makeBlockNode(node._desc, false), node);
    }
  }

  function avatarNode(msg) {
    var avatar = document.createElement('div');
    avatar.className = 'avatar';
    var url = safeUrl(msg.avatar);
    if (url) {
      var img = document.createElement('img');
      img.alt = '';
      img.src = url;
      // 图片挂了就退回名字首字，保持行高稳定
      img.addEventListener('error', function () {
        if (img.parentNode === avatar) avatar.removeChild(img);
        avatar.textContent = initials(msg.name);
      });
      avatar.appendChild(img);
    } else {
      avatar.textContent = initials(msg.name);
    }
    return avatar;
  }

  function signatureOf(msg) {
    return String(msg.signature == null ? '' : msg.signature);
  }

  // 多分支消息的切换条：‹ i/n ›，点击回请原生 selectSwipe
  function syncSwipes(col, msg) {
    if (!col) return;
    var bar = null;
    for (var i = 0; i < col.children.length; i++) {
      if (col.children[i].classList.contains('swipes')) { bar = col.children[i]; break; }
    }
    var count = Number(msg.swipeCount) || 0;
    var nodeId = msg.nodeId == null ? '' : String(msg.nodeId);
    if (count < 2 || !nodeId) {
      if (bar) col.removeChild(bar);
      return;
    }
    if (!bar) {
      bar = document.createElement('div');
      bar.className = 'swipes';
      bar.innerHTML = '<button type="button" class="swipe-btn" data-step="-1" aria-label="上一条分支">‹</button>' +
        '<span class="swipe-label"></span>' +
        '<button type="button" class="swipe-btn" data-step="1" aria-label="下一条分支">›</button>';
      bar.addEventListener('click', function (event) {
        event.stopPropagation();
        var btn = event.target && event.target.closest ? event.target.closest('.swipe-btn') : null;
        if (!btn || btn.disabled) return;
        var total = Number(bar.dataset.count) || 0;
        var next = (Number(bar.dataset.index) || 0) + (Number(btn.dataset.step) || 0);
        if (next < 0 || next >= total) return;
        requestNative('selectSwipe', JSON.stringify({ nodeId: bar.dataset.nodeId || '', index: next }));
      });
      col.appendChild(bar);
    }
    // 状态放在 dataset 上，点击时现读，避免闭包拿到旧下标
    var index = Math.max(0, Math.min(count - 1, Number(msg.swipeIndex) || 0));
    bar.dataset.count = String(count);
    bar.dataset.index = String(index);
    bar.dataset.nodeId = nodeId;
    var label = bar.querySelector('.swipe-label');
    if (label) label.textContent = (index + 1) + '/' + count;
    var prev = bar.querySelector('[data-step="-1"]');
    var next2 = bar.querySelector('[data-step="1"]');
    if (prev) prev.disabled = index <= 0;
    if (next2) next2.disabled = index >= count - 1;
  }

  function renderRow(msg, streaming) {
    var row = document.createElement('div');
    row.className = 'row ' + (msg.role === 'user' ? 'user' : 'ai');
    row.dataset.id = String(msg.id);
    row.dataset.signature = signatureOf(msg);
    row.dataset.streaming = streaming ? '1' : '';
    row.addEventListener('click', function (event) {
      if (event.target.closest && event.target.closest('a, .attach-doc, iframe, audio, video, .swipes, .bubble a')) return;
      native('messageAction', JSON.stringify({ id: String(msg.id), role: msg.role }));
    });
    row.appendChild(avatarNode(msg));

    var col = document.createElement('div');
    col.className = 'col';

    var name = document.createElement('div');
    name.className = 'name';
    name.textContent = msg.name == null ? '' : String(msg.name);
    name.hidden = !name.textContent;
    col.appendChild(name);

    var body = document.createElement('div');
    body.className = 'body';
    patchBody(body, msg, !!streaming);
    col.appendChild(body);

    var time = document.createElement('div');
    time.className = 'time';
    time.textContent = msg.time == null ? '' : String(msg.time);
    time.hidden = !time.textContent;
    col.appendChild(time);
    syncSwipes(col, msg);

    row.appendChild(col);
    return row;
  }

  // 就地更新一行（只碰正文与时间，头像不重建）
  function updateRow(row, msg, streaming) {
    var body = row.querySelector('.body');
    if (body) patchBody(body, msg, streaming);
    var name = row.querySelector('.name');
    if (name) {
      name.textContent = msg.name == null ? '' : String(msg.name);
      name.hidden = !name.textContent;
    }
    var time = row.querySelector('.time');
    if (time) {
      time.textContent = msg.time == null ? '' : String(msg.time);
      time.hidden = !time.textContent;
    }
    row.dataset.signature = signatureOf(msg);
    row.dataset.streaming = streaming ? '1' : '';
    syncSwipes(row.querySelector('.col'), msg);
  }

  function rowById(id) {
    var key = String(id);
    for (var i = 0; i < list.children.length; i++) {
      if (list.children[i].dataset && list.children[i].dataset.id === key) return list.children[i];
    }
    return null;
  }

  function showEmpty() {
    var empty = document.createElement('div');
    empty.className = 'empty';
    empty.textContent = state.placeholder || '';
    list.appendChild(empty);
  }

  function removeEmpty() {
    var empty = list.querySelector('.empty');
    if (empty) list.removeChild(empty);
  }

  // 全量重绘按消息 id 复用行：老实现的 list.innerHTML = '' 会把所有卡片 iframe 拆掉重建
  function renderAll() {
    if (!state.messages.length) {
      list.innerHTML = '';
      showEmpty();
      return;
    }
    removeEmpty();
    var existing = {};
    for (var c = 0; c < list.children.length; c++) {
      var child = list.children[c];
      if (!child.dataset || child.dataset.id === undefined) continue;
      (existing[child.dataset.id] = existing[child.dataset.id] || []).push(child);
    }
    var kept = [];
    var rows = state.messages.map(function (msg) {
      var id = String(msg.id);
      var row = existing[id] && existing[id].shift(); // 重复 id 的脏行留给下面统一清理
      if (row) updateRow(row, msg, !!msg.streaming);
      else row = renderRow(msg, !!msg.streaming);
      kept.push(row);
      return row;
    });
    for (var j = list.children.length - 1; j >= 0; j--) {
      var old = list.children[j];
      if (!old.dataset || old.dataset.id === undefined) continue;
      if (kept.indexOf(old) < 0) list.removeChild(old);
    }
    // 按新顺序重排：appendChild 对已在树上的节点是「移动」而不是重建
    rows.forEach(function (row) { list.appendChild(row); });
    scrollToBottom(false);
  }

  function nearBottom() {
    return (window.innerHeight + window.scrollY) >= (document.body.scrollHeight - 120);
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
      var value = theme[key];
      if (value == null) return;
      document.documentElement.style.setProperty('--md-' + key, String(value));
    });
  }

  // —— 底部工具条 ——
  function setPressed(node, on) {
    node.classList.toggle('on', !!on);
    node.setAttribute('aria-pressed', on ? 'true' : 'false');
  }

  function syncToolbarHeight() {
    if (!toolbar) return;
    var h = Math.round(toolbar.getBoundingClientRect().height);
    if (h > 0) document.documentElement.style.setProperty('--toolbar-h', h + 'px');
  }

  function applyToolbar() {
    if (!ui.model) return;
    ui.modelLabel.textContent = bar.modelName || '';
    ui.modelLabel.hidden = !bar.modelName;
    ui.model.setAttribute('aria-label', bar.modelName ? '选择模型：' + bar.modelName : '选择模型');

    // 可见性由 Kotlin 的 *Visible 决定；*Enabled 只影响选中态配色
    ui.search.hidden = !bar.searchVisible;
    setPressed(ui.search, bar.searchEnabled);
    ui.reasoning.hidden = !bar.reasoningVisible;
    setPressed(ui.reasoning, bar.reasoningEnabled);
    ui.symbol.hidden = !bar.symbolVisible;
    ui.more.hidden = !bar.moreVisible;

    syncToolbarHeight();
  }

  if (toolbar) {
    // 事件委托：整条工具条只挂一个监听
    toolbar.addEventListener('click', function (event) {
      var node = event.target && event.target.closest ? event.target.closest('.tb') : null;
      if (!node || node.hidden) return;
      var action = node.getAttribute('data-action');
      if (!action) return;
      // 符号按钮直接把 Kotlin 下发的符号交回原生插入输入框；没配符号时退回原生菜单
      if (action === 'symbol') {
        var sym = String(bar.bracketSymbol || '');
        if (sym) native('insertSymbol', sym);
        else native('requestMenu', 'symbol');
        return;
      }
      native('requestMenu', action);
    }, false);
  }

  window.addEventListener('resize', syncToolbarHeight, false);
  window.addEventListener('orientationchange', function () { setTimeout(syncToolbarHeight, 120); }, false);

  // —— 原生侧接口 ——
  window.webui = {
    // 原生下发的卡片运行库（jQuery / toastr / compat，base64 传输避免转义问题）
    setLibs: function (json) {
      var payload = parse(json) || {};
      var changed = false;
      Object.keys(payload).forEach(function (name) {
        try {
          var text = atob(payload[name]);
          if (state.libs[name] !== text) { state.libs[name] = text; changed = true; }
        } catch (e) { /* 解码失败就当没有 */ }
      });
      // compat.js 是页面唯一的完整补丁：index.html 那次加载没成功时，用原生下发的这份补上
      if (!window.RikkaCompat && state.libs.compat) injectScript(state.libs.compat);
      // 运行库变了：首帧装配的卡片是在没有 libs 的状态下建的，按版本重装一次
      if (changed) { shellVersion++; rebuildCards(); }
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

    // 原生回填：桥的 __rikkaResolve 与 compat.js 的 __rikkaNetResolve 两条通道都要喂（含本窗口）
    resolve: function (requestId, value) {
      var targets = [window];
      var frames = document.querySelectorAll('iframe');
      for (var i = 0; i < frames.length; i++) {
        try { if (frames[i].contentWindow) targets.push(frames[i].contentWindow); } catch (e) { /* 跨域忽略 */ }
      }
      targets.forEach(function (win) {
        try { if (typeof win.__rikkaResolve === 'function') win.__rikkaResolve(requestId, value); } catch (e) { /* 忽略 */ }
        try { if (typeof win.__rikkaNetResolve === 'function') win.__rikkaNetResolve(requestId, value); } catch (e) { /* 忽略 */ }
      });
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
    setState: function (json) {
      var payload = parse(json) || {};
      if (payload.theme) applyTheme(payload.theme);
      if (payload.macros) state.macros = payload.macros;
      // 卡片样式变了：外壳版本自增，下面的 renderAll 会把已装配的卡片按新样式重装
      if (typeof payload.cardStyle === 'string' && payload.cardStyle !== state.cardStyle) {
        state.cardStyle = payload.cardStyle;
        shellVersion++;
      }
      if (typeof payload.placeholder === 'string') state.placeholder = payload.placeholder;
      state.messages = payload.messages || [];
      renderAll();
    },
    // 流式增量：只动那条消息，卡片 iframe 原地保留
    appendStream: function (json) {
      var msg = parse(json);
      if (!msg) return;
      var stick = nearBottom();
      var row = rowById(msg.id);
      if (row) updateRow(row, msg, true);
      else { removeEmpty(); list.appendChild(renderRow(msg, true)); }
      if (stick) scrollToBottom(false);
    },
    // 追加或落定单条：signature 未变才跳过，但正在流式的那一行必须收尾（否则 typing 光标不灭）
    appendMessage: function (json) {
      var msg = parse(json);
      if (!msg) return;
      var stick = nearBottom();
      var row = rowById(msg.id);
      if (row) {
        var sig = signatureOf(msg);
        if (sig && row.dataset.signature === sig && row.dataset.streaming !== '1') return;
        updateRow(row, msg, false);
      } else {
        removeEmpty();
        list.appendChild(renderRow(msg, false));
      }
      if (stick) scrollToBottom(true);
    },
    setToolbar: function (json) {
      var payload = parse(json);
      if (!payload) return;
      Object.keys(bar).forEach(function (key) {
        if (payload[key] !== undefined) bar[key] = payload[key];
      });
      applyToolbar();
    },
    scrollToBottom: function () { scrollToBottom(true); },
    getVariables: function () { return JSON.stringify(variables); }
  };

  // —— 页面 -> 原生 ——
  function native(name, payload) {
    try {
      if (window.rikkaNative && typeof window.rikkaNative[name] === 'function') {
        window.rikkaNative[name](payload == null ? '' : String(payload));
      }
    } catch (e) { /* 原生侧未就绪时静默 */ }
  }

  // 无回填的 request 通道：rikkaNative.request(method, requestId, payload)
  function requestNative(method, payload) {
    try {
      if (window.rikkaNative && typeof window.rikkaNative.request === 'function') {
        window.rikkaNative.request(method, '', payload == null ? '' : String(payload));
      }
    } catch (e) { /* 原生侧未就绪时静默 */ }
  }

  // 卡片 iframe 的外发通道：send / slash / vars（变量同步到本地表）
  window.addEventListener('message', function (event) {
    var data = event.data;
    if (!data || !data.__rikka) return;
    var payload = typeof data.payload === 'string' ? data.payload : JSON.stringify(data.payload == null ? '' : data.payload);
    if (data.__rikka === 'send') native('send', payload);
    else if (data.__rikka === 'slash') native('slash', payload);
    else if (data.__rikka === 'vars') {
      try {
        var parsed = JSON.parse(payload);
        if (parsed && typeof parsed === 'object') { variables = parsed; saveVariables(); }
      } catch (e) { /* 忽略坏数据 */ }
    }
  }, false);

  // 页内链接一律交给原生（系统浏览器/内置浏览器），页面自己不做跳转
  document.addEventListener('click', function (event) {
    var link = event.target && event.target.closest ? event.target.closest('a') : null;
    if (link && link.href) {
      event.preventDefault();
      native('openLink', link.href);
    }
  }, true);

  // 首帧先把工具条高度量出来，再按默认状态铺一遍
  applyToolbar();
  window.rikkaSend = function (text) { native('send', text); };
  window.rikkaStop = function () { native('stop', ''); };
  window.rikkaRegenerate = function () { native('regenerate', ''); };
  window.rikkaInsertSymbol = function (text) { native('insertSymbol', text); };
})();
