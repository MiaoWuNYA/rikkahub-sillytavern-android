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
   * window.generate(request) → Promise<string>
   *
   * 官方契约：request.user_input 是 prompt，generation_id 用于流式过滤。
   * 卡里写法是 `g = window.generate(req); g.then(done, fail)`，
   * 所以必须返回标准 Promise。
   */
  window.generate = function (request) {
    return new Promise(function (resolve, reject) {
      var id;
      try {
        id = window.rikkaHostGen.generate(JSON.stringify(request || {}));
      } catch (e) { reject(e); return; }
      if (!id || id === '-') { reject(new Error('生成请求无效：prompt 为空')); return; }

      var timer = setInterval(function () {
        var raw;
        try { raw = window.rikkaHostGen.poll(id); } catch (e) { return; }
        if (raw === '') return; // 还在跑
        clearInterval(timer);
        // 分隔符是 NUL：正文里出现的冒号不会把结果切坏。
        // kind 截出来自带尾冒号（"ok:" / "err:"），比较时必须带上，
        // 否则永远走 reject 分支，卡会显示「生成失败：<正文>」。
        var sep = raw.indexOf('\u0000');
        if (sep < 0) { reject(new Error('宿主返回格式异常')); return; }
        var kind = raw.substring(0, sep);
        var body = raw.substring(sep + 1);
        if (kind === 'ok:') resolve(body);
        else reject(new Error(body || '生成失败'));
      }, POLL_MS);
    });
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
