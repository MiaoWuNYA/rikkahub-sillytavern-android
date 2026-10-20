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
    '  try { void W.localStorage.length; } catch (e) { try {',
    '    var mem = {};',
    '    Object.defineProperty(W, "localStorage", { configurable: true, value: {',
    '      getItem: function(k){ return Object.prototype.hasOwnProperty.call(mem, String(k)) ? mem[String(k)] : null; },',
    '      setItem: function(k, v){ mem[String(k)] = String(v); },',
    '      removeItem: function(k){ delete mem[String(k)]; },',
    '      clear: function(){ mem = {}; },',
    '      key: function(i){ return Object.keys(mem)[i] || null; },',
    '      get length(){ return Object.keys(mem).length; }',
    '    } });',
    '  } catch (e2) {} }',
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
    appearance: {},
    // 酒馆助手兼容渲染设置：codeCollapse 为 null 时回退到 appearance.codeCollapse
    helper: { renderEnabled: true, allowStreaming: false, codeCollapse: null, useBlobUrl: false, macroEnabled: true },
    labels: {},
    // 原生 TTS 是单例：朗读中所有助手行的朗读按钮一起切到停止态
    speaking: false,
    ttsAvailable: true,
  };
  var variables = loadVariables();
  // 除 local/global/message 外的变量域（character/preset/script/extension…），按名字独立存放
  var extraVariables = {};
  var globalVariables = {};
  var messageVariables = {};
  // 外壳版本：setLibs 或 cardStyle 变化时自增，用来把已装配的卡片按新外壳重装一次
  var shellVersion = 0;
  // 生成状态由原生权威下发；streamId 只负责指出是哪一条在生成
  var generating = false;
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

  var HTML_TAGS = {};
  ('a abbr address area article aside audio b base bdi bdo blockquote body br button canvas caption cite code col ' +
   'colgroup data datalist dd del details dfn dialog div dl dt em embed fieldset figcaption figure footer form ' +
   'h1 h2 h3 h4 h5 h6 head header hgroup hr html i iframe img input ins kbd label legend li link main map mark ' +
   'menu meta meter nav noscript object ol optgroup option output p picture pre progress q rp rt ruby s samp ' +
   'script search section select slot small source span strong style sub summary sup table tbody td template ' +
   'textarea tfoot th thead time title tr track u ul var video wbr svg path circle rect g line polygon polyline ' +
   'ellipse defs use text tspan mask clippath lineargradient radialgradient stop filter pattern image symbol ' +
   'marker foreignobject').split(' ').forEach(function (tag) { HTML_TAGS[tag.toUpperCase()] = 1; });

  var BLOCK_OVERRIDE = { MAIN: 1, OPTION: 1 };

  var ANY_TAG_RE = /<(\/?)([a-zA-Z][a-zA-Z0-9_:-]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;

  function isCardContainer(tag) {
    var up = String(tag).toUpperCase();
    return !!(BLOCK_OVERRIDE[up] || !HTML_TAGS[up]);
  }

  function convertBlockTags(chunk) {
    return String(chunk == null ? '' : chunk).replace(ANY_TAG_RE, function (full, slash, name, rawAttrs) {
      if (!isCardContainer(name)) return full;
      var tag = String(name).toLowerCase();
      var el = tag === 'option' ? 'div' : name;
      if (slash === '/') return '</' + el + '>';
      var cls = 'rikka-block rikka-block-' + tag;
      var attrs = String(rawAttrs || '');
      var existing = attrs.match(/\sclass\s*=\s*("[^"]*"|'[^']*')/i);
      if (existing) {
        var cur = existing[1].slice(1, -1);
        if (/(^|\s)rikka-block(\s|$)/.test(cur)) return full;
        attrs = attrs.replace(existing[0], ' class="' + cls + ' ' + cur + '"');
      } else {
        attrs = ' class="' + cls + '"' + attrs;
      }
      return '<' + el + attrs + '>';
    });
  }

  function convertCharaTags(chunk) {
    return convertBlockTags(chunk).replace(CHARA_TAG_RE, function (full, slash, name) {
      if (slash === '/') return '</span>';
      return '<span class="chara-tag" data-chara="' + name + '">';
    });
  }

  // 只改选择器里的非 ASCII 标签名；注释、声明体、@规则名一律不碰，支持嵌套块
  var CHARA_SEL_RE = /([A-Za-z0-9_:\-]*[\u0080-\uFFFF][A-Za-z0-9_:\-\u0080-\uFFFF]*)/g;

  function rewriteCharaSelector(sel) {
    if (!NON_ASCII.test(sel)) return sel;
    var cut = sel.lastIndexOf('*/');
    var head = cut >= 0 ? sel.slice(0, cut + 2) : '';
    var tail = cut >= 0 ? sel.slice(cut + 2) : sel;
    if (head && tail.indexOf('{') < 0 && tail.trim() === '') return head;
    return head + tail.replace(CHARA_SEL_RE, function (name) {
      return name.indexOf('@') === 0 ? name : '[data-chara="' + name + '"]';
    });
  }

  function rewriteCharaCss(css) {
    var text = String(css == null ? '' : css);
    if (!NON_ASCII.test(text)) return text;
    var out = '';
    var i = 0;
    while (i < text.length) {
      var ch = text[i];
      if (ch === '{') {
        var depth = 1, j = i + 1;
        while (j < text.length && depth > 0) {
          if (text[j] === '{') depth++;
          else if (text[j] === '}') depth--;
          j++;
        }
        var block = text.slice(i + 1, Math.max(i + 1, j - 1));
        out += '{' + (block.indexOf('{') >= 0 ? rewriteCharaCss(block) : block) + '}';
        i = j;
        continue;
      }
      var next = text.indexOf('{', i);
      if (next < 0) { out += rewriteCharaSelector(text.slice(i)); break; }
      var seg = text.slice(i, next);
      out += seg.charAt(seg.length - 1) === '}' || seg.indexOf('@') === 0 && seg.indexOf('{') < 0
        ? seg : rewriteCharaSelector(seg);
      i = next;
    }
    return out;
  }

  function normalizeCharaTags(html) {
    var text = String(html == null ? '' : html);
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
    applyBubbleColors();
  }

  // 气泡底色：设置里没配色时用主题色，与原生 customBubbleColor 的 fallback 一致
  function applyBubbleColors() {
    var a = state.appearance || {};
    var alpha = a.bubbleOpacity == null ? 0.9 : a.bubbleOpacity;
    setVar('--bubble-user', a.userBubbleColor
      ? withAlpha(a.userBubbleColor, alpha)
      : withAlpha(themeHex('--md-primary-container', '#EADDFF'), alpha));
    setVar('--bubble-ai', a.assistantBubbleColor
      ? withAlpha(a.assistantBubbleColor, alpha)
      : withAlpha(themeHex('--md-surface-container-high', '#ECE6F0'), alpha));
  }

  function themeHex(name, fallback) {
    try {
      var value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
      return /^#[0-9a-f]{6}$/i.test(value) ? value : fallback;
    } catch (e) { return fallback; }
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
    setVar('--code-font-scale', a.codeFontScale == null ? null : String(a.codeFontScale));
    setVar('--bubble-border-width', px(a.borderWidth, '0px'));
    setVar('--bubble-image-size', a.imageSize === 'cover' ? 'cover' : 'contain');
    if (a.primaryColor) setVar('--md-primary', a.primaryColor);
    applyBubbleColors();
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
    setVar('--md-emphasis-bg', a.mdEmphasisBg || undefined);
    setVar('--md-emphasis-color', a.mdEmphasisColor || undefined);
    setVar('--md-emphasis-radius', px(a.mdEmphasisRadius, null));

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
  // 手势期间一律不跟随；程序化滚动不回写状态；只有真正贴底才恢复跟随。
  var stick = true;
  var gesture = false;
  var gestureMoved = false;
  var gestureY = 0;
  var programmaticUntil = 0;

  function distanceFromBottom() {
    return scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight;
  }

  function nearBottom() {
    return distanceFromBottom() < 32;
  }

  function scrollToBottomNow(smooth) {
    var top = scroller.scrollHeight;
    // 平滑滚动会连续多帧派发 scroll，整个窗口内都不算用户意图
    programmaticUntil = Date.now() + (smooth ? 600 : 80);
    if (smooth && scroller.scrollTo) {
      try { scroller.scrollTo({ top: top, behavior: 'smooth' }); return; } catch (e) { /* 老引擎不支持选项 */ }
    }
    scroller.scrollTop = top;
  }

  function followBottom() {
    if (!stick || gesture) return;
    schedule(function () { if (gesture || !stick) return; scrollToBottomNow(false); });
  }

  function updateJump() {
    if (jumpBtn) schedule(function () { jumpBtn.classList.toggle('on', !stick); });
  }

  scroller.addEventListener('scroll', function () {
    if (Date.now() < programmaticUntil) return;
    stick = nearBottom();
    updateJump();
  }, { passive: true });

  // 用户一上滑（手指下移）就脱离跟随，避免流式帧每帧把列表拉回底部
  scroller.addEventListener('touchstart', function (event) {
    gesture = true;
    gestureMoved = false;
    gestureY = event.touches && event.touches[0] ? event.touches[0].clientY : 0;
  }, { passive: true });

  scroller.addEventListener('touchmove', function (event) {
    if (!event.touches || !event.touches[0]) return;
    var y = event.touches[0].clientY;
    if (Math.abs(y - gestureY) < 4) return;
    gestureMoved = true;
    if (y > gestureY) stick = false;
  }, { passive: true });

  function endGesture() {
    gesture = false;
    if (gestureMoved && nearBottom()) stick = true;
    updateJump();
  }

  scroller.addEventListener('touchend', endGesture, { passive: true });
  scroller.addEventListener('touchcancel', endGesture, { passive: true });

  scroller.addEventListener('wheel', function (event) {
    if (event.deltaY < 0) stick = false;
    else if (nearBottom()) stick = true;
    updateJump();
  }, { passive: true });

  if (jumpBtn) {
    jumpBtn.addEventListener('click', function () {
      stick = true;
      scrollToBottomNow(true);
      updateJump();
    });
  }

  // 卡片内容变化导致高度变化时，贴底的话跟着走（卡片在 iframe 里自报高度）
  window.addEventListener('message', function (event) {
    var data = event.data;
    if (!data || data.__rikkaFrame !== 'height') return;
    var h = Math.round(Number(data.h) || 0);
    if (h > 0) {
      var frames = document.querySelectorAll('iframe.card-frame');
      for (var i = 0; i < frames.length; i++) {
        if (frames[i].contentWindow !== event.source) continue;
        var want = h + 'px';
        if (frames[i].style.height !== want) frames[i].style.height = want;
        break;
      }
    }
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
    requestNative('setVariables', JSON.stringify({ scope: 'local', vars: variables }));
  }

  function pushVarsToCards() {
    var frames = document.querySelectorAll('iframe');
    var scopes = { local: variables, global: globalVariables, message: messageVariables };
    Object.keys(extraVariables).forEach(function (k) { scopes[k] = extraVariables[k]; });
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

  var SUPPORTS_WHERE = (function () {
    try { return !!(window.CSS && CSS.supports && CSS.supports('selector(:where(a))')); } catch (e) { return false; }
  })();
  var wrapWhere = function (selectors) {
    if (!SUPPORTS_WHERE) return selectors;
    return selectors.split(',').map(function (s) { return ':where(' + s.trim() + ')'; }).join(',');
  };

  var CARD_SHELL_CSS =
    wrapWhere('html,body') + '{margin:0;padding:0;height:auto;background:transparent;color:inherit;' +
    'font-family:inherit;font-size:inherit;line-height:inherit;word-break:break-word;overflow-x:hidden;}' +
    wrapWhere('img,video,canvas') + '{max-width:100%;height:auto;}' +
    wrapWhere('p') + '{margin:.5em 0;}' +
    wrapWhere('iframe') + '{border:0;}' +
    wrapWhere('mainbody,body>div,body>section,body>article,body>main') +
    '{display:block;box-sizing:border-box;max-width:100%;}' +
    wrapWhere('.rikka-block') + '{display:block;box-sizing:border-box;max-width:100%;margin:8px 0;' +
    'padding:10px 12px;border-radius:12px;background:var(--md-surface-container,#F3EDF7);' +
    'border:1px solid var(--md-outline-variant,#CAC4D0);white-space:pre-wrap;' +
    'line-height:1.7;font-size:13px;color:inherit;}' +
    wrapWhere('.rikka-block > *') + '{margin:0 0 4px;}' +
    wrapWhere('.rikka-block-option') + '{background:var(--md-primary-container,#EADDFF);border-color:transparent;}' +
    wrapWhere('mainbody > *') + '{margin:0 0 .5em;}' +
    CARD_CONTAINER_TAGS.map(function (tag) {
      return wrapWhere(tag) + '{display:block;box-sizing:border-box;max-width:100%;}';
    }).join('');

  // 高度自报：只靠观察者驱动，不用轮询（多张卡片的定时器是滚动卡死的主因）
  var CARD_FORM_GUARD = [
    '(function(){',
    '  var block = function (e) { e.preventDefault(); e.stopPropagation(); };',
    '  document.addEventListener("submit", block, true);',
    '})();'
  ].join('\n');

  var CARD_HEIGHT_SCRIPT = [
    '(function(){',
    '  var last = 0;',
    '  var scheduled = false;',
    '  var raf = window.requestAnimationFrame || function(f){ return setTimeout(f, 16); };',
    '  function contentHeight(){',
    '    var b = document.body;',
    '    if (!b) return 0;',
    '    var box = b.getBoundingClientRect();',
    '    var h = box.height;',
    '    var kids = b.children, bottom = 0;',
    '    for (var i = 0; i < kids.length; i++) {',
    '      var r = kids[i].getBoundingClientRect();',
    '      if (r.bottom > bottom) bottom = r.bottom;',
    '    }',
    '    if (bottom - box.top > h) h = bottom - box.top;',
    '    return Math.ceil(h);',
    '  }',
    '  function measure(){',
    '    scheduled = false;',
    '    var h = contentHeight();',
    '    if (h <= 0) return;',
    '    if (Math.abs(h - last) > 1) {',
    '      last = h;',
    '      try { if (window.frameElement) window.frameElement.style.height = h + "px"; } catch (e) {}',
    '      try { parent.postMessage({__rikkaFrame: "height", h: h}, "*"); } catch (e) {}',
    '    }',
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
    '  var parentApi = function(){ try { return window.parent && window.parent.TavernHelper; } catch (e) { return null; } };',
    '  var api = {',
    '    getMessageId: function(){ return MID; },',
    '    getVariables: function(option){',
    '      var p = parentApi(); if (p && p.getVariables) return p.getVariables(option);',
    '      return call("getVariables", typeof option === "string" ? option : JSON.stringify(option || {}));',
    '    },',
    '    setVariables: function(vars, option){',
    '      var p = parentApi(); if (p && p.replaceVariables) return p.replaceVariables(vars, option);',
    '      var v = vars;',
    '      if (typeof v === "string") { try { v = JSON.parse(v); } catch (e) { v = {}; } }',
    '      return call("setVariables", JSON.stringify({scope: option || "local", vars: v || {}}));',
    '    },',
    '    replaceVariables: function(vars, option){ return api.setVariables(vars, option); },',
    '    insertOrAssignVariables: function(vars, option){',
    '      var p = parentApi(); if (p && p.insertOrAssignVariables) return p.insertOrAssignVariables(vars, option);',
    '      return undefined;',
    '    },',
    '    insertVariables: function(vars, option){',
    '      var p = parentApi(); if (p && p.insertVariables) return p.insertVariables(vars, option);',
    '      return undefined;',
    '    },',
    '    updateVariablesWith: function(updater, option){',
    '      var p = parentApi(); if (p && p.updateVariablesWith) return p.updateVariablesWith(updater, option);',
    '      return undefined;',
    '    },',
    '    deleteVariable: function(path, option){',
    '      var p = parentApi(); if (p && p.deleteVariable) return p.deleteVariable(path, option);',
    '      return undefined;',
    '    },',
    '    registerVariableSchema: function(schema, option){',
    '      var p = parentApi(); if (p && p.registerVariableSchema) return p.registerVariableSchema(schema, option);',
    '    },',
    '    registerMacroLike: function(regex, replace){',
    '      var p = parentApi(); if (p && p.registerMacroLike) return p.registerMacroLike(regex, replace);',
    '      return { unregister: function(){} };',
    '    },',
    '    getTavernHelperVersion: function(){ var p = parentApi(); return (p && p.getTavernHelperVersion) ? p.getTavernHelperVersion() : "4.11.3"; },',
    '    getTavernVersion: function(){ var p = parentApi(); return (p && p.getTavernVersion) ? p.getTavernVersion() : "1.12.14"; },',
    '    getLastMessageId: function(){ var p = parentApi(); return (p && p.getLastMessageId) ? p.getLastMessageId() : MID; },',
    '    sendMessage: function(text){ post("send", String(text == null ? "" : text)); return Promise.resolve(); },',
    '    triggerSlash: function(cmd){ var c = String(cmd == null ? "" : cmd); post("slash", c); return call("triggerSlash", c); },',
    '    substituteMacros: function(text){ return call("substituteMacros", String(text == null ? "" : text)); },',
    '    getChatMessages: function(range){ return call("getChatMessages", String(range == null ? "" : range)); },',
    '    getWorldbookNames: function(){ var p = parentApi(); return (p && p.getWorldbookNames) ? p.getWorldbookNames() : []; },',
    '    getGlobalWorldbookNames: function(){ var p = parentApi(); return (p && p.getGlobalWorldbookNames) ? p.getGlobalWorldbookNames() : []; },',
    '    getCharWorldbookNames: function(){ var p = parentApi(); return (p && p.getCharWorldbookNames) ? p.getCharWorldbookNames() : {primary: null, additional: []}; },',
    '    getChatWorldbookName: function(){ return null; },',
    '    getWorldbook: function(name){ var p = parentApi(); return (p && p.getWorldbook) ? p.getWorldbook(name) : Promise.reject(new Error("no worldbook")); },',
    '    replaceWorldbook: function(name, wb){ var p = parentApi(); return (p && p.replaceWorldbook) ? p.replaceWorldbook(name, wb) : Promise.resolve(); },',
    '    updateWorldbookWith: function(name, fn){ var p = parentApi(); return (p && p.updateWorldbookWith) ? p.updateWorldbookWith(name, fn) : Promise.resolve([]); },',
    '    createWorldbook: function(name, wb){ var p = parentApi(); return (p && p.createWorldbook) ? p.createWorldbook(name, wb) : Promise.resolve(false); },',
    '    createOrReplaceWorldbook: function(name, wb){ var p = parentApi(); return (p && p.createOrReplaceWorldbook) ? p.createOrReplaceWorldbook(name, wb) : Promise.resolve(false); },',
    '    deleteWorldbook: function(name){ var p = parentApi(); return (p && p.deleteWorldbook) ? p.deleteWorldbook(name) : Promise.resolve(false); },',
    '    createWorldbookEntries: function(name, es){ var p = parentApi(); return (p && p.createWorldbookEntries) ? p.createWorldbookEntries(name, es) : Promise.resolve({worldbook: [], new_entries: []}); },',
    '    deleteWorldbookEntries: function(name, fn){ var p = parentApi(); return (p && p.deleteWorldbookEntries) ? p.deleteWorldbookEntries(name, fn) : Promise.resolve({worldbook: [], deleted_entries: []}); },',
    '    rebindGlobalWorldbooks: function(names){ var p = parentApi(); return (p && p.rebindGlobalWorldbooks) ? p.rebindGlobalWorldbooks(names) : Promise.resolve(); },',
    '    rebindCharWorldbooks: function(id, wb){ var p = parentApi(); return (p && p.rebindCharWorldbooks) ? p.rebindCharWorldbooks(id, wb) : Promise.resolve(); },',
    '    importRawWorldbook: function(name, content){ var p = parentApi(); return (p && p.importRawWorldbook) ? p.importRawWorldbook(name, content) : Promise.resolve({}); },',
    '    getPersonaNames: function(){ var p = parentApi(); return (p && p.getPersonaNames) ? p.getPersonaNames() : []; },',
    '    getPersonaIds: function(){ var p = parentApi(); return (p && p.getPersonaIds) ? p.getPersonaIds() : []; },',
    '    getCurrentPersonaName: function(){ var p = parentApi(); return (p && p.getCurrentPersonaName) ? p.getCurrentPersonaName() : null; },',
    '    getCurrentPersonaId: function(){ var p = parentApi(); return (p && p.getCurrentPersonaId) ? p.getCurrentPersonaId() : null; },',
    '    getPersonaAvatarPath: function(id){ var p = parentApi(); return (p && p.getPersonaAvatarPath) ? p.getPersonaAvatarPath(id) : null; },',
    '    getPersona: function(id){ var p = parentApi(); return (p && p.getPersona) ? p.getPersona(id) : {}; },',
    '    replacePersona: function(id, persona){ var p = parentApi(); return (p && p.replacePersona) ? p.replacePersona(id, persona) : Promise.resolve(); },',
    '    updatePersonaWith: function(id, fn){ var p = parentApi(); return (p && p.updatePersonaWith) ? p.updatePersonaWith(id, fn) : Promise.resolve({}); },',
    '    createPersona: function(name, persona){ var p = parentApi(); return (p && p.createPersona) ? p.createPersona(name, persona) : Promise.resolve(false); },',
    '    createOrReplacePersona: function(name, persona){ var p = parentApi(); return (p && p.createOrReplacePersona) ? p.createOrReplacePersona(name, persona) : Promise.resolve(false); },',
    '    deletePersona: function(id){ var p = parentApi(); return (p && p.deletePersona) ? p.deletePersona(id) : Promise.resolve(false); },',
    '    getCharacterNames: function(){ var p = parentApi(); return (p && p.getCharacterNames) ? p.getCharacterNames() : []; },',
    '    getCharacterIds: function(){ var p = parentApi(); return (p && p.getCharacterIds) ? p.getCharacterIds() : []; },',
    '    getCurrentCharacterName: function(){ var p = parentApi(); return (p && p.getCurrentCharacterName) ? p.getCurrentCharacterName() : null; },',
    '    getCurrentCharacterId: function(){ var p = parentApi(); return (p && p.getCurrentCharacterId) ? p.getCurrentCharacterId() : null; },',
    '    getCharAvatarPath: function(id){ var p = parentApi(); return (p && p.getCharAvatarPath) ? p.getCharAvatarPath(id) : null; },',
    '    getCharacter: function(id){ var p = parentApi(); return (p && p.getCharacter) ? p.getCharacter(id) : Promise.reject(new Error("no character")); },',
    '    replaceCharacter: function(id, c){ var p = parentApi(); return (p && p.replaceCharacter) ? p.replaceCharacter(id, c) : Promise.resolve(); },',
    '    updateCharacterWith: function(id, fn){ var p = parentApi(); return (p && p.updateCharacterWith) ? p.updateCharacterWith(id, fn) : Promise.resolve({}); },',
    '    createCharacter: function(id, c){ var p = parentApi(); return (p && p.createCharacter) ? p.createCharacter(id, c) : Promise.resolve(false); },',
    '    createOrReplaceCharacter: function(id, c){ var p = parentApi(); return (p && p.createOrReplaceCharacter) ? p.createOrReplaceCharacter(id, c) : Promise.resolve(false); },',
    '    deleteCharacter: function(id){ var p = parentApi(); return (p && p.deleteCharacter) ? p.deleteCharacter(id) : Promise.resolve(false); },',
    '    getPresetNames: function(){ var p = parentApi(); return (p && p.getPresetNames) ? p.getPresetNames() : []; },',
    '    getLoadedPresetName: function(){ var p = parentApi(); return (p && p.getLoadedPresetName) ? p.getLoadedPresetName() : ""; },',
    '    getPreset: function(name){ var p = parentApi(); return (p && p.getPreset) ? p.getPreset(name) : {}; },',
    '    isPresetNormalPrompt: function(pr){ var p = parentApi(); return (p && p.isPresetNormalPrompt) ? p.isPresetNormalPrompt(pr) : true; },',
    '    isPresetSystemPrompt: function(pr){ var p = parentApi(); return (p && p.isPresetSystemPrompt) ? p.isPresetSystemPrompt(pr) : false; },',
    '    isPresetPlaceholderPrompt: function(pr){ var p = parentApi(); return (p && p.isPresetPlaceholderPrompt) ? p.isPresetPlaceholderPrompt(pr) : false; },',
    '    replacePreset: function(name, pr){ var p = parentApi(); return (p && p.replacePreset) ? p.replacePreset(name, pr) : Promise.resolve(); },',
    '    updatePresetWith: function(name, fn){ var p = parentApi(); return (p && p.updatePresetWith) ? p.updatePresetWith(name, fn) : Promise.resolve(); },',
    '    setPreset: function(name, pr){ var p = parentApi(); return (p && p.setPreset) ? p.setPreset(name, pr) : Promise.resolve(); },',
    '    createPreset: function(name, pr){ var p = parentApi(); return (p && p.createPreset) ? p.createPreset(name, pr) : Promise.resolve(false); },',
    '    createOrReplacePreset: function(name, pr){ var p = parentApi(); return (p && p.createOrReplacePreset) ? p.createOrReplacePreset(name, pr) : Promise.resolve(false); },',
    '    deletePreset: function(name){ var p = parentApi(); return (p && p.deletePreset) ? p.deletePreset(name) : Promise.resolve(false); },',
    '    renamePreset: function(name, nn){ var p = parentApi(); return (p && p.renamePreset) ? p.renamePreset(name, nn) : Promise.resolve(false); },',
    '    loadPreset: function(name){ var p = parentApi(); return (p && p.loadPreset) ? p.loadPreset(name) : false; },',
    '    importRawPreset: function(name, content){ var p = parentApi(); return (p && p.importRawPreset) ? p.importRawPreset(name, content) : Promise.resolve(false); },',
    '    getTavernRegexes: function(opt){ var p = parentApi(); return (p && p.getTavernRegexes) ? p.getTavernRegexes(opt) : []; },',
    '    isCharacterTavernRegexesEnabled: function(){ var p = parentApi(); return (p && p.isCharacterTavernRegexesEnabled) ? p.isCharacterTavernRegexesEnabled() : true; },',
    '    replaceTavernRegexes: function(rs, opt){ var p = parentApi(); return (p && p.replaceTavernRegexes) ? p.replaceTavernRegexes(rs, opt) : Promise.resolve(); },',
    '    updateTavernRegexesWith: function(fn, opt){ var p = parentApi(); return (p && p.updateTavernRegexesWith) ? p.updateTavernRegexesWith(fn, opt) : Promise.resolve([]); },',
    '    formatAsTavernRegexedString: function(t, s, d, o){ var p = parentApi(); return (p && p.formatAsTavernRegexedString) ? p.formatAsTavernRegexedString(t, s, d, o) : String(t == null ? "" : t); },',
    '    eventOn: function(){ return function(){}; },',
    '    eventOnce: function(){ return function(){}; },',
    '    eventEmit: function(name, payload){ post("event", JSON.stringify({name: name, payload: payload})); },',
    '    emitEvent: function(name, payload){ post("event", JSON.stringify({name: name, payload: payload})); },',
    '    charName: CHAR,',
    '    userName: USER,',
    '    messageIndex: IDX',
    '  };',
    '  window.TavernHelper = window.TavernHelper || api;',
    '  window.SillyTavern = window.SillyTavern || api;',
    '  window.webui = window.webui || api;',
    '  window.API = window.API || api;',
    '  try { if (window.parent && window.parent !== window) {',
    '    window.parent.TavernHelper = window.parent.TavernHelper || api;',
    '    window.parent.SillyTavern = window.parent.SillyTavern || api;',
    '    window.parent.API = window.parent.API || api;',
    '    if (window.parent.webui && !window.parent.webui.emitEvent) window.parent.webui.emitEvent = api.emitEvent;',
    '    if (window.parent.webui && !window.parent.webui.setVariables) window.parent.webui.setVariables = api.setVariables;',
    '  } } catch (e) {}',
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
  function escapeScriptBodies(html) {
    return String(html == null ? '' : html).replace(
      /(<script\b[^>]*>)([\s\S]*?)(<\/script\s*>)/gi,
      function (all, open, body, close) {
        return open + body.replace(/<!--/g, '<\\!--') + close;
      }
    );
  }

  function inlineScript(source) {
    var body = String(source == null ? '' : source)
      .replace(/<\/(script)/gi, '<\\/$1')
      .replace(/<!--/g, '<\\!--');
    return '<script>' + body + '<\/script>';
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

  // 卡片文档：原生已拆好就直接用，收到整份文档时才自己拆（拆前先修非法标签，DOMParser 会把它们转义成文本）
  function cardParts(desc) {
    var html = String(desc.html == null ? '' : desc.html);
    if (desc.style != null || desc.head != null) {
      return { head: String(desc.head || ''), body: html, style: String(desc.style || ''), prepared: true };
    }
    var parts = splitCardDoc(decodeEscapedInlineTags(normalizeCharaTags(html)));
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
      inlineScript(buildCardBridge(ctx.messageId, ctx.cardIndex, ctx.charName, ctx.userName)) +
      inlineScript(CARD_FORM_GUARD) +
      inlineScript(CARD_HEIGHT_SCRIPT) +
      ownHtml +
      escapeScriptBodies(parts.prepared ? parts.head : '') +
      '</head><body class="rikka-card-body">' +
      body + '</body></html>';
  }

  function cardFrame(key, desc) {
    var frame = document.createElement('iframe');
    frame.className = 'card-frame';
    frame.dataset.cardKey = key;
    frame.setAttribute('frameborder', '0');
    // 起始高度给一个保守值，装上后用内容实测高度覆盖
    frame.style.height = '80px';
    var load = function (target) {
      var html = cardDocHtml(target, target.ctx || {});
      frame.dataset.cardDoc = html;
      // 可选：blob URL 便于调试；默认 srcdoc 同步解析且不产生导航，卡片脚本只执行一次
      if (state.helper && state.helper.useBlobUrl) {
        try {
          if (frame._blobUrl) URL.revokeObjectURL(frame._blobUrl);
          frame._blobUrl = URL.createObjectURL(new Blob([html], { type: 'text/html' }));
          frame.removeAttribute('srcdoc');
          frame.src = frame._blobUrl;
          return;
        } catch (e) { /* 退化回 srcdoc */ }
      }
      frame.removeAttribute('src');
      frame.srcdoc = html;
    };
    frame._ripple = function (nextKey, nextDesc) {
      if (nextKey) frame.dataset.cardKey = nextKey;
      load(nextDesc || desc);
    };
    frame._write = function () { load(desc); };
    load(desc);
    return frame;
  }

  // —— 消息正文：把一条消息摊平成有序块序列，再与现有 DOM 逐位对齐 ——
  function blocksOf(msg, streaming) {
    var helper = state.helper || {};
    // 渲染器关闭，或流式期间未允许流式渲染时，前端卡退化成普通 html 代码块
    var cardsAsText = helper.renderEnabled === false || (streaming === true && helper.allowStreaming !== true);
    var desc = [];
    if (msg.reasoning && state.appearance.showThinking !== false) {
      desc.push({ kind: 'reasoning', text: String(msg.reasoning), seconds: Number(msg.reasoningSeconds) || 0 });
    }
    var blocks = msg.blocks || [];
    if (!blocks.length && String(msg.text || '').trim()) blocks = [{ type: 'text', text: msg.text }];
    blocks.forEach(function (block) {
      if (!block) return;
      if (block.type === 'card') {
        if (cardsAsText) {
          desc.push({ kind: 'text', text: '```html\n' + (block.html == null ? '' : String(block.html)) + '\n```' });
          return;
        }
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
        // text 是原生给的中文工具名，参数与结果也一起带上
        desc.push({
          kind: 'tool',
          text: block.text == null ? '' : String(block.text),
          name: block.name == null ? '' : String(block.name),
          running: block.running === true,
          input: block.input == null ? '' : String(block.input),
          output: block.output == null ? '' : String(block.output),
        });
      } else if (block.type === 'text' && String(block.text || '').trim()) {
        desc.push({ kind: 'text', text: String(block.text) });
      }
    });
    if (msg.translation) desc.push({ kind: 'translation', text: String(msg.translation), translating: msg.translating === true });
    return groupThinkSteps(desc);
  }

  // 原生把连续的思考与工具调用收进同一张思考卡，页面照同样的分组来
  function groupThinkSteps(list) {
    var out = [];
    var steps = null;
    function flush() {
      if (steps) { out.push({ kind: 'think', steps: steps }); steps = null; }
    }
    list.forEach(function (item) {
      if (item.kind === 'reasoning' || item.kind === 'tool') {
        if (!steps) steps = [];
        steps.push(item);
      } else {
        flush();
        out.push(item);
      }
    });
    flush();
    return out;
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
      // 图挂了也要留一块可见占位，别缩成 0 宽
      img.addEventListener('error', function () { img.classList.add('failed'); });
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
      collapse: collapseMode(),
      colors: textColors()
    });
  }

  // 代码折叠模式：helper 三态优先，未设置时回退到外观布尔开关
  function collapseMode() {
    var mode = state.helper && state.helper.codeCollapse;
    if (mode === 'all') return true;
    if (mode === 'none') return false;
    if (mode === 'frontend_only') return 'frontend';
    return (state.appearance || {}).codeCollapse === true;
  }

  // 宏替换：酒馆助手设置关掉时原样返回，不再请求原生展开 {{...}}
  function macroSubstitute(text) {
    var value = text == null ? '' : String(text);
    if (state.helper && state.helper.macroEnabled === false) return value;
    value = expandVariableMacros(value);
    value = expandRegisteredMacros(value);
    return window.webui.request('substituteMacros', value);
  }

  function unregisterMacroLike(regex) {
    registeredMacros = registeredMacros.filter(function (e) { return e.regex !== regex; });
  }

  // 忽略以 $ 开头的键（酒馆助手约定：$ 前缀不发给 AI）
  function stripDollar(value) {
    if (Array.isArray(value)) return value.map(stripDollar);
    if (value && typeof value === 'object') {
      var out = {};
      Object.keys(value).forEach(function (k) {
        if (k.charAt(0) === '$') return;
        out[k] = stripDollar(value[k]);
      });
      return out;
    }
    return value;
  }

  function yamlScalar(value, quoted) {
    if (value == null) return 'null';
    if (typeof value === 'number' || typeof value === 'boolean') return String(value);
    var s = String(value);
    if (quoted) return JSON.stringify(s);
    if (s === '' || /[:#\-?\[\]{}&*!|>'"%@`]/.test(s) || /^\s|\s$/.test(s) ||
      /^(true|false|null|~|-?\d+)$/.test(s)) {
      return JSON.stringify(s);
    }
    return s;
  }

  // 极简 YAML 序列化，够酒馆助手变量宏用
  function toYaml(value, indent, quoted) {
    indent = indent || '';
    if (value == null || typeof value !== 'object') return yamlScalar(value, quoted);
    if (Array.isArray(value)) {
      if (!value.length) return '[]';
      return '\n' + value.map(function (v) {
        if (v && typeof v === 'object') {
          var inner = toYaml(v, indent + '  ', quoted);
          return indent + '-' + (inner.charAt(0) === '\n' ? inner : ' ' + inner);
        }
        return indent + '- ' + yamlScalar(v, quoted);
      }).join('\n');
    }
    var keys = Object.keys(value);
    if (!keys.length) return '{}';
    return '\n' + keys.map(function (k) {
      var v = value[k];
      if (v && typeof v === 'object') {
        var inner = toYaml(v, indent + '  ', quoted);
        return indent + yamlScalar(k) + ':' + (inner.charAt(0) === '\n' ? inner : ' ' + inner);
      }
      return indent + yamlScalar(k) + ': ' + yamlScalar(v, quoted);
    }).join('\n');
  }

  var VARIABLE_MACRO_RE =
    /\{\{\s*(get|format)_(global|preset|character|chat|message)_variable(_quoted)?::([^}\n]*)\}\}/g;

  // 酒馆助手内置变量宏：从页面变量域取值（聊天宏对应 chat=local）
  function expandVariableMacros(text) {
    if (text.indexOf('_variable::') < 0) return text;
    return text.replace(VARIABLE_MACRO_RE, function (full, mode, kind, quoted, path) {
      var scope = kind === 'chat' ? 'local' : kind;
      var value = deepGet(scopeStore(scope) || {}, String(path).trim());
      if (value === undefined) return full;
      var clean = stripDollar(value);
      if (mode === 'get') return JSON.stringify(clean);
      return toYaml(clean, '', quoted === '_quoted').replace(/^\n+/, '').replace(/\s+$/, '');
    });
  }

  function expandRegisteredMacros(text) {
    registeredMacros.forEach(function (entry) {
      try {
        text = text.replace(entry.regex, function (match) {
          var rest = Array.prototype.slice.call(arguments, 1);
          var captures = rest.slice(0, rest.length - 2);
          return entry.replace({}, match, captures[0], captures[1], captures[2]);
        });
      } catch (e) { /* 单个宏失败不影响其余 */ }
    });
    return text;
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
    var cm = collapseMode();
    return String(text) + '\u0000' + (a.lineNumbers === true ? 'n' : '') + (cm === true ? 'c' : (cm === 'frontend' ? 'f' : '')) +
      '\u0000' + (c ? (c.quote || '') + '|' + (c.bracket || '') : '-');
  }

  var REASONING_ICON = '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="M12 3a6 6 0 0 0-3.4 10.94c.5.36.8.94.8 1.56V17h5.2v-1.5c0-.62.3-1.2.8-1.56A6 6 0 0 0 12 3Z"></path>' +
    '<path d="M9.6 20h4.8"></path></svg>';
  var ARROW_ICON = '<svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="m6 9 6 6 6-6"></path></svg>';
  var TOOL_ICON = '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="M14.7 6.3a4 4 0 0 0 5 5L21 21H3l1.3-9.7a4 4 0 0 0 5-5Z"></path></svg>';
  // 对齐原生 ToolUIRenderer：搜索用放大镜、网页用地球
  var SEARCH_ICON = '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<circle cx="11" cy="11" r="7"></circle><path d="m20 20-3.5-3.5"></path></svg>';
  var GLOBE_ICON = '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<circle cx="12" cy="12" r="9"></circle><path d="M3 12h18"></path>' +
    '<path d="M12 3c2.5 2.4 3.8 5.6 3.8 9s-1.3 6.6-3.8 9c-2.5-2.4-3.8-5.6-3.8-9S9.5 5.4 12 3Z"></path></svg>';

  function safeJson(text) {
    try { return JSON.parse(String(text == null ? '' : text)); } catch (e) { return null; }
  }

  // 工具步骤图标：按工具名挑，未登记的原生也走通用图标
  function toolIcon(step) {
    var n = String(step.name || '').toLowerCase();
    if (n.indexOf('search') >= 0) return SEARCH_ICON;
    if (n === 'scrape_web' || n === 'web_fetch' || n === 'open_url' || n === 'download_file') return GLOBE_ICON;
    return TOOL_ICON;
  }

  // 步骤标题：网页类对齐原生 ToolUIRenderer 的标题（搜索带查询词），其余用中文工具名
  function toolLabel(step) {
    var n = String(step.name || '').toLowerCase();
    var args = safeJson(step.input) || {};
    if (n === 'search_web' || n === 'web_search' || n === 'search') {
      return '搜索网页: ' + (args.query || '');
    }
    if (n === 'scrape_web') return '抓取网页';
    return step.text || '工具调用';
  }

  // 步骤右侧附注：搜索给结果数、网页类给域名（对齐原生 Summary）
  function toolExtra(step) {
    var n = String(step.name || '').toLowerCase();
    if (n.indexOf('search') >= 0) {
      var out = safeJson(step.output);
      var items = out && out.items;
      return items && items.length ? ('共 ' + items.length + ' 条结果') : '';
    }
    if (n === 'scrape_web' || n === 'web_fetch' || n === 'open_url' || n === 'download_file') {
      var url = (safeJson(step.input) || {}).url || '';
      if (!url) return '';
      try { return new URL(url).host; } catch (e) { return url; }
    }
    return '';
  }

  function formatSeconds(value) {
    return Number(value || 0).toFixed(1);
  }

  // 工具卡：中文名与参数、结果都由原生给，页面只管摆位置
  function prettyJson(text) {
    var raw = String(text == null ? '' : text);
    try { return JSON.stringify(JSON.parse(raw), null, 2); } catch (e) { return raw; }
  }

  function toolSection(cap, text) {
    var box = document.createElement('div');
    box.className = 'tool-section';
    var head = document.createElement('div');
    head.className = 'tool-cap';
    head.textContent = cap;
    var val = document.createElement('div');
    val.className = 'tool-val';
    val.textContent = text;
    box.appendChild(head);
    box.appendChild(val);
    return box;
  }

  // 思考卡：对齐原生 ChainOfThought——步骤过多时顶部给「再显示 N 步 / 收起」，折叠只留末尾两步
  function thinkKey(desc) {
    return (desc.steps || []).map(function (s) {
      return s.kind === 'reasoning'
        ? 'r\u0000' + s.text + '\u0000' + (s.seconds || 0)
        : 't\u0000' + s.text + '\u0000' + (s.running === true ? '1' : '0') + '\u0000' + (s.input || '');
    }).join('\u0001');
  }

  // 推理步骤内容默认展开态：流式按 showThinkingContent 预览，落定后按 autoCloseThinking
  function reasoningExpanded(step, streaming) {
    if (!step || step.kind !== 'reasoning') return false;
    if (streaming) return state.appearance.showThinking !== false;
    return state.appearance.autoCloseThinking === false;
  }

  function updateThinkStep(node, step) {
    var head = node.querySelector('.think-step-head');
    if (!head) return;
    if (step.kind === 'reasoning') {
      var label = head.querySelector('.step-label');
      if (label) label.textContent = '思考了 ' + formatSeconds(step.seconds) + ' 秒';
      if (node._text !== step.text) {
        node._text = step.text;
        var text = node.querySelector('.think-text');
        if (text) text.textContent = step.text;
      }
      return;
    }
    head.classList.toggle('running', step.running === true);
    var label = head.querySelector('.step-label');
    if (label) label.textContent = toolLabel(step);
    var extra = head.querySelector('.step-extra');
    if (extra) extra.textContent = toolExtra(step);
    var key = (step.input || '') + '\u0000' + (step.output || '');
    if (node._body !== key) {
      node._body = key;
      var body = node.querySelector('.think-step-body');
      body.textContent = '';
      if (step.input) body.appendChild(toolSection('参数', prettyJson(step.input)));
      if (step.output) body.appendChild(toolSection('结果', prettyJson(step.output)));
    }
  }

  function thinkStepNode(step, expanded) {
    var box = document.createElement('div');
    box.className = 'think-step';
    box.dataset.stepKind = step.kind;
    if (step.kind === 'reasoning') {
      box.innerHTML = '<div class="think-step-head">' + REASONING_ICON +
        '<span class="step-label"></span>' + ARROW_ICON + '</div>' +
        '<div class="think-step-body"><div class="think-text"></div></div>';
    } else {
      box.innerHTML = '<div class="think-step-head">' + toolIcon(step) +
        '<span class="ico tool-dot"><i></i><i></i><i></i></span>' +
        '<span class="step-label"></span><span class="step-extra"></span>' + ARROW_ICON + '</div>' +
        '<div class="think-step-body"></div>';
      // 没有参数也没有结果的步骤不可展开
      if (!step.input && !step.output) box.classList.add('no-content');
    }
    box._expanded = expanded === true;
    box.classList.toggle('expanded', box._expanded);
    box.querySelector('.think-step-head').addEventListener('click', function () {
      if (box.classList.contains('no-content')) return;
      box._expanded = !box._expanded;
      box.classList.toggle('expanded', box._expanded);
    });
    updateThinkStep(box, step);
    return box;
  }

  function paintThink(node, desc, streaming) {
    node._desc = desc;
    var steps = desc.steps || [];
    var canCollapse = steps.length > 2;
    var showAll = !!node._expanded || !canCollapse;
    var start = showAll ? 0 : steps.length - 2;
    var visible = steps.slice(start);
    var running = steps.some(function (s) { return s.kind === 'tool' && s.running === true; });
    var more = node.querySelector('.think-more');
    if (more) {
      more.hidden = !canCollapse;
      more.classList.toggle('expanded', !!node._expanded);
      var moreLabel = more.querySelector('.think-more-label');
      if (moreLabel) moreLabel.textContent = node._expanded ? '收起' : ('再显示 ' + (steps.length - 2) + ' 步');
    }
    var body = node.querySelector('.think-body');
    if (!body) { node._text = thinkKey(desc); return; }
    for (var i = 0; i < visible.length; i++) {
      var fullIdx = start + i;
      var cur = body.children[i];
      if (cur && cur._stepIndex === fullIdx && cur.dataset.stepKind === visible[i].kind) {
        updateThinkStep(cur, visible[i]);
      } else {
        var fresh = thinkStepNode(visible[i], reasoningExpanded(visible[i], streaming));
        fresh._stepIndex = fullIdx;
        if (cur) body.replaceChild(fresh, cur); else body.appendChild(fresh);
      }
    }
    for (var j = body.children.length - 1; j >= visible.length; j--) body.removeChild(body.children[j]);
    body.classList.toggle('running', running);
    node._text = thinkKey(desc);
  }

  function makeBlockNode(desc, streaming) {
    if (desc.kind === 'attach') {
      var attach = attachmentNode(desc.block);
      attach.dataset.kind = 'attach';
      attach.dataset.attachKey = desc.key;
      return attach;
    }
    if (desc.kind === 'think') {
      var card = document.createElement('div');
      card.className = 'blk blk-think';
      card.dataset.kind = 'think';
      card._streaming = streaming;
      // 流式中全部铺开；落定后若开自动收起则折回末尾两步
      card._expanded = streaming ? true : (state.appearance.autoCloseThinking === false);
      card.innerHTML = '<button type="button" class="think-more" hidden>' + ARROW_ICON +
        '<span class="think-more-label"></span></button>' +
        '<div class="think-body"></div>';
      card.querySelector('.think-more').addEventListener('click', function () {
        card._expanded = !card._expanded;
        paintThink(card, card._desc, card._streaming);
        followBottom();
      });
      paintThink(card, desc, streaming);
      return card;
    }
    if (desc.kind === 'card') {
      var node = document.createElement('div');
      node.className = 'blk card';
      node.dataset.kind = 'card';
      node.dataset.cardKey = desc.key;
      // 流式帧只铺文本与光标，不装配卡片：半截 HTML 会渲染成残缺网页
      if (!streaming) {
        var cardEl = cardFrame(desc.key, desc);
        node.appendChild(cardEl);
        if (typeof cardEl._write === 'function') cardEl._write();
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
      tr.innerHTML = '<div class="tr-rule"></div>' +
        '<div class="tr-head"><span class="tr-title">' + TR_ICON +
        '<span class="tr-label"></span></span>' +
        '<button type="button" class="tr-toggle" aria-label="toggle">' + TR_ARROW + '</button></div>' +
        '<div class="tr-card"></div>';
      tr.querySelector('.tr-label').textContent = String((state.labels || {}).translation || '');
      tr.querySelector('.tr-toggle').addEventListener('click', function () { tr.classList.toggle('collapsed'); });
      fillTranslation(tr, desc);
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
    if (desc.kind === 'think') {
      var changed = node._text !== thinkKey(desc);
      var streamChanged = node._streaming !== streaming;
      if (changed || streamChanged) {
        node._streaming = streaming;
        if (streamChanged) {
          // 流式结束：整卡与推理内容都按 autoCloseThinking 折起
          node._expanded = !(!streaming && state.appearance.autoCloseThinking !== false);
          var body = node.querySelector('.think-body');
          if (body) body.textContent = '';
        }
        paintThink(node, desc, streaming);
      }
      return node;
    }
    if (desc.kind === 'translation') {
      var trKey = renderKey(desc.text);
      if (node._text !== trKey) {
        node._text = trKey;
        fillTranslation(node, desc);
      }
      return node;
    }
    if (desc.kind === 'text') {
      // 文本变了才重设 innerHTML，避免每帧重排导致滚动跳动
      var key = renderKey(desc.text);
      if (node._text !== key) {
        node._text = key;
        node.innerHTML = renderMarkdown(desc.text);
      }
      node.classList.toggle('typing', !!streaming);
      return node;
    }
    if (node.textContent !== desc.text) node.textContent = desc.text;
    return node;
  }

  // 把块序列铺进 body：同 kind（卡片还要求 key 相同）的节点原地复用
  function patchBody(body, msg, streaming) {
    var want = blocksOf(msg, streaming);
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
    return (Array.from(text)[0] || '?').toUpperCase();
  }

  function safeUrl(url) {
    var u = String(url == null ? '' : url).trim();
    if (!u) return '';
    if (/^(https?:|data:image\/|file:|content:)/i.test(u)) return u;
    if (/^[./#]/.test(u)) return u;
    return '';
  }

  // 头像来源由原生决定（助手头像 / 模型图标 / 用户头像 / 首字），页面只按描述画
  function avatarSpec(msg) {
    var spec = msg.avatar;
    if (spec && typeof spec === 'object') return spec;
    var url = safeUrl(spec);
    return { kind: url ? 'image' : 'initials', value: url };
  }

  function avatarKey(msg) {
    var s = avatarSpec(msg);
    var appearance = state.appearance || {};
    return [s.kind, s.value, s.from, s.to, msg.name,
      appearance.userAvatarFrame, appearance.botAvatarFrame, appearance.avatarFrameScale].join('\u0000');
  }

  function avatarNode(msg) {
    var spec = avatarSpec(msg);
    if (spec.kind === 'none') return null;
    var avatar = document.createElement('div');
    avatar.className = 'avatar';
    avatar.dataset.kind = spec.kind;
    avatar._key = avatarKey(msg);
    // 内容装在 .avatar-inner 里：形状自转时这一层反向转回来
    var inner = document.createElement('span');
    inner.className = 'avatar-inner';
    if (spec.kind === 'image') {
      var img = document.createElement('img');
      img.alt = '';
      img.src = safeUrl(spec.value);
      // 图片挂了就退回名字首字，保持行高稳定
      img.addEventListener('error', function () {
        if (img.parentNode === inner) {
          inner.removeChild(img);
          inner.textContent = initials(msg.name);
        }
      });
      inner.appendChild(img);
    } else if (spec.kind === 'emoji') {
      inner.textContent = String(spec.value == null ? '' : spec.value);
    } else if (spec.kind === 'dummy') {
      avatar.style.setProperty('--avatar-from', String(spec.from || 'transparent'));
      avatar.style.setProperty('--avatar-to', String(spec.to || 'transparent'));
    } else if (spec.kind === 'svg') {
      inner.innerHTML = String(spec.value == null ? '' : spec.value);
    } else {
      inner.textContent = initials(spec.value || msg.name);
    }
    avatar.appendChild(inner);
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

  var OPS_ICON = {
    copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h8"/>',
    regenerate: '<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 5v6h-6"/>',
    speak: '<path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="M16 9a4 4 0 0 1 0 6"/><path d="M19 6a8 8 0 0 1 0 12"/>',
    speakStop: '<circle cx="12" cy="12" r="8"/><rect x="9" y="9" width="6" height="6" rx="1"/>',
    edit: '<path d="M4 20h4l10.5-10.5a2.1 2.1 0 0 0-3-3L5 17v3Z"/>',
    translate: '<path d="M4 6h9"/><path d="M8 4v2c0 4-2 7-4 8"/><path d="M6.5 10c1 2.5 3 4.5 5.5 5.5"/><path d="m13 20 4-9 4 9"/><path d="M14.5 17h5"/>',
    menu: '<circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/>',
  };
  // 与原生消息操作排同序：复制、重试、朗读、翻译、更多（编辑等收进「更多」面板）
  var OPS_USER = [['copy', '复制'], ['regenerate', '重试'], ['menu', '菜单']];
  var OPS_ASSISTANT = [['copy', '复制'], ['regenerate', '重试'], ['speak', '朗读'], ['translate', '翻译'], ['menu', '菜单']];
  function opsFor(msg) {
    var list = msg.role === 'user' ? OPS_USER : OPS_ASSISTANT;
    return list.map(function (it) { return { action: it[0], label: it[1], icon: OPS_ICON[it[0]] || '' }; });
  }

  var TR_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<circle cx="12" cy="12" r="9"></circle><path d="M3 12h18"></path>' +
    '<path d="M12 3c2.5 2.6 3.8 5.6 3.8 9S14.5 18.4 12 21c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3Z"></path></svg>';
  var TR_ARROW = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="m6 15 6-6 6 6"></path></svg>';

  function fillTranslation(node, desc) {
    var card = node.querySelector('.tr-card');
    if (!card) return;
    card.innerHTML = '';
    if (desc.translating) {
      var row = document.createElement('div');
      row.className = 'tr-loading';
      var spin = document.createElement('span');
      spin.className = 'tr-spinner';
      var text = document.createElement('span');
      text.textContent = String(desc.text);
      row.appendChild(spin);
      row.appendChild(text);
      card.appendChild(row);
    } else {
      card.innerHTML = renderMarkdown(desc.text);
    }
    node._text = renderKey(desc.text);
  }

  function opIcon(name) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (OPS_ICON[name] || '') + '</svg>';
  }

  // 朗读中与原生一致：图标换停止、底色走 secondaryContainer
  function paintSpeakButtons() {
    var nodes = list.querySelectorAll('.op[data-action="speak"]');
    var on = state.speaking === true;
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].innerHTML = opIcon(on ? 'speakStop' : 'speak');
      nodes[i].classList.toggle('on', on);
      nodes[i].disabled = state.ttsAvailable === false;
    }
  }

  function buildOps(row, msg) {
    var ops = document.createElement('div');
    ops.className = 'ops';
    ops.dataset.kind = 'ops';
    opsFor(msg).forEach(function (def) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'op';
      btn.dataset.action = def.action;
      btn.setAttribute('aria-label', def.label);
      btn.setAttribute('title', def.label);
      if (def.action === 'speak') {
        var on = state.speaking === true;
        btn.innerHTML = opIcon(on ? 'speakStop' : 'speak');
        btn.classList.toggle('on', on);
        btn.disabled = state.ttsAvailable === false;
      } else {
        btn.innerHTML = opIcon(def.action);
        if (def.action === 'favorite') btn.classList.toggle('on', msg.favorited === true);
      }
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
  // 原生把分支条排在操作排里、时间之前，这里跟着放，避免多出一行
  function syncSwipes(col, msg) {
    if (!col) return;
    var ops = col.querySelector('.ops');
    var host = ops && !ops.hidden ? ops : col;
    // 查找范围始终是整行：流式时它挂在行上，落定后要挪进操作排
    var barNode = col.querySelector('.swipes');
    var count = Number(msg.swipeCount) || 0;
    var nodeId = msg.nodeId == null ? '' : String(msg.nodeId);
    if (count < 2 || !nodeId) {
      if (barNode && barNode.parentNode) barNode.parentNode.removeChild(barNode);
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
    }
    // 流式期间操作排整块隐藏，分支条先挂在行上；落定后要跟着挪回操作排里
    var before = host === ops ? ops.querySelector('.time') : null;
    if (barNode.parentNode !== host || (before && barNode.nextSibling !== before)) {
      if (before) ops.insertBefore(barNode, before); else host.appendChild(barNode);
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
      var userAvatar = avatarNode(msg);
      if (userAvatar) head.appendChild(userAvatar);
      var label = document.createElement('span');
      label.className = 'head-name';
      label.textContent = msg.name == null ? '' : String(msg.name);
      head.appendChild(label);
      // 用户侧整行由 showUserAvatar 控制
      if (state.appearance.showUserAvatar === false) head.hidden = true;
    } else {
      var aiAvatar = avatarNode(msg);
      if (aiAvatar) head.appendChild(aiAvatar);
      var model = document.createElement('span');
      model.className = 'head-name';
      model.textContent = msg.name == null ? '' : String(msg.name);
      head.appendChild(model);
      if (aiAvatar && state.appearance.showModelIcon === false) aiAvatar.hidden = true;
      if (state.appearance.showModelName === false) model.hidden = true;
    }
    return head;
  }

  function renderRow(msg, streaming) {
    var row = document.createElement('div');
    row.className = 'row enter ' + (msg.role === 'user' ? 'user' : 'ai');
    row.dataset.id = String(msg.id);
    row.dataset.role = msg.role === 'user' ? 'user' : 'ai';
    row.dataset.signature = signatureOf(msg);
    row.dataset.settled = streaming ? '' : '1';
    row.appendChild(headNode(msg));

    var col = document.createElement('div');
    col.className = 'col';

    var body = document.createElement('div');
    body.className = 'body';
    patchBody(body, msg, !!streaming);
    col.appendChild(body);

    var ops = buildOps(row, msg);
    ops.hidden = streaming === true;
    col.appendChild(ops);

    var nerd = document.createElement('div');
    nerd.className = 'nerd';
    syncNerd(nerd, msg);
    col.appendChild(nerd);

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
      if (nameNode && nameNode.textContent !== nameText) nameNode.textContent = nameText;
      if (avatarEl && avatarEl._key !== avatarKey(msg)) {
        var freshAvatar = avatarNode(msg);
        if (freshAvatar) head.replaceChild(freshAvatar, avatarEl);
        else head.removeChild(avatarEl);
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
    row.dataset.settled = streaming ? '' : '1';
  }

  // 流式光标只在助手消息上：用户刚发完时最后一条是用户消息，那种情况不打光标
  function applyTyping(row, msg, streaming) {
    if (!row) return;
    var body = row.querySelector('.body');
    if (!body) return;
    var bubbles = body.querySelectorAll('.blk.bubble');
    var isAssistant = msg.role !== 'user';
    var live = !!streaming && isAssistant;
    // 只看正文气泡：思考块与工具块里有字不代表正文已经开了头
    var tail = live && bubbles.length ? bubbles[bubbles.length - 1] : null;
    var text = tail ? String(tail.textContent || '').trim() : '';
    for (var i = 0; i < bubbles.length; i++) {
      bubbles[i].classList.toggle('typing', live && i === bubbles.length - 1 && !!text);
    }
    // 正文还没落字：圆点摆到块序列末尾（原生就是思考卡下方的生成指示），不跟光标同时出现
    var host = live ? (tail || body) : null;
    var dots = body.querySelector('.gen-dots');
    if (host && !text) {
      if (!dots) {
        dots = document.createElement('span');
        dots.className = 'gen-dots';
        dots.innerHTML = '<i></i><i></i><i></i>';
      }
      if (dots.parentNode !== host) host.appendChild(dots);
    } else if (dots) {
      dots.remove();
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

  // 正在生成的那条：原生说没在生成就一律不认，指示不会被残留的 streamId 拖着不灭
  function streamingRowId() {
    return generating ? streamId : null;
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
    var liveId = streamingRowId();
    var rows = state.messages.map(function (msg) {
      var id = String(msg.id);
      var streaming = liveId === id && msg.role !== 'user';
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
    var liveId = streamingRowId();
    for (var i = 0; i < list.children.length; i++) {
      var row = list.children[i];
      if (!row.dataset || row.dataset.id === undefined) continue;
      var msg = messageById(row.dataset.id);
      if (!msg) continue;
      var streaming = liveId === String(msg.id) && msg.role !== 'user';
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

  // —— 前端插件运行环境：页面级 TavernHelper 与事件总线 ——
  var TAVERN_EVENTS = {
    APP_READY: 'app_ready',
    EXTRAS_CONNECTED: 'extras_connected',
    MESSAGE_SWIPED: 'message_swiped',
    MESSAGE_SENT: 'message_sent',
    MESSAGE_RECEIVED: 'message_received',
    MESSAGE_EDITED: 'message_edited',
    MESSAGE_DELETED: 'message_deleted',
    MESSAGE_UPDATED: 'message_updated',
    MESSAGE_FILE_EMBEDDED: 'message_file_embedded',
    MESSAGE_REASONING_EDITED: 'message_reasoning_edited',
    MESSAGE_REASONING_DELETED: 'message_reasoning_deleted',
    MESSAGE_SWIPE_DELETED: 'message_swipe_deleted',
    MORE_MESSAGES_LOADED: 'more_messages_loaded',
    IMPERSONATE_READY: 'impersonate_ready',
    CHAT_CHANGED: 'chat_id_changed',
    GENERATION_AFTER_COMMANDS: 'GENERATION_AFTER_COMMANDS',
    GENERATION_STARTED: 'generation_started',
    GENERATION_STOPPED: 'generation_stopped',
    GENERATION_ENDED: 'generation_ended',
    SD_PROMPT_PROCESSING: 'sd_prompt_processing',
    EXTENSIONS_FIRST_LOAD: 'extensions_first_load',
    EXTENSION_SETTINGS_LOADED: 'extension_settings_loaded',
    SETTINGS_LOADED: 'settings_loaded',
    SETTINGS_UPDATED: 'settings_updated',
    MOVABLE_PANELS_RESET: 'movable_panels_reset',
    SETTINGS_LOADED_BEFORE: 'settings_loaded_before',
    SETTINGS_LOADED_AFTER: 'settings_loaded_after',
    CHATCOMPLETION_SOURCE_CHANGED: 'chatcompletion_source_changed',
    CHATCOMPLETION_MODEL_CHANGED: 'chatcompletion_model_changed',
    OAI_PRESET_CHANGED_BEFORE: 'oai_preset_changed_before',
    OAI_PRESET_CHANGED_AFTER: 'oai_preset_changed_after',
    OAI_PRESET_EXPORT_READY: 'oai_preset_export_ready',
    OAI_PRESET_IMPORT_READY: 'oai_preset_import_ready',
    WORLDINFO_SETTINGS_UPDATED: 'worldinfo_settings_updated',
    WORLDINFO_UPDATED: 'worldinfo_updated',
    CHARACTER_EDITOR_OPENED: 'character_editor_opened',
    CHARACTER_EDITED: 'character_edited',
    CHARACTER_PAGE_LOADED: 'character_page_loaded',
    USER_MESSAGE_RENDERED: 'user_message_rendered',
    CHARACTER_MESSAGE_RENDERED: 'character_message_rendered',
    FORCE_SET_BACKGROUND: 'force_set_background',
    CHAT_DELETED: 'chat_deleted',
    CHAT_CREATED: 'chat_created',
    GENERATE_BEFORE_COMBINE_PROMPTS: 'generate_before_combine_prompts',
    GENERATE_AFTER_COMBINE_PROMPTS: 'generate_after_combine_prompts',
    GENERATE_AFTER_DATA: 'generate_after_data',
    WORLD_INFO_ACTIVATED: 'world_info_activated',
    TEXT_COMPLETION_SETTINGS_READY: 'text_completion_settings_ready',
    CHAT_COMPLETION_SETTINGS_READY: 'chat_completion_settings_ready',
    CHAT_COMPLETION_PROMPT_READY: 'chat_completion_prompt_ready',
    CHARACTER_FIRST_MESSAGE_SELECTED: 'character_first_message_selected',
    CHARACTER_DELETED: 'characterDeleted',
    CHARACTER_DUPLICATED: 'character_duplicated',
    CHARACTER_RENAMED: 'character_renamed',
    CHARACTER_RENAMED_IN_PAST_CHAT: 'character_renamed_in_past_chat',
    SMOOTH_STREAM_TOKEN_RECEIVED: 'stream_token_received',
    STREAM_TOKEN_RECEIVED: 'stream_token_received',
    STREAM_REASONING_DONE: 'stream_reasoning_done',
    FILE_ATTACHMENT_DELETED: 'file_attachment_deleted',
    WORLDINFO_FORCE_ACTIVATE: 'worldinfo_force_activate',
    OPEN_CHARACTER_LIBRARY: 'open_character_library',
    ONLINE_STATUS_CHANGED: 'online_status_changed',
    IMAGE_SWIPED: 'image_swiped',
    CONNECTION_PROFILE_LOADED: 'connection_profile_loaded',
    CONNECTION_PROFILE_CREATED: 'connection_profile_created',
    CONNECTION_PROFILE_DELETED: 'connection_profile_deleted',
    CONNECTION_PROFILE_UPDATED: 'connection_profile_updated',
    TOOL_CALLS_PERFORMED: 'tool_calls_performed',
    TOOL_CALLS_RENDERED: 'tool_calls_rendered',
    CHARACTER_MANAGEMENT_DROPDOWN: 'charManagementDropdown',
    SECRET_WRITTEN: 'secret_written',
    SECRET_DELETED: 'secret_deleted',
    SECRET_ROTATED: 'secret_rotated',
    SECRET_EDITED: 'secret_edited',
    PRESET_CHANGED: 'preset_changed',
    PRESET_DELETED: 'preset_deleted',
    PRESET_RENAMED: 'preset_renamed',
    PRESET_RENAMED_BEFORE: 'preset_renamed_before',
    MAIN_API_CHANGED: 'main_api_changed',
    WORLDINFO_ENTRIES_LOADED: 'worldinfo_entries_loaded',
    WORLDINFO_SCAN_DONE: 'worldinfo_scan_done',
    MEDIA_ATTACHMENT_DELETED: 'media_attachment_deleted',
  };
  var IFRAME_EVENTS = {
    MESSAGE_IFRAME_RENDER_STARTED: 'message_iframe_render_started',
    MESSAGE_IFRAME_RENDER_ENDED: 'message_iframe_render_ended',
    GENERATION_STARTED: 'js_generation_started',
    GENERATION_REQUESTED: 'js_generation_requested',
    GENERATION_ENDED: 'js_generation_ended',
    STREAM_TOKEN_RECEIVED_FULLY: 'js_stream_token_received_fully',
    STREAM_TOKEN_RECEIVED_INCREMENTALLY: 'js_stream_token_received_incrementally',
  };

  var pageListeners = {};

  function onPageEvent(name, listener) {
    if (typeof listener !== 'function' || !name) return listener;
    (pageListeners[name] = pageListeners[name] || []).push(listener);
    return listener;
  }

  function offPageEvent(name, listener) {
    var arr = pageListeners[name];
    if (!arr) return;
    if (!listener) { delete pageListeners[name]; return; }
    pageListeners[name] = arr.filter(function (fn) { return fn !== listener; });
  }

  function emitPageEvent(name, args) {
    var arr = pageListeners[name];
    if (!arr || !arr.length) return;
    var snapshot = arr.slice();
    for (var i = 0; i < snapshot.length; i++) {
      try { snapshot[i].apply(window, args || []); } catch (e) { /* 单个监听器失败不影响其余 */ }
    }
  }

  // eventOn 系列返回句柄：既是原监听器（可传给 eventRemoveListener），也带 stop()
  function makeEventHandle(name, listener) {
    try { listener.stop = function () { offPageEvent(name, listener); }; } catch (e) { /* 忽略 */ }
    return listener;
  }

  // 原生事件名（大写）→ 酒馆事件名（大写）→ 渲染别名
  var EVENT_ALIAS = {
    GENERATION_STARTED: TAVERN_EVENTS.GENERATION_STARTED,
    GENERATION_ENDED: TAVERN_EVENTS.GENERATION_ENDED,
    MESSAGE_RECEIVED: TAVERN_EVENTS.MESSAGE_RECEIVED,
    MESSAGE_SENT: TAVERN_EVENTS.MESSAGE_SENT,
    MESSAGE_UPDATED: TAVERN_EVENTS.MESSAGE_UPDATED,
    MESSAGE_DELETED: TAVERN_EVENTS.MESSAGE_DELETED,
    // 重 roll / 切分支与编辑走各自的事件名：卡脚本靠它们重算状态栏，
    // 只发 message_updated 时这类卡收不到，界面会停在上一轮的状态
    MESSAGE_SWIPED: TAVERN_EVENTS.MESSAGE_SWIPED,
    MESSAGE_EDITED: TAVERN_EVENTS.MESSAGE_EDITED,
    CHAT_CHANGED: TAVERN_EVENTS.CHAT_CHANGED,
  };
  var RENDER_ALIAS = {
    message_received: TAVERN_EVENTS.CHARACTER_MESSAGE_RENDERED,
    message_sent: TAVERN_EVENTS.USER_MESSAGE_RENDERED,
  };
  var IFRAME_ALIAS = {
    GENERATION_STARTED: IFRAME_EVENTS.GENERATION_STARTED,
    GENERATION_ENDED: IFRAME_EVENTS.GENERATION_ENDED,
  };

  // 原生事件 → 页面脚本与卡片 iframe
  function dispatchPageEvent(name, payloadJson) {
    var args;
    try { args = typeof payloadJson === 'string' ? JSON.parse(payloadJson) : payloadJson; } catch (e) { args = []; }
    if (!Array.isArray(args)) args = [args];
    emitPageEvent(name, args);
    var alias = EVENT_ALIAS[name];
    if (alias) {
      emitPageEvent(alias, args);
      var render = RENDER_ALIAS[alias];
      if (render) emitPageEvent(render, args);
    }
    var iframeAlias = IFRAME_ALIAS[name];
    if (iframeAlias) emitPageEvent(iframeAlias, args);
  }

  function normScope(scope) {
    var s = String(scope == null ? '' : scope).toLowerCase();
    if (s === '' || s === 'chat' || s === 'local') return 'local';
    return s;
  }

  // 酒馆助手 VariableOption：{type, message_id?, script_id?, extension_id?}
  function optionScope(option) {
    if (option && typeof option === 'object') {
      var s = normScope(option.type);
      if (s === 'script' && option.script_id) return 'script:' + option.script_id;
      if (s === 'extension' && option.extension_id) return 'extension:' + option.extension_id;
      return s;
    }
    return normScope(option);
  }

  function scopeStore(scope) {
    var s = normScope(scope);
    if (s === 'global') return globalVariables;
    if (s === 'message') return messageVariables;
    if (s === 'local') return variables;
    return extraVariables[s] || {};
  }

  function applyScope(scope, obj) {
    var s = normScope(scope);
    var next = obj && typeof obj === 'object' ? obj : {};
    if (s === 'global') globalVariables = next;
    else if (s === 'message') messageVariables = next;
    else if (s === 'local') {
      variables = next;
      try { localStorage.setItem('rikka.card.vars.local', JSON.stringify(variables)); } catch (e) { /* 忽略 */ }
    } else {
      extraVariables[s] = next;
    }
    pushVarsToCards();
    requestNative('setVariables', JSON.stringify({ scope: s, vars: next }));
  }

  // —— 点路径读写（对齐 lodash 的 "a.b.c" 语义）——
  function pathParts(path) {
    return String(path == null ? '' : path).split('.').filter(function (p) { return p !== ''; });
  }

  function deepGet(obj, path) {
    var parts = pathParts(path);
    var cur = obj;
    for (var i = 0; i < parts.length; i++) {
      if (cur == null || typeof cur !== 'object') return undefined;
      cur = cur[parts[i]];
    }
    return cur;
  }

  function deepSet(obj, path, value) {
    var parts = pathParts(path);
    if (!parts.length) return obj;
    var cur = obj;
    for (var i = 0; i < parts.length - 1; i++) {
      var k = parts[i];
      if (cur[k] == null || typeof cur[k] !== 'object') cur[k] = {};
      cur = cur[k];
    }
    cur[parts[parts.length - 1]] = value;
    return obj;
  }

  function deepUnset(obj, path) {
    var parts = pathParts(path);
    if (!parts.length) return false;
    var cur = obj;
    for (var i = 0; i < parts.length - 1; i++) {
      if (cur == null || typeof cur !== 'object') return false;
      cur = cur[parts[i]];
    }
    var last = parts[parts.length - 1];
    if (cur != null && typeof cur === 'object' && Object.prototype.hasOwnProperty.call(cur, last)) {
      delete cur[last];
      return true;
    }
    return false;
  }

  // 酒馆助手/酒馆版本：脚本常用它做能力判断，探测时回一个较新的酒馆助手版本
  var TAVERN_HELPER_VERSION = '4.11.3';
  var TAVERN_VERSION = '1.12.14';
  // 注册的助手宏正则与替换，参与 substituteMacros
  var registeredMacros = [];
  // 变量结构注册表（仅用于变量管理器 UI 校验，当前只记录不校验）
  var variableSchemas = {};

  function normalMessage(raw) {
    if (raw == null) return null;
    if (typeof raw === 'string') return { mes: raw, is_user: false };
    var o = raw;
    return {
      message_id: o.message_id,
      mes: o.mes != null ? o.mes : (o.message != null ? o.message : ''),
      message: o.message != null ? o.message : (o.mes != null ? o.mes : ''),
      is_user: o.is_user === true || o.role === 'user',
      role: o.role,
      name: o.name,
      data: o.data,
      is_hidden: o.is_hidden,
    };
  }

  function normalMessages(list) {
    var arr = Array.isArray(list) ? list : [list];
    return arr.map(normalMessage).filter(function (m) { return m != null; });
  }

  // 同步读原生（酒馆助手里 getWorldbookNames 这类本身是同步 API）
  function syncCall(method, payload) {
    try {
      var raw = (window.rikkaNative && typeof window.rikkaNative.syncCall === 'function')
        ? window.rikkaNative.syncCall(method, payload == null ? '' : String(payload))
        : 'null';
      return JSON.parse(raw);
    } catch (e) {
      return null;
    }
  }

  function worldbookRequest(op, data) {
    var payload = {};
    if (data) Object.keys(data).forEach(function (k) { payload[k] = data[k]; });
    payload.op = op;
    return window.webui.request('worldbook', JSON.stringify(payload));
  }

  function personaRequest(op, data) {
    var payload = {};
    if (data) Object.keys(data).forEach(function (k) { payload[k] = data[k]; });
    payload.op = op;
    return window.webui.request('persona', JSON.stringify(payload));
  }

  function characterRequest(op, data) {
    var payload = {};
    if (data) Object.keys(data).forEach(function (k) { payload[k] = data[k]; });
    payload.op = op;
    return window.webui.request('character', JSON.stringify(payload));
  }

  function presetRequest(op, data) {
    var payload = {};
    if (data) Object.keys(data).forEach(function (k) { payload[k] = data[k]; });
    payload.op = op;
    return window.webui.request('preset', JSON.stringify(payload));
  }

  function tavernRegexRequest(option, regexes) {
    var opt = option || { type: 'global' };
    return window.webui.request('tavernRegex', JSON.stringify({
      type: opt.type || 'global',
      name: opt.name || '',
      regexes: regexes || [],
    }));
  }

  var PRESET_SYSTEM_IDS = ['main', 'nsfw', 'jailbreak', 'enhanceDefinitions'];
  var PRESET_PLACEHOLDER_IDS = ['worldInfoBefore', 'personaDescription', 'charDescription',
    'charPersonality', 'scenario', 'worldInfoAfter', 'dialogueExamples', 'chatHistory'];

  // 把 uid 按脚下标补齐；酒馆世界里 uid 只在世界书内部有意义
  function normalizeWorldbookEntries(list) {
    var arr = Array.isArray(list) ? list : [];
    return arr.map(function (entry, index) {
      var out = {};
      Object.keys(entry || {}).forEach(function (k) { out[k] = entry[k]; });
      out.uid = index;
      return out;
    });
  }

  var tavernApi = {
    getVariables: function (option) {
      var store = scopeStore(optionScope(option)) || {};
      return JSON.parse(JSON.stringify(store));
    },
    replaceVariables: function (vars, option) {
      applyScope(optionScope(option), vars);
      return JSON.parse(JSON.stringify(vars || {}));
    },
    updateVariablesWith: function (updater, option) {
      var scope = optionScope(option);
      var current = scopeStore(scope) || {};
      var result = typeof updater === 'function' ? updater(JSON.parse(JSON.stringify(current))) : current;
      if (result && typeof result.then === 'function') {
        return result.then(function (next) { applyScope(scope, next); return next; });
      }
      applyScope(scope, result);
      return result;
    },
    insertOrAssignVariables: function (vars, option) {
      var scope = optionScope(option);
      var store = scopeStore(scope) || {};
      var next = {};
      Object.keys(store).forEach(function (k) { next[k] = store[k]; });
      Object.keys(vars || {}).forEach(function (k) { next[k] = vars[k]; });
      applyScope(scope, next);
      return JSON.parse(JSON.stringify(next));
    },
    insertVariables: function (vars, option) {
      var scope = optionScope(option);
      var store = scopeStore(scope) || {};
      var next = {};
      Object.keys(store).forEach(function (k) { next[k] = store[k]; });
      Object.keys(vars || {}).forEach(function (k) {
        if (!Object.prototype.hasOwnProperty.call(next, k)) next[k] = vars[k];
      });
      applyScope(scope, next);
      return JSON.parse(JSON.stringify(next));
    },
    // 卡片桥沿用 setVariables 这个叫法，语义与 replaceVariables 相同
    setVariables: function (vars, option) { applyScope(optionScope(option), vars); return true; },
    getVariable: function (key, option) {
      var store = scopeStore(optionScope(option)) || {};
      return deepGet(store, key);
    },
    setVariable: function (key, value, option) {
      var scope = optionScope(option);
      var next = JSON.parse(JSON.stringify(scopeStore(scope) || {}));
      deepSet(next, key, value);
      applyScope(scope, next);
      return true;
    },
    deleteVariable: function (path, option) {
      var scope = optionScope(option);
      var next = JSON.parse(JSON.stringify(scopeStore(scope) || {}));
      var occurred = deepUnset(next, path);
      applyScope(scope, next);
      return { variables: next, delete_occurred: occurred };
    },
    registerVariableSchema: function (schema, option) {
      variableSchemas[optionScope(option)] = schema;
    },
    registerMacroLike: function (regex, replace) {
      var entry = { regex: regex, replace: replace };
      registeredMacros.push(entry);
      return { unregister: function () { unregisterMacroLike(regex); } };
    },
    unregisterMacroLike: function (regex) { unregisterMacroLike(regex); },
    getTavernHelperVersion: function () { return TAVERN_HELPER_VERSION; },
    getTavernVersion: function () { return TAVERN_VERSION; },
    getChatMessages: function (range) { return window.webui.request('getChatMessages', range == null ? '' : String(range)); },
    setChatMessages: function (messages, options) {
      return window.webui.request('setChatMessages', JSON.stringify(normalMessages(messages)));
    },
    createChatMessages: function (messages, options) {
      return window.webui.request('createChatMessages', JSON.stringify(normalMessages(messages)));
    },
    deleteChatMessages: function (ids) {
      var arr = Array.isArray(ids) ? ids : [ids];
      return window.webui.request('deleteChatMessages', JSON.stringify(arr));
    },
    getLastMessageId: function () { return (state.messages || []).length - 1; },
    // ---- 世界书 ----
    getWorldbookNames: function () { return syncCall('getWorldbookNames', '') || []; },
    getGlobalWorldbookNames: function () { return syncCall('getGlobalWorldbookNames', '') || []; },
    getCharWorldbookNames: function () {
      return syncCall('getCharWorldbookNames', '') || { primary: null, additional: [] };
    },
    getChatWorldbookName: function () { return null; },
    getOrCreateChatWorldbook: function (chat_name, worldbook_name) {
      return Promise.resolve(worldbook_name || ('聊天世界书 ' + Date.now()));
    },
    getWorldbook: function (worldbook_name) {
      var data = syncCall('getWorldbook', JSON.stringify(String(worldbook_name)));
      if (data == null) return Promise.reject(new Error('世界书不存在: ' + worldbook_name));
      return Promise.resolve(data);
    },
    replaceWorldbook: function (worldbook_name, worldbook) {
      return tavernApi.getWorldbook(worldbook_name).then(function () {
        return worldbookRequest('set', { name: String(worldbook_name), entries: normalizeWorldbookEntries(worldbook) });
      }).then(function () { return undefined; });
    },
    updateWorldbookWith: function (worldbook_name, updater) {
      return tavernApi.getWorldbook(worldbook_name).then(function (current) {
        var result = updater(current);
        return Promise.resolve(result).then(function (next) {
          var normalized = normalizeWorldbookEntries(next);
          return worldbookRequest('set', { name: String(worldbook_name), entries: normalized })
            .then(function () { return normalized; });
        });
      });
    },
    createWorldbook: function (worldbook_name, worldbook) {
      return worldbookRequest('set', {
        name: String(worldbook_name),
        entries: normalizeWorldbookEntries(worldbook),
        createOnly: true,
      }).then(function (r) { return r === true || r === 'true'; });
    },
    createOrReplaceWorldbook: function (worldbook_name, worldbook) {
      return worldbookRequest('set', {
        name: String(worldbook_name),
        entries: normalizeWorldbookEntries(worldbook),
      }).then(function (r) { return r === true || r === 'true'; });
    },
    deleteWorldbook: function (worldbook_name) {
      return worldbookRequest('delete', { name: String(worldbook_name) })
        .then(function (r) { return r === true || r === 'true'; });
    },
    createWorldbookEntries: function (worldbook_name, new_entries) {
      return tavernApi.getWorldbook(worldbook_name).then(function (current) {
        var added = normalizeWorldbookEntries(new_entries).map(function (entry, index) {
          return Object.assign({}, entry, { uid: current.length + index });
        });
        var merged = current.concat(added);
        return worldbookRequest('set', { name: String(worldbook_name), entries: merged })
          .then(function () { return { worldbook: merged, new_entries: added }; });
      });
    },
    deleteWorldbookEntries: function (worldbook_name, predicate) {
      return tavernApi.getWorldbook(worldbook_name).then(function (current) {
        var kept = [];
        var removed = [];
        current.forEach(function (entry) {
          if (predicate(entry)) removed.push(entry); else kept.push(entry);
        });
        var normalized = normalizeWorldbookEntries(kept);
        return worldbookRequest('set', { name: String(worldbook_name), entries: normalized })
          .then(function () { return { worldbook: normalized, deleted_entries: removed }; });
      });
    },
    rebindGlobalWorldbooks: function (worldbook_names) {
      return worldbookRequest('setGlobal', { names: Array.isArray(worldbook_names) ? worldbook_names : [] })
        .then(function () { return undefined; });
    },
    rebindCharWorldbooks: function (character_name_or_id, char_worldbooks) {
      var wb = char_worldbooks || {};
      return worldbookRequest('setChar', {
        primary: wb.primary || null,
        additional: Array.isArray(wb.additional) ? wb.additional : [],
      }).then(function () { return undefined; });
    },
    rebindChatWorldbook: function () { return Promise.resolve(); },
    importRawWorldbook: function (filename, content) {
      var parsed;
      try { parsed = JSON.parse(content); } catch (e) { parsed = null; }
      var entries = [];
      if (parsed && parsed.entries && !Array.isArray(parsed.entries)) {
        entries = Object.keys(parsed.entries).map(function (key) { return parsed.entries[key]; });
      } else if (parsed && Array.isArray(parsed.entries)) {
        entries = parsed.entries;
      } else if (Array.isArray(parsed)) {
        entries = parsed;
      }
      return tavernApi.createOrReplaceWorldbook(filename, entries).then(function (created) {
        return { ok: true, status: created ? 201 : 200, created: created };
      });
    },
    // ---- 用户人设 ----
    getPersonaNames: function () { return syncCall('getPersonaNames', '') || []; },
    getPersonaIds: function () { return syncCall('getPersonaIds', '') || []; },
    getCurrentPersonaName: function () { return syncCall('getCurrentPersonaName', ''); },
    getCurrentPersonaId: function () { return syncCall('getCurrentPersonaId', ''); },
    getPersonaAvatarPath: function (persona_id) {
      return syncCall('getPersonaAvatarPath', JSON.stringify(persona_id == null ? 'current' : String(persona_id)));
    },
    getPersona: function (persona_id) {
      var data = syncCall('getPersona', JSON.stringify(persona_id == null ? 'current' : String(persona_id)));
      if (data == null) throw new Error('用户人设不存在: ' + persona_id);
      return data;
    },
    replacePersona: function (persona_id, persona) {
      return personaRequest('set', {
        id: String(persona_id == null ? 'current' : persona_id),
        persona: persona || {},
      }).then(function () { return undefined; });
    },
    updatePersonaWith: function (persona_id, updater) {
      var current = tavernApi.getPersona(persona_id);
      var result = updater(current);
      return Promise.resolve(result).then(function (next) {
        return personaRequest('set', {
          id: String(persona_id == null ? 'current' : persona_id),
          persona: next,
        }).then(function () { return next; });
      });
    },
    createPersona: function (persona_name, persona) {
      return personaRequest('create', { name: String(persona_name), persona: persona || {} })
        .then(function (r) { return r === true || r === 'true'; });
    },
    createOrReplacePersona: function (persona_name, persona) {
      var names = tavernApi.getPersonaNames();
      if (names.indexOf(String(persona_name)) >= 0 || tavernApi.getPersonaIds().indexOf(String(persona_name)) >= 0) {
        return personaRequest('set', { id: String(persona_name), persona: persona || {} })
          .then(function () { return false; });
      }
      return personaRequest('create', { name: String(persona_name), persona: persona || {} })
        .then(function () { return true; });
    },
    deletePersona: function (persona_id) {
      return personaRequest('delete', { id: String(persona_id == null ? 'current' : persona_id) })
        .then(function (r) { return r === true || r === 'true'; });
    },
    // ---- 角色卡 ----
    getCharacterNames: function () { return syncCall('getCharacterNames', '') || []; },
    getCharacterIds: function () { return syncCall('getCharacterIds', '') || []; },
    getCurrentCharacterName: function () { return syncCall('getCurrentCharacterName', ''); },
    getCurrentCharacterId: function () { return syncCall('getCurrentCharacterId', ''); },
    getCharAvatarPath: function (character_name_or_id) {
      return syncCall('getCharAvatarPath', JSON.stringify(character_name_or_id == null ? 'current' : String(character_name_or_id)));
    },
    getCharacter: function (character_name_or_id) {
      var data = syncCall('getCharacter', JSON.stringify(character_name_or_id == null ? 'current' : String(character_name_or_id)));
      if (data == null) return Promise.reject(new Error('角色卡不存在: ' + character_name_or_id));
      return Promise.resolve(data);
    },
    replaceCharacter: function (character_name_or_id, character) {
      return characterRequest('set', {
        id: String(character_name_or_id == null ? 'current' : character_name_or_id),
        character: character || {},
      }).then(function () { return undefined; });
    },
    updateCharacterWith: function (character_name_or_id, updater) {
      return tavernApi.getCharacter(character_name_or_id).then(function (current) {
        return Promise.resolve(updater(current)).then(function (next) {
          return characterRequest('set', {
            id: String(character_name_or_id == null ? 'current' : character_name_or_id),
            character: next,
          }).then(function () { return next; });
        });
      });
    },
    createCharacter: function (character_name_or_id, character) {
      return characterRequest('create', { name: String(character_name_or_id), character: character || {} })
        .then(function (r) { return r === true || r === 'true'; });
    },
    createOrReplaceCharacter: function (character_name_or_id, character) {
      var names = tavernApi.getCharacterNames();
      var ids = tavernApi.getCharacterIds();
      if (names.indexOf(String(character_name_or_id)) >= 0 || ids.indexOf(String(character_name_or_id)) >= 0) {
        return characterRequest('set', { id: String(character_name_or_id), character: character || {} })
          .then(function () { return false; });
      }
      return characterRequest('create', { name: String(character_name_or_id), character: character || {} })
        .then(function () { return true; });
    },
    deleteCharacter: function (character_name_or_id) {
      return characterRequest('delete', { id: String(character_name_or_id == null ? 'current' : character_name_or_id) })
        .then(function (r) { return r === true || r === 'true'; });
    },
    // ---- 预设 ----
    getPresetNames: function () { return syncCall('getPresetNames', '') || []; },
    getLoadedPresetName: function () { return syncCall('getLoadedPresetName', '') || ''; },
    getPreset: function (preset_name) {
      var data = syncCall('getPreset', JSON.stringify(preset_name == null ? 'in_use' : String(preset_name)));
      if (data == null) throw new Error('预设不存在: ' + preset_name);
      return data;
    },
    isPresetNormalPrompt: function (prompt) {
      return PRESET_SYSTEM_IDS.indexOf(String(prompt && prompt.id)) < 0 &&
        PRESET_PLACEHOLDER_IDS.indexOf(String(prompt && prompt.id)) < 0;
    },
    isPresetSystemPrompt: function (prompt) {
      return PRESET_SYSTEM_IDS.indexOf(String(prompt && prompt.id)) >= 0;
    },
    isPresetPlaceholderPrompt: function (prompt) {
      return PRESET_PLACEHOLDER_IDS.indexOf(String(prompt && prompt.id)) >= 0;
    },
    replacePreset: function (preset_name, preset) {
      var name = preset_name === 'in_use'
        ? (tavernApi.getLoadedPresetName() || 'in_use')
        : String(preset_name);
      return presetRequest('save', { name: name, preset: preset || {} }).then(function () { return undefined; });
    },
    updatePresetWith: function (preset_name, updater) {
      var current = tavernApi.getPreset(preset_name);
      return Promise.resolve(updater(current)).then(function (next) {
        return tavernApi.replacePreset(preset_name, next).then(function () { return undefined; });
      });
    },
    setPreset: function (preset_name, preset) { return tavernApi.replacePreset(preset_name, preset); },
    createPreset: function (preset_name, preset) {
      return presetRequest('save', { name: String(preset_name), preset: preset || {}, createOnly: true })
        .then(function (r) { return r === true || r === 'true'; });
    },
    createOrReplacePreset: function (preset_name, preset) {
      return presetRequest('save', { name: String(preset_name), preset: preset || {} })
        .then(function (r) { return r === true || r === 'true'; });
    },
    deletePreset: function (preset_name) {
      return presetRequest('delete', { name: String(preset_name) })
        .then(function (r) { return r === true || r === 'true'; });
    },
    renamePreset: function (preset_name, new_name) {
      return presetRequest('rename', { name: String(preset_name), newName: String(new_name) })
        .then(function (r) { return r === true || r === 'true'; });
    },
    loadPreset: function (preset_name) {
      if (preset_name === 'in_use') return false;
      if (tavernApi.getPresetNames().indexOf(String(preset_name)) < 0) return false;
      presetRequest('load', { name: String(preset_name) });
      return true;
    },
    importRawPreset: function (filename, content) {
      var parsed;
      try { parsed = JSON.parse(content); } catch (e) { parsed = null; }
      if (parsed == null) return Promise.resolve(false);
      var name = String(filename).replace(/\.json$/i, '');
      return presetRequest('save', { name: name, preset: parsed }).then(function () { return true; });
    },
    // ---- 酒馆正则 ----
    getTavernRegexes: function (option) {
      return syncCall('getTavernRegexes', JSON.stringify(option || { type: 'global' })) || [];
    },
    isCharacterTavernRegexesEnabled: function () { return true; },
    replaceTavernRegexes: function (regexes, option) {
      return tavernRegexRequest(option, regexes).then(function () { return undefined; });
    },
    updateTavernRegexesWith: function (updater, option) {
      var current = tavernApi.getTavernRegexes(option);
      return Promise.resolve(updater(current)).then(function (next) {
        return tavernRegexRequest(option, next).then(function () { return next; });
      });
    },
    formatAsTavernRegexedString: function (text, source, destination, option) {
      var opts = option || {};
      var result = String(text == null ? '' : text);
      var regexes = tavernApi.getTavernRegexes({ type: 'global' })
        .concat(tavernApi.getTavernRegexes({ type: 'character' }));
      try { regexes = regexes.concat(tavernApi.getTavernRegexes({ type: 'preset', name: 'in_use' })); } catch (e) {}
      regexes.forEach(function (regex) {
        if (!regex || regex.enabled === false) return;
        var src = regex.source;
        if (source && src && src[source] !== true) return;
        var dest = regex.destination;
        if (dest) {
          if (destination === 'display' && dest.display === false) return;
          if (destination === 'prompt' && dest.prompt === false) return;
        }
        if (opts.depth != null) {
          if (regex.min_depth != null && opts.depth < regex.min_depth) return;
          if (regex.max_depth != null && regex.max_depth >= 0 && opts.depth > regex.max_depth) return;
        }
        try {
          result = result.replace(new RegExp(regex.find_regex, 'g'), regex.replace_string);
        } catch (e) { /* 非法正则忽略 */ }
      });
      return result;
    },
    sendMessage: function (text) { native('send', text == null ? '' : String(text)); return Promise.resolve(); },
    triggerSlash: function (cmd) { return window.webui.request('triggerSlash', cmd == null ? '' : String(cmd)); },
    substituteMacros: function (text) { return macroSubstitute(text); },
    eventOn: function (name, listener) { return makeEventHandle(name, onPageEvent(name, listener)); },
    eventOnce: function (name, listener) {
      var wrap = function () { offPageEvent(name, wrap); return listener.apply(window, arguments); };
      return makeEventHandle(name, onPageEvent(name, wrap));
    },
    eventMakeLast: function (name, listener) {
      offPageEvent(name, listener);
      (pageListeners[name] = pageListeners[name] || []).push(listener);
      return makeEventHandle(name, listener);
    },
    eventMakeFirst: function (name, listener) {
      offPageEvent(name, listener);
      var arr = pageListeners[name] = pageListeners[name] || [];
      arr.unshift(listener);
      return makeEventHandle(name, listener);
    },
    eventOff: offPageEvent,
    eventRemoveListener: offPageEvent,
    eventClearEvent: function (name) { delete pageListeners[name]; },
    eventClearListener: function (listener) {
      Object.keys(pageListeners).forEach(function (name) {
        pageListeners[name] = pageListeners[name].filter(function (fn) { return fn !== listener; });
      });
    },
    eventClearAll: function () { pageListeners = {}; },
    eventEmit: function (name) { emitPageEvent(name, Array.prototype.slice.call(arguments, 1)); return Promise.resolve(); },
    eventEmitAndWait: function (name) { emitPageEvent(name, Array.prototype.slice.call(arguments, 1)); return Promise.resolve(); },
    emitEvent: function (name) { emitPageEvent(name, Array.prototype.slice.call(arguments, 1)); return Promise.resolve(); },
    waitGlobalInitialized: function (name, timeout) {
      var limit = Number(timeout) > 0 ? Number(timeout) : 10000;
      return new Promise(function (resolve) {
        var started = Date.now();
        (function poll() {
          if (window[name] !== undefined) { resolve(window[name]); return; }
          if (Date.now() - started >= limit) { resolve(undefined); return; }
          setTimeout(poll, 50);
        })();
      });
    },
    getScriptId: function () { return window.__rikkaPlugin ? window.__rikkaPlugin.id : ''; },
    getScriptName: function () { return window.__rikkaPlugin ? window.__rikkaPlugin.name : ''; },
    getScriptInfo: function () { return window.__rikkaPlugin ? window.__rikkaPlugin.info : ''; },
    get charName() { return state.macros && state.macros.char ? state.macros.char : ''; },
    get userName() { return state.macros && state.macros.user ? state.macros.user : ''; },
    tavern_events: TAVERN_EVENTS,
    iframe_events: IFRAME_EVENTS,
  };

  window.TavernHelper = window.TavernHelper || tavernApi;
  window.SillyTavern = window.SillyTavern || tavernApi;
  // 脚本常直接调用这些全局函数，一并挂到 window
  ['getVariables', 'replaceVariables', 'updateVariablesWith', 'insertOrAssignVariables', 'insertVariables',
    'setVariables', 'getVariable', 'setVariable', 'deleteVariable', 'registerVariableSchema',
    'registerMacroLike', 'unregisterMacroLike', 'getTavernHelperVersion', 'getTavernVersion',
    'getChatMessages', 'setChatMessages', 'createChatMessages',
    'deleteChatMessages', 'getLastMessageId', 'sendMessage', 'triggerSlash', 'substituteMacros',
    'formatAsTavernRegexedString', 'eventOn', 'eventOnce', 'eventMakeLast', 'eventMakeFirst',
    'eventOff', 'eventRemoveListener', 'eventClearEvent', 'eventClearListener', 'eventClearAll',
    'eventEmit', 'eventEmitAndWait', 'emitEvent',
    'getWorldbookNames', 'getGlobalWorldbookNames', 'getCharWorldbookNames', 'getChatWorldbookName',
    'getOrCreateChatWorldbook', 'getWorldbook', 'replaceWorldbook', 'updateWorldbookWith',
    'createWorldbook', 'createOrReplaceWorldbook', 'deleteWorldbook', 'createWorldbookEntries',
    'deleteWorldbookEntries', 'rebindGlobalWorldbooks', 'rebindCharWorldbooks', 'rebindChatWorldbook',
    'importRawWorldbook',
    'getPersonaNames', 'getPersonaIds', 'getCurrentPersonaName', 'getCurrentPersonaId',
    'getPersonaAvatarPath', 'getPersona', 'replacePersona', 'updatePersonaWith',
    'createPersona', 'createOrReplacePersona', 'deletePersona',
    'getCharacterNames', 'getCharacterIds', 'getCurrentCharacterName', 'getCurrentCharacterId',
    'getCharAvatarPath', 'getCharacter', 'replaceCharacter', 'updateCharacterWith',
    'createCharacter', 'createOrReplaceCharacter', 'deleteCharacter',
    'getPresetNames', 'getLoadedPresetName', 'getPreset', 'isPresetNormalPrompt',
    'isPresetSystemPrompt', 'isPresetPlaceholderPrompt', 'replacePreset', 'updatePresetWith',
    'setPreset', 'createPreset', 'createOrReplacePreset', 'deletePreset', 'renamePreset',
    'loadPreset', 'importRawPreset',
    'getTavernRegexes', 'isCharacterTavernRegexesEnabled', 'replaceTavernRegexes',
    'updateTavernRegexesWith', 'formatAsTavernRegexedString',
    'waitGlobalInitialized', 'getScriptId', 'getScriptName', 'getScriptInfo'
  ].forEach(function (fn) { if (typeof window[fn] !== 'function') window[fn] = tavernApi[fn]; });
  window.tavern_events = window.tavern_events || TAVERN_EVENTS;
  window.iframe_events = window.iframe_events || IFRAME_EVENTS;

  // 前端插件脚本：按 id 去重，注入时暴露当前脚本身份
  var loadedScriptIds = {};
  function injectPluginScript(script) {
    if (!script || !script.id || loadedScriptIds[script.id]) return;
    loadedScriptIds[script.id] = true;
    var header =
      'window.__rikkaPlugin=' + JSON.stringify({
        id: script.id,
        name: script.name || '',
        info: script.info || '',
        pluginId: script.pluginId || '',
        pluginName: script.pluginName || '',
      }) + ';\n';
    injectScript('(function(){\n' + header + String(script.source == null ? '' : script.source) + '\n})();');
  }

  // —— 原生侧接口 ——
  var hostPending = {};
  window.webui = {
    // 卡片脚本在主页面上下文调用宿主 API 时走这条通道
    request: function (method, payload) {
      return new Promise(function (resolve) {
        var rid = 'h' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
        hostPending[rid] = resolve;
        try {
          if (window.rikkaNative && typeof window.rikkaNative.request === 'function') {
            window.rikkaNative.request(method, rid, payload == null ? '' : String(payload));
          } else { resolve(null); }
        } catch (e) { resolve(null); }
        setTimeout(function () { if (hostPending[rid]) { hostPending[rid] = null; resolve(null); } }, 8000);
      });
    },
    substituteMacros: function (text) { return macroSubstitute(text); },
    getChatMessages: function (range) { return window.webui.request('getChatMessages', range == null ? '' : String(range)); },
    triggerSlash: function (cmd) { native('slash', cmd == null ? '' : String(cmd)); return Promise.resolve(); },
    sendMessage: function (text) { native('send', text == null ? '' : String(text)); return Promise.resolve(); },
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
    setHelperSettings: function (json) {
      var payload = parse(json);
      if (!payload) return;
      state.helper = payload;
      // 折叠模式 / 渲染器 / 流式会改变块结构，整表重渲染
      schedule(function () { renderAll(); });
    },
    setTheme: function (json) { applyTheme(parse(json)); },

    // 前端插件脚本：只在前端模式由原生下发，按 id 增量注入
    setPlugins: function (json) {
      var payload = parse(json);
      if (!payload) return;
      var scripts = Array.isArray(payload) ? payload : (payload.scripts || []);
      scripts.forEach(injectPluginScript);
    },

    // 朗读状态：与原生那排按钮共用同一个 TTS 单例
    setSpeaking: function (json) {
      var payload = parse(json) || {};
      state.speaking = payload.speaking === true;
      state.ttsAvailable = payload.available !== false;
      schedule(paintSpeakButtons);
    },

    // 原生下发的变量域快照（local / global / message）
    setVariables: function (json) {
      var payload = parse(json) || {};
      Object.keys(payload).forEach(function (scope) {
        var s = normScope(scope);
        var vars = payload[scope] || {};
        if (s === 'local') variables = vars;
        else if (s === 'global') globalVariables = vars;
        else if (s === 'message') messageVariables = vars;
        else extraVariables[s] = vars;
      });
      pushVarsToCards();
    },

    // 原生回填：桥的 __rikkaResolve 与 compat.js 的 __rikkaNetResolve 两条通道都要喂（含本窗口）
    resolve: function (requestId, value) {
      var f = hostPending[requestId];
      if (f) { hostPending[requestId] = null; f(value); }
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

    // 原生事件 → 页面脚本 → 所有卡片
    emitEvent: function (name, payloadJson) {
      dispatchPageEvent(name, payloadJson);
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
      if (payload.labels) state.labels = payload.labels;
      state.messages = payload.messages || [];
      // 原生没报生成中就不再是流式：指示必须收干净，不做残留下次生成
      if (payload.generating != null) generating = payload.generating === true;
      if (!generating || (streamId && !messageById(streamId))) streamId = null;
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
      generating = true;
      upsertMessage(msg);
      schedule(function () {
        var row = rowById(msg.id);
        if (!row) { removeEmpty(); list.appendChild(renderRow(msg, true)); }
        else updateRow(row, msg, true);
        applyTyping(row || rowById(msg.id), msg, true);
        followBottom();
      });
    },

    // 落定单条：只有已经落定过、且内容签名未变的行才跳过（流式行必须走完整收尾）
    appendMessage: function (json) {
      var msg = parse(json);
      if (!msg) return;
      upsertMessage(msg);
      if (streamId === String(msg.id)) streamId = null;
      schedule(function () {
        var row = rowById(msg.id);
        if (row) {
          var sig = signatureOf(msg);
          if (sig && row.dataset.signature === sig && row.dataset.settled === '1') {
            applyTyping(row, msg, false);
            return;
          }
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

    scrollToMessage: function (id) {
      schedule(function () {
        var row = rowById(id);
        if (!row) return;
        stick = false;
        var top = row.getBoundingClientRect().top - scroller.getBoundingClientRect().top + scroller.scrollTop - 12;
        programmaticUntil = Date.now() + 80;
        scroller.scrollTop = Math.max(0, top);
        stick = nearBottom();
        updateJump();
      });
    },

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
        if (parsed && typeof parsed === 'object') { applyScope('local', parsed); }
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
    // 图片交给原生预览器：正文插图与附件图都算
    var img = target.closest('.bubble img, .attach-img');
    if (img && img.src) {
      native('openImage', img.src);
      return;
    }
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
  // 卡片脚本会在主页面上下文直接调宿主 API，别名必须在这里就绪，不能依赖卡片 iframe 里的桥
  window.API = window.API || window.webui;
  window.TavernHelper = window.TavernHelper || window.webui;
  window.SillyTavern = window.SillyTavern || window.webui;
})();
