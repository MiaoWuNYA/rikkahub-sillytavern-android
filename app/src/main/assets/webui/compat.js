// Android WebView 运行环境补丁：补齐旧引擎缺失的标准方法，并把剪贴板与本地路由请求接到原生
(function () {
  'use strict';

  var g = typeof window !== 'undefined' ? window : globalThis;

  // 不透明源（某些 WebView 下的 srcdoc iframe）一访问 localStorage 就抛，卡片脚本会整段中断
  try {
    void g.localStorage.length;
  } catch (e) {
    try {
      var memStore = {};
      Object.defineProperty(g, 'localStorage', {
        configurable: true,
        value: {
          getItem: function (k) { return Object.prototype.hasOwnProperty.call(memStore, String(k)) ? memStore[String(k)] : null; },
          setItem: function (k, v) { memStore[String(k)] = String(v); },
          removeItem: function (k) { delete memStore[String(k)]; },
          clear: function () { memStore = {}; },
          key: function (i) { return Object.keys(memStore)[i] || null; },
          get length() { return Object.keys(memStore).length; }
        }
      });
    } catch (e2) { /* 覆盖不了就保持原样 */ }
  }

  if (!g.requestIdleCallback) {
    g.requestIdleCallback = function (fn) {
      var start = Date.now();
      return setTimeout(function () {
        fn({ didTimeout: false, timeRemaining: function () { return Math.max(0, 50 - (Date.now() - start)); } });
      }, 1);
    };
    g.cancelIdleCallback = function (id) { clearTimeout(id); };
  }

  var defineIfMissing = function (target, name, fn) {
    try {
      if (target && !target[name]) Object.defineProperty(target, name, { value: fn, writable: true, configurable: true });
    } catch (e) { /* 内建对象可能被冻结 */ }
  };

  defineIfMissing(Array.prototype, 'at', function (index) {
    var i = Math.trunc(index) || 0;
    if (i < 0) i += this.length;
    return this[i];
  });
  defineIfMissing(String.prototype, 'at', function (index) {
    var i = Math.trunc(index) || 0;
    if (i < 0) i += this.length;
    return this[i];
  });
  defineIfMissing(Array.prototype, 'findLast', function (fn, thisArg) {
    for (var i = this.length - 1; i >= 0; i--) if (fn.call(thisArg, this[i], i, this)) return this[i];
    return undefined;
  });
  defineIfMissing(Array.prototype, 'findLastIndex', function (fn, thisArg) {
    for (var i = this.length - 1; i >= 0; i--) if (fn.call(thisArg, this[i], i, this)) return i;
    return -1;
  });
  defineIfMissing(Array.prototype, 'toReversed', function () { return this.slice().reverse(); });
  defineIfMissing(Array.prototype, 'toSorted', function (fn) { return this.slice().sort(fn); });
  defineIfMissing(Array.prototype, 'toSpliced', function () {
    var copy = this.slice();
    copy.splice.apply(copy, arguments);
    return copy;
  });
  defineIfMissing(Array.prototype, 'with', function (index, value) {
    var i = Math.trunc(index) || 0;
    if (i < 0) i += this.length;
    var copy = this.slice();
    copy[i] = value;
    return copy;
  });
  defineIfMissing(String.prototype, 'replaceAll', function (search, replacement) {
    return this.split(search).join(replacement);
  });
  defineIfMissing(Object, 'hasOwn', function (obj, key) { return Object.prototype.hasOwnProperty.call(obj, key); });
  defineIfMissing(Object, 'groupBy', function (items, fn) {
    var out = {};
    for (var i = 0; i < items.length; i++) {
      var key = fn(items[i], i);
      (out[key] = out[key] || []).push(items[i]);
    }
    return out;
  });
  defineIfMissing(Promise, 'withResolvers', function () {
    var resolve, reject;
    var promise = new Promise(function (res, rej) { resolve = res; reject = rej; });
    return { promise: promise, resolve: resolve, reject: reject };
  });
  defineIfMissing(Promise, 'any', function (iterable) {
    return new Promise(function (resolve, reject) {
      var items = Array.from(iterable || []);
      var errors = [];
      var pending = items.length;
      if (!pending) { reject(new Error('All promises were rejected')); return; }
      items.forEach(function (item, index) {
        Promise.resolve(item).then(resolve, function (err) {
          errors[index] = err;
          if (--pending === 0) reject(new Error('All promises were rejected'));
        });
      });
    });
  });

  // 剪贴板：WebView 暴露了 navigator.clipboard 但常直接 reject，统一走原生
  var writeClipboard = function (text) {
    var value = String(text == null ? '' : text);
    try {
      if (g.rikkaNative && typeof g.rikkaNative.clipboard === 'function') {
        g.rikkaNative.clipboard(value);
        return true;
      }
      if (g.parent && g.parent !== g && g.parent.rikkaNative && typeof g.parent.rikkaNative.clipboard === 'function') {
        g.parent.rikkaNative.clipboard(value);
        return true;
      }
    } catch (e) { /* 落到下面的兜底 */ }
    return false;
  };

  if (g.navigator) {
    try {
      var clipboard = g.navigator.clipboard || {};
      clipboard.writeText = function (text) {
        return writeClipboard(text) ? Promise.resolve() : Promise.reject(new Error('clipboard unavailable'));
      };
      clipboard.readText = clipboard.readText || function () { return Promise.resolve(''); };
      Object.defineProperty(g.navigator, 'clipboard', { value: clipboard, configurable: true });
    } catch (e) { /* 忽略 */ }
  }

  if (typeof g.document !== 'undefined' && g.document.execCommand) {
    var nativeExec = g.document.execCommand.bind(g.document);
    g.document.execCommand = function (command) {
      if (String(command).toLowerCase() === 'copy') {
        var selection = '';
        try { selection = g.getSelection ? String(g.getSelection()) : ''; } catch (e) { /* 忽略 */ }
        if (writeClipboard(selection)) return true;
      }
      try { return nativeExec.apply(null, arguments); } catch (e) { return false; }
    };
  }

  // 本地路由交给原生网络栈，其余请求放行
  var LOCAL_ROUTE = /^\/(api|v1|version|csrf-token|characters|chats|worldinfo|settings|extensions|themes|assets|scripts|backgrounds|user)/i;

  var nativeRequest = function (method, url, body) {
    return new Promise(function (resolve, reject) {
      var api = null;
      try {
        api = (g.rikkaNative && g.rikkaNative.request) ? g.rikkaNative
          : (g.parent && g.parent.rikkaNative && g.parent.rikkaNative.request ? g.parent.rikkaNative : null);
      } catch (e) { /* 跨域时取不到 */ }
      if (!api) { reject(new Error('no native bridge')); return; }
      var id = 'net' + Date.now() + Math.floor(Math.random() * 1000);
      g.__rikkaNetPending = g.__rikkaNetPending || {};
      g.__rikkaNetPending[id] = { resolve: resolve, reject: reject };
      setTimeout(function () {
        if (g.__rikkaNetPending && g.__rikkaNetPending[id]) {
          delete g.__rikkaNetPending[id];
          reject(new Error('native request timeout'));
        }
      }, 20000);
      try {
        api.request('httpRequest', id, JSON.stringify({ method: method, url: url, body: body == null ? '' : String(body) }));
      } catch (e) { reject(e); }
    });
  };

  // 原生回填格式：{ status, body, contentType }
  g.__rikkaNetResolve = function (id, payload) {
    var pending = g.__rikkaNetPending && g.__rikkaNetPending[id];
    if (!pending) return;
    delete g.__rikkaNetPending[id];
    var info = {};
    try { info = JSON.parse(payload || '{}'); } catch (e) { /* 忽略 */ }
    pending.resolve(new g.Response(info.body == null ? '' : String(info.body), {
      status: info.status || 200,
      headers: { 'content-type': info.contentType || 'application/json' }
    }));
  };

  var nativeFetch = g.fetch;
  if (nativeFetch) {
    g.fetch = function (input, init) {
      var url = '';
      try { url = typeof input === 'string' ? input : (input && input.url) || ''; } catch (e) { /* 忽略 */ }
      var isLocal = url && !/^https?:/i.test(url) && LOCAL_ROUTE.test(url.replace(/^\.\//, '/'));
      if (!isLocal) return nativeFetch.apply(g, arguments);
      return nativeRequest((init && init.method) || 'GET', url, (init && init.body) || '');
    };
  }

  var NativeXHR = g.XMLHttpRequest;
  if (NativeXHR) {
    var open = NativeXHR.prototype.open;
    var send = NativeXHR.prototype.send;
    NativeXHR.prototype.open = function (method, url) {
      this.__rikkaLocal = url && !/^https?:/i.test(url) && LOCAL_ROUTE.test(String(url).replace(/^\.\//, '/'));
      this.__rikkaMethod = method;
      this.__rikkaUrl = url;
      return open.apply(this, arguments);
    };
    NativeXHR.prototype.send = function (data) {
      if (!this.__rikkaLocal) return send.apply(this, arguments);
      var xhr = this;
      nativeRequest(this.__rikkaMethod || 'GET', this.__rikkaUrl || '', data || '').then(function (response) {
        return response.text().then(function (text) {
          try {
            Object.defineProperty(xhr, 'readyState', { value: 4, configurable: true });
            Object.defineProperty(xhr, 'status', { value: response.status, configurable: true });
            Object.defineProperty(xhr, 'responseText', { value: text, configurable: true });
            Object.defineProperty(xhr, 'response', { value: text, configurable: true });
          } catch (e) { /* 忽略 */ }
          if (typeof xhr.onreadystatechange === 'function') xhr.onreadystatechange();
          if (typeof xhr.onload === 'function') xhr.onload();
        });
      }).catch(function (error) {
        if (typeof xhr.onerror === 'function') xhr.onerror(error);
      });
      return undefined;
    };
  }

  try {
    if (g.jQuery && g.jQuery.ajax) {
      var ajax = g.jQuery.ajax;
      g.jQuery.ajax = function (options) {
        var url = typeof options === 'string' ? options : (options && options.url) || '';
        var isLocal = url && !/^https?:/i.test(url) && LOCAL_ROUTE.test(String(url).replace(/^\.\//, '/'));
        if (!isLocal) return ajax.apply(this, arguments);
        var settings = typeof options === 'string' ? {} : (options || {});
        var deferred = g.jQuery.Deferred ? g.jQuery.Deferred() : null;
        nativeRequest(settings.method || settings.type || 'GET', url, settings.data || '').then(function (response) {
          return response.text().then(function (text) {
            var parsed = text;
            try { parsed = JSON.parse(text); } catch (e) { /* 原样返回 */ }
            if (deferred) { deferred.resolve(parsed); return; }
            if (typeof settings.success === 'function') settings.success(parsed);
          });
        }).catch(function (error) {
          if (deferred) { deferred.reject(error); return; }
          if (typeof settings.error === 'function') settings.error(error);
        });
        return deferred || { then: function () { return this; }, catch: function () { return this; } };
      };
    }
  } catch (e) { /* 忽略 */ }

  g.RikkaCompat = { writeClipboard: writeClipboard, nativeRequest: nativeRequest };
})();
