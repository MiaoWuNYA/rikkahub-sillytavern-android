package me.rerere.rikkahub.ui.components.richtext

/**
 * 注入到卡片页面的宿主 API 垫片。
 *
 * 酒馆前端卡跑在官方宿主里时，可以直接用 `window.generate`、`eventOn`、
 * `iframe_events` 这些全局能力。我们把卡塞进 WebView 后，这些都不存在，
 * 卡第一步就会报「宿主未注入 generate 接口，无法生成」。
 *
 * 这段脚本在卡片内容之前执行，把官方那套 API 用 `rikkaHost` 桥重建出来，
 * 卡里的代码一个字都不用改。
 *
 * 实现上刻意用**轮询**拿结果，而不是让 Kotlin 反向 `evaluateJavascript`：
 * `addJavascriptInterface` 只支持 JS→Kotlin 单向，反向调用需要 WebView 引用
 * 和线程切换，容易在卡片被回收时崩；轮询只在卡自己的异步循环里跑，生命周期
 * 跟着卡片走，天然安全。
 */
internal fun cardHostShim(): String = """
<script id="rikka-card-host-shim">
(function () {
  if (window.__RIKKA_HOST_SHIM__) return;
  window.__RIKKA_HOST_SHIM__ = true;
  // rikkaHostGen 是宿主注入的桥对象。刻意不复用 rikkaHost：
  // 那个名字已经被高度上报的 HeightBridge 占了，一个 WebView 上同名只能挂一个，
  // 覆盖掉会让卡片高度再也报不上来。
  if (!window.rikkaHostGen) return;

  // ---- 事件总线：官方 iframe_events 的最小可用子集 ----
  var listeners = {};
  window.iframe_events = window.iframe_events || {};

  /**
   * STREAM_TOKEN_RECEIVED_FULLY：官方在流式生成期间推送**累积全文**。
   * 卡用 typeof eventOn === 'function' 守卫，缺失只是不实时出字，不算致命。
   */
  if (!window.iframe_events.STREAM_TOKEN_RECEIVED_FULLY) {
    window.iframe_events.STREAM_TOKEN_RECEIVED_FULLY = 'stream_token_received_fully';
  }

  window.eventOn = function (topic, handler) {
    if (!listeners[topic]) listeners[topic] = [];
    listeners[topic].push(handler);
  };
  window.eventOnce = function (topic, handler) {
    window.eventOn(topic, function () {
      window.eventRemoveListener(topic, handler);
      handler.apply(null, arguments);
    });
  };
  window.eventRemoveListener = function (topic, handler) {
    var a = listeners[topic];
    if (!a) return;
    var i = a.indexOf(handler);
    if (i >= 0) a.splice(i, 1);
  };
  function emit(topic, a, b) {
    var list = listeners[topic];
    if (!list) return;
    for (var i = 0; i < list.length; i++) {
      try { list[i](a, b); } catch (e) {}
    }
  }

  // ---- 流式推送：轮询 rikkaHost.pollStream() ----
  var lastStreamText = '';
  setInterval(function () {
    var raw;
    try { raw = window.rikkaHostGen.pollStream(); } catch (e) { return; }
    if (!raw) return;
    var info;
    try { info = JSON.parse(raw); } catch (e) { return; }
    // 只在内容真的变了才推送，避免每秒几十次无意义重排
    if (!info || info.text === lastStreamText) return;
    lastStreamText = info.text;
    emit(window.iframe_events.STREAM_TOKEN_RECEIVED_FULLY, info.text, info.generation_id);
  }, 120);

  // 每张卡都要用的常量：官方 eventOn 的第二个参数就是这张表里的值
  var POLL_MS = 120;

  /**
   * 轮询一个宿主请求直到出结果。
   *
   * 成功/失败协议：空串=还在跑；"ok:\u0000<正文>"=成功；"err:\u0000<消息>"=失败。
   * kind 截出来自带尾冒号（"ok:" / "err:"），比较时必须带上，
   * 否则永远走 reject 分支，卡会显示「生成失败：<正文>」。
   */
  function awaitHost(id) {
    return new Promise(function (resolve, reject) {
      var timer = setInterval(function () {
        var raw;
        try { raw = window.rikkaHostGen.poll(id); } catch (e) { return; }
        if (raw === '') return; // 还在跑
        clearInterval(timer);
        // 分隔符是 NUL：正文里出现的冒号不会把结果切坏
        var sep = raw.indexOf('\u0000');
        if (sep < 0) { reject(new Error('宿主返回格式异常')); return; }
        var kind = raw.substring(0, sep);
        var body = raw.substring(sep + 1);
        if (kind === 'ok:') resolve(body);
        else reject(new Error(body || '操作失败'));
      }, POLL_MS);
    });
  }

  /**
   * window.generate(request) → Promise<string>
   *
   * 官方契约：request.user_input 是 prompt，generation_id 用于流式过滤。
   * 卡里写法是 `g = window.generate(req); g.then(done, fail)`，
   * 所以必须返回标准 Promise。
   */
  window.generate = function (request) {
    var id;
    try {
      id = window.rikkaHostGen.generate(JSON.stringify(request || {}));
    } catch (e) { return Promise.reject(e); }
    if (!id || id === '-') return Promise.reject(new Error('生成请求无效：prompt 为空'));
    return awaitHost(id);
  };

  /**
   * window.setChatMessages(messages, options) → Promise
   *
   * 卡用它把开局正文写回第 0 条消息：
   *   await setChatMessages([{message_id:0, is_hidden:false, message:正文}], {refresh:'affected'})
   * 以及隐藏卡面：
   *   setChatMessages([{message_id:0, is_hidden:true}], {refresh:'none'})
   *
   * 卡里对缺失是硬失败：
   *   if (typeof setChatMessages !== 'function') throw new Error('当前宿主没有 setChatMessages，无法写入第 0 条消息')
   * 所以必须真的存在，且返回 Promise（卡用 await / .catch）。
   */
  window.setChatMessages = function (messages, options) {
    var list = Array.isArray(messages) ? messages : [];
    var msg = list[0] || {};
    var payload = {
      message_id: (typeof msg.message_id === 'number') ? msg.message_id : 0,
      message: (msg.message == null) ? '' : String(msg.message),
      is_hidden: !!msg.is_hidden,
      refresh: (options && options.refresh) ? String(options.refresh) : 'affected'
    };
    try {
      return awaitHost(window.rikkaHostGen.setChatMessages(JSON.stringify(payload)));
    } catch (e) {
      return Promise.reject(e);
    }
  };

  // ---- MVU 变量系统 ----
  //
  // 卡里对 MVU 是**软降级**：
  //   if (typeof Mvu !== 'undefined' && Mvu && typeof Mvu.getMvuData === 'function')
  // 缺失时只写正文、跳过变量，并提示「未检测到可用 MVU，已只写入开场白正文」。
  // 但整套角色状态（属性/世界/剧情线）都在这棵树里，缺了它卡等于残废。
  //
  // 变量初值由宿主从卡内世界书 `[initvar]变量初始化勿开` 解析后注入。
  window.Mvu = {
    /** 读变量树。卡只关心 .stat_data，取不到就当没有。 */
    getMvuData: function (option) {
      var raw;
      try { raw = window.rikkaHostGen.getMvuData(); } catch (e) { return null; }
      if (!raw) return null;
      try {
        var parsed = JSON.parse(raw);
        // 卡会先 clone() 再改，返回普通对象即可；
        // 但它会连 stat_data 一起结构化克隆，所以必须保证是纯 JSON 数据。
        return parsed && typeof parsed === 'object' ? parsed : null;
      } catch (e) { return null; }
    },
    /** 整体写回变量树。卡里是 await，这里直接同步存完。 */
    replaceMvuData: function (data, option) {
      try {
        window.rikkaHostGen.replaceMvuData(JSON.stringify(data == null ? {} : data));
      } catch (e) {}
      return Promise.resolve();
    }
  };

  // 官方 waitGlobalInitialized('Mvu')：卡会等这个再取变量。
  // 我们的 Mvu 是同步就绪的，立刻兑现即可，省掉卡里的 2.5s 超时等待。
  if (typeof window.waitGlobalInitialized !== 'function') {
    window.waitGlobalInitialized = function () { return Promise.resolve(); };
  }

  // ---- getVariables / updateVariablesWith：chat 作用域变量 ----
  //
  // 卡用它记界面偏好（XY_UI_KEY = '${'$'}xuanyin_ui_v0883'）。
  // 卡里两处都带 typeof 守卫并有 localStorage 兜底，所以缺失不会致命；
  // 但补上能让「显示模式」这类设置跟着对话走，而不是每台设备各存一份。
  var chatVarStore = {};
  try {
    var persisted = window.localStorage.getItem('__rikka_chat_vars__');
    if (persisted) chatVarStore = JSON.parse(persisted) || {};
  } catch (e) { chatVarStore = {}; }

  function persistChatVars() {
    try {
      window.localStorage.setItem('__rikka_chat_vars__', JSON.stringify(chatVarStore));
    } catch (e) {}
  }

  window.getVariables = function (option) {
    var scope = (option && option.type) ? String(option.type) : 'chat';
    if (scope !== 'chat') return {};
    try { return JSON.parse(JSON.stringify(chatVarStore)); } catch (e) { return {}; }
  };

  window.updateVariablesWith = function (updater, option) {
    var scope = (option && option.type) ? String(option.type) : 'chat';
    if (scope !== 'chat') return Promise.resolve(chatVarStore);
    try {
      var next = typeof updater === 'function' ? updater(chatVarStore) : chatVarStore;
      if (next && typeof next === 'object') {
        chatVarStore = next;
        persistChatVars();
      }
    } catch (e) {}
    return Promise.resolve(chatVarStore);
  };

  // ---- getChatMessages / setChatMessage：读消息与切开局 ----
  //
  // 卡的「命牌问卜」按钮靠这两个把第二条开局（Alternate Greeting）切出来。
  // 卡里对缺失只弹 alert，不影响其他功能，但那个按钮就废了。
  //
  // 宿主把 MessageNode.messages 映射成官方 swipes 数组。
  window.getChatMessages = function (begin, options) {
    var all;
    try { all = JSON.parse(window.rikkaHostGen.getChatMessages()); } catch (e) { return []; }
    if (!Array.isArray(all)) return [];
    var from = (typeof begin === 'number' && begin > 0) ? begin : 0;
    return all.slice(from);
  };

  window.setChatMessage = function (text, messageId, options) {
    var opts = options || {};
    var swipeId = (typeof opts.swipe_id === 'number') ? opts.swipe_id : 0;
    var payload = {
      message_id: (typeof messageId === 'number') ? messageId : 0,
      swipe_id: swipeId,
      text: (text == null) ? '' : String(text)
    };
    try {
      return awaitHost(window.rikkaHostGen.setChatMessage(JSON.stringify(payload)));
    } catch (e) {
      return Promise.reject(e);
    }
  };

  // ---- toastr：卡用 window.toastr && typeof window.toastr.error === 'function' 守卫 ----
  window.toastr = window.toastr || {};
  window.toastr.error = function (msg) {
    try { window.rikkaHostGen.toast(String(msg == null ? '' : msg), false); } catch (e) {}
  };
  window.toastr.warning = function (msg) {
    try { window.rikkaHostGen.toast(String(msg == null ? '' : msg), true); } catch (e) {}
  };
  window.toastr.success = function () {};
  window.toastr.info = function () {};
})();
</script>
""".trimIndent()
