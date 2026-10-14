/**
 * 聊天 WebView 宿主页面（webui 模式）。
 *
 * 一个 WebView 承载整个消息区：普通消息渲染成 MD3 气泡，前端卡（HTML 文档）交给同页
 * iframe。顶部标题栏与底部输入栏由原生 Compose 提供，页面里不复制工具条。
 *
 * 性能约定（Android WebView 上滚动的生命线）：
 *   - 唯一滚动容器 #scroller，body/html 不滚；
 *   - 所有 DOM 更新经 requestAnimationFrame 合帧，流式一帧只落一次；
 *   - 卡片高度由 iframe 内的 ResizeObserver/MutationObserver 自报，不用轮询定时器。
 *
 * 原生 -> 页面（window.webui.*）：
 *   setTheme / setAppearance / setSafeArea / setLibs / setVariables / setState /
 *   setToolbar / appendStream / appendMessage / emitEvent / resolve / scrollToBottom
 * 页面 -> 原生（window.rikkaNative.*）：
 *   send / stop / regenerate / openLink / slash / requestMenu / insertSymbol /
 *   messageAction / clipboard / request(method, requestId, payload)
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

  var scroller = document.getElementById('scroller');
  var list = document.getElementById('list');
  var jumpBtn = document.getElementById('jump');
  var toolbar = document.getElementById('toolbar');

  var state = {
    messages: [],
    placeholder: '',
    macros: {},
    libs: {},
    cardStyle: '',
    appearance: {}
  };
  var variables = loadVariables();
  var globalVariables = {};
  var messageVariables = {};
  // 外壳版本：setLibs 或 cardStyle 变化时自增，用来把已装配的卡片按新外壳重装一次
  var shellVersion = 0;
  // 正在流式的那条消息 id：typing 光标只认它，且只认助手消息
  var streamId = null;

  // —— 合帧调度：一帧内同一件事只做一次 ——
  var rafId = 0;
  var queue = [];
  var rafFn = window.requestAnimationFrame || function (fn) { return setTimeout(fn, 16); };

  function schedule(fn) {
    if (queue.indexOf(fn) < 0) queue.push(fn);
    if (rafId) return;
    rafId = rafFn(function () {
      rafId = 0;
      var run = queue;
      queue = [];
      for (var i = 0; i < run.length; i++) {
        try { run[i](); } catch (e) { /* 单个任务失败不影响其余 */ }
      }
    });
  }

  function parse(json) {
    if (json == null) return null;
    if (typeof json === 'object') return json;
    try { return JSON.parse(json); } catch (e) { return null; }
  }

  // —— 非 ASCII 标签 ——
  // 卡作者常把说话人写成 <清韵>：HTML 解析器不认这种标签名（必须以 ASCII 字母开头），
  // 整段会被当纯文本，页面上就出现「<清韵>」字面量。渲染前转成可命中的行内元素。
  var NON_ASCII = /[\u0080-\uFFFF]/;
  var CHARA_TAG_RE = /<(\/?)([A-Za-z0-9_:\-]*[\u0080-\uFFFF][A-Za-z0-9_:\-\u0080-\uFFFF]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;
  var SKIP_REGION_RE = /<(style|script|pre|code|textarea)\b[^>]*>[\s\S]*?<\/\1\s*>|<!--[\s\S]*?-->/gi;

  function convertCharaTags(chunk) {
    return chunk.replace(CHARA_TAG_RE, function (full, slash, name) {
      if (slash === '/') return '</span>';
      return '<span class="chara-tag" data-chara="' + name + '">';
    });
  }

  function normalizeCharaTags(html) {
    var text = String(html == null ? '' : html);
    if (!NON_ASCII.test(text)) return text;
    var out = '';
    var last = 0;
    var m;
    SKIP_REGION_RE.lastIndex = 0;
    while ((m = SKIP_REGION_RE.exec(text)) !== null) {
      out += convertCharaTags(text.slice(last, m.index)) + m[0];
      last = m.index + m[0].length;
    }
    return out + convertCharaTags(text.slice(last));
  }

  // 卡片 HTML 里若把标签本身转义了（&lt;span style=…&gt;），浏览器只会把它当文字显示。
  // 只还原白名单行内标签的转义形态，其余转义（代码、想展示的源码）一律保持原样。
  var ESCAPED_INLINE_RE = /&lt;(\/?)(span|font|b|i|u|em|strong|sub|sup|small)\b((?:(?!&gt;)[\s\S])*?)&gt;/gi;

  function decodeEscapedInlineTags(html) {
    var text = String(html == null ? '' : html);
    if (text.indexOf('&lt;') < 0) return text;
    return text.replace(ESCAPED_INLINE_RE, function (full, slash, name, attrs) {
      return '<' + slash + name + String(attrs).replace(/&quot;/g, '"').replace(/&#39;/g, "'") + '>';
    });
  }

  // 卡片 CSS 里的非 ASCII 元素选择器（清韵{…}）同样是死规则，改写成属性选择器；
  // 只在选择器区域替换，声明体（font-family: 微软雅黑 之类）原样保留。
  function rewriteCharaSelector(part) {
    if (!NON_ASCII.test(part)) return part;
    return part.replace(/(^|[\s,>+~(\[])([\u0080-\uFFFF][\w\u0080-\uFFFF-]*)/g, function (full, lead, name) {
      return lead + '[data-chara="' + name + '"]';
    });
  }

  function rewriteCharaCss(css) {
    var text = String(css == null ? '' : css);
    if (!NON_ASCII.test(text) || text.indexOf('{') < 0) return text;
    var out = '';
    var buf = '';
    var depth = 0;
    for (var i = 0; i < text.length; i++) {
      var ch = text[i];
      if (ch === '{') {
        out += rewriteCharaSelector(buf) + '{';
        buf = '';
        depth++;
      } else if (ch === '}') {
        out += buf + '}';
        buf = '';
        if (depth > 0) depth--;
      } else {
        buf += ch;
      }
    }
    return out + buf;
  }

  // —— 主题 / 外观 ——
  var FONT_STACKS = {
    serif: 'Georgia, "Noto Serif CJK SC", "Songti SC", serif',
    monospace: 'ui-monospace, "Noto Sans Mono CJK SC", monospace'
  };

  function withAlpha(hex, alpha) {
    var m = /^#?([0-9a-f]{6})$/i.exec(String(hex || ''));
    if (!m) return hex;
    var n = parseInt(m[1], 16);
    var a = (alpha == null ? 1 : Math.max(0, Math.min(1, alpha)));
    return 'rgba(' + ((n >> 16) & 255) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')';
  }

  function applyTheme(theme) {
    if (!theme) return;
    Object.keys(theme).forEach(function (key) {
      var value = theme[key];
      if (value == null) return;
      document.documentElement.style.setProperty('--md-' + key, String(value));
    });
  }

  function setVar(name, value) {
    if (value === undefined || value === null || value === '') return;
    document.documentElement.style.setProperty(name, value);
  }

  function px(value, fallback) {
    if (value === undefined || value === null || value === '') return fallback;
    return (Number(value) || 0) + 'px';
  }

  // 设置 → CSS：DisplaySetting 里凡影响消息区外观的字段都在这里落地
  function applyAppearance(a) {
    if (!a) return;
    state.appearance = a;
    var body = document.body;
    var alpha = a.bubbleOpacity == null ? 0.9 : a.bubbleOpacity;

    setVar('--bubble-radius', px(a.bubbleRadius, '16px'));
    setVar('--font-scale', a.fontSizeRatio == null ? null : String(a.fontSizeRatio));
    setVar('--bubble-border-width', px(a.borderWidth, '0px'));
    setVar('--bubble-image-size', a.imageSize === 'cover' ? 'cover' : 'contain');
    if (a.primaryColor) setVar('--md-primary', a.primaryColor);
    if (a.userBubbleColor) setVar('--bubble-user', withAlpha(a.userBubbleColor, alpha));
    if (a.assistantBubbleColor) setVar('--bubble-ai', withAlpha(a.assistantBubbleColor, alpha));
    if (a.thinkingBubbleColor) setVar('--md-reasoning-bg', withAlpha(a.thinkingBubbleColor, alpha));
    if (a.chatBackgroundColor) setVar('--chat-bg', a.chatBackgroundColor);
    if (a.textColor) setVar('--text-color', a.textColor);
    if (a.borderColor) setVar('--bubble-border-color', a.borderColor);
    if (a.shadowColor) setVar('--bubble-shadow', '0 ' + (a.shadowWidth || 2) + 'px ' + ((a.shadowWidth || 2) * 2) + 'px ' + a.shadowColor);
    if (a.quoteColor) setVar('--quote-color', a.quoteColor);
    if (a.italicsColor) setVar('--italics-color', a.italicsColor);
    if (a.bracketColor) setVar('--bracket-color', a.bracketColor);
    if (a.underlineColor) setVar('--underline-color', a.underlineColor);
    if (a.quoteBackgroundColor) setVar('--md-quote-bg', a.quoteBackgroundColor);
    if (a.userBubbleImage) setVar('--bubble-user-image', 'url("' + a.userBubbleImage + '")');
    if (a.assistantBubbleImage) setVar('--bubble-ai-image', 'url("' + a.assistantBubbleImage + '")');
    if (a.chatBackgroundImage) setVar('--chat-bg-image', 'url("' + a.chatBackgroundImage + '")');
    if (a.userAvatarFrame) setVar('--avatar-frame-user', 'url("' + a.userAvatarFrame + '")');
    if (a.botAvatarFrame) setVar('--avatar-frame-bot', 'url("' + a.botAvatarFrame + '")');
    if (a.avatarFrameScale) setVar('--avatar-frame-scale', String(a.avatarFrameScale));

    // 主题导入的 Markdown 元素样式（原生渲染路径没消费这些，WebUI 侧落到变量上）
    setVar('--md-inline-code-bg', a.mdInlineCodeBg);
    setVar('--md-inline-code-color', a.mdInlineCodeColor);
    setVar('--md-inline-code-radius', px(a.mdInlineCodeRadius, null));
    setVar('--md-code-bg', a.mdCodeBg);
    setVar('--md-code-color', a.mdCodeColor);
    setVar('--md-code-radius', px(a.mdCodeRadius, null));
    setVar('--md-code-border-color', a.mdCodeBorder);
    setVar('--md-code-border-width', px(a.mdCodeBorderWidth, null));
    setVar('--md-quote-bg', a.mdQuoteBg || undefined);
    setVar('--md-quote-radius', px(a.mdQuoteRadius, null));
    setVar('--md-quote-border', a.mdQuoteBorder);
    setVar('--md-quote-accent', px(a.mdQuoteBorderWidth || a.mdQuoteAccentWidth, null));
    setVar('--md-mark-bg', a.mdMarkBg);
    setVar('--md-mark-color', a.mdMarkColor);
    setVar('--md-reasoning-color', a.mdReasoningColor);
    setVar('--md-reasoning-radius', px(a.mdReasoningRadius, null));

    body.classList.toggle('hide-ai-bubble', a.showAssistantBubble === false);
    body.classList.toggle('bubble-image-overlay', a.imageOverlay === true);
    body.classList.toggle('solid-text-color', a.textColorEnabled === true && !!a.textColor);
    body.classList.toggle('code-wrap', a.codeWrap === true);
    body.classList.toggle('hide-time', a.showTime !== true);
    if (a.fontFamily) body.dataset.font = String(a.fontFamily).toLowerCase();
    applyCustomFont(a);
    if (jumpBtn) {
      jumpBtn.hidden = a.messageJumper === false;
      jumpBtn.classList.toggle('left', a.jumperLeft === true);
    }
    schedule(syncRows);
  }

  // CUSTOM 字体：把本地字体文件挂成 @font-face
  function applyCustomFont(a) {
    var url = a.customFontUrl;
    if (!url) return;
    var face = document.getElementById('rikka-custom-font');
    if (!face) {
      face = document.createElement('style');
      face.id = 'rikka-custom-font';
      document.head.appendChild(face);
    }
    face.textContent = '@font-face{font-family:"RikkaCustom";src:url("' + url + '");}' +
      'body[data-font="custom"]{--chat-font:"RikkaCustom",sans-serif;}';
  }

  // —— 滚动 ——
  // 用户上滑就停止跟随；贴底时新内容才自动滚动。所有写入都在 rAF 里做。
  var stick = true;

  function distanceFromBottom() {
    return scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight;
  }

  function nearBottom() {
    return distanceFromBottom() < 120;
  }

  function scrollToBottomNow(smooth) {
    var top = scroller.scrollHeight;
    if (smooth && scroller.scrollTo) {
      try { scroller.scrollTo({ top: top, behavior: 'smooth' }); return; } catch (e) { /* 老引擎不支持选项 */ }
    }
    scroller.scrollTop = top;
  }

  function followBottom() {
    if (!stick) return;
    schedule(function () { scrollToBottomNow(false); });
  }

  scroller.addEventListener('scroll', function () {
    stick = nearBottom();
    if (jumpBtn && !jumpBtn.hidden) {
      schedule(function () { jumpBtn.classList.toggle('on', !stick); });
    }
  }, { passive: true });

  if (jumpBtn) {
    jumpBtn.addEventListener('click', function () {
      stick = true;
      scrollToBottomNow(true);
    });
  }

  // 卡片内容变化导致高度变化时，贴底的话跟着走（卡片在 iframe 里自报高度）
  window.addEventListener('message', function (event) {
    var data = event.data;
    if (!data || data.__rikkaFrame !== 'height') return;
    followBottom();
  }, false);

  // —— 变量域 ——
  function loadVariables() {
    try {
      var raw = localStorage.getItem('rikka.card.vars.local');
      return raw ? JSON.parse(raw) : {};
    } catch (e) { return {}; }
  }

  function saveVariables() {
    try { localStorage.setItem('rikka.card.vars.local', JSON.stringify(variables)); } catch (e) { /* 忽略 */ }
    requestNative('saveVariables', JSON.stringify(variables));
  }

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

  // 页面侧宏补全：兜底用，权威实现仍在后端引擎
  function expandMacros(text) {
    var macros = state.macros || {};
    return String(text == null ? '' : text).replace(/\{\{\s*([a-zA-Z_][\w.]*)\s*\}\}/g, function (full, key) {
      var value = macros[key];
      return value == null ? full : String(value);
    });
  }

  // —— 卡片外壳 ——
  // 卡片里大量自定义标签（<mainbody>/<phone>）在 HTML 里默认是行内元素，
  // 不兜底就会挤成一行；已登记为容器的按块级排版。
  var CARD_CONTAINER_TAGS = ('mainbody phone wechat weibo twitter status statusbar topbar screen bilibili ' +
    'douyin adv ad chat message dialog dialogue bubble card panel content container item row col column ' +
    'header footer title avatar name text bubble-wrapper').split(' ');

  var CARD_SHELL_CSS =
    'html,body{margin:0;padding:0;height:auto;background:transparent;color:inherit;' +
    'font-family:inherit;font-size:inherit;line-height:inherit;word-break:break-word;overflow:hidden;}' +
    'img,video,canvas{max-width:100%;height:auto;}' +
    'p{margin:.5em 0;}iframe{border:0;}' +
    'mainbody,body>div,body>section,body>article,body>main{display:block;box-sizing:border-box;max-width:100%;}' +
    'mainbody>*{margin:0 0 .5em;}' +
    CARD_CONTAINER_TAGS.map(function (tag) {
      return tag + '{display:block;box-sizing:border-box;max-width:100%;}';
    }).join('');

  // CSS 命中不了「未知标签」，只能在渲染后遍历：容器上下文里还是 inline 的未知标签升为块级
  var CARD_SHELL_NORMALIZE = [
    '(function(){',
    '  var KNOWN = {};',
    '  ("a abbr address area article aside audio b base bdi bdo blockquote body br button canvas caption cite code col colgroup data datalist dd del details dfn dialog div dl dt em embed fieldset figcaption figure footer form h1 h2 h3 h4 h5 h6 head header hgroup hr html i iframe img input ins kbd label legend li link main map mark mark menu meta meter nav noscript object ol optgroup option output p picture pre progress q rp rt ruby s samp script search section select slot small source span strong style sub summary sup table tbody td template textarea tfoot th thead time title tr track u ul var video wbr svg path circle rect g line polygon polyline ellipse defs use text tspan mask clippath lineargradient radialgradient stop filter pattern image symbol marker foreignobject").split(" ").forEach(function(t){ KNOWN[t.toUpperCase()] = 1; });',
    '  var CONTAINER = {};',
    '  ("' + CARD_CONTAINER_TAGS.join(' ') + '").split(" ").forEach(function(t){ CONTAINER[t.toUpperCase()] = 1; });',
    '  function normalize(){',
    '    if (!document.body) return;',
    '    var els = document.body.getElementsByTagName("*");',
    '    for (var i = 0; i < els.length; i++) {',
    '      var el = els[i];',
    '      if (el.namespaceURI !== "http://www.w3.org/1999/xhtml") continue;',
    '      if (el.getAttribute && el.getAttribute("data-chara")) continue;',
    '      var tag = String(el.tagName || "").toUpperCase();',
    '      if (KNOWN[tag]) continue;',
    '      if (CONTAINER[tag]) { el.style.display = "block"; continue; }',
    '      // 余下的未知标签：只在容器上下文里、且计算样式是行内时才升块级',
    '      var parent = el.parentNode;',
    '      var parentTag = parent ? String(parent.tagName || "").toUpperCase() : "";',
    '      if (parent !== document.body && (KNOWN[parentTag] || CONTAINER[parentTag])) continue;',
    '      try { if (getComputedStyle(el).display === "inline") el.style.display = "block"; } catch (e) {}',
    '    }',
    '  }',
    '  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", normalize);',
    '  else normalize();',
    '  window.addEventListener("load", normalize);',
    '  setTimeout(normalize, 200);',
    '  window.__rikkaNormalize = normalize;',
    '})();'
  ].join('\n');

  // 高度自报：只靠观察者驱动，不用轮询（多张卡片的定时器是滚动卡死的主因）
  var CARD_HEIGHT_SCRIPT = [
    '(function(){',
    '  var last = 0;',
    '  var scheduled = false;',
    '  var raf = window.requestAnimationFrame || function(f){ return setTimeout(f, 16); };',
    '  function measure(){',
    '    scheduled = false;',
    '    var d = document.documentElement, b = document.body;',
    '    var h = Math.max(d ? d.scrollHeight : 0, b ? b.scrollHeight : 0, d ? d.offsetHeight : 0, b ? b.offsetHeight : 0);',
    '    if (h <= 0) return;',
    '    if (Math.abs(h - last) > 1) {',
    '      last = h;',
    '      try { if (window.frameElement) window.frameElement.style.height = h + "px"; } catch (e) {}',
    '    }',
    '    try { parent.postMessage({__rikkaFrame: "height", h: h}, "*"); } catch (e) {}',
    '  }',
    '  function schedule(){ if (scheduled) return; scheduled = true; raf(measure); }',
    '  try {',
    '    if (window.ResizeObserver) {',
    '      var ro = new ResizeObserver(schedule);',
    '      ro.observe(document.documentElement);',
    '      if (document.body) ro.observe(document.body);',
    '    }',
    '    if (window.MutationObserver) {',
    '      new MutationObserver(schedule).observe(document.documentElement, {childList: true, subtree: true, attributes: true, characterData: true});',
    '    }',
    '  } catch (e) {}',
    '  document.addEventListener("DOMContentLoaded", schedule);',
    '  window.addEventListener("load", schedule);',
    '  // 有限次兜底：卡片脚本常在若干延时后补齐内容，不做轮询',
    '  [50, 200, 800, 2000].forEach(function(t){ setTimeout(schedule, t); });',
    '  schedule();',
    '})();'
  ].join('\n');

  // 卡片桥：TavernHelper / SillyTavern 那套薄接口
  var CARD_BRIDGE_TEMPLATE = [
    '(function(){',
    '  var MID = __MID__, IDX = __IDX__, CHAR = __CHAR__, USER = __USER__;',
    '  var seq = 0, pending = {};',
    '  var post = function(name, payload){ try { parent.postMessage({__rikka:name, payload:payload}, "*"); } catch(e){} };',
    '  var call = function(method, payload){',
    '    var id = "c" + (++seq) + "_" + Math.random().toString(36).slice(2, 8);',
    '    return new Promise(function(resolve){',
    '      pending[id] = resolve;',
    '      try {',
    '        if (window.parent && window.parent.rikkaNative && window.parent.rikkaNative.request) {',
    '          window.parent.rikkaNative.request(method, id, String(payload == null ? "" : payload));',
    '        } else { resolve(null); }',
    '      } catch (e) { resolve(null); }',
    '      setTimeout(function(){ if (pending[id]) { pending[id] = null; resolve(null); } }, 8000);',
    '    });',
    '  };',
    '  window.__rikkaResolve = function(id, value){ var f = pending[id]; if (f) { pending[id] = null; f(value); } };',
    '  var api = {',
    '    getMessageId: function(){ return MID; },',
    '    getVariables: function(scope){ return call("getVariables", String(scope == null ? "local" : scope)); },',
    '    setVariables: function(vars, scope){ return call("setVariables", JSON.stringify({scope: scope || "local", vars: vars || {}})); },',
    '    sendMessage: function(text){ post("send", String(text == null ? "" : text)); return Promise.resolve(); },',
    '    triggerSlash: function(cmd){ var c = String(cmd == null ? "" : cmd); post("slash", c); return call("triggerSlash", c); },',
    '    substituteMacros: function(text){ return call("substituteMacros", String(text == null ? "" : text)); },',
    '    getChatMessages: function(range){ return call("getChatMessages", String(range == null ? "" : range)); },',
    '    eventOn: function(){ return function(){}; },',
    '    eventOnce: function(){ return function(){}; },',
    '    eventEmit: function(name, payload){ post("event", JSON.stringify({name: name, payload: payload})); },',
    '    charName: CHAR,',
    '    userName: USER,',
    '    messageIndex: IDX',
    '  };',
    '  window.TavernHelper = window.TavernHelper || api;',
    '  window.SillyTavern = window.SillyTavern || api;',
    '  window.triggerSlash = window.triggerSlash || api.triggerSlash;',
    '  window.sendMessage = window.sendMessage || api.sendMessage;',
    '  window.eventOn = window.eventOn || api.eventOn;',
    '  window.getMessageId = window.getMessageId || api.getMessageId;',
    '  window.messageFormat = window.messageFormat || { getMessageId: function(){ return MID; } };',
    '  window.__rikkaEmit = function(name, argsJson){',
    '    try {',
    '      var detail = typeof argsJson === "string" ? JSON.parse(argsJson) : argsJson;',
    '      window.dispatchEvent(new CustomEvent("rikka:" + name, {detail: detail}));',
    '    } catch (e) {}',
    '  };',
    '  window.__rikkaSetVars = function(scope, json){',
    '    try { localStorage.setItem("rikka.card.vars." + scope, json); } catch(e){}',
    '  };',
    '})();'
  ].join('\n');

  var LODASH_LITE = [
    '(function(){',
    '  var _ = window._ || {};',
    '  _.debounce = function(fn, wait){ var t; return function(){ var a = arguments, self = this; clearTimeout(t); t = setTimeout(function(){ fn.apply(self, a); }, wait || 0); }; };',
    '  _.throttle = function(fn, wait){ var last = 0, t; return function(){ var a = arguments, self = this, now = Date.now(); if (now - last >= (wait || 0)) { last = now; fn.apply(self, a); } else { clearTimeout(t); t = setTimeout(function(){ last = Date.now(); fn.apply(self, a); }, wait - (now - last)); } }; };',
    '  window._ = window._ || _;',
    '  window.lodash = window.lodash || _;',
    '})();'
  ].join('\n');

  function buildCardBridge(messageId, cardIndex, charName, userName) {
    return CARD_BRIDGE_TEMPLATE
      .replace('__MID__', JSON.stringify(String(messageId == null ? '' : messageId)))
      .replace('__IDX__', JSON.stringify(Number(cardIndex) || 0))
      .replace('__CHAR__', JSON.stringify(String(charName == null ? '' : charName)))
      .replace('__USER__', JSON.stringify(String(userName == null ? '' : userName)));
  }

  // 脚本源码包成 srcdoc 里可用的内联 <script>：源码里的 `</script` 会提前收尾，先打断
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

  /**
   * 装配一张卡片的独立文档。
   *
   * 样式来源三层，缺一不可：
   *   1. 卡片文档自带的 <style>（first_mes 这类完整 HTML 文档卡的样式在这里）；
   *   2. 助手级 cardStyle —— 备选开场白自己不带 style，外观全靠它；
   *   3. 外壳兜底排版，保证未知标签不会挤成一行。
   */
  /**
   * 卡片文档的样式来源三层：卡片自带、助手级 cardStyle、外壳兜底。
   *
   * 原生侧已把卡片的 <html>/<head> 外壳与 ``` 围栏拆掉（body 只留正文、样式单列在 style），
   * 这里直接用；收到整份文档（旧数据）时才退回自己拆。
   */
  function cardParts(desc) {
    var html = String(desc.html == null ? '' : desc.html);
    if (desc.style != null || desc.head != null) {
      return { head: String(desc.head || ''), body: html, style: String(desc.style || ''), prepared: true };
    }
    var parts = splitCardDoc(html);
    return { head: parts.head, body: parts.body, style: '', prepared: false };
  }

  function cardDocHtml(desc, ctx) {
    var parts = cardParts(desc);
    var body = expandMacros(decodeEscapedInlineTags(normalizeCharaTags(parts.body)));
    var libs = state.libs || {};
    var compat = libs.compat || RUNTIME_COMPAT;
    var cardCss = rewriteCharaCss(state.cardStyle);
    // 拆好的那份是纯 CSS，要自己包 <style>；兜底那份已经是 <style> 标签的 outerHTML
    var ownHtml = parts.prepared
      ? (function () {
        var css = rewriteCharaCss(parts.style);
        return css ? '<style data-rikka="card-own">' + css + '</style>' : '';
      })()
      : rewriteCharaCss(parts.head);
    return '<!DOCTYPE html><html><head><meta charset="utf-8">' +
      '<meta name="viewport" content="width=device-width, initial-scale=1.0">' +
      '<style>' + CARD_SHELL_CSS +
      ':root{--TH-viewport-height:' + Math.max(320, window.innerHeight || 640) + 'px;}' +
      '</style>' +
      // 卡片样式必须各自独立成 <style>：嵌在外层 <style> 里只是纯文本，规则与变量全废
      (cardCss ? '<style data-rikka="card-style">' + cardCss + '</style>' : '') +
      // 运行环境排在最前：卡片的 jQuery/lodash 与卡片脚本都可能在首帧用到这些 API
      inlineScript(compat) +
      (libs.jquery ? inlineScript(libs.jquery) : '') +
      (libs.toastr ? inlineScript(libs.toastr) : '') +
      inlineScript(LODASH_LITE) +
      buildCardBridge(ctx.messageId, ctx.cardIndex, ctx.charName, ctx.userName) +
      inlineScript(CARD_SHELL_NORMALIZE) +
      inlineScript(CARD_HEIGHT_SCRIPT) +
      ownHtml +
      (parts.prepared ? parts.head : '') +
      '</head><body class="rikka-card-body">' + body + '</body></html>';
  }

  function cardFrame(key, desc) {
    var frame = document.createElement('iframe');
    frame.className = 'card-frame';
    frame.dataset.cardKey = key;
    frame.setAttribute('frameborder', '0');
    // 起始高度给一个保守值，装上后用内容实测高度覆盖
    frame.style.height = '80px';
    // 重装文档但不重建 iframe 元素：重建会丢掉卡内脚本状态
    frame._ripple = function (nextKey, nextDesc) {
      if (nextKey) frame.dataset.cardKey = nextKey;
      var target = nextDesc || desc;
      frame.srcdoc = cardDocHtml(target, target.ctx || {});
    };
    frame.srcdoc = cardDocHtml(desc, desc.ctx || {});
    return frame;
  }

  // —— 消息正文：把一条消息摊平成有序块序列，再与现有 DOM 逐位对齐 ——
  function blocksOf(msg) {
    var desc = [];
    if (msg.reasoning && state.appearance.showThinking !== false) {
      desc.push({ kind: 'reasoning', text: String(msg.reasoning) });
    }
    var blocks = msg.blocks || [];
    if (!blocks.length && String(msg.text || '').trim()) blocks = [{ type: 'text', text: msg.text }];
    blocks.forEach(function (block) {
      if (!block) return;
      if (block.type === 'card') {
        desc.push({
          kind: 'card',
          key: String(msg.id) + ':' + (block.index || 0),
          html: block.html == null ? '' : String(block.html),
          style: block.style == null ? null : String(block.style),
          head: block.head == null ? null : String(block.head),
          ctx: {
            messageId: msg.id,
            cardIndex: block.index || 0,
            charName: (state.macros || {}).char,
            userName: (state.macros || {}).user
          }
        });
      } else if (block.type === 'image' || block.type === 'video' || block.type === 'audio' || block.type === 'document') {
        // 附件也是有序块：交给 patchBody 定位复用，直接塞 body 会重复叠加
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
    if (msg.translation) desc.push({ kind: 'translation', text: String(msg.translation) });
    return desc;
  }

  function attachmentNode(block) {
    var wrap = document.createElement('div');
    wrap.className = 'blk attach';
    if (block.type === 'image') {
      var img = document.createElement('img');
      img.className = 'attach-img';
      img.src = block.url || '';
      img.loading = 'lazy';
      img.alt = '';
      img.addEventListener('load', followBottom);
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
    } else {
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

  // 正文渲染：代码块三个开关会改变输出结构，故渲染键带上它们，开关变了才重渲染
  function textColors() {
    var a = state.appearance || {};
    if (a.textColorEnabled === false) return null;
    if (!a.quoteColor && !a.bracketColor) return null;
    return { quote: a.quoteColor, bracket: a.bracketColor };
  }

  function renderMarkdown(text) {
    var a = state.appearance || {};
    return window.RikkaMarkdown.render(text, {
      lineNumbers: a.lineNumbers === true,
      collapse: a.codeCollapse === true,
      colors: textColors()
    });
  }

  // 渲染键带上影响输出结构的开关，设置变了才重渲染
  // 卡片文档的比价键：正文、自带样式、自带 head 任一变化都要重装
  function cardSig(desc) {
    return String(desc.html == null ? '' : desc.html) + '\u0000' +
      (desc.style == null ? '' : String(desc.style)) + '\u0000' +
      (desc.head == null ? '' : String(desc.head));
  }

  function renderKey(text) {
    var a = state.appearance || {};
    var c = textColors();
    return String(text) + '\u0000' + (a.lineNumbers === true ? 'n' : '') + (a.codeCollapse === true ? 'c' : '') +
      '\u0000' + (c ? (c.quote || '') + '|' + (c.bracket || '') : '-');
  }

  var REASONING_ICON = '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="M12 3a6 6 0 0 0-3.4 10.94c.5.36.8.94.8 1.56V17h5.2v-1.5c0-.62.3-1.2.8-1.56A6 6 0 0 0 12 3Z"></path>' +
    '<path d="M9.6 20h4.8"></path></svg>';
  var ARROW_ICON = '<svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="m6 9 6 6 6-6"></path></svg>';
  var TOOL_ICON = '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="M14.7 6.3a4 4 0 0 0 5 5L21 21H3l1.3-9.7a4 4 0 0 0 5-5Z"></path></svg>';

  function makeBlockNode(desc, streaming) {
    if (desc.kind === 'attach') {
      var attach = attachmentNode(desc.block);
      attach.dataset.kind = 'attach';
      attach.dataset.attachKey = desc.key;
      return attach;
    }
    if (desc.kind === 'reasoning') {
      var det = document.createElement('details');
      det.className = 'blk blk-reasoning';
      det.dataset.kind = 'reasoning';
      det.innerHTML = '<summary class="reasoning-head">' + REASONING_ICON +
        '<span class="r-label">思考</span><span class="dur"></span>' + ARROW_ICON + '</summary>' +
        '<div class="reasoning-body"></div>';
      det.querySelector('.reasoning-body').textContent = desc.text;
      // 原生语义：流式中先露一小段预览，落定后按 autoCloseThinking 收起
      det.open = !!streaming && state.appearance.autoCloseThinking !== false;
      return det;
    }
    if (desc.kind === 'tool') {
      var tool = document.createElement('details');
      tool.className = 'blk blk-tool';
      tool.dataset.kind = 'tool';
      tool.innerHTML = '<summary class="tool-head">' + TOOL_ICON +
        '<span class="t-label"></span>' + ARROW_ICON + '</summary>' +
        '<div class="tool-body"></div>';
      tool.querySelector('.t-label').textContent = desc.text || '工具调用';
      tool.querySelector('.tool-body').textContent = desc.text || '';
      // 原生工具步骤默认展开
      tool.open = true;
      return tool;
    }
    if (desc.kind === 'card') {
      var node = document.createElement('div');
      node.className = 'blk card';
      node.dataset.kind = 'card';
      node.dataset.cardKey = desc.key;
      // 流式帧只铺文本与光标，不装配卡片：半截 HTML 会渲染成残缺网页
      if (!streaming) {
        node.appendChild(cardFrame(desc.key, desc));
        node._html = cardSig(desc);
        node._desc = desc;
        node._shellVersion = shellVersion;
      }
      return node;
    }
    if (desc.kind === 'translation') {
      var tr = document.createElement('div');
      tr.className = 'blk translation';
      tr.dataset.kind = 'translation';
      tr.innerHTML = renderMarkdown(desc.text);
      tr._text = renderKey(desc.text);
      return tr;
    }
    var bubble = document.createElement('div');
    bubble.className = 'blk bubble';
    bubble.dataset.kind = 'text';
    bubble.innerHTML = renderMarkdown(desc.text);
    bubble._text = renderKey(desc.text);
    return bubble;
  }

  // 就地更新块；返回真正留在 DOM 里的节点（卡片换了内容时会换成新节点）
  function updateBlockNode(node, desc, streaming) {
    if (desc.kind === 'card') {
      // 流式期间绝不重建 iframe（卡内脚本状态会全丢）
      if (streaming) return node;
      var htmlChanged = node._html !== cardSig(desc);
      var shellChanged = node._shellVersion !== shellVersion;
      if (!htmlChanged && !shellChanged) return node;
      var frame = node.querySelector('iframe.card-frame');
      // 只有外壳（运行库/助手级样式）变了：换文档、留元素
      if (!htmlChanged && frame && typeof frame._ripple === 'function') {
        frame._ripple(desc.key, desc);
        node._desc = desc;
        node._shellVersion = shellVersion;
        return node;
      }
      var fresh = makeBlockNode(desc, false);
      node.parentNode.replaceChild(fresh, node);
      return fresh;
    }
    if (desc.kind === 'attach') return node;
    if (desc.kind === 'reasoning') {
      var body = node.querySelector('.reasoning-body');
      if (body && node._text !== desc.text) {
        node._text = desc.text;
        body.textContent = desc.text;
      }
      // 流式结束且开了自动收起：折起来（原生 autoCloseThinking 语义）
      if (!streaming && node.open && state.appearance.autoCloseThinking !== false) node.open = false;
      if (!streaming && state.appearance.autoCloseThinking === false) node.open = true;
      return node;
    }
    if (desc.kind === 'tool') {
      var tbody = node.querySelector('.tool-body');
      if (tbody && node._text !== desc.text) {
        node._text = desc.text;
        tbody.textContent = desc.text;
        node.querySelector('.t-label').textContent = desc.text || '工具调用';
      }
      return node;
    }
    if (desc.kind === 'text' || desc.kind === 'translation') {
      // 文本变了才重设 innerHTML，避免每帧重排导致滚动跳动
      var key = renderKey(desc.text);
      if (node._text !== key) {
        node._text = key;
        node.innerHTML = renderMarkdown(desc.text);
      }
      if (desc.kind === 'text') node.classList.toggle('typing', !!streaming);
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

  // —— 头像与头部行 ——
  function initials(name) {
    var text = String(name == null ? '' : name).trim();
    if (!text) return '?';
    return Array.from(text)[0] || '?';
  }

  function safeUrl(url) {
    var u = String(url == null ? '' : url).trim();
    if (!u) return '';
    if (/^(https?:|data:image\/|file:|content:)/i.test(u)) return u;
    if (/^[./#]/.test(u)) return u;
    return '';
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
    var isUser = msg.role === 'user';
    var frameUrl = safeUrl(isUser ? state.appearance.userAvatarFrame : state.appearance.botAvatarFrame);
    if (frameUrl) {
      var frame = document.createElement('img');
      frame.className = 'frame';
      frame.alt = '';
      frame.src = frameUrl;
      avatar.appendChild(frame);
    }
    return avatar;
  }

  function signatureOf(msg) {
    return String(msg.signature == null ? '' : msg.signature);
  }

  var OPS = [
    { action: 'copy', label: '复制', icon: '<rect x="9" y="9" width="11" height="11" rx="2"></rect><path d="M5 15V5a2 2 0 0 1 2-2h8"></path>' },
    { action: 'edit', label: '编辑', icon: '<path d="M4 20h4l10.5-10.5a2.1 2.1 0 0 0-3-3L5 17v3Z"></path>' },
    { action: 'regenerate', label: '重新生成', icon: '<path d="M20 11a8 8 0 1 0-2.3 5.7"></path><path d="M20 5v6h-6"></path>' },
    { action: 'translate', label: '翻译', icon: '<path d="M4 6h9"></path><path d="M8 4v2c0 4-2 7-4 8"></path><path d="M6.5 10c1 2.5 3 4.5 5.5 5.5"></path><path d="m13 20 4-9 4 9"></path><path d="M14.5 17h5"></path>' },
    { action: 'favorite', label: '收藏', icon: '<path d="m12 4 2.4 4.9 5.4.8-3.9 3.8.9 5.4-4.8-2.6-4.8 2.6.9-5.4L4.2 9.7l5.4-.8Z"></path>' },
    { action: 'share', label: '分享', icon: '<path d="M12 16V4"></path><path d="m8 8 4-4 4 4"></path><path d="M5 14v5a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-5"></path>' },
    { action: 'delete', label: '删除', icon: '<path d="M4 7h16"></path><path d="M9 7V5h6v2"></path><path d="M6 7l1 13h10l1-13"></path>' }
  ];

  function buildOps(row, msg) {
    var ops = document.createElement('div');
    ops.className = 'ops';
    ops.dataset.kind = 'ops';
    OPS.forEach(function (def) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'op';
      btn.dataset.action = def.action;
      btn.setAttribute('aria-label', def.label);
      btn.setAttribute('title', def.label);
      btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" ' +
        'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + def.icon + '</svg>';
      if (def.action === 'favorite') btn.classList.toggle('on', msg.favorited === true);
      ops.appendChild(btn);
    });
    ops.addEventListener('click', function (event) {
      var btn = event.target && event.target.closest ? event.target.closest('.op') : null;
      if (!btn) return;
      event.stopPropagation();
      requestNative('messageAction', JSON.stringify({ action: btn.dataset.action, id: String(msg.id) }));
    });
    return ops;
  }

  // 多分支消息的切换条：‹ i/n ›，点击回请原生 selectSwipe
  function syncSwipes(col, msg) {
    if (!col) return;
    var barNode = null;
    for (var i = 0; i < col.children.length; i++) {
      if (col.children[i].classList.contains('swipes')) { barNode = col.children[i]; break; }
    }
    var count = Number(msg.swipeCount) || 0;
    var nodeId = msg.nodeId == null ? '' : String(msg.nodeId);
    if (count < 2 || !nodeId) {
      if (barNode) col.removeChild(barNode);
      return;
    }
    if (!barNode) {
      barNode = document.createElement('div');
      barNode.className = 'swipes';
      barNode.innerHTML = '<button type="button" class="swipe-btn" data-step="-1" aria-label="上一条分支">‹</button>' +
        '<span class="swipe-label"></span>' +
        '<button type="button" class="swipe-btn" data-step="1" aria-label="下一条分支">›</button>';
      barNode.addEventListener('click', function (event) {
        event.stopPropagation();
        var btn = event.target && event.target.closest ? event.target.closest('.swipe-btn') : null;
        if (!btn || btn.disabled) return;
        var total = Number(barNode.dataset.count) || 0;
        var next = (Number(barNode.dataset.index) || 0) + (Number(btn.dataset.step) || 0);
        if (next < 0 || next >= total) return;
        requestNative('selectSwipe', JSON.stringify({ nodeId: barNode.dataset.nodeId || '', index: next }));
      });
      col.appendChild(barNode);
    }
    // 状态放 dataset 上，点击时现读，避免闭包拿到旧下标
    var index = Math.max(0, Math.min(count - 1, Number(msg.swipeIndex) || 0));
    barNode.dataset.count = String(count);
    barNode.dataset.index = String(index);
    barNode.dataset.nodeId = nodeId;
    var label = barNode.querySelector('.swipe-label');
    if (label) label.textContent = (index + 1) + '/' + count;
    var prev = barNode.querySelector('[data-step="-1"]');
    var next2 = barNode.querySelector('[data-step="1"]');
    if (prev) prev.disabled = index <= 0;
    if (next2) next2.disabled = index >= count - 1;
  }

  function formatNumber(value) {
    var n = Number(value) || 0;
    return n.toLocaleString('en-US');
  }

  // 技术统计行：token / 缓存 / tok·s⁻¹ / 耗时（对齐 ChatMessageNerdLine）
  function syncNerd(node, msg) {
    if (!node) return;
    if (state.appearance.showTokenUsage === false || !msg.usage) {
      node.hidden = true;
      node.textContent = '';
      return;
    }
    var usage = msg.usage;
    var cached = Number(usage.cached) || 0;
    var prompt = Number(usage.prompt) || 0;
    var parts = [];
    parts.push('<span class="item"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20V5"></path><path d="m6 11 6-6 6 6"></path></svg>' +
      formatNumber(prompt) + ' tokens' + (cached > 0 ? ' (' + formatNumber(cached) + ' cached' + (prompt > 0 ? ' / ' + Math.round(cached * 100 / prompt) + '%' : '') + ')' : '') + '</span>');
    parts.push('<span class="item"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v15"></path><path d="m6 13 6 6 6-6"></path></svg>' +
      formatNumber(usage.completion) + ' tokens</span>');
    if (msg.finishedAt && msg.createdAt && msg.finishedAt > msg.createdAt) {
      var seconds = (msg.finishedAt - msg.createdAt) / 1000;
      var tps = (Number(usage.completion) || 0) / seconds;
      parts.push('<span class="item"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z"></path></svg>' + tps.toFixed(1) + ' tok/s</span>');
      parts.push('<span class="item"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v5l3 2"></path></svg>' + seconds.toFixed(1) + 's</span>');
    }
    node.innerHTML = parts.join('');
    node.hidden = false;
  }

  function headNode(msg) {
    var head = document.createElement('div');
    head.className = 'head';
    var isUser = msg.role === 'user';
    if (isUser) {
      head.appendChild(avatarNode(msg));
      var label = document.createElement('span');
      label.className = 'head-name';
      label.textContent = msg.name == null ? '' : String(msg.name);
      head.appendChild(label);
      // 用户侧整行由 showUserAvatar 控制
      if (state.appearance.showUserAvatar === false) head.hidden = true;
    } else {
      head.appendChild(avatarNode(msg));
      var model = document.createElement('span');
      model.className = 'head-name';
      model.textContent = msg.name == null ? '' : String(msg.name);
      head.appendChild(model);
      if (state.appearance.showModelIcon === false) head.querySelector('.avatar').hidden = true;
      if (state.appearance.showModelName === false) model.hidden = true;
    }
    return head;
  }

  function renderRow(msg, streaming) {
    var row = document.createElement('div');
    row.className = 'row ' + (msg.role === 'user' ? 'user' : 'ai');
    row.dataset.id = String(msg.id);
    row.dataset.role = msg.role === 'user' ? 'user' : 'ai';
    row.dataset.signature = signatureOf(msg);
    row.appendChild(headNode(msg));

    var col = document.createElement('div');
    col.className = 'col';

    var body = document.createElement('div');
    body.className = 'body';
    patchBody(body, msg, !!streaming);
    col.appendChild(body);

    var nerd = document.createElement('div');
    nerd.className = 'nerd';
    syncNerd(nerd, msg);
    col.appendChild(nerd);

    var ops = buildOps(row, msg);
    ops.hidden = streaming === true;
    col.appendChild(ops);

    var time = document.createElement('span');
    time.className = 'time';
    time.textContent = state.appearance.showTime === true ? String(msg.time || '') : '';
    time.hidden = !time.textContent;
    ops.appendChild(time);

    syncSwipes(col, msg);

    row.appendChild(col);
    return row;
  }

  // 就地更新一行（正文、头部、统计、操作排）
  function updateRow(row, msg, streaming) {
    var body = row.querySelector('.body');
    if (body) patchBody(body, msg, streaming);

    var head = row.querySelector('.head');
    if (head) {
      var isUser = msg.role === 'user';
      var nameNode = head.querySelector('.head-name');
      var avatarEl = head.querySelector('.avatar');
      var nameText = msg.name == null ? '' : String(msg.name);
      if (nameNode && nameNode.textContent !== nameText) {
        nameNode.textContent = nameText;
        avatarEl.textContent = '';
        var freshAvatar = avatarNode(msg);
        head.replaceChild(freshAvatar, avatarEl);
      }
      head.hidden = isUser ? state.appearance.showUserAvatar === false : false;
      var icon = head.querySelector('.avatar');
      if (icon && !isUser) icon.hidden = state.appearance.showModelIcon === false;
      if (nameNode && !isUser) nameNode.hidden = state.appearance.showModelName === false;
    }

    syncNerd(row.querySelector('.nerd'), msg);

    var ops = row.querySelector('.ops');
    if (ops) {
      ops.hidden = streaming === true;
      var fav = ops.querySelector('[data-action="favorite"]');
      if (fav) fav.classList.toggle('on', msg.favorited === true);
      var time = ops.querySelector('.time');
      if (time) {
        var text = state.appearance.showTime === true ? String(msg.time || '') : '';
        time.textContent = text;
        time.hidden = !text;
      }
    }
    syncSwipes(row.querySelector('.col'), msg);
    row.dataset.signature = signatureOf(msg);
  }

  // 流式光标只在助手消息上：用户刚发完时最后一条是用户消息，那种情况不打光标
  function applyTyping(row, msg, streaming) {
    if (!row) return;
    var body = row.querySelector('.body');
    if (!body) return;
    var bubbles = body.querySelectorAll('.blk.bubble');
    var isAssistant = msg.role !== 'user';
    var live = !!streaming && isAssistant;
    for (var i = 0; i < bubbles.length; i++) {
      bubbles[i].classList.toggle('typing', live && i === bubbles.length - 1);
    }
    var avatar = row.querySelector('.avatar');
    if (avatar) avatar.classList.toggle('loading', live);
  }

  function rowById(id) {
    var key = String(id);
    for (var i = 0; i < list.children.length; i++) {
      if (list.children[i].dataset && list.children[i].dataset.id === key) return list.children[i];
    }
    return null;
  }

  function showEmpty() {
    if (list.querySelector('.empty')) return;
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
      while (list.firstChild) list.removeChild(list.firstChild);
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
      var streaming = streamId === id && msg.role !== 'user';
      var row = existing[id] && existing[id].shift(); // 重复 id 的脏行留给下面统一清理
      if (row) updateRow(row, msg, streaming);
      else row = renderRow(msg, streaming);
      applyTyping(row, msg, streaming);
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
  }

  // 外观变化后按新设置重刷所有行（不动卡片 iframe）
  function syncRows() {
    for (var i = 0; i < list.children.length; i++) {
      var row = list.children[i];
      if (!row.dataset || row.dataset.id === undefined) continue;
      var msg = messageById(row.dataset.id);
      if (!msg) continue;
      var streaming = streamId === String(msg.id) && msg.role !== 'user';
      updateRow(row, msg, streaming);
      applyTyping(row, msg, streaming);
    }
  }

  function messageById(id) {
    var key = String(id);
    for (var i = 0; i < state.messages.length; i++) {
      if (String(state.messages[i].id) === key) return state.messages[i];
    }
    return null;
  }

  window.addEventListener('resize', followBottom, false);

  // —— 原生侧接口 ——
  window.webui = {
    // 原生下发的卡片运行库（jQuery / toastr / compat，base64 传输避免转义问题）
    setLibs: function (json) {
      var payload = parse(json) || {};
      var changed = false;
      Object.keys(payload).forEach(function (name) {
        try {
          var text = decodeBase64(payload[name]);
          if (state.libs[name] !== text) { state.libs[name] = text; changed = true; }
        } catch (e) { /* 解码失败就当没有 */ }
      });
      // compat.js 是页面唯一的完整补丁：index.html 那次加载没成功时，用原生下发的这份补上
      if (!window.RikkaCompat && state.libs.compat) injectScript(state.libs.compat);
      // 运行库变了：首帧装配的卡片是在没有 libs 的状态下建的，按版本重装一次
      if (changed) {
        shellVersion++;
        schedule(function () { rebuildCards(); });
      }
    },

    setSafeArea: function (json) {
      var area = parse(json);
      if (!area) return;
      var root = document.documentElement;
      if (area.top != null) root.style.setProperty('--safe-top', area.top + 'px');
      if (area.bottom != null) root.style.setProperty('--safe-bottom', area.bottom + 'px');
    },

    setAppearance: function (json) { applyAppearance(parse(json)); },
    setTheme: function (json) { applyTheme(parse(json)); },

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
          var win = frames[i].contentWindow;
          var fn = win && win.__rikkaEmit;
          if (fn) fn(name, payloadJson);
        } catch (e) { /* 忽略 */ }
      }
    },

    setState: function (json) {
      var payload = parse(json) || {};
      if (payload.theme) applyTheme(payload.theme);
      if (payload.macros) state.macros = payload.macros;
      // 卡片样式变了：外壳版本自增，已装配的卡片按新样式重装（不重建 iframe 元素）
      if (typeof payload.cardStyle === 'string' && payload.cardStyle !== state.cardStyle) {
        state.cardStyle = payload.cardStyle;
        shellVersion++;
      }
      if (typeof payload.placeholder === 'string') state.placeholder = payload.placeholder;
      state.messages = payload.messages || [];
      if (streamId && !messageById(streamId)) streamId = null;
      schedule(renderAll);
      followBottom();
    },

    // 流式增量：只动那条消息，卡片 iframe 原地保留
    appendStream: function (json) {
      var msg = parse(json);
      if (!msg) return;
      // 光标只认助手消息：用户消息的 id 不会进来（原生侧已过滤）
      if (msg.role === 'user') return;
      streamId = String(msg.id);
      upsertMessage(msg);
      schedule(function () {
        var row = rowById(msg.id);
        if (!row) { removeEmpty(); list.appendChild(renderRow(msg, true)); }
        else updateRow(row, msg, true);
        applyTyping(row || rowById(msg.id), msg, true);
        followBottom();
      });
    },

    // 追加或落定单条：signature 未变才跳过，但正在流式的那一行必须收尾（否则光标不灭）
    appendMessage: function (json) {
      var msg = parse(json);
      if (!msg) return;
      upsertMessage(msg);
      if (streamId === String(msg.id)) streamId = null;
      schedule(function () {
        var row = rowById(msg.id);
        if (row) {
          var sig = signatureOf(msg);
          if (sig && row.dataset.signature === sig && !row.querySelector('.typing')) return;
          updateRow(row, msg, false);
        } else {
          removeEmpty();
          list.appendChild(renderRow(msg, false));
        }
        applyTyping(rowById(msg.id), msg, false);
        followBottom();
      });
    },

    setToolbar: function (json) { /* 工具条由原生输入栏提供，页面不再渲染 */ },

    scrollToBottom: function () { stick = true; scrollToBottomNow(true); },
    getVariables: function () { return JSON.stringify(variables); }
  };

  function upsertMessage(msg) {
    var key = String(msg.id);
    for (var i = 0; i < state.messages.length; i++) {
      if (String(state.messages[i].id) === key) { state.messages[i] = msg; return; }
    }
    state.messages.push(msg);
  }

  // 运行库或 cardStyle 变化后重装已装配的卡片文档（iframe 元素本身保留）
  function rebuildCards() {
    var nodes = list.querySelectorAll('.blk.card');
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (!node._desc || node._html == null || !node.parentNode) continue;
      var frame = node.querySelector('iframe.card-frame');
      if (frame && typeof frame._ripple === 'function') {
        frame._ripple(node.dataset.cardKey, node._desc);
        node._shellVersion = shellVersion;
      } else {
        node.parentNode.replaceChild(makeBlockNode(node._desc, false), node);
      }
    }
  }

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

  function decodeBase64(value) {
    if (!value) return '';
    try { return atob(value); } catch (e) {
      try { return decodeURIComponent(escape(atob(value))); } catch (e2) { return ''; }
    }
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

  function copyText(text) {
    try {
      if (window.rikkaNative && typeof window.rikkaNative.clipboard === 'function') {
        window.rikkaNative.clipboard(String(text));
        return;
      }
    } catch (e) { /* 落到 navigator 通道 */ }
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(String(text));
    } catch (e) { /* 无剪贴板能力时静默 */ }
  }

  // 代码块复制/展开，用户气泡点击进入编辑（与原生 onClick=onEdit 一致）
  document.addEventListener('click', function (event) {
    var target = event.target;
    if (!target || !target.closest) return;
    var copyBtn = target.closest('.code-head .copy');
    if (copyBtn) {
      var pre = copyBtn.closest('pre');
      var code = pre ? pre.querySelector('code') : null;
      if (code) copyText(code.textContent);
      return;
    }
    var expandBtn = target.closest('pre .expand');
    if (expandBtn) {
      var host = expandBtn.closest('pre');
      if (host) {
        host.classList.remove('collapsed');
        if (expandBtn.parentNode) expandBtn.parentNode.removeChild(expandBtn);
      }
      return;
    }
    var bubble = target.closest('.row.user .bubble');
    if (bubble && !target.closest('a, img, video, audio')) {
      var row = bubble.closest('.row');
      if (row) requestNative('messageAction', JSON.stringify({ action: 'edit', id: String(row.dataset.id) }));
    }
  }, false);

  window.rikkaSend = function (text) { native('send', text); };
  window.rikkaStop = function () { native('stop', ''); };
  window.rikkaRegenerate = function () { native('regenerate', ''); };
  window.rikkaInsertSymbol = function (text) { native('insertSymbol', text); };
})();
